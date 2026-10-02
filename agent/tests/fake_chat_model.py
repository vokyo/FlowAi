from collections.abc import Callable, Sequence
from typing import Any

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel, LanguageModelInput
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.runnables import Runnable
from langchain_core.tools import BaseTool
from langchain_core.utils.function_calling import convert_to_openai_tool


class FakeChatModel(BaseChatModel):
    """Answers with the scripted replies in order and remembers what it was sent."""

    replies: list[AIMessage]
    received: list[list[BaseMessage]] = []
    bound_tools: list[list[str]] = []

    @property
    def _llm_type(self) -> str:
        return "fake"

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        self.received.append(list(messages))
        reply = self.replies[len(self.received) - 1]
        return ChatResult(generations=[ChatGeneration(message=reply)])

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | Callable[..., Any] | BaseTool],
        *,
        tool_choice: str | None = None,
        **kwargs: Any,
    ) -> Runnable[LanguageModelInput, AIMessage]:
        names = [convert_to_openai_tool(tool)["function"]["name"] for tool in tools]
        self.bound_tools.append(names)
        return self
