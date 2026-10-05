"""Compare the agent's issue search modes on the baseline's fixed goals.

Every goal runs through the real planning graph against the running backend's
internal search, once per mode (keyword, fulltext, semantic) and REPEATS times per
mode, with the same model throughout. Retrieval is measured as in the baseline:
recall of the issues marked relevant in relevant-issues.md, plus tokens, searches,
empty searches and time. There is no judge; plan quality is not compared here.

The internal API takes agent tokens, which the backend normally issues only when it
starts a run. The script signs them itself, the way AgentTokenService does, from the
backend's JWT_SECRET, so it is for a local backend only.

Run from agent/ with the backend up, embeddings computed, and a user access token:

    FLOWAI_USER_TOKEN=... uv run --env-file ../.env python evals/search_modes.py

Results go to docs/baseline/search-modes-<date>.{json,md}; set RUN_LABEL for a
second run on the same day.
"""

import asyncio
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from datetime import UTC, date, datetime
from statistics import fmean
from typing import Any, cast

import httpx2
from baseline import (
    BACKEND,
    GOALS,
    LABELS,
    OUTPUT_DIR,
    Outcome,
    fetch_snapshots,
    percent,
    read_labels,
    recall,
    run_planning,
)
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI

from flowai_agent.config import Settings
from flowai_agent.tools.client import BackendClient, SearchMode

MODES = cast(
    list[SearchMode], os.environ.get("MODES", "keyword,fulltext,semantic").split(",")
)
REPEATS = int(os.environ.get("REPEATS", "3"))
CONCURRENCY = 5


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def claims_of(token: str) -> dict[str, Any]:
    """The payload of a JWT, unverified: only the ids are read from it."""
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def agent_token(secret: str, user: dict[str, Any], project_id: str) -> str:
    """An HS256 agent token with the claims AgentTokenService puts in one."""
    now = int(time.time())
    claims = {
        "iss": "flowai",
        "aud": ["flowai-agent"],
        "iat": now,
        "exp": now + 15 * 60,
        "sub": user["sub"],
        "workspaceId": user["workspaceId"],
        "membershipId": user["membershipId"],
        "projectId": project_id,
        "runId": str(uuid.uuid4()),
    }
    header = b64url(json.dumps({"alg": "HS256"}).encode())
    body = b64url(json.dumps(claims).encode())
    signature = hmac.new(
        secret.encode(), f"{header}.{body}".encode(), hashlib.sha256
    ).digest()
    return f"{header}.{body}.{b64url(signature)}"


async def project_ids(token: str) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx2.AsyncClient(base_url=BACKEND, headers=headers) as http:
        response = await http.get("/api/projects")
        return {p["name"]: p["id"] for p in response.raise_for_status().json()}


async def run_one(
    model: BaseChatModel,
    settings: Settings,
    secret: str,
    user: dict[str, Any],
    project_id: str,
    mode: SearchMode,
    goal: str,
    today: date,
) -> Outcome:
    client = BackendClient(
        BACKEND,
        agent_token(secret, user, project_id),
        search_mode=mode,
    )
    return await run_planning(model, client, goal, today, settings)


def status_of(outcome: Outcome) -> str:
    if outcome.plan is not None:
        return "PLANNED"
    if outcome.failure is not None:
        return "FAILED"
    return "INSUFFICIENT_INFO"


