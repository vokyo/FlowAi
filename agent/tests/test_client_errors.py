import httpx2
import pytest

from flowai_agent.tools.client import BackendClient, BackendError

TOKEN = "agent-token"


def java_error(status: int, message: str) -> httpx2.Response:
    body: dict[str, object] = {
        "code": "X",
        "message": message,
        "fieldErrors": {},
        "traceId": "trace",
    }
    return httpx2.Response(status, json=body)


@pytest.mark.anyio
async def test_a_rejected_argument_carries_the_backends_message() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return java_error(400, "limit must be between 1 and 20")

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    with pytest.raises(BackendError) as caught:
        await client.search_issues("login", 50)
    await client.aclose()

    assert caught.value.kind == "invalid_argument"
    assert "limit must be between 1 and 20" in caught.value.message


@pytest.mark.anyio
@pytest.mark.parametrize("status", [401, 403, 404])
async def test_token_and_access_failures_are_fatal(status: int) -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return java_error(status, "no access")

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    with pytest.raises(BackendError) as caught:
        await client.search_issues("login", 5)
    await client.aclose()

    assert caught.value.kind == "fatal"


@pytest.mark.anyio
@pytest.mark.parametrize("status", [500, 503])
async def test_server_failures_are_retryable(status: int) -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        return java_error(status, "boom")

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    with pytest.raises(BackendError) as caught:
        await client.list_members()
    await client.aclose()

    assert caught.value.kind == "retryable"


@pytest.mark.anyio
async def test_a_timeout_is_retryable() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("timed out", request=request)

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    with pytest.raises(BackendError) as caught:
        await client.search_issues("login", 5)
    await client.aclose()

    assert caught.value.kind == "retryable"


@pytest.mark.anyio
async def test_an_unreachable_backend_is_retryable() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("connection refused", request=request)

    client = BackendClient("http://backend", TOKEN, httpx2.MockTransport(handler))
    with pytest.raises(BackendError) as caught:
        await client.list_members()
    await client.aclose()

    assert caught.value.kind == "retryable"
