import json
from datetime import date

import httpx2
import pytest
from fake_chat_model import FakeChatModel
from langchain_core.messages import AIMessage, ToolCall, ToolMessage
from langgraph.graph import END

from flowai_agent.graph.build import route_after_model, run_graph
from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import AgentState
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


def search(query: str, call_id: str) -> ToolCall:
    return {"name": "search_project_issues", "args": {"query": query}, "id": call_id}


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


@pytest.mark.parametrize(
    ("last", "next_node"),
    [
        pytest.param(
            AIMessage("", tool_calls=[list_members("call_1")]),
            "run_tools",
            id="asks for a tool",
        ),
        pytest.param(AIMessage("I know enough to plan."), END, id="asks for nothing"),
    ],
)
def test_the_models_last_reply_decides_where_the_graph_goes(
    last: AIMessage, next_node: str
) -> None:
    state = AgentState(goal=GOAL, today=TODAY, messages=[last])

    assert route_after_model(state) == next_node


@pytest.mark.anyio
async def test_the_loop_stops_once_the_model_has_what_it_needs() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage(
                "", tool_calls=[search("login", "call_1"), list_members("call_2")]
            ),
            AIMessage("I know enough to plan."),
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries=set())

    final = await run_graph(PlanningNodes(model, build_tools(client)), GOAL, TODAY)
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
    assert len(model.received) == 2
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
        ]
    )
    sent: list[httpx2.Request] = []
    client = backend(sent, truncated_queries={"login"})

    final = await run_graph(PlanningNodes(model, build_tools(client)), GOAL, TODAY)
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
    assert final.decision_rounds_used == 3
    assert final.tool_calls_used == 3
