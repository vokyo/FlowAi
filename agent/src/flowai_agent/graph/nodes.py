from typing import Any
from uuid import UUID

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from pydantic import ValidationError

from flowai_agent.graph.state import AgentState
from flowai_agent.models.plan import Plan
from flowai_agent.models.project import ProjectMemberResponse
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
- 3 to 5 new tasks. Do not repeat an existing issue or another task in the plan.
- Give each task a clientItemId such as "item-1", unique within the plan.
- Only assign a task to a userId of a project member you found; otherwise leave
  suggestedAssigneeUserId empty.
- A dueDate is optional; if you set one, it must be between {today} and one year
  after it."""


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
        member_call_ids = {
            call["id"]
            for message in state.messages
            if isinstance(message, AIMessage)
            for call in message.tool_calls
            if call["name"] == "get_project_members"
        }
        members: set[UUID] = set()
        for message in state.messages:
            if (
                not isinstance(message, ToolMessage)
                or message.tool_call_id not in member_call_ids
            ):
                continue
            try:
                result = ProjectMemberResponse.model_validate_json(str(message.content))
            except ValidationError:
                continue
            members.update(member.userId for member in result.items)
        items = [
            item
            if item.suggestedAssigneeUserId is None
            or item.suggestedAssigneeUserId in members
            else item.model_copy(update={"suggestedAssigneeUserId": None})
            for item in plan.items
        ]
        return {"plan": plan.model_copy(update={"items": items})}

    async def report_insufficient(self, state: AgentState) -> dict[str, Any]:
        last = state.messages[-1]
        assert isinstance(last, AIMessage)
        missing = [
            f"Still wanted to call {call['name']} with {call['args']}"
            for call in last.tool_calls
        ]
        return {"missing": missing[:5]}
