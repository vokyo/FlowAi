import json
from datetime import date
from uuid import UUID

import httpx2
import pytest
from fake_chat_model import FakeChatModel
from langchain_core.messages import (
    AIMessage,
    AnyMessage,
    HumanMessage,
    SystemMessage,
    ToolCall,
    ToolMessage,
)

from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import AgentState
from flowai_agent.models.plan import Plan
from flowai_agent.tools.client import BackendClient
from flowai_agent.tools.definitions import build_tools

GOAL = "Clear the login tech debt within two weeks"
TODAY = date(2026, 10, 4)
ISSUES: dict[str, object] = {
    "items": [
        {
            "id": "0b8c6a52-6a3e-4d6b-9a39-5f1d2c3b4a51",
            "title": "Fix login timeout",
            "status": "IN_PROGRESS",
            "priority": "HIGH",
            "assigneeUserId": None,
            "assigneeDisplayName": None,
        }
    ],
    "truncated": False,
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
SEARCH_LOGIN: ToolCall = {
    "name": "search_project_issues",
    "args": {"query": "login"},
    "id": "call_A",
}
LIST_MEMBERS: ToolCall = {"name": "get_project_members", "args": {}, "id": "call_B"}
ANN = "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60"
STRANGER = "5e0c1a2b-3d4e-4f60-8a7b-9c0d1e2f3a4b"
SEARCH_TOO_MANY: ToolCall = {
    "name": "search_project_issues",
    "args": {"query": "login", "limit": 50},
    "id": "call_A",
}
PLAN: dict[str, object] = {
    "overview": "Rate-limit login, then make token expiry configurable.",
    "items": [
        {
            "clientItemId": "item-1",
            "title": "Rate-limit the login endpoint",
            "priority": "HIGH",
            "suggestedAssigneeUserId": "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60",
            "dueDate": "2026-10-08",
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


def backend(sent: list[httpx2.Request]) -> BackendClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        if request.url.path.endswith("/issues"):
            return httpx2.Response(200, json=ISSUES)
        return httpx2.Response(200, json=MEMBERS)

    return BackendClient("http://backend", "agent-token", httpx2.MockTransport(handler))


def flaky_backend(
    sent: list[httpx2.Request], outcomes: list[int | Exception]
) -> BackendClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        outcome = outcomes[min(len(sent), len(outcomes)) - 1]
        if isinstance(outcome, Exception):
            raise outcome
        if outcome == 200:
            return httpx2.Response(200, json=ISSUES)
        error: dict[str, object] = {"code": "X", "message": f"status {outcome}"}
        return httpx2.Response(outcome, json=error)

    return BackendClient("http://backend", "agent-token", httpx2.MockTransport(handler))


def state_with(
    messages: list[AnyMessage], decision_rounds_used: int = 0, tool_calls_used: int = 0
) -> AgentState:
    return AgentState(
        goal=GOAL,
        today=TODAY,
        messages=messages,
        decision_rounds_used=decision_rounds_used,
        tool_calls_used=tool_calls_used,
    )


@pytest.mark.anyio
async def test_write_prompt_opens_with_the_rules_for_today_and_the_goal() -> None:
    client = backend([])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))

    update = await nodes.write_prompt(
        AgentState(goal=GOAL, today=TODAY, max_decision_rounds=3, max_tool_calls=5)
    )
    await client.aclose()

    rules, goal = update["messages"]
    assert isinstance(rules, SystemMessage)
    assert "2026-10-04" in rules.content
    # The last round must not call tools, or the run ends without a plan.
    assert "at most 2 replies and at most 5" in rules.content
    assert isinstance(goal, HumanMessage)
    assert goal.content == GOAL


@pytest.mark.anyio
async def test_ask_model_sends_the_whole_conversation_with_both_tools() -> None:
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN])
    model = FakeChatModel(replies=[reply])
    client = backend([])
    nodes = PlanningNodes(model, build_tools(client))
    conversation: list[AnyMessage] = [SystemMessage("rules"), HumanMessage(GOAL)]

    update = await nodes.ask_model(state_with(conversation, decision_rounds_used=1))
    await client.aclose()

    assert ["search_project_issues", "get_project_members"] in model.bound_tools
    assert model.received == [conversation]
    assert update["messages"][0].tool_calls == reply.tool_calls
    assert update["decision_rounds_used"] == 2


