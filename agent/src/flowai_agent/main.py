from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException
from langchain_core.language_models import BaseChatModel
from langchain_openai import ChatOpenAI

from flowai_agent.config import Settings
from flowai_agent.models.run import RunRequest

app = FastAPI()


def get_chat_model() -> BaseChatModel:
    settings = Settings()
    if settings.openai_api_key is None:
        raise HTTPException(status_code=503, detail="openai api key is required")
    return ChatOpenAI(model=settings.ai_model, api_key=settings.openai_api_key)


@app.post("/runs")
async def create_run(
    request: RunRequest, model: Annotated[BaseChatModel, Depends(get_chat_model)]
) -> dict[str, str]:
    reply = await model.ainvoke(request.goal)
    return {"status": "mock", "reply": reply.text}
