"""Measure the planning agent between a floor and a ceiling on fixed goals.

The same goal is planned three ways, all with the same model and the same snapshot
of a project's issues and members:

- floor: one prompt with the goal and the members but no issues, so the plan
  knows nothing about the project;
- agent: the planning graph, which finds issues through its search tool;
- ceiling: one prompt with every issue in the project. No context can be more
  complete, so on projects this small it is the upper bound, and the agent is
  measured by how much of the gap from floor to ceiling it closes, and at what
  cost.

A judge model scores the three plans together and blind (shown as A, B and C in a
random order). A person checks a few verdicts by hand and marks which existing
issues matter for each goal in relevant-issues.md, so the agent's retrieval is
also measured without the judge (recall.py does this for earlier runs too).

Run from agent/ with the backend up and a user access token:

    FLOWAI_USER_TOKEN=... uv run --env-file ../.env python evals/baseline.py

Results go to docs/baseline/, named by date. A second run on the same day needs
RUN_LABEL=... to get its own files; the script will not overwrite a run.
"""

import asyncio
import json
import os
import random
import re
import time
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path
from statistics import fmean
from typing import Literal

import httpx2
from langchain_core.callbacks import get_usage_metadata_callback
from langchain_core.callbacks.usage import UsageMetadataCallbackHandler
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field

from flowai_agent.config import Settings
from flowai_agent.graph.build import run_graph
from flowai_agent.graph.nodes import PLAN_PROMPT, PlanningNodes
from flowai_agent.graph.state import AgentState
from flowai_agent.models.plan import Plan
from flowai_agent.tools.client import BackendClient
from flowai_agent.tools.definitions import build_tools

BACKEND = os.environ.get("FLOWAI_BACKEND_URL", "http://localhost:8080")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "gpt-4o")
OUTPUT_DIR = Path(__file__).resolve().parents[2] / "docs" / "baseline"
LABELS = OUTPUT_DIR / "relevant-issues.md"
MAX_MEMBER_RESULTS = 50
SPOT_CHECK = [1, 3, 8]

Group = Literal["floor", "agent", "ceiling"]
Best = Literal["floor", "agent", "ceiling", "tie"]
GROUPS: list[Group] = ["floor", "agent", "ceiling"]
LETTERS = "ABC"

GOALS: list[tuple[str, str]] = [
    ("Web Platform", "两周内把登录模块的技术债清掉"),
    (
        "Web Platform",
        "Harden authentication before the enterprise launch in three weeks",
    ),
    ("Web Platform", "Make the board feel real-time and fast this sprint"),
    ("Web Platform", "Improve how people get notified about their work"),
    ("Web Platform", "Plan the SAML SSO project for next month and assign it"),
    ("Web Platform", "Within one week, ship CSV export and saved filter views"),
    ("Web Platform", "Clean up the issue list experience in the next sprint"),
    ("Web Platform", "Add billing and paid subscription plans"),
    ("Mobile App", "Prepare the mobile app for the App Store launch in a month"),
    ("Mobile App", "Make the mobile app work well offline"),
]

RUBRIC = """Score each plan from 0 to 2 on six criteria:
- no_duplicates: 2 = proposes nothing that is already done, in progress or
  already a to-do issue; 1 = one such task; 0 = two or more.
- uses_project_info: 2 = tasks build on the existing issues; 1 = partly;
  0 = generic tasks that ignore the project.
- covers_goal: 2 = doing these tasks would mostly reach the goal; 1 = misses an
  important part; 0 = off the goal.
- actionable: 2 = every title could be started as an issue; 1 = about half;
  0 = mostly vague.
- timing: 2 = due dates fit the goal's time frame and order; 1 = some dates fall
  outside it or are in a poor order; 0 = clearly unreasonable.
- assignment: 2 = sensible owners, not all on one person and in line with who
  already works on what; 1 = acceptable; 0 = unreasonable.
A side that returned no plan scores 0 on every criterion.
Then say which plan is best overall (A, B, C or tie) and give short reasons."""

