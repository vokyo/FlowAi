import json
from datetime import date
from uuid import UUID

import httpx2
import pytest
from fake_chat_model import FakeChatModel
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    ToolCall,
    ToolMessage,
)
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.graph import END
from langgraph.types import Command, GraphOutput, StateSnapshot

from flowai_agent.graph.build import (
    build_graph,
    route_after_model,
    route_after_tools,
    run_graph,
)
from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import CHECKPOINT_TYPES, AgentState
from flowai_agent.models.plan import Plan
from flowai_agent.tools.client import BackendClient
from flowai_agent.tools.definitions import build_tools

GOAL = "Clear the login tech debt within two weeks"
TODAY = date(2026, 10, 4)
ISSUE: dict[str, object] = {
    "id": "0b8c6a52-6a3e-4d6b-9a39-5f1d2c3b4a51",
    "title": "Fix login timeout",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "assigneeUserId": None,
    "assigneeDisplayName": None,
}
MEMBERS: dict[str, object] = {
    "items": [
        {
            "userId": "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60",
            "displayName": "Ann",
            "role": "OWNER",
        }
    ],
    "truncated": False,
}


PLAN: dict[str, object] = {
    "overview": "Rate-limit login, then make token expiry configurable.",
    "items": [
        {
            "clientItemId": "item-1",
            "title": "Rate-limit the login endpoint",
            "priority": "HIGH",
        },
        {
            "clientItemId": "item-2",
            "title": "Make token expiry configurable",
            "priority": "MEDIUM",
        },
        {
            "clientItemId": "item-3",
            "title": "Remove the remember-me code",
            "priority": "LOW",
        },
    ],
}
WRITE_PLAN = AIMessage(
    "", tool_calls=[{"name": "Plan", "args": PLAN, "id": "call_plan"}]
)
ANN = "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60"
STRANGER = "5e0c1a2b-3d4e-4f60-8a7b-9c0d1e2f3a4b"
ASSIGNED_PLAN: dict[str, object] = {
    "overview": "Rate-limit login, then make token expiry configurable.",
    "items": [
        {
            "clientItemId": "item-1",
            "title": "Rate-limit the login endpoint",
            "priority": "HIGH",
            "suggestedAssigneeUserId": ANN,
        },
        {
            "clientItemId": "item-2",
            "title": "Make token expiry configurable",
            "priority": "MEDIUM",
            "suggestedAssigneeUserId": STRANGER,
        },
        {
            "clientItemId": "item-3",
            "title": "Remove the remember-me code",
            "priority": "LOW",
        },
    ],
}


def search(query: str, call_id: str) -> ToolCall:
    return {"name": "search_project_issues", "args": {"query": query}, "id": call_id}


def search_too_many(call_id: str) -> ToolCall:
    return {
        "name": "search_project_issues",
        "args": {"query": "login", "limit": 50},
        "id": call_id,
    }


def list_members(call_id: str) -> ToolCall:
    return {"name": "get_project_members", "args": {}, "id": call_id}


def backend(sent: list[httpx2.Request], truncated_queries: set[str]) -> BackendClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        if request.url.path.endswith("/issues"):
            truncated = request.url.params.get("q") in truncated_queries
            return httpx2.Response(200, json={"items": [ISSUE], "truncated": truncated})
        return httpx2.Response(200, json=MEMBERS)

    return BackendClient("http://backend", "agent-token", httpx2.MockTransport(handler))


ASK_FOR_MEMBERS = AIMessage("", tool_calls=[list_members("call_1")])
ASK_FOR_TWO = AIMessage(
    "", tool_calls=[list_members("call_1"), search("login", "call_2")]
)
ENOUGH = AIMessage("I know enough to plan.")


@pytest.mark.parametrize(
    ("last", "rounds_used", "calls_used", "next_node"),
    [
        pytest.param(ASK_FOR_MEMBERS, 1, 0, "run_tools", id="asks within budget"),
        pytest.param(ENOUGH, 4, 8, "generate_plan", id="asks for nothing"),
        pytest.param(
            ASK_FOR_MEMBERS, 4, 0, "report_insufficient", id="asks in the last round"
        ),
        pytest.param(
            ASK_FOR_TWO, 1, 7, "report_insufficient", id="calls would go over budget"
        ),
        pytest.param(ASK_FOR_MEMBERS, 1, 7, "run_tools", id="the last call still fits"),
    ],
)
def test_the_reply_and_the_budget_decide_where_the_graph_goes(
    last: AIMessage, rounds_used: int, calls_used: int, next_node: str
) -> None:
    state = AgentState(
        goal=GOAL,
        today=TODAY,
        messages=[last],
        decision_rounds_used=rounds_used,
        tool_calls_used=calls_used,
    )

    assert route_after_model(state) == next_node


