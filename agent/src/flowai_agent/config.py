from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings

from flowai_agent.tools.client import SearchMode


class Settings(BaseSettings):
    # gpt-4o over gpt-4o-mini, 2026-10-05: of the new tasks outside the SAML goal, 5%
    # repeated an existing issue against 23%, for about 20 times the cost per run.
    ai_model: str = "gpt-4o"
    backend_base_url: str = "http://localhost:8080"
    max_decision_rounds: int = Field(default=4, ge=1, le=10)
    max_tool_calls: int = Field(default=8, ge=1, le=20)
    openai_api_key: SecretStr | None = None
    run_timeout_seconds: float = Field(default=50, gt=0)
    # Semantic search won the 2026-10-05 comparison (docs/baseline/search-modes-*).
    # The backend must have embeddings enabled (SPRING_AI_MODEL_EMBEDDING=openai).
    search_mode: SearchMode = "semantic"
    search_max_results: int = Field(default=20, ge=1, le=20)
