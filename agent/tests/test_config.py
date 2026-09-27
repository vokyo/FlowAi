import pytest

from flowai_agent.config import Settings


def test_settings_work_without_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("AI_MODEL", raising=False)
    settings = Settings()
    assert settings.openai_api_key is None
    assert settings.ai_model == "gpt-4o-mini"


def test_env_overrides_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_MODEL", "gpt-4.1-mini")
    monkeypatch.setenv("MAX_DECISION_ROUNDS", "6")
    settings = Settings()
    assert settings.ai_model == "gpt-4.1-mini"
    assert settings.max_decision_rounds == 6