@pytest.mark.anyio
async def test_run_tools_answers_every_call_in_the_reply_by_its_id() -> None:
    sent: list[httpx2.Request] = []
    client = backend(sent)
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN, LIST_MEMBERS])

    update = await nodes.run_tools(
        state_with([HumanMessage(GOAL), reply], tool_calls_used=3)
    )
    await client.aclose()

    results = update["messages"]
    assert all(isinstance(result, ToolMessage) for result in results)
    assert [result.tool_call_id for result in results] == ["call_A", "call_B"]
    assert json.loads(results[0].content) == ISSUES
    assert json.loads(results[1].content) == MEMBERS
    assert sent[0].url.params["q"] == "login"
    assert update["tool_calls_used"] == 5


@pytest.mark.anyio
async def test_run_tools_leaves_calls_from_earlier_rounds_alone() -> None:
    sent: list[httpx2.Request] = []
    client = backend(sent)
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    earlier = [
        HumanMessage(GOAL),
        AIMessage("", tool_calls=[SEARCH_LOGIN]),
        ToolMessage(json.dumps(ISSUES), tool_call_id="call_A"),
    ]
    latest = AIMessage("", tool_calls=[LIST_MEMBERS])

    update = await nodes.run_tools(state_with([*earlier, latest], tool_calls_used=1))
    await client.aclose()

    assert [result.tool_call_id for result in update["messages"]] == ["call_B"]
    assert [request.url.path for request in sent] == [
        "/api/internal/agent/project/members"
    ]
    assert update["tool_calls_used"] == 2


@pytest.mark.anyio
async def test_generate_plan_asks_for_a_plan_under_the_rules_for_today() -> None:
    plan_call: ToolCall = {"name": "Plan", "args": PLAN, "id": "call_plan"}
    model = FakeChatModel(replies=[AIMessage("", tool_calls=[plan_call])])
    client = backend([])
    nodes = PlanningNodes(model, build_tools(client))
    conversation: list[AnyMessage] = [
        SystemMessage("rules"),
        HumanMessage(GOAL),
        AIMessage("I know enough to plan."),
    ]

    update = await nodes.generate_plan(state_with(conversation))
    await client.aclose()

    assert update["plan"] == Plan.model_validate(PLAN)
    sent = model.received[0]
    assert sent[:3] == conversation
    assert "2026-10-04" in sent[-1].text
    assert ["Plan"] in model.bound_tools


@pytest.mark.anyio
async def test_report_insufficient_lists_at_most_five_wanted_calls() -> None:
    client = backend([])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    wanted: list[ToolCall] = [
        {
            "name": "search_project_issues",
            "args": {"query": f"topic {n}"},
            "id": f"c{n}",
        }
        for n in range(1, 8)
    ]

    update = await nodes.report_insufficient(
        state_with([HumanMessage(GOAL), AIMessage("", tool_calls=wanted)])
    )
    await client.aclose()

    missing = update["missing"]
    assert len(missing) == 5
    assert "search_project_issues" in missing[0]
    assert "topic 1" in missing[0]


@pytest.mark.anyio
@pytest.mark.parametrize("status", [401, 403, 404])
async def test_run_tools_stops_at_once_when_the_backend_refuses_access(
    status: int,
) -> None:
    sent: list[httpx2.Request] = []
    client = flaky_backend(sent, [status])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN, LIST_MEMBERS])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    assert len(sent) == 1
    assert "search_project_issues" in update["failure_reason"]
    assert f"status {status}" in update["failure_reason"]
    assert update["tool_calls_used"] == 1


@pytest.mark.anyio
async def test_run_tools_retries_a_failing_backend_and_counts_every_attempt() -> None:
    sent: list[httpx2.Request] = []
    client = flaky_backend(sent, [503, 503, 200])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    assert len(sent) == 3
    assert json.loads(update["messages"][0].content) == ISSUES
    assert update["tool_calls_used"] == 3
    assert "failure_reason" not in update


@pytest.mark.anyio
@pytest.mark.parametrize(
    "outcome",
    [
        pytest.param(503, id="server error"),
        pytest.param(httpx2.ConnectError("refused"), id="backend unreachable"),
    ],
)
async def test_run_tools_gives_up_after_two_retries(outcome: int | Exception) -> None:
    sent: list[httpx2.Request] = []
    client = flaky_backend(sent, [outcome])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    assert len(sent) == 3
    assert "search_project_issues" in update["failure_reason"]
    assert update["tool_calls_used"] == 3


