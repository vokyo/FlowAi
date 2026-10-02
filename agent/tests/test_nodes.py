import json
from datetime import date

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


def backend(sent: list[httpx2.Request]) -> BackendClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        if request.url.path.endswith("/issues"):
            return httpx2.Response(200, json=ISSUES)
        return httpx2.Response(200, json=MEMBERS)

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

    update = await nodes.write_prompt(AgentState(goal=GOAL, today=TODAY))
    await client.aclose()

    rules, goal = update["messages"]
    assert isinstance(rules, SystemMessage)
    assert "2026-10-04" in rules.content
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

    assert model.bound_tool_names == ["search_project_issues", "get_project_members"]
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