NONE_RELEVANT = "(none of these are relevant)"
LABEL_LINE = re.compile(r"^- \[(?P<mark>[ xX])\] (?:\[[A-Z_]+\] )?(?P<title>.+)$")


class ApiUser(BaseModel):
    id: str
    displayName: str


class ApiIssue(BaseModel):
    id: str
    title: str
    description: str | None
    status: str
    priority: str | None
    assignee: ApiUser | None
    createdAt: str


class ApiIssuePage(BaseModel):
    items: list[ApiIssue]
    nextCursor: str | None


class ApiMember(BaseModel):
    userId: str
    displayName: str
    role: str
    status: str


class ApiProject(BaseModel):
    id: str
    name: str


class Scores(BaseModel):
    no_duplicates: int = Field(ge=0, le=2)
    uses_project_info: int = Field(ge=0, le=2)
    covers_goal: int = Field(ge=0, le=2)
    actionable: int = Field(ge=0, le=2)
    timing: int = Field(ge=0, le=2)
    assignment: int = Field(ge=0, le=2)

    def total(self) -> int:
        return sum(self.model_dump().values())


class Verdict(BaseModel):
    plan_a: Scores
    plan_b: Scores
    plan_c: Scores
    best: Literal["A", "B", "C", "tie"]
    reasons: str

    def scores(self) -> list[Scores]:
        return [self.plan_a, self.plan_b, self.plan_c]


@dataclass
class Snapshot:
    issues: list[ApiIssue]
    members: list[ApiMember]


@dataclass
class Outcome:
    plan: Plan | None
    missing: list[str]
    failure: str | None
    model_calls: int
    tool_calls: int
    tokens: int
    seconds: float
    trace: list[str] = field(default_factory=list[str])
    searches: int = 0
    empty_searches: int = 0
    seen_issue_ids: list[str] = field(default_factory=list[str])

    @property
    def issues_seen(self) -> int:
        return len(self.seen_issue_ids)

    def to_json(self) -> dict[str, object]:
        return {
            "plan": self.plan.model_dump(mode="json") if self.plan else None,
            "missing": self.missing,
            "failure": self.failure,
            "modelCalls": self.model_calls,
            "toolCalls": self.tool_calls,
            "tokens": self.tokens,
            "seconds": round(self.seconds, 1),
            "trace": self.trace,
            "searches": self.searches,
            "emptySearches": self.empty_searches,
            "issuesSeen": self.issues_seen,
            "seenIssueIds": self.seen_issue_ids,
        }


@dataclass
class Result:
    number: int
    project: str
    goal: str
    shown: list[Group]
    """The group behind plan A, B and C."""
    outcomes: dict[Group, Outcome]
    scores: dict[Group, Scores]
    best: Best
    reasons: str
    plans: list[str]
    """Plans A, B and C as the judge saw them."""

    def to_json(self) -> dict[str, object]:
        return {
            "number": self.number,
            "project": self.project,
            "goal": self.goal,
            "shownAs": dict(zip(LETTERS, self.shown, strict=True)),
            **{group: self.outcomes[group].to_json() for group in GROUPS},
            "scores": {group: self.scores[group].model_dump() for group in GROUPS},
            "best": self.best,
            "judgeReasons": self.reasons,
            "plans": dict(zip(LETTERS, self.plans, strict=True)),
        }


def issue_item(issue: ApiIssue) -> dict[str, object]:
    return {
        "id": issue.id,
        "title": issue.title,
        "status": issue.status,
        "priority": issue.priority,
        "assigneeUserId": issue.assignee.id if issue.assignee else None,
        "assigneeDisplayName": issue.assignee.displayName if issue.assignee else None,
    }