@pytest.mark.anyio
async def test_the_loop_stops_once_the_model_has_what_it_needs() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            ),
            AIMessage("I know enough to plan."),
            WRITE_PLAN,
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries=set())

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    assert [type(message).__name__ for message in final.messages] == [
        "SystemMessage",
        "HumanMessage",
        "AIMessage",
        "ToolMessage",
        "ToolMessage",
        "AIMessage",
    ]
    assert final.messages[-1].text == "I know enough to plan."
    assert len(model.received) == 3
    assert final.plan == Plan.model_validate(PLAN)
    assert final.decision_rounds_used == 2
    assert final.tool_calls_used == 2
    assert [request.url.path.rsplit("/", 1)[-1] for request in sent] == [
        "issues",
        "members",
    ]


@pytest.mark.anyio
async def test_a_truncated_search_reaches_the_model_before_it_searches_again() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            ),
            AIMessage("", tool_calls=[search("login timeout", "call_3")]),
            AIMessage("I know enough to plan."),
            WRITE_PLAN,
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries={"login"})

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    first_search = next(
        message
        for message in model.received[1]
        if isinstance(message, ToolMessage) and message.tool_call_id == "call_1"
    )
    assert json.loads(first_search.text)["truncated"] is True
    assert [
        request.url.params.get("q")
        for request in sent
        if request.url.path.endswith("/issues")
    ] == ["login", "login timeout"]
    assert final.plan == Plan.model_validate(PLAN)
    assert final.decision_rounds_used == 3
    assert final.tool_calls_used == 3


@pytest.mark.anyio
async def test_a_model_that_never_stops_searching_is_cut_off_after_round_four() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search(f"login {n}", f"call_{n}")])
            for n in range(1, 5)
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries=set())

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    assert len(model.received) == 4
    assert final.decision_rounds_used == 4
    assert final.tool_calls_used == 3
    assert len(sent) == 3
    assert final.plan is None
    assert len(final.missing) == 1
    assert "search_project_issues" in final.missing[0]
    assert "login 4" in final.missing[0]


@pytest.mark.anyio
async def test_a_round_that_would_go_over_the_tool_call_budget_is_not_run() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            ),
            AIMessage(
                "",
                tool_calls=[
                    search("login timeout", "call_3"),
                    search("session", "call_4"),
                ],
            ),
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries={"login"})

    final = await run_graph(
        PlanningNodes(model, build_tools(client)),
        AgentState(goal=GOAL, today=TODAY, max_tool_calls=3),
    )
    await client.aclose()

    assert final.tool_calls_used == 2
    assert len(sent) == 2
    assert final.plan is None
    assert len(final.missing) == 2


@pytest.mark.parametrize(
    ("failure_reason", "next_node"),
    [
        pytest.param(None, "ask_model", id="tools answered"),
        pytest.param("search_project_issues failed: 401", END, id="a tool failed"),
    ],
)
def test_a_tool_failure_ends_the_run_instead_of_asking_the_model_again(
    failure_reason: str | None, next_node: str
) -> None:
    state = AgentState(goal=GOAL, today=TODAY, failure_reason=failure_reason)

    assert route_after_tools(state) == next_node


@pytest.mark.anyio
async def test_a_backend_that_keeps_failing_ends_the_run_with_a_reason() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            )
        ]
    )
    sent: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        return httpx2.Response(503, json={"code": "X", "message": "down"})

    client = BackendClient(
        "http://backend", "agent-token", httpx2.MockTransport(handler)
    )

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    assert len(model.received) == 1
    assert [request.url.path.rsplit("/", 1)[-1] for request in sent] == ["issues"] * 3
    assert final.failure_reason is not None
    assert "search_project_issues" in final.failure_reason
    assert final.plan is None
    assert final.missing == []
    assert final.tool_calls_used == 3


