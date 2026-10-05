import asyncio
import logging
from typing import Annotated

import httpx2
from fastapi import Depends, FastAPI, Header, HTTPException
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI

from flowai_agent.config import Settings
from flowai_agent.graph.build import run_graph
from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import AgentState
from flowai_agent.models.run import RunRequest, RunResult, RunStats
from flowai_agent.tools.client import BackendClient
from flowai_agent.tools.definitions import build_tools

logger = logging.getLogger(__name__)
app = FastAPI()


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
    return to_result(final)


def to_result(final: AgentState) -> RunResult:
    stats = RunStats(
        decisionRounds=final.decision_rounds_used, toolCalls=final.tool_calls_used
    )
    if final.failure_reason is not None:
        return RunResult(status="FAILED", reason=final.failure_reason, stats=stats)
    if final.missing:
        return RunResult(status="INSUFFICIENT_INFO", missing=final.missing, stats=stats)
    if final.plan is not None:
        return RunResult(status="PLANNED", plan=final.plan, stats=stats)
    else:
        return RunResult(
            status="FAILED", reason="The run ended without a plan", stats=stats
        )


def failed(reason: str) -> RunResult:
    return RunResult(
        status="FAILED", reason=reason, stats=RunStats(decisionRounds=0, toolCalls=0)
    )