def member_item(member: ApiMember) -> dict[str, object]:
    return {
        "userId": member.userId,
        "displayName": member.displayName,
        "role": member.role,
    }


async def fetch_snapshot(http: httpx2.AsyncClient, project_id: str) -> Snapshot:
    issues: list[ApiIssue] = []
    cursor: str | None = None
    while True:
        params: dict[str, str | int] = {"projectId": project_id, "limit": 100}
        if cursor is not None:
            params["cursor"] = cursor
        response = await http.get("/api/issues", params=params)
        page = ApiIssuePage.model_validate_json(response.raise_for_status().content)
        issues += page.items
        cursor = page.nextCursor
        if cursor is None:
            break
    response = await http.get(f"/api/projects/{project_id}/members")
    members = [
        ApiMember.model_validate(member)
        for member in response.raise_for_status().json()
        if member["status"] == "ACTIVE"
    ]
    return Snapshot(issues, members)


async def fetch_snapshots(token: str) -> dict[str, Snapshot]:
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx2.AsyncClient(base_url=BACKEND, headers=headers) as http:
        response = await http.get("/api/projects")
        projects = [
            ApiProject.model_validate(project)
            for project in response.raise_for_status().json()
        ]
        return {
            project.name: await fetch_snapshot(http, project.id) for project in projects
        }


def matching_issues(snapshot: Snapshot, query: str | None) -> list[ApiIssue]:
    """The backend's search rule: the whole query as one case-insensitive piece of
    text in the title or description, newest first."""
    needle = (query or "").strip().lower()
    newest_first = sorted(
        snapshot.issues, key=lambda issue: (issue.createdAt, issue.id), reverse=True
    )
    return [
        issue
        for issue in newest_first
        if needle in issue.title.lower() or needle in (issue.description or "").lower()
    ]


def internal_api(snapshot: Snapshot) -> httpx2.MockTransport:
    """Answers the agent's two internal endpoints from the snapshot, the way the
    backend does, with one extra row to tell whether the results were cut off."""

    def handler(request: httpx2.Request) -> httpx2.Response:
        if request.url.path.endswith("/project/issues"):
            matches = matching_issues(snapshot, request.url.params.get("q"))
            limit = int(request.url.params["limit"])
            body = {
                "items": [issue_item(issue) for issue in matches[:limit]],
                "truncated": len(matches) > limit,
            }
            return httpx2.Response(200, json=body)
        if request.url.path.endswith("/project/members"):
            members = snapshot.members[:MAX_MEMBER_RESULTS]
            body = {
                "items": [member_item(member) for member in members],
                "truncated": len(snapshot.members) > MAX_MEMBER_RESULTS,
            }
            return httpx2.Response(200, json=body)
        return httpx2.Response(404)

    return httpx2.MockTransport(handler)


def total_tokens(usage: UsageMetadataCallbackHandler) -> int:
    return sum(item["total_tokens"] for item in usage.usage_metadata.values())


def found_issue_ids(tool_output: str) -> list[str]:
    """The issue ids in a search result, or none when the tool returned an error."""
    try:
        items = json.loads(tool_output)["items"]
    except ValueError, KeyError, TypeError:
        return []
    return [item["id"] for item in items]


async def run_agent(
    model: BaseChatModel, snapshot: Snapshot, goal: str, today: date, settings: Settings
) -> Outcome:
    client = BackendClient("http://snapshot", "unused", internal_api(snapshot))
    return await run_planning(model, client, goal, today, settings)


