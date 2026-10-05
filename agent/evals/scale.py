"""Scale experiment: the Web Platform goals on a 1000-issue copy of the project.

build: generates distractor issues with a model and saves them to
    docs/baseline/scale-issues.json (kept, so every run uses the same data), then
    creates the project "Web Platform XL" through the public API: the 56 Web Platform
    issues and the distractors in one shuffled order, so the newest issues are a random
    mix. The backend queues an embedding for each new issue.

run: plans goals 1-8 on that project five ways (see APPROACHES) and reports recall
    of the issues marked relevant in relevant-issues.md, tokens and time.

The distractors are about topics none of the goals covers, so the 36 relevance labels
still hold; a few share the goals' words on purpose ("export", "filter", "real-time",
"subscription") to test that search is not fooled by vocabulary alone.

Run from agent/ with the backend up, embeddings enabled, and a user access token:

    FLOWAI_USER_TOKEN=... uv run --env-file ../.env python evals/scale.py build
    FLOWAI_USER_TOKEN=... uv run --env-file ../.env python evals/scale.py run
"""

import asyncio
import json
import os
import random
import re
import sys
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime
from statistics import fmean

import httpx2
from baseline import (
    BACKEND,
    GOALS,
    LABELS,
    OUTPUT_DIR,
    Outcome,
    Snapshot,
    fetch_snapshots,
    percent,
    read_labels,
    recall,
    run_planning,
    run_single_prompt,
)
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field
from search_modes import agent_token, claims_of, project_ids

from flowai_agent.config import Settings
from flowai_agent.tools.client import BackendClient

XL_PROJECT = "Web Platform XL"
TARGET_ISSUES = 1000
DISTRACTORS = OUTPUT_DIR / "scale-issues.json"
GENERATOR_MODEL = "gpt-4o-mini"

# Areas of a project management app that none of goals 1-8 is about.
TOPICS = [
    "project templates and project creation",
    "workspace switching and the workspace sidebar",
    "user profile pages, avatars and display names",
    "the markdown editor for issue descriptions",
    "file attachments on issues",
    "keyboard shortcuts and the command palette",
    "the analytics overview page and its charts",
    "labels: creating, renaming, colours and merging",
    "configurable workflow states and columns",
    "archiving and restoring projects",
    "recurring maintenance issues",
    "due dates, time zones and date pickers",
    "a calendar view of issues",
    "a roadmap timeline view",
    "dependencies between issues",
    "the GitHub integration and linking pull requests",
    "importing issues from Jira and Trello",
    "the public REST API and its documentation",
    "webhooks for external systems",
    "dark mode and theming of the web app",
    "translating the web app into other languages",
    "screen reader and keyboard accessibility of the web app",
    "onboarding tours and empty states",
    "the in-app help centre",
    "database migrations, backups and restore drills",
    "CI pipeline speed and flaky tests",
    "Docker images and the deployment pipeline",
    "structured logging and tracing in the backend",
    "error pages and offline banners in the web app",
    "issue comments: editing, reactions and threading",
    "mentions of teammates in comments",
    "issue templates for bug reports",
    "sprint planning and capacity",
    "estimation with story points",
    "custom fields on issues",
    "public read-only sharing links",
    "the workspace member directory",
    "browser support and polyfills",
    "front-end bundle size and dependency upgrades",
    "Java backend dependency upgrades and refactoring of non-auth services",
]

# Share a goal's words but not its work, to test that search reads meaning.
NEAR_MISS_TOPICS = [
    "exporting analytics charts as PNG or PDF images (not issue data)",
    "date range and project filters on the analytics page (not the issue list)",
    "subscribing to (watching) issues and projects to follow changes",
    "collaborative cursors in the markdown description editor",
    "speeding up the settings pages and the workspace sidebar (not the board)",
]

