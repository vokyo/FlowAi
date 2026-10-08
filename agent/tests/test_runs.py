import time
from collections.abc import Callable, Iterator
from typing import Any

import httpx2
import pytest
from fake_chat_model import FakeChatModel
from fastapi.testclient import TestClient
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolCall
from langchain_core.outputs import ChatResult
from langgraph.checkpoint.memory import InMemorySaver

from flowai_agent.config import Settings
from flowai_agent.main import (
    app,
    get_backend_transport,
    get_chat_model,
    get_checkpointer,
    get_settings,
)
from flowai_agent.models.plan import Plan

TOKEN = "agent-token"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}
BODY: dict[str, object] = {
    "runId": "4f2a8e1c-9b3d-4c5e-8f7a-1b2c3d4e5f60",
    "goal": "Clear the login tech debt within two weeks",
    "today": "2026-10-04",
}
ISSUES: dict[str, object] = {"items": [], "truncated": False}
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
            "suggestedAssigneeUserId": "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60",
            "dueDate": "2026-10-08",
        },
        {
            "clientItemId": "item-2",
            "title": "Make expiry configurable",
            "priority": "LOW",
        },
        {
            "clientItemId": "item-3",
            "title": "Remove remember-me code",
            "priority": "LOW",
        },
    ],
}
SEARCH: ToolCall = {
    "name": "search_project_issues",
    "args": {"query": "login"},
    "id": "c1",
}
MEMBERS_CALL: ToolCall = {"name": "get_project_members", "args": {}, "id": "c2"}
WRITE_PLAN: ToolCall = {"name": "Plan", "args": PLAN, "id": "c3"}
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
            "title": "Remove remember-me code",
            "priority": "LOW",
        },
    ],
}
WRITE_REVISED_PLAN: ToolCall = {"name": "Plan", "args": REVISED_PLAN, "id": "c4"}
RESUME = f"/runs/{BODY['runId']}/resume"

Handler = Callable[[httpx2.Request], httpx2.Response]


def healthy_backend(sent: list[httpx2.Request]) -> Handler:
    def handler(request: httpx2.Request) -> httpx2.Response:
        sent.append(request)
        if request.url.path.endswith("/issues"):
            return httpx2.Response(200, json=ISSUES)
        return httpx2.Response(200, json=MEMBERS)

    return handler


def serve(model: BaseChatModel, handler: Handler, **settings: Any) -> TestClient:
    app.dependency_overrides[get_chat_model] = lambda: model
    app.dependency_overrides[get_backend_transport] = lambda: httpx2.MockTransport(
        handler
    )
    app.dependency_overrides[get_settings] = lambda: Settings(
        backend_base_url="http://backend", **settings
    )
    app.dependency_overrides[get_checkpointer] = lambda: InMemorySaver()
    return TestClient(app)


@pytest.fixture(autouse=True)
def clear_overrides() -> Iterator[None]:
    yield
    app.dependency_overrides.clear()


def test_a_run_with_enough_information_returns_the_plan() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[SEARCH, MEMBERS_CALL]),
            AIMessage("I know enough to plan."),
            AIMessage("", tool_calls=[WRITE_PLAN]),
        ]
    )
    sent: list[httpx2.Request] = []

    response = serve(model, healthy_backend(sent)).post(
        "/runs", json=BODY, headers=HEADERS
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "PLANNED"
    assert body["plan"] == Plan.model_validate(PLAN).model_dump(mode="json")
    assert body["stats"] == {"decisionRounds": 2, "toolCalls": 2}
    assert [request.headers["Authorization"] for request in sent] == [
        f"Bearer {TOKEN}"
    ] * 2


def test_a_planned_run_is_saved_under_its_run_id_waiting_for_review() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[SEARCH, MEMBERS_CALL]),
            AIMessage("I know enough to plan."),
            AIMessage("", tool_calls=[WRITE_PLAN]),
        ]
    )
    client = serve(model, healthy_backend([]))
    checkpointer = InMemorySaver()
    app.dependency_overrides[get_checkpointer] = lambda: checkpointer

    response = client.post("/runs", json=BODY, headers=HEADERS)

    assert response.json()["status"] == "PLANNED"
    saved = checkpointer.get_tuple({"configurable": {"thread_id": BODY["runId"]}})
    assert saved is not None
    assert [write[1] for write in saved.pending_writes or []] == ["__interrupt__"]
    # The backend keeps this id with the version, to revise it from exactly here.
    paused_at = saved.config.get("configurable", {}).get("checkpoint_id")
    assert paused_at is not None
    assert response.json()["checkpointId"] == paused_at


