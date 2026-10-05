from datetime import date
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class PlanItem(BaseModel):
    clientItemId: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=240)
    description: str | None = Field(default=None, max_length=10000)
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"]
    suggestedAssigneeUserId: UUID | None = None
    dueDate: date | None = None


class ExistingIssue(BaseModel):
    issueId: UUID
    reason: str = Field(min_length=1, max_length=500)


class Plan(BaseModel):
    overview: str = Field(min_length=1, max_length=2000)
    existingIssues: list[ExistingIssue] = Field(default=[], max_length=10)
    items: list[PlanItem] = Field(min_length=0, max_length=5)