async def run_planning(
    model: BaseChatModel,
    client: BackendClient,
    goal: str,
    today: date,
    settings: Settings,
) -> Outcome:
    """Runs the planning graph with the given backend client and closes it."""
    start = AgentState(
        goal=goal,
        today=today,
        max_decision_rounds=settings.max_decision_rounds,
        max_tool_calls=settings.max_tool_calls,
    )
    with get_usage_metadata_callback() as usage:
        began = time.perf_counter()
        final = await run_graph(
            PlanningNodes(model, build_tools(client, settings.search_max_results)),
            start,
        )
        seconds = time.perf_counter() - began
    await client.aclose()
    outputs = {
        message.tool_call_id: str(message.content)
        for message in final.messages
        if isinstance(message, ToolMessage)
    }
    trace: list[str] = []
    searches = empty_searches = 0
    seen: set[str] = set()
    for message in final.messages:
        if not isinstance(message, AIMessage):
            continue
        for call in message.tool_calls:
            line = f"{call['name']}({json.dumps(call['args'], ensure_ascii=False)})"
            output = outputs.get(call["id"] or "")
            if output is None:
                line += " -> not run"
            elif call["name"] == "search_project_issues":
                found = found_issue_ids(output)
                searches += 1
                empty_searches += not found
                seen.update(found)
                line += f" -> {len(found)}"
            trace.append(line)
    return Outcome(
        plan=final.plan,
        missing=final.missing,
        failure=final.failure_reason,
        model_calls=final.decision_rounds_used + (1 if final.plan else 0),
        tool_calls=final.tool_calls_used,
        tokens=total_tokens(usage),
        seconds=seconds,
        trace=trace,
        searches=searches,
        empty_searches=empty_searches,
        seen_issue_ids=sorted(seen),
    )


async def run_single_prompt(
    model: BaseChatModel,
    snapshot: Snapshot,
    goal: str,
    today: date,
    *,
    with_issues: bool,
) -> Outcome:
    """The ceiling when with_issues is true (every issue in one prompt), the floor
    when it is false (members only)."""
    members = [member_item(member) for member in snapshot.members]
    if with_issues:
        intro = "Every issue in the project and every member who can be assigned:\n"
        data: dict[str, object] = {
            "issues": [issue_item(issue) for issue in snapshot.issues],
            "members": members,
        }
    else:
        intro = "Every member who can be assigned:\n"
        data = {"members": members}
    messages = [
        SystemMessage(f"You plan work for one software project. Today is {today}."),
        HumanMessage(goal),
        HumanMessage(intro + json.dumps(data, ensure_ascii=False)),
        HumanMessage(PLAN_PROMPT.format(today=today.isoformat())),
    ]
    with get_usage_metadata_callback() as usage:
        began = time.perf_counter()
        plan = await model.with_structured_output(Plan).ainvoke(messages)
        seconds = time.perf_counter() - began
    return Outcome(
        plan=Plan.model_validate(plan),
        missing=[],
        failure=None,
        model_calls=1,
        tool_calls=0,
        tokens=total_tokens(usage),
        seconds=seconds,
        seen_issue_ids=[issue.id for issue in snapshot.issues] if with_issues else [],
    )


def describe(outcome: Outcome, members: dict[str, str]) -> str:
    if outcome.plan is None:
        reason = outcome.failure or "; ".join(outcome.missing)
        return f"No plan. The run ended with: {reason}"
    lines = [f"Overview: {outcome.plan.overview}"]
    for item in outcome.plan.items:
        owner = members.get(str(item.suggestedAssigneeUserId), "unassigned")
        lines.append(
            f"- [{item.priority}] {item.title} (owner: {owner}, due: {item.dueDate})"
            + (f"\n  {item.description}" if item.description else "")
        )
    return "\n".join(lines)


def existing_issues(snapshot: Snapshot) -> str:
    return "\n".join(
        f"- [{issue.status}] {issue.title}"
        + (f" (owner: {issue.assignee.displayName})" if issue.assignee else "")
        for issue in snapshot.issues
    )


