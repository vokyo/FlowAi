from pydantic import BaseModel, Field


class RunRequest(BaseModel):
    goal: str = Field(min_length=1, max_length=500, pattern=r"\S")