@pytest.mark.anyio
async def test_run_tools_does_not_retry_past_the_tool_call_budget() -> None:
    sent: list[httpx2.Request] = []
    client = flaky_backend(sent, [503])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN])
    state = AgentState(goal=GOAL, today=TODAY, messages=[reply], max_tool_calls=2)

    update = await nodes.run_tools(state)
    await client.aclose()

    assert len(sent) == 2
    assert update["tool_calls_used"] == 2
    assert "failure_reason" in update


@pytest.mark.anyio
async def test_run_tools_hands_invalid_arguments_back_to_the_model_once() -> None:
    sent: list[httpx2.Request] = []
    client = backend(sent)
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_TOO_MANY])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    assert sent == []
    [result] = update["messages"]
    assert result.tool_call_id == "call_A"
    assert "limit" in result.content
    assert update["argument_fixes_used"] == 1
    assert "failure_reason" not in update


@pytest.mark.anyio
async def test_run_tools_hands_a_request_the_backend_rejected_back_to_the_model() -> (
    None
):
    sent: list[httpx2.Request] = []
    client = flaky_backend(sent, [400])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_LOGIN])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    assert len(sent) == 1
    [result] = update["messages"]
    assert "status 400" in result.content
    assert update["argument_fixes_used"] == 1
    assert "failure_reason" not in update


@pytest.mark.anyio
async def test_run_tools_stops_when_the_arguments_are_invalid_a_second_time() -> None:
    client = backend([])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_TOO_MANY])
    state = AgentState(goal=GOAL, today=TODAY, messages=[reply], argument_fixes_used=1)

    update = await nodes.run_tools(state)
    await client.aclose()

    assert "invalid arguments again" in update["failure_reason"]


@pytest.mark.anyio
async def test_one_bad_call_does_not_stop_the_other_calls_in_the_round() -> None:
    sent: list[httpx2.Request] = []
    client = backend(sent)
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    reply = AIMessage("", tool_calls=[SEARCH_TOO_MANY, LIST_MEMBERS])

    update = await nodes.run_tools(state_with([HumanMessage(GOAL), reply]))
    await client.aclose()

    error, members = update["messages"]
    assert error.tool_call_id == "call_A"
    assert "limit" in error.content
    assert members.tool_call_id == "call_B"
    assert json.loads(members.content) == MEMBERS
    assert update["argument_fixes_used"] == 1


def plan_assigned_to(*owners: str | None) -> Plan:
    return Plan.model_validate(
        {
            "overview": "Rate-limit login, then make token expiry configurable.",
            "items": [
                {
                    "clientItemId": f"item-{n}",
                    "title": f"Task {n}",
                    "priority": "MEDIUM",
                    "suggestedAssigneeUserId": owner,
                }
                for n, owner in enumerate(owners, start=1)
            ],
        }
    )


@pytest.mark.anyio
async def test_check_plan_clears_assignees_who_are_not_in_the_member_list() -> None:
    client = backend([])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    state = AgentState(
        goal=GOAL,
        today=TODAY,
        messages=[
            AIMessage("", tool_calls=[LIST_MEMBERS]),
            ToolMessage(json.dumps(MEMBERS), tool_call_id="call_B"),
        ],
        plan=plan_assigned_to(ANN, STRANGER, None),
    )

    update = await nodes.check_plan(state)
    await client.aclose()

    owners = [item.suggestedAssigneeUserId for item in update["plan"].items]
    assert owners == [UUID(ANN), None, None]


@pytest.mark.anyio
@pytest.mark.parametrize(
    "conversation",
    [
        pytest.param(
            [
                AIMessage("", tool_calls=[SEARCH_LOGIN]),
                ToolMessage(json.dumps(ISSUES), tool_call_id="call_A"),
            ],
            id="never asked for members",
        ),
        pytest.param(
            [
                AIMessage("", tool_calls=[LIST_MEMBERS]),
                ToolMessage("Error: boom", tool_call_id="call_B"),
            ],
            id="the member call returned an error",
        ),
    ],
)
async def test_check_plan_clears_every_assignee_without_a_member_list(
    conversation: list[AnyMessage],
) -> None:
    client = backend([])
    nodes = PlanningNodes(FakeChatModel(replies=[]), build_tools(client))
    state = AgentState(
        goal=GOAL,
        today=TODAY,
        messages=conversation,
        plan=plan_assigned_to(ANN, STRANGER, None),
    )

    update = await nodes.check_plan(state)
    await client.aclose()

    owners = [item.suggestedAssigneeUserId for item in update["plan"].items]
    assert owners == [None, None, None]
