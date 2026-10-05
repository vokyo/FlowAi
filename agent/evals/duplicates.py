"""Count new tasks that repeat existing issues, before and after plans could name them.

A new task is a duplicate when its work belongs to an existing issue of the project:
the same work reworded, or a part of it (its design, implementation, tests or
finishing), including redoing work an issue already marks DONE. That definition was
chosen on 2026-10-05; work no existing issue covers is not a duplicate.

A judge model (JUDGE_MODEL, gpt-4o-mini by default, temperature 0) reads one plan at a
time against every issue in the project. A person then reviews a random sample blind
in duplicates-review-<date>.md, and `score` reports how often the two agreed.

Run from agent/ with the backend up and a user access token:

    FLOWAI_USER_TOKEN=... uv run --env-file ../.env python evals/duplicates.py \\
        judge BEFORE.json AFTER.json
    FLOWAI_USER_TOKEN=... uv run python evals/duplicates.py score

BEFORE.json and AFTER.json are search_modes.py results; their semantic runs are used.
"""

import asyncio
import json
import os
import random
import re
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from statistics import fmean
from typing import Any

from baseline import GOALS, LABELS, OUTPUT_DIR, Snapshot, fetch_snapshots, read_labels
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field

from flowai_agent.config import Settings

JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "gpt-4o-mini")
SAMPLE_SIZE = 20
NONE = "none (not a duplicate)"
CHECKBOX = re.compile(r"^- \[(?P<mark>[ xX])\] (?P<text>.+)$")

DEFINITION = """A new task is a DUPLICATE when its work belongs to an existing issue:
- the same work in other words, or
- a part of an existing issue's work: its design, implementation, tests or finishing, or
- redoing work an existing issue already marks DONE.
A task is NOT a duplicate when no existing issue covers its work, even if the topic is
related (for example reviewing a whole release, or a feature the project lacks)."""


class TaskVerdict(BaseModel):
    task: str = Field(description="The task's letter.")
    duplicate_of: int | None = Field(
        description="The number of the existing issue it duplicates, or null."
    )
    reason: str = Field(description="One short sentence.")


class PlanVerdict(BaseModel):
    verdicts: list[TaskVerdict]


@dataclass
class JudgedTask:
    arm: str
    run: int
    goal: int
    title: str
    description: str | None
    duplicate_of: str | None
    """The id of the existing issue it duplicates, per the judge."""
    reason: str


def plans_of(path: str) -> list[list[dict[str, Any] | None]]:
    """Runs of ten plans each, from a search_modes.py results file."""
    runs = json.loads(Path(path).read_text())["semantic"]
    return [[outcome["plan"] for outcome in run] for run in runs]


def issue_list(snapshot: Snapshot) -> str:
    lines: list[str] = []
    for number, issue in enumerate(snapshot.issues, start=1):
        about = (issue.description or "").replace("\n", " ")[:150]
        lines.append(
            f"{number}. [{issue.status}] {issue.title}"
            + (f" — {about}" if about else "")
        )
    return "\n".join(lines)


async def judge_plan(
    model: BaseChatModel, goal: str, snapshot: Snapshot, items: list[dict[str, Any]]
) -> list[TaskVerdict]:
    letters = [chr(ord("A") + index) for index in range(len(items))]
    tasks = "\n".join(
        f"{letter}. {item['title']}"
        + (f" — {item['description']}" if item.get("description") else "")
        for letter, item in zip(letters, items, strict=True)
    )
    prompt = (
        f"Goal: {goal}\n\n{DEFINITION}\n\n"
        f"Existing issues in the project:\n{issue_list(snapshot)}\n\n"
        f"New tasks in a plan for the goal:\n{tasks}\n\n"
        "For every new task, give the number of the existing issue it duplicates, "
        "or null."
    )
    verdict = await model.with_structured_output(PlanVerdict).ainvoke(
        [HumanMessage(prompt)]
    )
    assert isinstance(verdict, PlanVerdict)
    by_letter = {v.task.strip().upper()[:1]: v for v in verdict.verdicts}
    return [
        by_letter.get(
            letter, TaskVerdict(task=letter, duplicate_of=None, reason="(no verdict)")
        )
        for letter in letters
    ]


def metrics(
    arm: str,
    runs: list[list[dict[str, Any] | None]],
    judged: list[JudgedTask],
    labels: dict[int, set[str]],
) -> dict[str, Any]:
    plans = [plan for run in runs for plan in run]
    planned = [plan for plan in plans if plan is not None]
    tasks = [task for task in judged if task.arm == arm]
    duplicates = [task for task in tasks if task.duplicate_of is not None]
    plans_with_duplicate = {(task.run, task.goal) for task in duplicates}
    reused = [
        (goal, entry["issueId"])
        for run in runs
        for goal, plan in enumerate(run, start=1)
        if plan is not None
        for entry in plan.get("existingIssues", [])
    ]
    reused_relevant = [pair for pair in reused if pair[1] in labels.get(pair[0], set())]
    return {
        "plans": len(plans),
        "planned": len(planned),
        "newTasks": len(tasks),
        "newTasksPerPlan": fmean(len(plan["items"]) for plan in planned)
        if planned
        else 0,
        "duplicates": len(duplicates),
        "duplicateRate": len(duplicates) / len(tasks) if tasks else 0,
        "plansWithDuplicate": len(plans_with_duplicate),
        "reused": len(reused),
        "reusedPerPlan": len(reused) / len(planned) if planned else 0,
        "reusedMarkedRelevant": len(reused_relevant),
    }