@pytest.mark.anyio
async def test_the_model_gets_one_chance_to_fix_its_arguments() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search_too_many("call_1")]),
            AIMessage(
                "", tool_calls=[search("login", "call_2"), list_members("call_3")]
            ),
            AIMessage("I know enough to plan."),
            WRITE_PLAN,
        ]
    )
    client = backend([], truncated_queries=set())

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    error = next(
        message
        for message in model.received[1]
        if isinstance(message, ToolMessage) and message.tool_call_id == "call_1"
    )
    assert "limit" in error.text
    assert final.argument_fixes_used == 1
    assert final.plan == Plan.model_validate(PLAN)


@pytest.mark.anyio
async def test_a_model_that_keeps_sending_invalid_arguments_ends_the_run() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search_too_many("call_1")]),
            AIMessage("", tool_calls=[search_too_many("call_2")]),
        ]
    )
    client = backend([], truncated_queries=set())

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    assert len(model.received) == 2
    assert final.failure_reason is not None
    assert "invalid arguments again" in final.failure_reason
    assert final.plan is None


@pytest.mark.anyio
async def test_the_final_plan_keeps_only_assignees_from_the_member_list() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            ),
            AIMessage("I know enough to plan."),
            AIMessage(
                "",
                tool_calls=[{"name": "Plan", "args": ASSIGNED_PLAN, "id": "call_plan"}],
            ),
        ]
    )
    client = backend([], truncated_queries=set())

    final = await run_graph(
        PlanningNodes(model, build_tools(client)), AgentState(goal=GOAL, today=TODAY)
    )
    await client.aclose()

    assert final.plan is not None
    owners = [item.suggestedAssigneeUserId for item in final.plan.items]
    assert owners == [UUID(ANN), None, None]


REVISED_PLAN: dict[str, object] = {
    "overview": "Rate-limit login and drop the remember-me code.",
    "items": [
        {
            "clientItemId": "item-1",
            "title": "Rate-limit the login endpoint",
            "priority": "HIGH",
        },
        {
            "clientItemId": "item-3",
            "title": "Remove the remember-me code",
            "priority": "LOW",
        },
    ],
}


WRITE_REVISED_PLAN = AIMessage(
    "", tool_calls=[{"name": "Plan", "args": REVISED_PLAN, "id": "call_revised"}]
)


async def plan_then_revise(
    model: FakeChatModel, sent: list[httpx2.Request]
) -> tuple[
    GraphOutput[AgentState], StateSnapshot, GraphOutput[AgentState], StateSnapshot
]:
    """Runs until the plan waits for review, then revises it with "Drop item-2"."""
    client = backend(sent, truncated_queries=set())
    graph = build_graph(
        PlanningNodes(model, build_tools(client)), checkpointer=InMemorySaver()
    )
    run: RunnableConfig = {"configurable": {"thread_id": "run-1"}}

    # LangGraph types Command as Command[Unknown], so ainvoke is partly unknown.
    first = await graph.ainvoke(  # pyright: ignore[reportUnknownMemberType]
        AgentState(goal=GOAL, today=TODAY), run, version="v2"
    )
    paused = await graph.aget_state(run)
    revised = await graph.ainvoke(  # pyright: ignore[reportUnknownMemberType]
        Command(resume="Drop item-2"), run, version="v2"
    )
    after = await graph.aget_state(run)
    await client.aclose()
    return first, paused, revised, after


@pytest.mark.anyio
async def test_a_revision_sees_the_plan_under_review_and_then_the_feedback() -> None:
    model = FakeChatModel(
        replies=[
            ENOUGH,
            WRITE_PLAN,
            AIMessage("I know enough to revise."),
            WRITE_REVISED_PLAN,
        ]
    )

    first, paused, revised, after = await plan_then_revise(model, [])

    assert [interrupt.value for interrupt in first.interrupts] == [
        Plan.model_validate(PLAN)
    ]
    assert paused.next == ("review",)
    previous_plan, feedback = model.received[2][-2:]
    assert isinstance(previous_plan, AIMessage)
    assert Plan.model_validate_json(previous_plan.text) == Plan.model_validate(PLAN)
    assert isinstance(feedback, HumanMessage)
    assert feedback.text == (
        "Revise the plan above as follows: Drop item-2\n"
        "For this revision, at most 1 of your replies can call tools, with at\n"
        "most 4 tool calls in total.\n"
        "When you know enough to plan, reply without calling any tool."
    )
    assert revised.value.plan == Plan.model_validate(REVISED_PLAN)
    assert after.next == ("review",)