def test_a_revision_continues_from_the_checkpoint_the_run_stopped_at() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[SEARCH, MEMBERS_CALL]),
            AIMessage("I know enough to plan."),
            AIMessage("", tool_calls=[WRITE_PLAN]),
            AIMessage("I know enough to revise."),
            AIMessage("", tool_calls=[WRITE_REVISED_PLAN]),
        ]
    )
    client = serve(model, healthy_backend([]))
    checkpointer = InMemorySaver()
    app.dependency_overrides[get_checkpointer] = lambda: checkpointer
    planned = client.post("/runs", json=BODY, headers=HEADERS).json()

    response = client.post(
        RESUME,
        json={"checkpointId": planned["checkpointId"], "feedback": "Drop item-2"},
        headers=HEADERS,
    )

    assert response.status_code == 200
    revised = response.json()
    assert revised["status"] == "PLANNED"
    assert revised["plan"] == Plan.model_validate(REVISED_PLAN).model_dump(mode="json")
    assert revised["checkpointId"] not in (None, planned["checkpointId"])
    assert model.received[3][-1].text.startswith(
        "Revise the plan above as follows: Drop item-2"
    )


def test_a_revision_from_a_checkpoint_not_waiting_for_review_is_refused() -> None:
    model = FakeChatModel(replies=[])

    response = serve(model, healthy_backend([])).post(
        RESUME,
        json={"checkpointId": "no-such-checkpoint", "feedback": "Drop item-2"},
        headers=HEADERS,
    )

    assert response.status_code == 409
    assert model.received == []


def test_a_revision_without_an_agent_token_is_refused() -> None:
    response = serve(FakeChatModel(replies=[]), healthy_backend([])).post(
        RESUME, json={"checkpointId": "c", "feedback": "Drop item-2"}
    )

    assert response.status_code == 401


@pytest.mark.parametrize(
    "body",
    [
        pytest.param({"checkpointId": "c", "feedback": "   "}, id="blank feedback"),
        pytest.param({"feedback": "Drop item-2"}, id="no checkpoint"),
        pytest.param(
            {"checkpoint_id": "c", "feedback": "Drop item-2"}, id="snake case field"
        ),
    ],
)
def test_a_revision_that_breaks_the_contract_is_rejected(
    body: dict[str, object],
) -> None:
    response = serve(FakeChatModel(replies=[]), healthy_backend([])).post(
        RESUME, json=body, headers=HEADERS
    )

    assert response.status_code == 422


def test_the_service_refuses_to_start_without_a_checkpoint_database(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("CHECKPOINT_DATABASE_URL", raising=False)

    with pytest.raises(RuntimeError, match="CHECKPOINT_DATABASE_URL is required"):
        with TestClient(app):
            pass


def test_a_run_searches_with_the_configured_mode() -> None:
    model = FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[SEARCH, MEMBERS_CALL]),
            AIMessage("I know enough to plan."),
            AIMessage("", tool_calls=[WRITE_PLAN]),
        ]
    )
    sent: list[httpx2.Request] = []

    serve(model, healthy_backend(sent), search_mode="semantic").post(
        "/runs", json=BODY, headers=HEADERS
    )

    searches = [r for r in sent if r.url.path.endswith("/issues")]
    assert [r.url.params["mode"] for r in searches] == ["semantic"]