# Goal vocabulary a plain distractor must not use.
GOAL_WORDS = re.compile(
    r"\b(log ?in|sign[- ]?in|auth\w*|password|session|token|sso|saml|security|"
    r"real[- ]?time|live update|board|drag|notif\w*|e-?mail|slack|digest|alert|"
    r"csv|export\w*|saved (filter|view)|filter view|issue list|sort\w*|paginat\w*|"
    r"bulk|billing|subscri\w*|payment|checkout|pricing|invoice|plan tier)\b",
    re.IGNORECASE,
)


class GeneratedIssue(BaseModel):
    title: str = Field(description="5 to 12 words, like a real issue title.")
    description: str = Field(description="One or two sentences.")


class GeneratedBatch(BaseModel):
    issues: list[GeneratedIssue]


async def generate(
    model: BaseChatModel, topic: str, count: int
) -> list[GeneratedIssue]:
    prompt = (
        f"Write {count} distinct issues for the tracker of a team building a "
        f"Linear-like project management web app, all about {topic}. Mix bugs, "
        "features and chores at different sizes. Plain, specific titles."
    )
    batch = await model.with_structured_output(GeneratedBatch).ainvoke(
        [HumanMessage(prompt)]
    )
    assert isinstance(batch, GeneratedBatch)
    return batch.issues


async def build() -> None:
    settings = Settings()
    token = os.environ["FLOWAI_USER_TOKEN"]
    snapshots = await fetch_snapshots(token)
    if XL_PROJECT in snapshots:
        raise SystemExit(f"{XL_PROJECT} already exists")
    originals = snapshots["Web Platform"].issues
    needed = TARGET_ISSUES - len(originals)

    if DISTRACTORS.exists():
        distractors = json.loads(DISTRACTORS.read_text())
    else:
        model = ChatOpenAI(
            model=GENERATOR_MODEL, api_key=settings.openai_api_key, temperature=1
        )
        plain: list[dict[str, str]] = []
        near_miss: list[dict[str, str]] = []
        limit = asyncio.Semaphore(8)

        async def bounded(topic: str, count: int) -> list[GeneratedIssue]:
            async with limit:
                return await generate(model, topic, count)

        plain_batches = await asyncio.gather(
            *(bounded(topic, 25) for topic in TOPICS for _ in range(2))
        )
        for issue in [issue for batch in plain_batches for issue in batch]:
            if GOAL_WORDS.search(f"{issue.title} {issue.description}") is None:
                plain.append(
                    {
                        "title": issue.title,
                        "description": issue.description,
                        "kind": "plain",
                    }
                )
        near_batches = await asyncio.gather(
            *(bounded(topic, 10) for topic in NEAR_MISS_TOPICS)
        )
        for issue in [issue for batch in near_batches for issue in batch]:
            near_miss.append(
                {
                    "title": issue.title,
                    "description": issue.description,
                    "kind": "near-miss",
                }
            )
        print(
            f"{len(plain)} plain and {len(near_miss)} near-miss distractors generated"
        )
        seen = {issue.title.lower() for issue in originals}
        unique: list[dict[str, str]] = []
        for issue in near_miss + plain:
            key = issue["title"].lower()
            if key not in seen:
                seen.add(key)
                unique.append(issue)
        distractors = unique[:needed]
        if len(distractors) < needed:
            raise SystemExit(f"only {len(distractors)} distractors; need {needed}")
        statuses = random.Random(20261005)
        for issue in distractors:
            issue["status"] = statuses.choices(
                ["DONE", "IN_PROGRESS", "TODO"], weights=[40, 25, 35]
            )[0]
        DISTRACTORS.write_text(json.dumps(distractors, ensure_ascii=False, indent=2))
        print(f"wrote {len(distractors)} distractors to {DISTRACTORS}")
        raise SystemExit("review them, then run build again to create the project")
        raise SystemExit("review them, then run build again to create the project")

    rows = [
        {"title": i.title, "description": i.description, "status": i.status}
        for i in originals
    ] + [{k: d[k] for k in ("title", "description", "status")} for d in distractors]
    random.Random(1005).shuffle(rows)
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx2.AsyncClient(
        base_url=BACKEND, headers=headers, timeout=30
    ) as http:
        project = (
            (
                await http.post(
                    "/api/projects",
                    json={
                        "name": XL_PROJECT,
                        "description": "Web Platform at 1000 issues",
                    },
                )
            )
            .raise_for_status()
            .json()
        )
        for number, row in enumerate(rows, start=1):
            response = await http.post(
                "/api/issues", json={"projectId": project["id"], **row}
            )
            response.raise_for_status()
            if number % 100 == 0:
                print(f"created {number}/{len(rows)}")
    print(f"{XL_PROJECT}: {len(rows)} issues")


