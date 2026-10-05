from typing import Literal

import httpx2
from pydantic import ValidationError

from flowai_agent.models.project import IssueSearchResponse, ProjectMemberResponse

# How the backend matches a search. It comes from configuration so an evaluation can
# compare modes on the same goals; the model never chooses it.
SearchMode = Literal["keyword", "fulltext", "semantic"]


class BackendError(Exception):
    def __init__(
        self, kind: Literal["invalid_argument", "fatal", "retryable"], message: str
    ) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class BackendClient:
    def __init__(
        self,
        base_url: str,
        token: str,
        transport: httpx2.AsyncBaseTransport | None = None,
        search_mode: SearchMode = "keyword",
    ) -> None:
        self._search_mode: SearchMode = search_mode
        self._http = httpx2.AsyncClient(
            base_url=base_url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
            transport=transport,
        )

    @property
    def search_mode(self) -> SearchMode:
        return self._search_mode

    async def search_issues(self, query: str | None, limit: int) -> IssueSearchResponse:
        params: dict[str, str | int] = {"limit": limit, "mode": self._search_mode}
        if query is not None:
            params["q"] = query
        response = await self._get("/api/internal/agent/project/issues", params)
        try:
            return IssueSearchResponse.model_validate_json(response.content)
        except ValidationError as e:
            raise BackendError(
                "fatal", "backend returned an unexpected response"
            ) from e

    async def list_members(self) -> ProjectMemberResponse:
        response = await self._get("/api/internal/agent/project/members")
        try:
            return ProjectMemberResponse.model_validate_json(response.content)
        except ValidationError as e:
            raise BackendError(
                "fatal", "backend returned an unexpected response"
            ) from e

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(
        self, path: str, params: dict[str, str | int] | None = None
    ) -> httpx2.Response:
        try:
            response = await self._http.get(path, params=params)
        except httpx2.TransportError as e:
            raise BackendError("retryable", f"backend request failed:{e}") from e
        status = response.status_code
        if status == 400:
            raise BackendError("invalid_argument", response.text)
        if 400 < status < 500:
            raise BackendError("fatal", response.text)
        if status >= 500:
            raise BackendError("retryable", response.text)
        return response
