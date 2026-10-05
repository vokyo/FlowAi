from datetime import date
from uuid import UUID

import pytest
from pydantic import ValidationError

from flowai_agent.models.plan import Plan, PlanItem

USER_ID = "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60"
ISSUE_ID = "0b8c6a52-6a3e-4d6b-9a39-5f1d2c3b4a51"


def item(number: int, **fields: object) -> dict[str, object]:
    body: dict[str, object] = {
        "clientItemId": f"item-{number}",
        "title": f"Task {number}",
        "priority": "MEDIUM",
    }
    return body | fields


def plan(items: list[dict[str, object]], **fields: object) -> dict[str, object]:
    body: dict[str, object] = {"overview": "Clear the login tech debt.", "items": items}
    return body | fields


def plan_with_first_item(**fields: object) -> dict[str, object]:
    return plan([item(1, **fields), item(2), item(3)])


def existing(**fields: object) -> dict[str, object]:
    body: dict[str, object] = {"issueId": ISSUE_ID, "reason": "Already planned."}
    return body | fields


def test_a_plan_round_trips_the_json_java_reads() -> None:
    body: dict[str, object] = {
        "overview": "Rate-limit login, make token expiry configurable, drop dead code.",
        "existingIssues": [
            {"issueId": ISSUE_ID, "reason": "Login rate limiting is already planned."}
        ],
        "items": [
            {
                "clientItemId": "item-1",
                "title": "Rate-limit the login endpoint",
                "description": "At most 10 attempts per IP per minute, then 429.",
                "priority": "HIGH",
                "suggestedAssigneeUserId": USER_ID,
                "dueDate": "2026-10-08",
            },
            {
                "clientItemId": "item-2",
                "title": "Make token expiry configurable",
                "description": None,
                "priority": "MEDIUM",
                "suggestedAssigneeUserId": None,
                "dueDate": None,
            },
            {
                "clientItemId": "item-3",
                "title": "Remove the unused remember-me code",
                "description": None,
                "priority": "LOW",
                "suggestedAssigneeUserId": USER_ID,
                "dueDate": "2026-10-16",
            },
        ],
    }

    parsed = Plan.model_validate(body)

    assert parsed.existingIssues[0].issueId == UUID(ISSUE_ID)
    assert parsed.items[0].suggestedAssigneeUserId == UUID(USER_ID)
    assert parsed.items[0].dueDate == date(2026, 10, 8)
    assert parsed.model_dump(mode="json") == body


def test_existing_issues_can_be_left_out() -> None:
    parsed = Plan.model_validate(plan([item(1)]))

    assert parsed.existingIssues == []


def test_optional_item_fields_can_be_left_out() -> None:
    parsed = PlanItem.model_validate(item(1))

    assert parsed.description is None
    assert parsed.suggestedAssigneeUserId is None
    assert parsed.dueDate is None


@pytest.mark.parametrize(
    "body",
    [
        pytest.param(plan([item(n) for n in range(1, 6)]), id="five items"),
        pytest.param(
            plan([], existingIssues=[existing(), existing()]),
            id="no new items when existing issues cover the goal",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing()] * 10),
            id="ten existing issues",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing(reason="r" * 500)]),
            id="reason at its maximum length",
        ),
        pytest.param(
            plan(
                [
                    item(
                        1,
                        clientItemId="i" * 100,
                        title="t" * 240,
                        description="d" * 10000,
                    ),
                    item(2),
                    item(3),
                ],
                overview="o" * 2000,
            ),
            id="every text at its maximum length",
        ),
    ],
)
def test_a_plan_right_at_the_limits_is_accepted(body: dict[str, object]) -> None:
    Plan.model_validate(body)


@pytest.mark.parametrize(
    ("body", "field"),
    [
        pytest.param(plan([item(n) for n in range(1, 7)]), ("items",), id="six items"),
        pytest.param(
            plan([item(1), item(2), item(3)], overview=""),
            ("overview",),
            id="empty overview",
        ),
        pytest.param(
            plan([item(1), item(2), item(3)], overview="o" * 2001),
            ("overview",),
            id="overview too long",
        ),
        pytest.param(
            plan_with_first_item(clientItemId=""),
            ("items", 0, "clientItemId"),
            id="empty clientItemId",
        ),
        pytest.param(
            plan_with_first_item(clientItemId="i" * 101),
            ("items", 0, "clientItemId"),
            id="clientItemId too long",
        ),
        pytest.param(
            plan_with_first_item(title=""), ("items", 0, "title"), id="empty title"
        ),
        pytest.param(
            plan_with_first_item(title="t" * 241),
            ("items", 0, "title"),
            id="title too long",
        ),
        pytest.param(
            plan_with_first_item(description="d" * 10001),
            ("items", 0, "description"),
            id="description too long",
        ),
        pytest.param(
            plan_with_first_item(priority="CRITICAL"),
            ("items", 0, "priority"),
            id="unknown priority",
        ),
        pytest.param(
            plan([{"clientItemId": "item-1", "title": "Task 1"}, item(2), item(3)]),
            ("items", 0, "priority"),
            id="missing priority",
        ),
        pytest.param(
            plan_with_first_item(suggestedAssigneeUserId="alice"),
            ("items", 0, "suggestedAssigneeUserId"),
            id="assignee is not a uuid",
        ),
        pytest.param(
            plan_with_first_item(dueDate="2026-13-01"),
            ("items", 0, "dueDate"),
            id="due date is not a date",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing()] * 11),
            ("existingIssues",),
            id="eleven existing issues",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing(issueId="CSV export")]),
            ("existingIssues", 0, "issueId"),
            id="existing issue id is not a uuid",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing(reason="")]),
            ("existingIssues", 0, "reason"),
            id="empty reason",
        ),
        pytest.param(
            plan([item(1)], existingIssues=[existing(reason="r" * 501)]),
            ("existingIssues", 0, "reason"),
            id="reason too long",
        ),
    ],
)
def test_a_plan_breaking_a_rule_is_rejected(
    body: dict[str, object], field: tuple[str | int, ...]
) -> None:
    with pytest.raises(ValidationError) as caught:
        Plan.model_validate(body)

    assert caught.value.errors()[0]["loc"] == field
