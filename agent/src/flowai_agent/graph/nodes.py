from collections.abc import Sequence
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    AnyMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.tools import BaseTool
from langgraph.types import interrupt
from pydantic import BaseModel, ValidationError

from flowai_agent.graph.state import AgentState
from flowai_agent.models.plan import ExistingIssue, Plan
from flowai_agent.models.project import IssueSearchResponse, ProjectMemberResponse
from flowai_agent.tools.client import BackendError

MAX_RETRIES = 2
MAX_ARGUMENT_FIXES = 1

SYSTEM_PROMPT = """You plan work for one software project. Today is {today}.
Before planning, find out what the project already has: the issues that exist,
so the plan does not repeat them, and the members who can be assigned.
Make several calls in the same reply: search for each part of the goal and get
the members in that reply too. The search tool's description says how it
matches and how to word a search.
You can call tools in at most {tool_rounds} replies and at most {max_tool_calls}
times in total.
When you know enough to plan, reply without calling any tool."""

PLAN_PROMPT = """Now write the plan for the goal, following these rules:
- First, in existingIssues, list each issue from your search results that
  already covers part of the goal: its id exactly as the search returned it, and
  why it covers that part. List at most 10, and only ids you saw.
- Then add new tasks only for the work no existing issue covers, 0 to 5 of them.
  If existing issues cover the whole goal, add none. Do not repeat an existing
  issue or another task in the plan.
- Give each task a clientItemId such as "item-1", unique within the plan.
- Only assign a task to a userId of a project member you found; otherwise leave
  suggestedAssigneeUserId empty.
- A dueDate is optional; if you set one, it must be between {today} and one year
  after it."""


def tool_results[T: BaseModel](
    messages: Sequence[AnyMessage], tool_name: str, response_type: type[T]
) -> list[T]:
    """The parsed results of every call to one tool, skipping errors and output
    that does not parse."""
    call_ids = {
        call["id"]
        for message in messages
        if isinstance(message, AIMessage)
        for call in message.tool_calls
        if call["name"] == tool_name
    }
    results: list[T] = []
    for message in messages:
        if not isinstance(message, ToolMessage) or message.tool_call_id not in call_ids:
            continue
        try:
            results.append(response_type.model_validate_json(str(message.content)))
        except ValidationError:
            continue
    return results


class PlanningNodes:
    def __init__(self, model: BaseChatModel, tools: list[BaseTool]):
        self._model = model.bind_tools(tools)
        self._tools = {tool.name: tool for tool in tools}
        self._planner = model.with_structured_output(Plan)

    async def write_prompt(self, state: AgentState) -> dict[str, Any]:
        return {
            "messages": [
                SystemMessage(
                    SYSTEM_PROMPT.format(
                        today=state.today.isoformat(),
                        tool_rounds=state.max_decision_rounds - 1,
                        max_tool_calls=state.max_tool_calls,
                    )
                ),
                HumanMessage(state.goal),
            ]
        }

    async def ask_model(self, state: AgentState) -> dict[str, Any]:
        response = await self._model.ainvoke(state.messages)
        return {
            "messages": [response],
            "decision_rounds_used": state.decision_rounds_used + 1,
        }

    async def run_tools(self, state: AgentState) -> dict[str, Any]:
        last = state.messages[-1]
        fixes = state.argument_fixes_used
        assert isinstance(last, AIMessage)
        results: list[ToolMessage] = []
        used = state.tool_calls_used
        for call in last.tool_calls:
            tool = self._tools[call["name"]]
            for retry in range(MAX_RETRIES + 1):
                used += 1
                try:
                    output = await tool.ainvoke(call["args"])
                except (ValidationError, BackendError) as error:
                    if (
                        isinstance(error, ValidationError)
                        or error.kind == "invalid_argument"
                    ):
                        if fixes >= MAX_ARGUMENT_FIXES:
                            reason = (
                                f"{call['name']} got invalid arguments again: {error}"
                            )
                            return {
                                "messages": results,
                                "tool_calls_used": used,
                                "argument_fixes_used": fixes,
                                "failure_reason": reason,
                            }
                        fixes += 1
                        output = f"Error: {error}\nFix the arguments and call again."
                    else:
                        can_retry = (
                            retry < MAX_RETRIES
                            and error.kind == "retryable"
                            and used < state.max_tool_calls
                        )
                        if can_retry:
                            continue
                        return {
                            "messages": results,
                            "tool_calls_used": used,
                            "argument_fixes_used": fixes,
                            "failure_reason": f"{call['name']} failed: {error.message}",
                        }
                results.append(ToolMessage(output, tool_call_id=call["id"]))
                break
        return {
            "messages": results,
            "tool_calls_used": used,
            "argument_fixes_used": fixes,
        }

    async def generate_plan(self, state: AgentState) -> dict[str, Any]:
        plan_prompt = HumanMessage(PLAN_PROMPT.format(today=state.today.isoformat()))
        response = await self._planner.ainvoke([*state.messages, plan_prompt])
        return {"plan": response}

    async def check_plan(self, state: AgentState) -> dict[str, Any]:
        plan = state.plan
        assert plan is not None
        members = {
            member.userId
            for result in tool_results(
                state.messages, "get_project_members", ProjectMemberResponse
            )
            for member in result.items
        }
        # An issue the model never saw in a search result is a made-up or mistyped
        # id. Dropping that one entry keeps the backend from rejecting the plan.
        seen_issues = {
            issue.id
            for result in tool_results(
                state.messages, "search_project_issues", IssueSearchResponse
            )
            for issue in result.items
        }
        existing: list[ExistingIssue] = []
        for entry in plan.existingIssues:
            if entry.issueId in seen_issues and all(
                kept.issueId != entry.issueId for kept in existing
            ):
                existing.append(entry)
        items = [
            item
            if item.suggestedAssigneeUserId is None
            or item.suggestedAssigneeUserId in members
            else item.model_copy(update={"suggestedAssigneeUserId": None})
            for item in plan.items
        ]
        return {
            "plan": plan.model_copy(update={"existingIssues": existing, "items": items})
        }

    async def report_insufficient(self, state: AgentState) -> dict[str, Any]:
        last = state.messages[-1]
        assert isinstance(last, AIMessage)
        missing = [
            f"Still wanted to call {call['name']} with {call['args']}"
            for call in last.tool_calls
        ]
        return {"missing": missing[:5]}

    async def review(self, state: AgentState) -> dict[str, Any]:
        assert state.plan is not None
        feedback = interrupt(state.plan)
        plan = state.plan.model_dump_json()
        return {"messages": [AIMessage(plan), HumanMessage(feedback)]}