WEB_GOALS = range(1, 9)
RUNS = 2
RECENT = 100
RAG_RESULTS = 20


@dataclass
class Approach:
    name: str
    model: str
    runs: int
    plan: Callable[["Context", int], Awaitable[Outcome]]


@dataclass
class Context:
    settings: Settings
    models: dict[str, ChatOpenAI]
    snapshot: Snapshot
    project_id: str
    secret: str
    user: dict[str, object]
    today: date

    def client(self) -> BackendClient:
        token = agent_token(self.secret, dict(self.user), self.project_id)
        return BackendClient(BACKEND, token, search_mode="semantic")


def agent(model: str) -> Callable[[Context, int], Awaitable[Outcome]]:
    async def plan(context: Context, goal: int) -> Outcome:
        return await run_planning(
            context.models[model],
            context.client(),
            GOALS[goal - 1][1],
            context.today,
            context.settings,
        )

    return plan


async def classic_rag(context: Context, goal: int) -> Outcome:
    """One search with the goal itself, by code, then one model call with the hits."""
    client = context.client()
    found = await client.search_issues(GOALS[goal - 1][1], RAG_RESULTS)
    await client.aclose()
    ids = {str(item.id) for item in found.items}
    hits = [issue for issue in context.snapshot.issues if issue.id in ids]
    return await run_single_prompt(
        context.models["gpt-4o-mini"],
        Snapshot(hits, context.snapshot.members),
        GOALS[goal - 1][1],
        context.today,
        with_issues=True,
    )


async def recent(context: Context, goal: int) -> Outcome:
    newest = sorted(
        context.snapshot.issues, key=lambda i: (i.createdAt, i.id), reverse=True
    )[:RECENT]
    return await run_single_prompt(
        context.models["gpt-4o-mini"],
        Snapshot(newest, context.snapshot.members),
        GOALS[goal - 1][1],
        context.today,
        with_issues=True,
    )


async def ceiling(context: Context, goal: int) -> Outcome:
    return await run_single_prompt(
        context.models["gpt-4o-mini"],
        context.snapshot,
        GOALS[goal - 1][1],
        context.today,
        with_issues=True,
    )


APPROACHES = [
    Approach("agent", "gpt-4o", RUNS, agent("gpt-4o")),
    Approach("agent", "gpt-4o-mini", RUNS, agent("gpt-4o-mini")),
    Approach("classic RAG (one search, top 20)", "gpt-4o-mini", RUNS, classic_rag),
    Approach(f"newest {RECENT} issues", "gpt-4o-mini", RUNS, recent),
    Approach("every issue (ceiling)", "gpt-4o-mini", 1, ceiling),
]


