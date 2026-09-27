from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from flowai_agent.main import app, get_chat_model

GOAL = "clean the tech backlog in 2 weeks"


@pytest.fixture
def client() -> Iterator[TestClient]:
    fake_model = FakeListChatModel(responses=["fake plan"])
    app.dependency_overrides[get_chat_model] = lambda: fake_model
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_valid_goal_returns_mock_result(client: TestClient) -> None:
    response = client.post("/runs", json={"goal": GOAL})
    assert response.status_code == 200
    assert response.json() == {"status": "mock", "reply": "fake plan"}


def test_blank_goal_is_rejected(client: TestClient) -> None:
    response = client.post("/runs", json={"goal": "  "})
    assert response.status_code == 422


def test_missing_goal_is_rejected(client: TestClient) -> None:
    response = client.post("/runs", json={})
    assert response.status_code == 422


def test_runs_fail_clearly_without_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = TestClient(app).post("/runs", json={"goal": GOAL})
    assert response.status_code == 503
