from datetime import date
from typing import Annotated

from langchain_core.messages import AnyMessage
from langgraph.graph import add_messages
from pydantic import BaseModel

from flowai_agent.models.plan import Plan


class AgentState(BaseModel):
    goal: str
    today: date
    messages: Annotated[list[AnyMessage], add_messages] = []
    decision_rounds_used: int = 0
    tool_calls_used: int = 0
    plan: Plan | None = None
    max_decision_rounds: int = 4
    max_tool_calls: int = 8
    argument_fixes_used: int = 0
    missing: list[str] = []
    failure_reason: str | None = None


# Our own types a checkpoint holds. LangGraph rebuilds only the types it knows and
# the ones listed here, so a state field of a new type of ours must be added.
CHECKPOINT_TYPES = [Plan]