async def judge(
    model: BaseChatModel, goal: str, today: date, snapshot: Snapshot, plans: list[str]
) -> Verdict:
    members = ", ".join(member.displayName for member in snapshot.members)
    shown = "\n\n".join(
        f"Plan {letter}:\n{plan}" for letter, plan in zip(LETTERS, plans, strict=True)
    )
    prompt = (
        f"Today is {today}. A team asked for a plan for this goal:\n{goal}\n\n"
        f"Existing issues in the project:\n{existing_issues(snapshot)}\n\n"
        f"Members: {members}\n\n{shown}\n\n{RUBRIC}"
    )
    verdict = await model.with_structured_output(Verdict).ainvoke(
        [HumanMessage(prompt)]
    )
    return Verdict.model_validate(verdict)


def label_template(snapshots: dict[str, Snapshot]) -> str:
    lines = [
        "# Relevant existing issues per goal",
        "",
        "For each goal, change [ ] to [x] for every existing issue a good plan should "
        "know about: the plan would duplicate it, build on it or depend on it. If "
        f"none is, mark {NONE_RELEVANT}. A goal with nothing marked counts as not "
        "labeled yet. Do not edit the titles.",
    ]
    for number, (project, goal) in enumerate(GOALS, start=1):
        lines += ["", f"## {number}. {goal} ({project})", "", f"- [ ] {NONE_RELEVANT}"]
        lines += [
            f"- [ ] [{issue.status}] {issue.title}"
            for issue in snapshots[project].issues
        ]
    return "\n".join(lines) + "\n"


def read_labels(text: str, snapshots: dict[str, Snapshot]) -> dict[int, set[str]]:
    """Goal number to the ids of the issues marked relevant, for labeled goals."""
    labels: dict[int, set[str]] = {}
    number = 0
    for line in text.splitlines():
        heading = re.match(r"^## (\d+)\. ", line)
        if heading:
            number = int(heading.group(1))
            continue
        match = LABEL_LINE.match(line)
        if number == 0 or match is None or match["mark"] == " ":
            continue
        relevant = labels.setdefault(number, set())
        if match["title"] == NONE_RELEVANT:
            continue
        project = GOALS[number - 1][0]
        ids = {issue.title: issue.id for issue in snapshots[project].issues}
        if match["title"] not in ids:
            raise SystemExit(f"{LABELS.name}, goal {number}: no issue titled {line!r}")
        relevant.add(ids[match["title"]])
    return labels


def recall(seen: list[str], relevant: set[str]) -> float | None:
    return len(relevant.intersection(seen)) / len(relevant) if relevant else None


def percent(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.0%}"


