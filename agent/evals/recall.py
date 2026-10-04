"""Recall of the agent's searches against the issues a person marked as relevant
in docs/baseline/relevant-issues.md, for any baseline run.

Runs before the floor/ceiling version did not record which issues the agent saw,
so their searches are replayed against the snapshot with the backend's rule.

Run from agent/ with the backend up and a user access token:

    FLOWAI_USER_TOKEN=... uv run python evals/recall.py ../docs/baseline/results-*.json
"""

import asyncio
import json
import os
import sys
from pathlib import Path
from statistics import fmean
from typing import Any

from baseline import (
    GOALS,
    LABELS,
    Snapshot,
    fetch_snapshots,
    matching_issues,
    read_labels,
)

SEARCH = "search_project_issues("
DEFAULT_LIMIT = 20


def seen_issue_ids(agent: dict[str, Any], snapshot: Snapshot) -> set[str]:
    if "seenIssueIds" in agent:
        return set(agent["seenIssueIds"])
    seen: set[str] = set()
    for line in agent["trace"]:
        if not line.startswith(SEARCH) or line.endswith("-> not run"):
            continue
        args, _ = json.JSONDecoder().raw_decode(line[len(SEARCH) :])
        found = matching_issues(snapshot, args.get("query"))
        seen.update(issue.id for issue in found[: args.get("limit", DEFAULT_LIMIT)])
    return seen


def main() -> None:
    snapshots = asyncio.run(fetch_snapshots(os.environ["FLOWAI_USER_TOKEN"]))
    labels = read_labels(LABELS.read_text(), snapshots)
    print(f"{len(labels)} of {len(GOALS)} goals labeled")
    for path in sys.argv[1:]:
        recalls: list[float] = []
        for result in json.loads(Path(path).read_text()):
            relevant = labels.get(result["number"])
            if not relevant:
                continue
            seen = seen_issue_ids(result["agent"], snapshots[result["project"]])
            recalls.append(len(seen & relevant) / len(relevant))
        summary = (
            f"{fmean(recalls):.0%} over {len(recalls)} goals" if recalls else "n/a"
        )
        print(f"{Path(path).name}: agent recall {summary}")


if __name__ == "__main__":
    main()
