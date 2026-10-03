from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    ai_model: str = "gpt-4o-mini"
    backend_base_url: str = "http://localhost:8080"
    max_decision_rounds: int = Field(default=4, ge=1, le=10)
    max_tool_calls: int = Field(default=8, ge=1, le=20)
    openai_api_key: SecretStr | None = None
    run_timeout_seconds: float = Field(default=50, gt=0)
