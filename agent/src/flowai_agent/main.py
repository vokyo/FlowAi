import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import httpx2
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from psycopg import AsyncConnection
from psycopg.rows import DictRow, dict_row
from psycopg_pool import AsyncConnectionPool

from flowai_agent.config import Settings
from flowai_agent.graph.build import resume_graph, run_graph
from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import CHECKPOINT_TYPES, AgentState
from flowai_agent.models.run import ResumeRequest, RunRequest, RunResult, RunStats
from flowai_agent.tools.client import BackendClient
from flowai_agent.tools.definitions import build_tools

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    url = Settings().checkpoint_database_url
    if url is None:
        raise RuntimeError("CHECKPOINT_DATABASE_URL is required")
    async with AsyncConnectionPool(
        url.get_secret_value(),
        connection_class=AsyncConnection[DictRow],
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
    ) as pool:
        checkpointer = AsyncPostgresSaver(
            pool, serde=JsonPlusSerializer(allowed_msgpack_modules=CHECKPOINT_TYPES)
        )
        await checkpointer.setup()
        app.state.checkpointer = checkpointer
        yield


app = FastAPI(lifespan=lifespan)


def get_checkpointer(request: Request) -> BaseCheckpointSaver[str]:
    return request.app.state.checkpointer


def get_settings() -> Settings:
    return Settings()


def get_chat_model(
    settings: Annotated[Settings, Depends(get_settings)],
) -> BaseChatModel:
    if settings.openai_api_key is None:
        raise HTTPException(status_code=503, detail="openai api key is required")
    return ChatOpenAI(model=settings.ai_model, api_key=settings.openai_api_key)


def get_backend_transport() -> httpx2.AsyncBaseTransport | None:
    return None


@app.post("/runs")
async def create_run(
    request: RunRequest,
    model: Annotated[BaseChatModel, Depends(get_chat_model)],
    settings: Annotated[Settings, Depends(get_settings)],
    transport: Annotated[
        httpx2.AsyncBaseTransport | None, Depends(get_backend_transport)
    ],
    checkpointer: Annotated[BaseCheckpointSaver[str], Depends(get_checkpointer)],
    authorization: Annotated[str | None, Header()] = None,
) -> RunResult:
    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="agent token is required")
    token = authorization.removeprefix("Bearer ")
    client = BackendClient(
        settings.backend_base_url, token, transport, search_mode=settings.search_mode
    )
    start = AgentState(
        goal=request.goal,
        today=request.today,
        max_decision_rounds=settings.max_decision_rounds,
        max_tool_calls=settings.max_tool_calls,
    )
    try:
        async with asyncio.timeout(settings.run_timeout_seconds):
            final = await run_graph(
                PlanningNodes(model, build_tools(client, settings.search_max_results)),
                start,
                checkpointer,
                str(request.runId),
            )
            saved = await checkpointer.aget_tuple(
                {"configurable": {"thread_id": str(request.runId)}}
            )
            checkpoint_id = (
                None
                if saved is None
                else saved.config.get("configurable", {}).get("checkpoint_id")
            )
    except TimeoutError:
        logger.warning("run %s timed out", request.runId)
        return failed(
            f"The run took longer than {settings.run_timeout_seconds} seconds"
        )
    except Exception as error:
        logger.exception("run %s failed", request.runId)
        return failed(f"The run failed unexpectedly: {type(error).__name__}")
    finally:
        await client.aclose()
    return to_result(final, checkpoint_id)


@app.post("/runs/{run_id}/resume")
async def resume_run(
    run_id: UUID,
    request: ResumeRequest,
    model: Annotated[BaseChatModel, Depends(get_chat_model)],
    settings: Annotated[Settings, Depends(get_settings)],
    transport: Annotated[
        httpx2.AsyncBaseTransport | None, Depends(get_backend_transport)
    ],
    checkpointer: Annotated[BaseCheckpointSaver[str], Depends(get_checkpointer)],
    authorization: Annotated[str | None, Header()] = None,
) -> RunResult:
    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="agent token is required")
    token = authorization.removeprefix("Bearer ")
    client = BackendClient(
        settings.backend_base_url, token, transport, search_mode=settings.search_mode
    )
    try:
        async with asyncio.timeout(settings.run_timeout_seconds):
            resumed = await resume_graph(
                PlanningNodes(model, build_tools(client, settings.search_max_results)),
                checkpointer,
                str(run_id),
                request.checkpointId,
                request.feedback,
            )
    except TimeoutError:
        logger.warning("revision of run %s timed out", run_id)
        return failed(
            f"The revision took longer than {settings.run_timeout_seconds} seconds"
        )
    except Exception as error:
        logger.exception("revision of run %s failed", run_id)
        return failed(f"The revision failed unexpectedly: {type(error).__name__}")
    finally:
        await client.aclose()
    if resumed is None:
        raise HTTPException(
            status_code=409, detail="checkpoint is not waiting for review"
        )
    final, checkpoint_id = resumed
    return to_result(final, checkpoint_id)


def to_result(final: AgentState, checkpoint_id: str | None) -> RunResult:
    stats = RunStats(
        decisionRounds=final.decision_rounds_used, toolCalls=final.tool_calls_used
    )
    if final.failure_reason is not None:
        return RunResult(status="FAILED", reason=final.failure_reason, stats=stats)
    if final.missing:
        return RunResult(status="INSUFFICIENT_INFO", missing=final.missing, stats=stats)
    if final.plan is not None:
        return RunResult(
            status="PLANNED", plan=final.plan, stats=stats, checkpointId=checkpoint_id
        )
    else:
        return RunResult(
            status="FAILED", reason="The run ended without a plan", stats=stats
        )


def failed(reason: str) -> RunResult:
    return RunResult(
        status="FAILED", reason=reason, stats=RunStats(decisionRounds=0, toolCalls=0)
    )