def report(
    results: list[Result],
    snapshots: dict[str, Snapshot],
    labels: dict[int, set[str]],
    model: str,
    today: date,
) -> str:
    count = len(results)
    recalls = {
        r.number: recall(r.outcomes["agent"].seen_issue_ids, labels[r.number])
        for r in results
        if r.number in labels
    }
    lines = [
        f"# Floor, agent and ceiling ({today})",
        "",
        f"All three plan with {model} from the same project snapshot. Floor: one "
        "prompt with no issues. Ceiling: one prompt with every issue. Judge: "
        f"{JUDGE_MODEL} at temperature 0, scoring the three plans together and "
        "blind (shown as A, B and C in a random order). Scores are out of 12.",
        "",
        "| # | Goal | Floor | Agent | Ceiling | Best | Agent issues seen "
        "| Agent recall | Agent tokens | Ceiling tokens | Agent s | Ceiling s |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        agent, ceiling = r.outcomes["agent"], r.outcomes["ceiling"]
        lines.append(
            f"| {r.number} | {r.goal} | {r.scores['floor'].total()} "
            f"| {r.scores['agent'].total()} | {r.scores['ceiling'].total()} "
            f"| {r.best} | {agent.issues_seen} | {percent(recalls.get(r.number))} "
            f"| {agent.tokens} | {ceiling.tokens} | {agent.seconds:.1f} "
            f"| {ceiling.seconds:.1f} |"
        )
    best = {name: sum(r.best == name for r in results) for name in [*GROUPS, "tie"]}
    averages = {g: fmean(r.scores[g].total() for r in results) for g in GROUPS}
    lines += [
        "",
        f"Best plan: floor {best['floor']}, agent {best['agent']}, "
        f"ceiling {best['ceiling']}, tie {best['tie']} (of {count}).",
        "",
        "| | Floor | Agent | Ceiling |",
        "|---|---|---|---|",
        "| score | " + " | ".join(f"{averages[g]:.1f}" for g in GROUPS) + " |",
    ]
    for criterion in Scores.model_fields:
        lines.append(
            f"| {criterion} | "
            + " | ".join(
                f"{fmean(getattr(r.scores[g], criterion) for r in results):.2f}"
                for g in GROUPS
            )
            + " |"
        )
    for name, unit in [("tokens", ".0f"), ("seconds", ".1f")]:
        lines.append(
            f"| {name} | "
            + " | ".join(
                format(fmean(getattr(r.outcomes[g], name) for r in results), unit)
                for g in GROUPS
            )
            + " |"
        )
    gap = averages["ceiling"] - averages["floor"]
    lines.append("")
    if gap > 0:
        share = (averages["agent"] - averages["floor"]) / gap
        lines.append(
            f"The agent closes {share:.0%} of the {gap:.1f}-point gap from floor to "
            "ceiling."
        )
    else:
        lines.append(
            "The ceiling did not score above the floor, so these scores cannot show "
            "how much of the gap the agent closes."
        )
    lines += [
        "",
        "| Project | Issues | Agent tokens | Ceiling tokens | Agent / ceiling "
        "| Agent s / ceiling s |",
        "|---|---|---|---|---|---|",
    ]
    for project, snapshot in snapshots.items():
        rows = [r for r in results if r.project == project]
        if not rows:
            continue
        agent_tokens = fmean(r.outcomes["agent"].tokens for r in rows)
        ceiling_tokens = fmean(r.outcomes["ceiling"].tokens for r in rows)
        agent_seconds = fmean(r.outcomes["agent"].seconds for r in rows)
        ceiling_seconds = fmean(r.outcomes["ceiling"].seconds for r in rows)
        lines.append(
            f"| {project} | {len(snapshot.issues)} | {agent_tokens:.0f} "
            f"| {ceiling_tokens:.0f} | {agent_tokens / ceiling_tokens:.0%} "
            f"| {agent_seconds / ceiling_seconds:.1f}x |"
        )
    labeled = [value for value in recalls.values() if value is not None]
    agents = [r.outcomes["agent"] for r in results]
    seen_per_goal = fmean(a.issues_seen for a in agents)
    lines += [
        "",
        "| Agent retrieval | Value |",
        "|---|---|",
        f"| searches | {sum(a.searches for a in agents)} |",
        f"| searches that found nothing | {sum(a.empty_searches for a in agents)} |",
        "| goals planned without seeing any issue | "
        f"{sum(1 for a in agents if a.issues_seen == 0)} of {count} |",
        f"| distinct issues seen per goal | {seen_per_goal:.1f} |",
        "| recall of the issues marked relevant | "
        + (
            f"{fmean(labeled):.0%} over {len(labeled)} goals"
            if labeled
            else "n/a (not labeled yet)"
        )
        + " |",
        f"| runs without a plan | {sum(1 for a in agents if a.plan is None)} |",
        "",
        "## Details",
    ]
    for r in results:
        shown = ", ".join(
            f"{letter} = {group}"
            for letter, group in zip(LETTERS, r.shown, strict=True)
        )
        lines += [
            "",
            f"### {r.number}. {r.goal}",
            "",
            f"Shown as: {shown}.",
            "",
            "Agent tool calls: " + ("; ".join(r.outcomes["agent"].trace) or "none"),
            "",
            f"Judge: {r.reasons}",
        ]
    return "\n".join(lines) + "\n"


def spot_check(results: list[Result], snapshots: dict[str, Snapshot]) -> str:
    lines = [
        "# Spot check (score these yourself before opening the report)",
        "",
        "Score each plan 0-2 on: no_duplicates, uses_project_info, covers_goal, "
        "actionable, timing, assignment. Then pick the best plan, or tie.",
    ]
    for r in results:
        if r.number not in SPOT_CHECK:
            continue
        lines += [
            "",
            f"## {r.number}. {r.goal}",
            "",
            "Existing issues:",
            existing_issues(snapshots[r.project]),
        ]
        for letter, plan in zip(LETTERS, r.plans, strict=True):
            lines += ["", f"### Plan {letter}", plan]
        lines += [
            "",
            "| Criterion | Plan A | Plan B | Plan C |",
            "|---|---|---|---|",
            *[f"| {criterion} | | | |" for criterion in Scores.model_fields],
            "",
            "Best overall (A / B / C / tie):",
        ]
    return "\n".join(lines) + "\n"


async def main() -> None:
    settings = Settings()
    if settings.openai_api_key is None:
        raise SystemExit("OPENAI_API_KEY is not set")
    token = os.environ["FLOWAI_USER_TOKEN"]
    model = ChatOpenAI(model=settings.ai_model, api_key=settings.openai_api_key)
    judge_model = ChatOpenAI(
        model=JUDGE_MODEL, api_key=settings.openai_api_key, temperature=0
    )
    today = datetime.now(UTC).date()
    label = os.environ.get("RUN_LABEL")
    stamp = f"{today.isoformat()}-{label}" if label else today.isoformat()
    outputs = {
        name: OUTPUT_DIR / f"{name}-{stamp}.{suffix}"
        for name, suffix in [
            ("results", "json"),
            ("report", "md"),
            ("spot-check", "md"),
        ]
    }
    if any(path.exists() for path in outputs.values()):
        raise SystemExit(
            f"{outputs['results']} already exists; set RUN_LABEL to keep both runs"
        )
    order = random.Random(20261003)
    snapshots = await fetch_snapshots(token)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    if not LABELS.exists():
        LABELS.write_text(label_template(snapshots))
        print(f"wrote {LABELS} for a person to fill in")

    results: list[Result] = []
    for number, (project, goal) in enumerate(GOALS, start=1):
        snapshot = snapshots[project]
        members = {member.userId: member.displayName for member in snapshot.members}
        outcomes: dict[Group, Outcome] = {
            "floor": await run_single_prompt(
                model, snapshot, goal, today, with_issues=False
            ),
            "agent": await run_agent(model, snapshot, goal, today, settings),
            "ceiling": await run_single_prompt(
                model, snapshot, goal, today, with_issues=True
            ),
        }
        shown = list(GROUPS)
        order.shuffle(shown)
        plans = [describe(outcomes[group], members) for group in shown]
        verdict = await judge(judge_model, goal, today, snapshot, plans)
        best: Best = (
            "tie" if verdict.best == "tie" else shown[LETTERS.index(verdict.best)]
        )
        results.append(
            Result(
                number=number,
                project=project,
                goal=goal,
                shown=shown,
                outcomes=outcomes,
                scores=dict(zip(shown, verdict.scores(), strict=True)),
                best=best,
                reasons=verdict.reasons,
                plans=plans,
            )
        )
        # No scores here, so the spot check stays blind.
        print(f"{number:>2}/{len(GOALS)} done")

    labels = read_labels(LABELS.read_text(), snapshots)
    outputs["results"].write_text(
        json.dumps([r.to_json() for r in results], ensure_ascii=False, indent=2)
    )
    outputs["report"].write_text(
        report(results, snapshots, labels, settings.ai_model, today)
    )
    outputs["spot-check"].write_text(spot_check(results, snapshots))
    print(f"written to {outputs['report']}")


if __name__ == "__main__":
    asyncio.run(main())
