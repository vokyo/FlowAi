# pyright: reportUnknownMemberType=false
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import CheckpointPayload, Command

from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import AgentState


def route_after_tools(state: AgentState) -> str:
    if state.failure_reason is not None:
        return END
    return "ask_model"


def route_after_model(state: AgentState) -> str:
    last = state.messages[-1]
    assert isinstance(last, AIMessage)
    if last.tool_calls:
        if (
            state.decision_rounds_used >= state.max_decision_rounds
            or state.tool_calls_used + len(last.tool_calls) > state.max_tool_calls
        ):
            return "report_insufficient"
        return "run_tools"
    else:
        return "generate_plan"


def build_graph(
    nodes: PlanningNodes, checkpointer: BaseCheckpointSaver[str] | None = None
):
    builder = StateGraph(AgentState)
    builder.add_node("write_prompt", nodes.write_prompt)
    builder.add_node("ask_model", nodes.ask_model)
    builder.add_node("generate_plan", nodes.generate_plan)
    builder.add_node("check_plan", nodes.check_plan)
    builder.add_node("report_insufficient", nodes.report_insufficient)
    builder.add_node("run_tools", nodes.run_tools)
    builder.add_node("review", nodes.review)
    builder.add_edge(START, "write_prompt")
    builder.add_edge("write_prompt", "ask_model")
    builder.add_conditional_edges(
        "ask_model",
        route_after_model,
        ["run_tools", "generate_plan", "report_insufficient"],
    )
    builder.add_conditional_edges("run_tools", route_after_tools, ["ask_model", END])
    builder.add_edge("generate_plan", "check_plan")
    builder.add_edge("check_plan", "review")
    builder.add_edge("review", "ask_model")
    builder.add_edge("report_insufficient", END)
    return builder.compile(checkpointer=checkpointer)


async def run_graph(
    nodes: PlanningNodes,
    start: AgentState,
    checkpointer: BaseCheckpointSaver[str] | None = None,
    thread_id: str | None = None,
) -> AgentState:
    run: RunnableConfig = {"configurable": {"thread_id": thread_id}}
    output = await build_graph(nodes, checkpointer).ainvoke(start, run, version="v2")
    return output.value


async def resume_graph(
    nodes: PlanningNodes,
    checkpointer: BaseCheckpointSaver[str],
    thread_id: str,
    checkpoint_id: str,
    feedback: str,
) -> tuple[AgentState, str | None] | None:
    graph = build_graph(nodes, checkpointer)

    paused = await graph.aget_state(
        {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": checkpoint_id,
            }
        }
    )
    if paused.next != ("review",):
        return None

    branch = await graph.aupdate_state(paused.config, None, as_node="check_plan")

    last: CheckpointPayload[AgentState] | None = None
    async for part in graph.astream(
        Command(resume=feedback), branch, stream_mode="checkpoints", version="v2"
    ):
        if part["type"] == "checkpoints":
            last = part["data"]
    if last is None or last["config"] is None:
        return None

    stopped_at = last["config"].get("configurable", {}).get("checkpoint_id")
    return AgentState.model_validate(last["values"]), stopped_at
