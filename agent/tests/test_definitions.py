import json

import httpx2
import pytest
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import ValidationError

from flowai_agent.tools.client import BackendClient, BackendError, SearchMode
from flowai_agent.tools.definitions import build_tools

TOKEN = "agent-token"
ISSUES_JSON: dict[str, object] = {"items": [], "truncated": False}
MEMBERS_JSON: dict[str, object] = {
    "items": [
        {
            "userId": "7d1f3e9a-2c4b-4e8f-9a6d-1b2c3d4e5f60",
            "displayName": "Bob",
            "role": "MEMBER",
        }
    ],
    "truncated": False,
}


def recording_backend(seen: list[httpx2.Request]) -> httpx2.MockTransport:
    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        if request.url.path.endswith("/members"):
            return httpx2.Response(200, json=MEMBERS_JSON)
        return httpx2.Response(200, json=ISSUES_JSON)

    return httpx2.MockTransport(handler)


@pytest.mark.anyio
async def test_the_model_can_only_set_query_and_limit() -> None:
    client = BackendClient("http://backend", TOKEN, recording_backend([]))
    search, members = build_tools(client)
    await client.aclose()

    search_spec = convert_to_openai_tool(search)["function"]
    members_spec = convert_to_openai_tool(members)["function"]
    assert search_spec["name"] == "search_project_issues"
    assert set(search_spec["parameters"]["properties"]) == {"query", "limit"}
    assert members_spec["name"] == "get_project_members"
    assert members_spec["parameters"]["properties"] == {}


@pytest.mark.anyio
async def test_the_search_tool_sends_the_models_query_to_the_backend() -> None:
    seen: list[httpx2.Request] = []
    client = BackendClient("http://backend", TOKEN, recording_backend(seen))
    search, _ = build_tools(client)

    output = await search.ainvoke({"query": "登录", "limit": 5})
    await client.aclose()

    assert seen[0].url.params["q"] == "登录"
    assert seen[0].url.params["limit"] == "5"
    assert json.loads(output) == ISSUES_JSON


@pytest.mark.anyio
async def test_the_members_tool_returns_the_members_as_json() -> None:
    seen: list[httpx2.Request] = []
    client = BackendClient("http://backend", TOKEN, recording_backend(seen))
    _, members = build_tools(client)

    output = await members.ainvoke({})
    await client.aclose()

    assert seen[0].url.path == "/api/internal/agent/project/members"
    assert json.loads(output) == MEMBERS_JSON


@pytest.mark.anyio
async def test_a_project_id_from_the_model_never_reaches_the_backend() -> None:
    seen: list[httpx2.Request] = []
    client = BackendClient("http://backend", TOKEN, recording_backend(seen))
    search, members = build_tools(client)

    await search.ainvoke({"query": "登录", "projectId": "someone-elses-project"})
    await members.ainvoke({"projectId": "someone-elses-project"})
    await client.aclose()

    assert "projectId" not in seen[0].url.params
    assert seen[0].url.params["limit"] == "20"
    assert str(seen[1].url) == "http://backend/api/internal/agent/project/members"


@pytest.mark.anyio
async def test_a_limit_above_20_is_rejected_before_any_request() -> None:
    seen: list[httpx2.Request] = []
    client = BackendClient("http://backend", TOKEN, recording_backend(seen))
    search, _ = build_tools(client)

    with pytest.raises(ValidationError):
        await search.ainvoke({"query": "登录", "limit": 50})
    await client.aclose()

    assert seen == []


@pytest.mark.anyio
async def test_backend_errors_come_out_of_the_tool_unchanged() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(401, json={"code": "AUTHENTICATION_REQUIRED"})

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    _, members = build_tools(client)

    with pytest.raises(BackendError) as caught:
        await members.ainvoke({})
    await client.aclose()

    assert caught.value.kind == "fatal"


@pytest.mark.anyio
async def test_a_capped_search_tells_the_model_its_maximum() -> None:
    seen: list[httpx2.Request] = []
    client = BackendClient("http://backend", TOKEN, recording_backend(seen))
    search = build_tools(client, max_results=10)[0]

    limit = search.args["limit"]
    assert (limit["default"], limit["maximum"]) == (10, 10)
    await search.ainvoke({"query": "login"})
    with pytest.raises(ValidationError):
        await search.ainvoke({"query": "login", "limit": 15})
    await client.aclose()

    assert [request.url.params["limit"] for request in seen] == ["10"]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("mode", "matching", "wording"),
    [
        ("keyword", "one exact piece of text", "One short word"),
        ("fulltext", "English base form", "One to three English words"),
        ("semantic", "even when none is related", "in any language"),
    ],
)
async def test_the_search_tool_explains_how_its_mode_matches(
    mode: SearchMode, matching: str, wording: str
) -> None:
    client = BackendClient(
        "http://backend", TOKEN, recording_backend([]), search_mode=mode
    )
    search = build_tools(client)[0]
    await client.aclose()

    assert matching in search.description
    assert wording in search.args["query"]["description"]