@pytest.mark.anyio
async def test_a_revision_can_search_after_the_run_used_its_whole_budget() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search("login", "call_1")]),
            AIMessage("", tool_calls=[search("token", "call_2")]),
            AIMessage("", tool_calls=[list_members("call_3")]),
            ENOUGH,
            WRITE_PLAN,
            AIMessage("", tool_calls=[search("remember me", "call_4")]),
            AIMessage("I know enough to revise."),
            WRITE_REVISED_PLAN,
        ]
    )
    sent: list[httpx2.Request] = []

    first, _, revised, after = await plan_then_revise(model, sent)

    assert first.value.decision_rounds_used == first.value.max_decision_rounds == 4
    assert [request.url.params.get("q") for request in sent] == [
        "login",
        "token",
        None,
        "remember me",
    ]
    assert revised.value.decision_rounds_used == 2
    assert revised.value.tool_calls_used == 1
    assert revised.value.plan == Plan.model_validate(REVISED_PLAN)
    assert after.next == ("review",)


@pytest.mark.anyio
async def test_a_revision_that_keeps_searching_is_cut_off_after_its_two_rounds() -> (
    None
):
    model = FakeChatModel(
        replies=[
            ENOUGH,
            WRITE_PLAN,
            AIMessage("", tool_calls=[search("remember me", "call_1")]),
            AIMessage("", tool_calls=[search("cookies", "call_2")]),
        ]
    )
    sent: list[httpx2.Request] = []

    _, _, revised, after = await plan_then_revise(model, sent)

    assert [request.url.params.get("q") for request in sent] == ["remember me"]
    assert revised.value.missing == [
        "Still wanted to call search_project_issues with {'query': 'cookies'}"
    ]
    assert not revised.interrupts
    assert after.next == ()


@pytest.mark.anyio
async def test_a_revision_gets_its_own_chance_to_fix_its_arguments() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search_too_many("call_1")]),
            AIMessage("I know enough to plan."),
            WRITE_PLAN,
            AIMessage("", tool_calls=[search_too_many("call_2")]),
            AIMessage("I know enough to revise."),
            WRITE_REVISED_PLAN,
        ]
    )

    first, _, revised, after = await plan_then_revise(model, [])

    assert first.value.argument_fixes_used == 1
    assert revised.value.argument_fixes_used == 1
    assert revised.value.plan == Plan.model_validate(REVISED_PLAN)
    assert after.next == ("review",)


@pytest.mark.anyio
async def test_a_revision_round_that_would_go_over_four_tool_calls_is_not_run() -> None:
    model = FakeChatModel(
        replies=[
            ENOUGH,
            WRITE_PLAN,
            AIMessage(
                "",
                tool_calls=[search(f"part {n}", f"call_{n}") for n in range(1, 6)],
            ),
        ]
    )
    sent: list[httpx2.Request] = []

    _, _, revised, after = await plan_then_revise(model, sent)

    assert sent == []
    assert len(revised.value.missing) == 5
    assert after.next == ()


@pytest.mark.anyio
async def test_a_checkpoint_gives_back_the_plan_as_a_plan_not_a_dict() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[search("login", "call_1")]),
            ENOUGH,
            WRITE_PLAN,
        ]
    )
    client = backend([], truncated_queries=set())
    # Listing types makes the saver rebuild nothing else, as LangGraph will by default.
    checkpointer = InMemorySaver(
        serde=JsonPlusSerializer(allowed_msgpack_modules=CHECKPOINT_TYPES)
    )
    graph = build_graph(
        PlanningNodes(model, build_tools(client)), checkpointer=checkpointer
    )
    run: RunnableConfig = {"configurable": {"thread_id": "run-1"}}

    await graph.ainvoke(  # pyright: ignore[reportUnknownMemberType]
        AgentState(goal=GOAL, today=TODAY), run, version="v2"
    )
    saved = (await graph.aget_state(run)).values
    await client.aclose()

    assert isinstance(saved["plan"], Plan)
    assert isinstance(saved["today"], date)
    assert all(isinstance(message, BaseMessage) for message in saved["messages"])
