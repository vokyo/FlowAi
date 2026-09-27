from fastapi.testclient import TestClient

from flowai_agent.main import app

client = TestClient(app)

GOAL = "clean the tech backlog in 2 weeks"


def test_valid_goal_returns_mock_result() -> None:
    response = client.post("/runs", json={"goal": GOAL})
    assert response.status_code == 200
    assert response.json() == {"status": "mock", "goal": GOAL}


def test_blank_goal_is_rejected() -> None:
    response = client.post("/runs", json={"goal": "  "})
    assert response.status_code == 422


def test_missing_goal_is_rejected() -> None:
    response = client.post("/runs", json={})
    assert response.status_code == 422