def report(
    runs: dict[SearchMode, list[list[Outcome]]],
    labels: dict[int, set[str]],
    model: str,
    today: date,
) -> str:
    relevant_total = sum(len(labels.get(n, set())) for n in range(1, len(GOALS) + 1))
    lines = [
        f"# Search modes, {today.isoformat()}",
        "",
        f"Model {model}; {len(GOALS)} goals; {REPEATS} runs per mode; "
        f"at most {Settings().search_max_results} results per search; "
        f"{relevant_total} issues marked relevant in {LABELS.name}.",
        "Recall is found / relevant over all goals in one run; "
        "the range is across runs.",
        "Tokens are the chat model's; a semantic search also makes one embedding call "
        "on the backend, which is not counted here.",
        "",
        "| Mode | Recall (mean) | Range | Tokens / goal | Searches / goal "
        "| Empty searches | Issues seen / goal | Seconds / goal | Planned |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for mode in MODES:
        repeats = runs[mode]
        recalls: list[float] = []
        for outcomes in repeats:
            found = sum(
                len(labels.get(n, set()).intersection(o.seen_issue_ids))
                for n, o in enumerate(outcomes, start=1)
            )
            recalls.append(found / relevant_total)
        flat = [o for outcomes in repeats for o in outcomes]
        searches = sum(o.searches for o in flat)
        empty = sum(o.empty_searches for o in flat)
        planned = sum(status_of(o) == "PLANNED" for o in flat)
        lines.append(
            f"| {mode} | {percent(fmean(recalls))} | "
            f"{percent(min(recalls))}–{percent(max(recalls))} | "
            f"{fmean(o.tokens for o in flat):.0f} | "
            f"{fmean(o.searches for o in flat):.1f} | "
            f"{empty}/{searches} | "
            f"{fmean(o.issues_seen for o in flat):.1f} | "
            f"{fmean(o.seconds for o in flat):.1f} | "
            f"{planned}/{len(flat)} |"
        )
    lines += [
        "",
        "## Recall per goal (mean over runs)",
        "",
        "| # | Goal | Relevant | " + " | ".join(MODES) + " |",
        "|---|---|---|" + "---|" * len(MODES),
    ]
    for number, (_, goal) in enumerate(GOALS, start=1):
        relevant = labels.get(number, set())
        cells: list[str] = []
        for mode in MODES:
            values = [
                recall(outcomes[number - 1].seen_issue_ids, relevant)
                for outcomes in runs[mode]
            ]
            known = [v for v in values if v is not None]
            cells.append(percent(fmean(known)) if known else "n/a")
        lines.append(
            f"| {number} | {goal} | {len(relevant)} | " + " | ".join(cells) + " |"
        )
    lines += ["", "## Searches in the first run", ""]
    for number, (_, goal) in enumerate(GOALS, start=1):
        lines.append(f"### {number}. {goal}")
        for mode in MODES:
            trace = "; ".join(runs[mode][0][number - 1].trace) or "(no tool calls)"
            lines.append(f"- {mode}: {trace}")
        lines.append("")
    return "\n".join(lines)


async def main() -> None:
    settings = Settings()
    if settings.openai_api_key is None:
        raise SystemExit("OPENAI_API_KEY is not set")
    secret = os.environ["JWT_SECRET"]
    user_token = os.environ["FLOWAI_USER_TOKEN"]
    user = claims_of(user_token)
    model = ChatOpenAI(model=settings.ai_model, api_key=settings.openai_api_key)
    today = datetime.now(UTC).date()
    label = os.environ.get("RUN_LABEL")
    stamp = f"{today.isoformat()}-{label}" if label else today.isoformat()
    results_path = OUTPUT_DIR / f"search-modes-{stamp}.json"
    report_path = OUTPUT_DIR / f"search-modes-{stamp}.md"
    if results_path.exists():
        raise SystemExit(f"{results_path} already exists; set RUN_LABEL")

    snapshots = await fetch_snapshots(user_token)
    labels = read_labels(LABELS.read_text(), snapshots)
    projects = await project_ids(user_token)
    limit = asyncio.Semaphore(CONCURRENCY)

    async def bounded(mode: SearchMode, repeat: int, number: int) -> Outcome:
        project, goal = GOALS[number - 1]
        async with limit:
            outcome = await run_one(
                model, settings, secret, user, projects[project], mode, goal, today
            )
        print(f"{mode:<8} run {repeat} goal {number:>2}: {status_of(outcome)}")
        return outcome

    runs: dict[SearchMode, list[list[Outcome]]] = {}
    for mode in MODES:
        runs[mode] = []
        for repeat in range(1, REPEATS + 1):
            outcomes = await asyncio.gather(
                *(bounded(mode, repeat, n) for n in range(1, len(GOALS) + 1))
            )
            runs[mode].append(list(outcomes))

    results_path.write_text(
        json.dumps(
            {
                mode: [[o.to_json() for o in outcomes] for outcomes in runs[mode]]
                for mode in MODES
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    report_path.write_text(report(runs, labels, settings.ai_model, today))
    print(f"written to {report_path}")


if __name__ == "__main__":
    asyncio.run(main())
