import httpx2
import pytest

from flowai_agent.tools.client import BackendClient

TOKEN = "agent-token"
ISSUE_ID = "0b8c6a52-6a3e-4d6b-9a39-5f1d2c3b4a51"
USER_ID = "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60"


@pytest.mark.anyio
async def test_search_issues_sends_token_query_and_limit() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(
            200,
            json={
                "items": [
                    {
                        "id": ISSUE_ID,
                        "title": "Fix login timeout",
                        "status": "TODO",
                        "priority": "HIGH",
                        "assigneeUserId": None,
                        "assigneeDisplayName": None,
                    }
                ],
                "truncated": True,
            },
        )

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    result = await client.search_issues("登录", 5)
    await client.aclose()

    request = seen[0]
    assert request.url.path == "/api/internal/agent/project/issues"
    assert request.url.params["q"] == "登录"
    assert request.url.params["limit"] == "5"
    assert request.headers["Authorization"] == f"Bearer {TOKEN}"
    assert result.items[0].title == "Fix login timeout"
    assert result.truncated is True


@pytest.mark.anyio
async def test_search_issues_without_query_leaves_out_q() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json={"items": [], "truncated": False})

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    await client.search_issues(None, 20)
    await client.aclose()

    assert "q" not in seen[0].url.params
    assert seen[0].url.params["limit"] == "20"
    assert seen[0].url.params["mode"] == "keyword"


@pytest.mark.anyio
async def test_search_issues_sends_the_configured_search_mode() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json={"items": [], "truncated": False})

    client = BackendClient(
        "http://backend",
        TOKEN,
        httpx2.MockTransport(handler),
        search_mode="semantic",
    )
    await client.search_issues("登录", 5)
    await client.aclose()

    assert seen[0].url.params["mode"] == "semantic"


@pytest.mark.anyio
async def test_search_issues_keeps_special_characters_in_the_query() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(200, json={"items": [], "truncated": False})

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    await client.search_issues("C++ & bug #12", 5)
    await client.aclose()

    assert seen[0].url.params["q"] == "C++ & bug #12"


@pytest.mark.anyio
async def test_list_members_calls_the_members_endpoint() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(
            200,
            json={
                "items": [{"userId": USER_ID, "displayName": "Bob", "role": "MEMBER"}],
                "truncated": False,
            },
        )

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    result = await client.list_members()
    await client.aclose()

    assert seen[0].url.path == "/api/internal/agent/project/members"
    assert seen[0].headers["Authorization"] == f"Bearer {TOKEN}"
    assert result.items[0].displayName == "Bob"
