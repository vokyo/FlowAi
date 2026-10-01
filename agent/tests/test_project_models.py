from uuid import UUID

from flowai_agent.models.project import IssueSearchResponse, ProjectMemberResponse

ISSUE_ID = "0b8c6a52-6a3e-4d6b-9a39-5f1d2c3b4a51"
USER_ID = "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60"


def test_issue_search_response_parses_backend_json() -> None:
    body = {
        "items": [
            {
                "id": ISSUE_ID,
                "title": "Fix login timeout",
                "status": "IN_PROGRESS",
                "priority": None,
                "assigneeUserId": None,
                "assigneeDisplayName": None,
            }
        ],
        "truncated": False,
    }

    response = IssueSearchResponse.model_validate(body)
    assert response.items[0].id == UUID(ISSUE_ID)
    assert response.items[0].assigneeUserId is None
    assert response.truncated is False


def test_project_member_search_response_parses_backend_json() -> None:
    body = {
        "items": [{"userId": USER_ID, "displayName": "test", "role": "OWNER"}],
        "truncated": True,
    }
    response = ProjectMemberResponse.model_validate(body)
    assert response.items[0].userId == UUID(USER_ID)
    assert response.items[0].role == "OWNER"
    assert response.items[0].displayName == "test"
    assert response.truncated is True