def review_sheet(
    sample: list[JudgedTask],
    snapshots: dict[str, Snapshot],
    labels: dict[int, set[str]],
) -> str:
    lines = [
        "# Duplicate review (blind)",
        "",
        "For each new task, mark the existing issue it duplicates, or the last line",
        "if it duplicates none. A duplicate is the same work reworded, a part of an",
        "existing issue's work (design, implementation, tests, finishing), or",
        "redoing DONE work.",
        "Candidates are the goal's issues marked relevant plus any others the judge",
        "considered; the order says nothing. Mark exactly one line per task.",
        "",
    ]
    for number, task in enumerate(sample, start=1):
        project, goal = GOALS[task.goal - 1]
        issues = {issue.id: issue for issue in snapshots[project].issues}
        candidates = set(labels.get(task.goal, set()))
        if task.duplicate_of is not None:
            candidates.add(task.duplicate_of)
        ordered = sorted(candidates, key=lambda issue_id: issues[issue_id].title)
        lines += [f"## R{number}. {goal}", "", f"New task: **{task.title}**"]
        if task.description:
            lines.append(f"> {task.description}")
        lines.append("")
        lines += [f"- [ ] [{issues[i].status}] {issues[i].title}" for i in ordered]
        lines += [f"- [ ] {NONE}", ""]
    return "\n".join(lines)


async def judge(before_path: str, after_path: str) -> None:
    settings = Settings()
    if settings.openai_api_key is None:
        raise SystemExit("OPENAI_API_KEY is not set")
    model = ChatOpenAI(
        model=JUDGE_MODEL, api_key=settings.openai_api_key, temperature=0
    )
    snapshots = await fetch_snapshots(os.environ["FLOWAI_USER_TOKEN"])
    labels = read_labels(LABELS.read_text(), snapshots)
    arms = {"before": plans_of(before_path), "after": plans_of(after_path)}

    judged: list[JudgedTask] = []
    for arm, runs in arms.items():
        for run_number, run in enumerate(runs, start=1):
            for goal_number, plan in enumerate(run, start=1):
                if plan is None or not plan["items"]:
                    continue
                project, goal = GOALS[goal_number - 1]
                snapshot = snapshots[project]
                verdicts = await judge_plan(model, goal, snapshot, plan["items"])
                for item, verdict in zip(plan["items"], verdicts, strict=True):
                    index = verdict.duplicate_of
                    valid = index is not None and 1 <= index <= len(snapshot.issues)
                    judged.append(
                        JudgedTask(
                            arm=arm,
                            run=run_number,
                            goal=goal_number,
                            title=item["title"],
                            description=item.get("description"),
                            duplicate_of=snapshot.issues[index - 1].id
                            if valid and index
                            else None,
                            reason=verdict.reason,
                        )
                    )
            print(f"{arm} run {run_number} judged")

    stamp = datetime.now(UTC).date().isoformat()
    sample = random.Random(20261005).sample(
        [t for t in judged if t.arm == "before"], SAMPLE_SIZE // 2
    ) + random.Random(20261005).sample(
        [t for t in judged if t.arm == "after"],
        min(SAMPLE_SIZE // 2, sum(t.arm == "after" for t in judged)),
    )
    random.Random(1005).shuffle(sample)
    result = {
        "judgeModel": JUDGE_MODEL,
        "before": before_path,
        "after": after_path,
        "metrics": {
            arm: metrics(arm, runs, judged, labels) for arm, runs in arms.items()
        },
        "tasks": [task.__dict__ for task in judged],
        "reviewSample": [judged.index(task) for task in sample],
    }
    (OUTPUT_DIR / f"duplicates-{stamp}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2)
    )
    (OUTPUT_DIR / f"duplicates-review-{stamp}.md").write_text(
        review_sheet(sample, snapshots, labels)
    )
    print(json.dumps(result["metrics"], indent=2))


async def score() -> None:
    results = sorted(OUTPUT_DIR.glob("duplicates-2*.json"))[-1]
    data = json.loads(results.read_text())
    sheet = results.with_name(
        results.name.replace("duplicates-", "duplicates-review-")
    ).with_suffix(".md")
    snapshots = await fetch_snapshots(os.environ["FLOWAI_USER_TOKEN"])
    titles = {
        f"[{issue.status}] {issue.title}": issue.id
        for snapshot in snapshots.values()
        for issue in snapshot.issues
    }
    marks: list[str | None] = []
    current: list[str] = []
    for line in sheet.read_text().splitlines() + ["## end"]:
        if line.startswith("## "):
            if current:
                if len(current) != 1:
                    raise SystemExit(f"mark exactly one line per task; got {current}")
                marks.append(None if current[0] == NONE else titles[current[0]])
            current = []
            continue
        match = CHECKBOX.match(line)
        if match and match["mark"] != " ":
            current.append(match["text"])
    sample = [data["tasks"][index] for index in data["reviewSample"]]
    if len(marks) != len(sample):
        raise SystemExit(
            f"{len(marks)} reviewed of {len(sample)}; finish the sheet first"
        )
    same_call = sum(
        (m is None) == (t["duplicate_of"] is None)
        for m, t in zip(marks, sample, strict=True)
    )
    same_issue = sum(m == t["duplicate_of"] for m, t in zip(marks, sample, strict=True))
    print(
        f"duplicate or not: {same_call}/{len(sample)} agree; "
        f"same issue too: {same_issue}/{len(sample)}"
    )


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "judge" and len(sys.argv) == 4:
        asyncio.run(judge(sys.argv[2], sys.argv[3]))
    elif command == "score":
        asyncio.run(score())
    else:
        raise SystemExit(__doc__)