def test_a_run_out_of_budget_says_what_is_missing() -> None:
    model = FakeChatModel(replies=[AIMessage("", tool_calls=[SEARCH])])
    sent: list[httpx2.Request] = []

    response = serve(model, healthy_backend(sent), max_decision_rounds=1).post(
        "/runs", json=BODY, headers=HEADERS
    )

    body = response.json()
    assert body["status"] == "INSUFFICIENT_INFO"
    assert len(body["missing"]) == 1
    assert body["plan"] is None
    assert body["checkpointId"] is None
    assert body["stats"] == {"decisionRounds": 1, "toolCalls": 0}
    assert sent == []


def test_a_refused_tool_call_fails_the_run_with_its_reason() -> None:
    model = FakeChatModel(replies=[AIMessage("", tool_calls=[SEARCH])])

    def refusing(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(401, json={"code": "UNAUTHORIZED", "message": "expired"})

    response = serve(model, refusing).post("/runs", json=BODY, headers=HEADERS)

    body = response.json()
    assert body["status"] == "FAILED"
    assert "search_project_issues" in body["reason"]
    assert body["stats"] == {"decisionRounds": 1, "toolCalls": 1}


def test_an_unexpected_error_fails_the_run_instead_of_crashing() -> None:
    model = FakeChatModel(replies=[])

    response = serve(model, healthy_backend([])).post(
        "/runs", json=BODY, headers=HEADERS
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED"
    assert "IndexError" in body["reason"]


class SlowModel(FakeChatModel):
    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        time.sleep(0.5)
        return super()._generate(messages, stop, run_manager, **kwargs)


def test_a_run_that_takes_too_long_fails_before_the_backend_gives_up() -> None:
    model = SlowModel(replies=[AIMessage("I know enough to plan.")])

    response = serve(model, healthy_backend([]), run_timeout_seconds=0.1).post(
        "/runs", json=BODY, headers=HEADERS
    )

    body = response.json()
    assert body["status"] == "FAILED"
    assert "longer than" in body["reason"]


def test_a_run_without_an_agent_token_is_refused() -> None:
    model = FakeChatModel(replies=[])

    response = serve(model, healthy_backend([])).post("/runs", json=BODY)

    assert response.status_code == 401
    assert model.received == []


@pytest.mark.parametrize(
    "body",
    [
        pytest.param({**BODY, "goal": "  "}, id="blank goal"),
        pytest.param({k: v for k, v in BODY.items() if k != "today"}, id="no today"),
        pytest.param({**BODY, "runId": "not-a-uuid"}, id="bad run id"),
    ],
)
def test_a_request_that_breaks_the_contract_is_rejected(
    body: dict[str, object],
) -> None:
    response = serve(FakeChatModel(replies=[]), healthy_backend([])).post(
        "/runs", json=body, headers=HEADERS
    )

    assert response.status_code == 422


class ClosableTransport(httpx2.MockTransport):
    closed = False

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.parametrize(
    "replies",
    [
        pytest.param(
            [
                AIMessage("I know enough to plan."),
                AIMessage("", tool_calls=[WRITE_PLAN]),
            ],
            id="run planned",
        ),
        pytest.param([], id="run failed"),
    ],
)
def test_the_backend_connection_is_closed_however_the_run_ends(
    replies: list[AIMessage],
) -> None:
    transport = ClosableTransport(healthy_backend([]))
    app.dependency_overrides[get_chat_model] = lambda: FakeChatModel(replies=replies)
    app.dependency_overrides[get_backend_transport] = lambda: transport
    app.dependency_overrides[get_settings] = lambda: Settings(
        backend_base_url="http://backend"
    )
    app.dependency_overrides[get_checkpointer] = lambda: InMemorySaver()

    TestClient(app).post("/runs", json=BODY, headers=HEADERS)

    assert transport.closed


def test_runs_fail_clearly_without_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)

    response = TestClient(app).post("/runs", json=BODY, headers=HEADERS)

    assert response.status_code == 503
