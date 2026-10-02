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


class Plan(BaseModel):
    overview: str = Field(min_length=1, max_length=2000)
    items: list[PlanItem] = Field(min_length=3, max_length=5)
