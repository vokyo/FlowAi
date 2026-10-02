# pyright: reportUnknownMemberType=false
from datetime import date

from langchain_core.messages import AIMessage
from langgraph.graph import END, START, StateGraph

from flowai_agent.graph.nodes import PlanningNodes
from flowai_agent.graph.state import AgentState


def route_after_model(state: AgentState) -> str:
    last = state.messages[-1]
    assert isinstance(last, AIMessage)
    if last.tool_calls:
        return "run_tools"
    else:
        return END


def build_graph(nodes: PlanningNodes):
    builder = StateGraph(AgentState)
    builder.add_node("write_prompt", nodes.write_prompt)
    builder.add_node("ask_model", nodes.ask_model)
    builder.add_node("run_tools", nodes.run_tools)
    builder.add_edge(START, "write_prompt")
    builder.add_edge("write_prompt", "ask_model")
    builder.add_conditional_edges("ask_model", route_after_model, ["run_tools", END])
    builder.add_edge("run_tools", "ask_model")
    return builder.compile()


async def run_graph(nodes: PlanningNodes, goal: str, today: date) -> AgentState:
    output = await build_graph(nodes).ainvoke(
        AgentState(goal=goal, today=today), version="v2"
    )
    return output.value
