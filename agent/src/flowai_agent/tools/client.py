import httpx2

from flowai_agent.models.project import IssueSearchResponse, ProjectMemberResponse


class BackendClient:
    def __init__(
        self,
        base_url: str,
        token: str,
        transport: httpx2.AsyncBaseTransport | None = None,
    ) -> None:
        self._http = httpx2.AsyncClient(
            base_url=base_url,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
            transport=transport,
        )

    async def search_issues(self, q: str | None, limit: int) -> IssueSearchResponse:
        params: dict[str, str | int] = {"limit": limit}
        if q is not None:
            params["q"] = q
        response = await self._http.get(
            "/api/internal/agent/project/issues", params=params
        )
        return IssueSearchResponse.model_validate(response.json())

    async def list_members(self) -> ProjectMemberResponse:
        response = await self._http.get("/api/internal/agent/project/members")
        return ProjectMemberResponse.model_validate(response.json())

    async def aclose(self) -> None:
        await self._http.aclose()