def run_report(
    results: dict[str, list[list[Outcome]]],
    labels: dict[int, set[str]],
    issues: int,
    today: date,
) -> str:
    relevant_total = sum(len(labels.get(g, set())) for g in WEB_GOALS)
    lines = [
        f"# Scale: {XL_PROJECT}, {today.isoformat()}",
        "",
        f"{issues} issues (the 56 Web Platform issues and generated distractors); "
        f"goals 1-8; {relevant_total} issues marked relevant. Recall is found / "
        "relevant over the eight goals in one run; tokens and seconds are per goal.",
        "",
        "| Approach | Model | Runs | Recall | Range | Issues seen | Tokens | Seconds "
        "| Planned |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for approach in APPROACHES:
        key = f"{approach.name} / {approach.model}"
        runs = results[key]
        recalls = [
            sum(
                len(labels.get(g, set()).intersection(o.seen_issue_ids))
                for g, o in zip(WEB_GOALS, outcomes, strict=True)
            )
            / relevant_total
            for outcomes in runs
        ]
        flat = [o for outcomes in runs for o in outcomes]
        planned = sum(o.plan is not None for o in flat)
        lines.append(
            f"| {approach.name} | {approach.model} | {len(runs)} | "
            f"{percent(fmean(recalls))} | {percent(min(recalls))}–"
            f"{percent(max(recalls))} | {fmean(o.issues_seen for o in flat):.0f} | "
            f"{fmean(o.tokens for o in flat):.0f} | "
            f"{fmean(o.seconds for o in flat):.1f} | {planned}/{len(flat)} |"
        )
    lines += [
        "",
        "## Recall per goal (mean over runs)",
        "",
        "| # | Goal | Relevant | "
        + " | ".join(f"{a.name} / {a.model}" for a in APPROACHES)
        + " |",
        "|---|---|---|" + "---|" * len(APPROACHES),
    ]
    for goal in WEB_GOALS:
        relevant = labels.get(goal, set())
        cells: list[str] = []
        for approach in APPROACHES:
            values = [
                recall(outcomes[goal - 1].seen_issue_ids, relevant)
                for outcomes in results[f"{approach.name} / {approach.model}"]
            ]
            known = [v for v in values if v is not None]
            cells.append(percent(fmean(known)) if known else "n/a")
        lines.append(
            f"| {goal} | {GOALS[goal - 1][1]} | {len(relevant)} | "
            + " | ".join(cells)
            + " |"
        )
    return "\n".join(lines)


async def run() -> None:
    settings = Settings()
    token = os.environ["FLOWAI_USER_TOKEN"]
    snapshots = await fetch_snapshots(token)
    xl = snapshots[XL_PROJECT]
    # The labels name Web Platform issues by title; the XL project has the same titles.
    labels = read_labels(
        LABELS.read_text(), {"Web Platform": xl, "Mobile App": snapshots["Mobile App"]}
    )
    context = Context(
        settings=settings,
        models={
            name: ChatOpenAI(model=name, api_key=settings.openai_api_key, max_retries=8)
            for name in ("gpt-4o", "gpt-4o-mini")
        },
        snapshot=xl,
        project_id=(await project_ids(token))[XL_PROJECT],
        secret=os.environ["JWT_SECRET"],
        user=claims_of(token),
        today=datetime.now(UTC).date(),
    )
    results: dict[str, list[list[Outcome]]] = {}
    for approach in APPROACHES:
        key = f"{approach.name} / {approach.model}"
        results[key] = []
        for number in range(1, approach.runs + 1):
            outcomes: list[Outcome] = []
            for goal in WEB_GOALS:
                outcomes.append(await approach.plan(context, goal))
                print(f"{key} run {number} goal {goal} done")
            results[key].append(outcomes)
    stamp = context.today.isoformat()
    (OUTPUT_DIR / f"scale-{stamp}.json").write_text(
        json.dumps(
            {k: [[o.to_json() for o in r] for r in v] for k, v in results.items()},
            ensure_ascii=False,
            indent=2,
        )
    )
    report = run_report(results, labels, len(xl.issues), context.today)
    (OUTPUT_DIR / f"scale-{stamp}.md").write_text(report)
    print(report)


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "build":
        asyncio.run(build())
    elif command == "run":
        asyncio.run(run())
    else:
        raise SystemExit(__doc__)
