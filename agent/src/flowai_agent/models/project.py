from typing import Literal
from uuid import UUID

from pydantic import BaseModel


class IssueItem(BaseModel):
    id: UUID
    title: str
    status: Literal["TODO", "IN_PROGRESS", "DONE"]
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"] | None
    assigneeUserId: UUID | None
    assigneeDisplayName: str | None


class IssueSearchResponse(BaseModel):
    items: list[IssueItem]
    truncated: bool


class MemberItem(BaseModel):
    userId: UUID
    displayName: str
    role: Literal["OWNER", "MEMBER"]


class ProjectMemberResponse(BaseModel):
    items: list[MemberItem]
    truncated: bool
