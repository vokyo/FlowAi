# FlowAI Planning Agent

A FastAPI service that turns a project goal into a reviewable plan. A LangGraph workflow searches existing issues, reads project members, proposes work that is still missing, and pauses for human review. The Spring Boot backend owns authorization, plan versions, approval, and all writes to project data.

For the application, demo, and backend setup, start with the [project README](../README.md).

## Contents

- [Run locally](#run-locally)
- [Run in containers](#run-in-containers)
- [How planning and revision work](#how-planning-and-revision-work)
- [Configuration](#configuration)
- [Service contract](#service-contract)
- [Security and persistence](#security-and-persistence)
- [Evaluation](#evaluation)
- [Checks and troubleshooting](#checks-and-troubleshooting)

## Run locally

Requirements: Python 3.14+, `uv`, Docker, and the FlowAI backend. Real planning needs an OpenAI key; automated tests use a fake model.

1. From the repository root, prepare the environment and database:

   ```bash
   cp .env.example .env
   docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis
   ```

   If `.env` already exists, keep it and add any missing settings from the template. Replace `JWT_SECRET` with at least 32 random bytes and `OPENAI_API_KEY` with your provider key. Keep `.env` out of version control.

2. Add the backend's agent switch and choose a search mode in `.env`:

   ```dotenv
   AGENT_ENABLED=true
   AGENT_BASE_URL=http://localhost:8000
   BACKEND_BASE_URL=http://localhost:8080
   SEARCH_MODE=keyword
   ```

   Keyword mode works without embeddings. To use the default semantic search, set `SEARCH_MODE=semantic` and `SPRING_AI_MODEL_EMBEDDING=openai` in the backend environment as well. The backend needs its own `OPENAI_API_KEY` for embedding calls. `AI_ENABLED=true` and `SPRING_AI_MODEL_CHAT=openai` are needed only for the separate Copilot features.

3. A fresh Compose PostgreSQL volume automatically creates the `flowai_agent` role and `agent_checkpoint` schema. For a volume created before the agent was added, run this once from the repository root:

   ```bash
   set -a
   source .env
   set +a
   docker compose exec -T -e AGENT_DB_PASSWORD postgres sh -s < agent/db/init-checkpoint-schema.sh
   ```

   `CHECKPOINT_DATABASE_URL` must use that role, the same password as `AGENT_DB_PASSWORD`, and the correct database address. The template contains a working local URL. URL-encode special characters in the password when constructing a connection URL.

4. Start the backend in one terminal, from `backend/`:

   ```bash
   set -a
   source ../.env
   set +a
   ./mvnw spring-boot:run
   ```

5. Start the agent in another terminal, from `agent/`:

   ```bash
   uv sync --locked
   uv run --env-file ../.env fastapi dev src/flowai_agent/main.py
   ```

   `Settings` reads process environment variables; it does not load `.env` automatically. The `--env-file` flag above supplies them.

6. Start the frontend with `npm ci` and `npm run dev` from `frontend/`. Open [http://localhost:5173](http://localhost:5173), sign in, open a project, and select **Planning agent** in its sidebar.

The service exposes its OpenAPI document at [http://localhost:8000/openapi.json](http://localhost:8000/openapi.json) and interactive docs at [http://localhost:8000/docs](http://localhost:8000/docs). Successful startup checks the checkpoint database; it does not prove that the model key or backend connection works. `GET /api/ai/status` on the backend reports `agentAvailable` from the deployment switch, rather than probing agent health.

## Run in containers

The repository's default `docker-compose.yml` starts PostgreSQL, Redis, the backend, and the frontend. It provisions the checkpoint role but **does not start the agent** or forward the backend's agent and embedding settings.

To add it, save the following as `docker-compose.agent.yml` in the repository root. This example uses keyword search, so only the agent makes provider calls:

```yaml
services:
  agent:
    build:
      context: ./agent
    image: flowai-agent:local
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY:?OPENAI_API_KEY must be set}
      CHECKPOINT_DATABASE_URL: ${CHECKPOINT_DATABASE_URL:?CHECKPOINT_DATABASE_URL must be set}
      BACKEND_BASE_URL: http://backend:8080
      AI_MODEL: ${AGENT_AI_MODEL:-gpt-4o}
      SEARCH_MODE: ${SEARCH_MODE:-keyword}
    depends_on:
      postgres:
        condition: service_healthy
    expose:
      - "8000"
    restart: unless-stopped
    networks:
      - flowai

  backend:
    environment:
      AGENT_ENABLED: "true"
      AGENT_BASE_URL: http://agent:8000
```

In `.env`, change the agent database URL's hostname from `localhost` to `postgres`, keeping the credentials and database name consistent:

```dotenv
CHECKPOINT_DATABASE_URL=postgresql://flowai_agent:flowai_agent_dev_password@postgres:5432/flowai
SEARCH_MODE=keyword
```

Then run, from the repository root:

```bash
docker compose -f docker-compose.yml -f docker-compose.agent.yml up --build -d --wait
docker compose -f docker-compose.yml -f docker-compose.agent.yml logs -f agent
```

For semantic search, set `SEARCH_MODE=semantic` and add `SPRING_AI_MODEL_EMBEDDING: openai` to the overlay's `backend.environment`. The base Compose file already forwards `OPENAI_API_KEY` to the backend. Wait for issue embeddings to be processed before comparing retrieval results.

`AGENT_AI_MODEL` is a convenience variable in this overlay: it maps to the agent's `AI_MODEL` without changing the backend's Copilot model. It is not a Python settings field.

### Hosted deployment

Build [Dockerfile](./Dockerfile) with `agent/` as the build context. The image installs locked production dependencies, runs as a non-root user, and listens on port `8000` by default. `HOST` defaults to an empty string so Uvicorn accepts both IPv4 and IPv6 connections; `PORT` can be supplied by the platform. The image's health check reads `/openapi.json`.

Keep the agent on the private network: its endpoints check for a Bearer header but do not verify the token's signature, claims, or scope. Exposing the service publicly would bypass the backend's run authorization and rate limits and expose checkpoint deletion.

Provision a hosted database once with an administrator connection that can create roles. With `DATABASE_URL` and `AGENT_DB_PASSWORD` supplied in the shell environment, run from the repository root:

```bash
docker run --rm -i -e DATABASE_URL -e AGENT_DB_PASSWORD \
  pgvector/pgvector:pg17 sh -s < agent/db/init-checkpoint-schema.sh
```

The script creates missing objects and resets the role password when rerun. Connect the agent with `CHECKPOINT_DATABASE_URL` for `flowai_agent` at the database's private address. Set `BACKEND_BASE_URL` on the agent and `AGENT_BASE_URL` plus `AGENT_ENABLED=true` on the backend to their respective private service addresses. Add the backend embedding settings if using semantic search.

## How planning and revision work

```mermaid
flowchart TB
    Goal["Goal + backend-issued run ID"] --> Prompt["Write project-planning prompt"]
    Prompt --> Decide["Ask model"]
    Decide -->|"Needs context; budget available"| Tools["Search issues / list members"]
    Tools --> Decide
    Decide -->|"Needs context; budget exhausted"| Missing["INSUFFICIENT_INFO"]
    Decide -->|"Enough context"| Plan["Generate structured plan"]
    Plan --> Check["Remove unseen issue IDs and invalid assignees"]
    Check --> Review["Checkpoint + interrupt for review"]
    Review -->|"Feedback on this version"| Revise["Resume checkpoint with a fresh revision budget"]
    Revise --> Decide
    Review -->|"Approve or cancel in backend"| Close["Backend closes run; checkpoint cleanup"]
```

1. The backend checks project access, applies the AI generation rate limit, and acquires a Redis lock for this person and project when Redis is available. It issues a token valid for 15 minutes, scoped to the workspace, membership, project, and run. The user's normal access token never reaches the agent.
2. The graph uses two tools: `search_project_issues(query, limit)` and `get_project_members()`. The project comes from the token, so the model cannot pick a different project ID.
3. The initial decision loop defaults to 4 model rounds and an 8-tool-call budget. The final structured generation is a separate model call. Backend tool requests have a 5-second timeout; retryable errors can be retried twice, and one invalid-argument correction is allowed.
4. The plan names up to 10 existing issues with reasons for reuse and proposes 0–5 new issues. Each new issue has a unique `clientItemId`, title, optional description, priority, optional member assignee, and optional due date within 365 days of the run's reference date. A plan that neither reuses an issue nor proposes new work fails backend validation.
5. `check_plan` removes issue IDs not returned by searches, deduplicates reused IDs, and clears assignees absent from the tool's member results. The backend validates the plan against current project data and saves a version with its content hash. Invalid plans and plans with no new tasks are retained with `approvable=false` and a reason, so they can be reviewed and revised rather than applied.
6. A LangGraph interrupt preserves the review point in PostgreSQL. Revision resumes from the checkpoint attached to the version being reviewed, with 2 decision rounds and a 4-tool-call budget. This avoids resuming an abandoned branch after a failed revision.
7. The backend permits at most 5 saved versions. Revision sends `basedOnVersion`; approval sends the latest `version` and the exact `contentHash` the user reviewed. Approval revalidates and creates new issues in one idempotent transaction. Reused issues remain references in the plan.
8. After approval, cancellation, or an initial run ending without a plan, the backend requests checkpoint deletion after its transaction commits. Cleanup is best effort: failures are logged and do not undo approval or cancellation.

A failed revision leaves the last saved version available for review. Switching `AGENT_ENABLED` off prevents new runs and revisions; existing runs can still be read, approved, and cancelled.

Approval also requires an approvable, unexpired draft. Plan drafts use the backend's `AI_SUGGESTION_TTL` (default `7d`). A plan that only reuses existing issues can be reviewed, revised, or cancelled, but has nothing to create and cannot be approved.

## Configuration

The source of defaults and ranges is [config.py](./src/flowai_agent/config.py).

| Agent environment variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_API_KEY` | Unset | Required for planning/resume; missing key returns HTTP 503 |
| `CHECKPOINT_DATABASE_URL` | Unset | Required at startup; PostgreSQL URL for the checkpoint role |
| `BACKEND_BASE_URL` | `http://localhost:8080` | Backend address for internal read-only tools |
| `AI_MODEL` | `gpt-4o` | Model for decisions and structured planning |
| `SEARCH_MODE` | `semantic` | `semantic`, `fulltext`, or `keyword` |
| `SEARCH_MAX_RESULTS` | `20` | Maximum results per search, 1–20 |
| `MAX_DECISION_ROUNDS` | `4` | Initial decision budget, 1–10 |
| `MAX_TOOL_CALLS` | `8` | Initial tool-call budget, 1–20 |
| `RUN_TIMEOUT_SECONDS` | `50` | Positive timeout for a run or revision |

Revision budgets are constants in [nodes.py](./src/flowai_agent/graph/nodes.py), rather than environment settings. The Docker image also reads `HOST` and `PORT`; these configure Uvicorn, not `Settings`.

| Related backend variable | Default | Purpose |
| --- | --- | --- |
| `AGENT_ENABLED` | `false` | Allows starts/revisions and advertises agent availability |
| `AGENT_BASE_URL` | `http://localhost:8000` | Python service address |
| `AGENT_CONNECT_TIMEOUT` / `AGENT_READ_TIMEOUT` | `2s` / `60s` | Backend's connection and response deadlines |
| `SPRING_AI_MODEL_EMBEDDING` | `none` | Set to `openai` for semantic search |
| `AI_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model; stored vectors have 1536 dimensions |
| `AI_RATE_LIMIT_CAPACITY` / `AI_RATE_LIMIT_WINDOW` | `10` / `1m` | Shared policy for agent starts/revisions and Copilot generation |
| `AI_SUGGESTION_TTL` | `7d` | Lifetime of the draft attached to an approvable plan version |
| `REDIS_ENABLED` / `REDIS_URL` | `false` / `redis://localhost:6379` | Shared rate limits and concurrent-run lock |

If both services load the same `.env`, `AI_MODEL` changes the agent and Copilot together; their code defaults differ (`gpt-4o` and `gpt-4o-mini`). Keep the timeouts nested: tool requests at 5 seconds, agent at 50, backend at 60 by default.

### Search modes

| Mode | Matching | Prerequisite / caveat |
| --- | --- | --- |
| `keyword` | Case-insensitive substring in title or description | No embeddings; synonyms and translations do not match |
| `fulltext` | PostgreSQL English full-text search, including word forms | All query words must match; other languages and synonyms can miss |
| `semantic` | pgvector ranks issues by similarity to a query embedding | Backend embedding provider and processed issue vectors required; returned issues may still be irrelevant |

The backend queues embeddings when issues are created or their text changes, and the migration queues pre-existing issues. Jobs wait while the embedding provider is off and are processed in the background once enabled. The agent's semantic mode does not automatically fall back to keyword search; choose keyword explicitly when embeddings are unavailable.

## Service contract

These are **private service endpoints**, distinct from the user-facing `/api/agent/runs` API. Each takes `Authorization: Bearer <agent-token>`.

| Agent endpoint | Request / result |
| --- | --- |
| `POST /runs` | `{ "runId": "<uuid>", "goal": "<1–500 characters>", "today": "YYYY-MM-DD" }` |
| `POST /runs/{run_id}/resume` | `{ "checkpointId": "<saved checkpoint>", "feedback": "<1–1000 characters>" }` |
| `DELETE /runs/{run_id}` | Deletes that run's checkpoint thread; returns 204 |

Planning/resume returns `status`, `plan`, `missing`, `reason`, `stats` (`decisionRounds`, `toolCalls`), and `checkpointId`:

| Status | Meaning |
| --- | --- |
| `PLANNED` | Structured plan ready for backend validation and review; includes its review checkpoint |
| `INSUFFICIENT_INFO` | Decision/tool budget exhausted while the model still wanted more context |
| `FAILED` | Tool, model, timeout, or unexpected run failure |

A completed run can return HTTP 200 with `FAILED`; callers must inspect `status`. Missing Bearer headers return 401 once model dependencies are available; missing model keys return 503; invalid bodies return 422; resuming a checkpoint that is not waiting for review returns 409. The agent's FastAPI error format differs from the backend's public API errors.

The tools call `GET /api/internal/agent/project/issues?q=...&limit=...&mode=...` and `GET /api/internal/agent/project/members`. These backend endpoints verify the dedicated agent-token audience and project scope; ordinary browser access tokens cannot substitute for agent tokens.

## Security and persistence

- The backend enforces tenant and membership checks, validates reused issue IDs and assignees again at approval, and owns the transaction that creates issues. The agent has no write tool for project data.
- LangGraph checkpoints use the run UUID as `thread_id` and live in the `agent_checkpoint` schema. The `flowai_agent` role owns that schema and receives no application-table grants from the initialization script. Application data is retrieved through backend APIs.
- `AsyncPostgresSaver.setup()` creates or upgrades checkpoint tables on startup. Flyway manages the backend's application tables separately. Review checkpoints survive an agent restart when its database is preserved.
- Redis locks prevent simultaneous starts/revisions by the same person on the same project. If Redis is disabled or unreachable, planning proceeds unlocked and rate limits use local memory; transactional approval remains the data-integrity boundary.
- Checkpoints contain project context and generated plans. Include their database in retention and backup decisions. There is currently no periodic retry job for failed checkpoint cleanup.

## Evaluation

### Recorded results

These measurements were recorded on **2026-10-05**. Recall is the share of labeled relevant issues retrieved, not a score of plan quality. Chat-token counts exclude backend embedding calls.

Search comparison: gpt-4o-mini, 10 goals over the demo's Web Platform and Mobile App projects, 3 runs per mode, 36 labeled relevant issues. Web Platform has 56 issues.

| Search | Mean recall | Range | Chat tokens / goal |
| --- | --- | --- | --- |
| Keyword | 33% | 31%–39% | 4,274 |
| English full text | 36% | 33%–39% | 4,120 |
| Semantic | 82% | 72%–92% | 10,957 |
| Semantic with a mode-specific tool description | 87% | 86%–89% | 8,061 |

Scale comparison: 985 issues (56 source issues plus generated distractors), 8 goals and 26 relevant issues; 2 runs per approach, except 1 for every-issue context.

| Approach | Model | Mean recall | Issues seen / goal | Chat tokens / goal |
| --- | --- | --- | --- | --- |
| Agent | gpt-4o | 65% | 30 | 7,065 |
| Agent | gpt-4o-mini | 62% | 28 | 7,623 |
| One semantic search, top 20 | gpt-4o-mini | 50% | 20 | 2,315 |
| Newest 100 issues | gpt-4o-mini | 12% | 100 | 7,707 |
| Every issue in context | gpt-4o-mini | 100% | 985 | 66,349 |

The same-model comparison is 62% versus 50% recall for agent and single search. Sending every issue makes all relevant issues visible by construction; whether the model uses them correctly was not measured. The token ratio against the gpt-4o agent is about 9.4×. Small projects can fit in one prompt, so these results support the agent primarily as a way to retrieve context for larger projects.

Adding explicit reuse of existing issues reduced duplicate tasks from 43 to 16 across 10 goals × 3 runs in the recorded comparison. In the subsequent model comparison, 5% of new tasks duplicated existing work with gpt-4o versus 23% with gpt-4o-mini, at roughly 20× the observed cost per run. Those percentages exclude the SAML goal that asks for decomposition of an existing issue: FlowAI has no subtask relationship yet.

These are small experiments with AI-assisted labels and manual review, not a general benchmark. An early LLM judge was dropped as the main quality measure because it credited plans that had seen no project data. A larger labeled regression suite, streaming progress, dependencies, risks, and assumptions remain future work. Revisions with gpt-4o-mini have been observed to change tasks the feedback did not mention.

### Reproduce and extend

Run scripts from `agent/` against a **local, disposable, seeded backend**. They call real models and require `OPENAI_API_KEY` plus a current user access token in `FLOWAI_USER_TOKEN`; a personal access token for MCP is not interchangeable. `search_modes.py` and `scale.py run` also need the local backend's `JWT_SECRET` because they sign evaluation agent tokens themselves. They exercise the planning graph directly rather than the application's approval flow, so checkpoint configuration is not needed for these scripts.

| Script | Purpose |
| --- | --- |
| [baseline.py](./evals/baseline.py) | Fixed goals with no-issue, agent-retrieval, and all-issue context; generates a relevance-label template and historical judge reports |
| [recall.py](./evals/recall.py) | Measures recall for saved baseline results against relevance labels |
| [search_modes.py](./evals/search_modes.py) | Compares keyword, full-text, and semantic retrieval through the local backend |
| [scale.py](./evals/scale.py) | `build` generates distractors for review, then writes a larger project on a subsequent invocation; `run` compares five approaches |
| [duplicates.py](./evals/duplicates.py) | `judge` compares saved before/after plans; `score` checks manually reviewed judgments |

With `FLOWAI_USER_TOKEN` already exported in the shell:

```bash
uv run --env-file ../.env python evals/baseline.py
RUN_LABEL=semantic-check MODES=keyword,fulltext,semantic REPEATS=3 CONCURRENCY=1 \
  uv run --env-file ../.env python evals/search_modes.py
uv run --env-file ../.env python evals/recall.py ../docs/baseline/results-*.json
```

Before running the search comparison, fill in and review `docs/baseline/relevant-issues.md`. The scripts resolve results and labels to `docs/baseline/` at the repository root. **That directory is ignored and the historical artifacts are not shipped in a clean clone**; regenerate the baseline and labels or supply your own dataset before reproducing the measurements. In this checkout, `docs` may be a local symlink, so check its destination before running scripts that write results.

`FLOWAI_BACKEND_URL` changes the evaluation backend address (default `http://localhost:8080`); it is separate from the runtime's `BACKEND_BASE_URL`. `RUN_LABEL` gives baseline/search-mode runs distinct output names. The scale experiment targets about 1,000 issues but the recorded dataset contains 985; its `build` step creates real project rows. Preserve the generated distractors and labels when comparing runs. Scale and duplicate output names are date-based and can be overwritten by another run on the same day.

For a new scale dataset, run `uv run --env-file ../.env python evals/scale.py build`, review the generated `docs/baseline/scale-issues.json`, and run `build` again to create **Web Platform XL**. Once its embeddings are ready, run `uv run --env-file ../.env python evals/scale.py run`. If distractors already exist, `build` uses them and creates the project directly.

## Checks and troubleshooting

From `agent/`:

```bash
uv sync --locked
uv run ruff check
uv run ruff format --check
uv run pyright
uv run pytest
```

CI runs these checks with fake models and mocked backend responses; [test_checkpoint_postgres.py](./tests/test_checkpoint_postgres.py) uses a real PostgreSQL 17 Testcontainers database and verifies schema isolation, persistence, revision, and cleanup. Docker must be available for the full suite. For a quick pass without Docker:

```bash
uv run pytest --ignore=tests/test_checkpoint_postgres.py
```

| Symptom | Check |
| --- | --- |
| Startup says `CHECKPOINT_DATABASE_URL is required` | Supply it through the process environment or `uv run --env-file`; verify the role/schema initialization |
| Agent cannot connect to PostgreSQL | Use `localhost` for local development and the database service hostname inside containers; match and URL-encode the role password |
| UI reports agent disabled | Restart the backend with `AGENT_ENABLED=true`; frontend availability comes from `/api/ai/status` |
| Run fails in semantic search | Enable backend embeddings and inspect `issue_embedding_batch` / `issue_embedding_failed` logs, or choose `SEARCH_MODE=keyword` |
| HTTP 503 for planning | Supply `OPENAI_API_KEY` to the Python process; a configured but invalid key fails when the model is called |
| Resume returns 409 | Resume the checkpoint stored with the current backend plan version; it must be waiting at `review` |
| Backend rejects a stale review | Reload the latest version and hash before revising or approving |
| Concurrent run returns 409 | Wait for the current request for this person/project to finish and release its Redis lock |
| Closed-run checkpoints remain | Look for `agent_checkpoints_not_deleted` in backend logs; cleanup is best effort |

## Source layout

```text
agent/
├── src/flowai_agent/
│   ├── main.py              FastAPI lifecycle and private run endpoints
│   ├── config.py            Environment settings and budgets
│   ├── graph/               State, nodes, routing, interrupt and checkpoint resume
│   ├── tools/               Backend client and model-facing read-only tools
│   └── models/              Project context, plans and run contracts
├── db/                      Checkpoint role/schema initialization
├── evals/                   Retrieval, scale and duplicate experiments
├── tests/                   Fake-model tests and real PostgreSQL checkpoint tests
├── Dockerfile               Production service image
├── pyproject.toml           Dependencies and check configuration
└── uv.lock                  Locked dependency versions
```
