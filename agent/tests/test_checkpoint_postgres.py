"""Checkpoints in a real Postgres, set up by agent/db/init-checkpoint-schema.sh.

The container starts from an empty volume, so the postgres image runs the init script
exactly as it does for docker compose. Needs Docker.
"""

from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from typing import LiteralString

import httpx2
import psycopg
import pytest
from fake_chat_model import FakeChatModel
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.types import Command
from psycopg import AsyncConnection, errors
from psycopg.rows import DictRow, dict_row
from psycopg_pool import AsyncConnectionPool
from test_graph import (
    ENOUGH,
    GOAL,
    PLAN,
    REVISED_PLAN,
    TODAY,
    WRITE_PLAN,
    WRITE_REVISED_PLAN,
    backend,
)
from test_runs import BODY, HEADERS, MEMBERS_CALL, SEARCH, healthy_backend
from testcontainers.community.postgres import PostgresContainer

from flowai_agent.config import Settings
from flowai_agent.graph.build import build_graph
from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import CHECKPOINT_TYPES, AgentState
from flowai_agent.main import app, get_backend_transport, get_chat_model, get_settings
from flowai_agent.models.plan import Plan
from flowai_agent.models.run import RunStats
from flowai_agent.tools.definitions import build_tools

INIT_SCRIPT = Path(__file__).parents[1] / "db" / "init-checkpoint-schema.sh"
AGENT_PASSWORD = "agent-test-password"


class Database:
    def __init__(self, container: PostgresContainer) -> None:
        host = container.get_container_host_ip()
        port = container.get_exposed_port(5432)
        self.admin_url = container.get_connection_url(driver=None)
        self.agent_url = (
            f"postgresql://flowai_agent:{AGENT_PASSWORD}@{host}:{port}/flowai"
        )


@pytest.fixture(scope="module")
def database() -> Iterator[Database]:
    container = (
        PostgresContainer(
            "pgvector/pgvector:pg17",
            username="flowai",
            password="flowai-test-password",
            dbname="flowai",
            driver=None,
        )
        .with_env("AGENT_DB_PASSWORD", AGENT_PASSWORD)
        .with_volume_mapping(
            INIT_SCRIPT, "/docker-entrypoint-initdb.d/20-agent-checkpoint-schema.sh"
        )
    )
    with container:
        yield Database(container)


def open_pool(url: str) -> AsyncConnectionPool[AsyncConnection[DictRow]]:
    return AsyncConnectionPool(
        url,
        connection_class=AsyncConnection[DictRow],
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
        open=False,
    )


async def saver(
    pool: AsyncConnectionPool[AsyncConnection[DictRow]],
) -> AsyncPostgresSaver:
    checkpointer = AsyncPostgresSaver(
        pool, serde=JsonPlusSerializer(allowed_msgpack_modules=CHECKPOINT_TYPES)
    )
    await checkpointer.setup()
    return checkpointer


@pytest.fixture
async def clean_thread(database: Database) -> AsyncIterator[str]:
    yield "run-restart"
    async with open_pool(database.agent_url) as pool:
        await (await saver(pool)).adelete_thread("run-restart")


@pytest.mark.anyio
async def test_a_run_waiting_for_review_can_be_revised_after_a_restart(
    database: Database, clean_thread: str
) -> None:
    run: RunnableConfig = {"configurable": {"thread_id": clean_thread}}
    client = backend([], truncated_queries=set())

    async with open_pool(database.agent_url) as pool:
        before = build_graph(
            PlanningNodes(FakeChatModel(replies=[ENOUGH, WRITE_PLAN]), []),
            checkpointer=await saver(pool),
        )
        await before.ainvoke(  # pyright: ignore[reportUnknownMemberType]
            AgentState(goal=GOAL, today=TODAY), run, version="v2"
        )

    # A new pool and checkpointer, as after a restart: nothing is left in memory.
    model = FakeChatModel(
        replies=[AIMessage("I know enough to revise."), WRITE_REVISED_PLAN]
    )
    async with open_pool(database.agent_url) as pool:
        after = build_graph(
            PlanningNodes(model, build_tools(client)), checkpointer=await saver(pool)
        )
        revised = await after.ainvoke(  # pyright: ignore[reportUnknownMemberType]
            Command(resume="Drop item-2"), run, version="v2"
        )
        paused_again = await after.aget_state(run)
    await client.aclose()

    previous_plan, feedback = model.received[0][-2:]
    assert Plan.model_validate_json(previous_plan.text) == Plan.model_validate(PLAN)
    assert isinstance(feedback, HumanMessage)
    assert feedback.text.startswith("Revise the plan above as follows: Drop item-2")
    assert revised.value.plan == Plan.model_validate(REVISED_PLAN)
    assert paused_again.next == ("review",)


@pytest.mark.parametrize(
    "statement",
    [
        pytest.param("select count(*) from public.issues", id="read a table"),
        pytest.param("update public.issues set title = 'x'", id="change a table"),
        pytest.param("create table public.probe (id int)", id="add a table"),
        pytest.param("create schema probe", id="add a schema"),
    ],
)
def test_the_agent_role_cannot_touch_the_application_data(
    database: Database, statement: LiteralString
) -> None:
    with psycopg.connect(database.admin_url) as admin:
        admin.execute("create table if not exists public.issues (title text)")

    with psycopg.connect(database.agent_url) as agent:
        with pytest.raises(errors.InsufficientPrivilege):
            agent.execute(statement)


def test_the_service_saves_a_run_in_postgres_and_rebuilds_only_listed_types(
    database: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CHECKPOINT_DATABASE_URL", database.agent_url)
    app.dependency_overrides[get_chat_model] = lambda: FakeChatModel(
        replies=[
            AIMessage("", tool_calls=[SEARCH, MEMBERS_CALL]),
            AIMessage("I know enough to plan."),
            AIMessage("", tool_calls=[{"name": "Plan", "args": PLAN, "id": "c3"}]),
        ]
    )
    app.dependency_overrides[get_backend_transport] = lambda: httpx2.MockTransport(
        healthy_backend([])
    )
    app.dependency_overrides[get_settings] = lambda: Settings(
        backend_base_url="http://backend"
    )
    run_id = str(BODY["runId"])
    # One of our types that is not listed, saved by a serializer that allows all.
    unlisted = JsonPlusSerializer().dumps_typed(RunStats(decisionRounds=1, toolCalls=2))

    try:
        with TestClient(app) as client:
            response = client.post("/runs", json=BODY, headers=HEADERS)
            checkpointer: AsyncPostgresSaver = app.state.checkpointer
            portal = client.portal
            assert portal is not None
            run: RunnableConfig = {"configurable": {"thread_id": run_id}}
            saved = portal.call(checkpointer.aget_tuple, run)
            portal.call(checkpointer.adelete_thread, run_id)
            rebuilt = checkpointer.serde.loads_typed(unlisted)
    finally:
        app.dependency_overrides.clear()

    assert response.json()["status"] == "PLANNED"
    assert saved is not None
    assert isinstance(saved.checkpoint["channel_values"]["plan"], Plan)
    assert not isinstance(rebuilt, RunStats)
