from datetime import date
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field

from flowai_agent.models.plan import Plan


class RunRequest(BaseModel):
    goal: str = Field(min_length=1, max_length=500, pattern=r"\S")
    runId: UUID
    today: date


class RunStats(BaseModel):
    decisionRounds: int
    toolCalls: int


class RunResult(BaseModel):
    status: Literal["PLANNED", "INSUFFICIENT_INFO", "FAILED"]
    plan: Plan | None = None
    missing: list[str] = []
    reason: str | None = None
    stats: RunStats
    checkpointId: str | None = None
