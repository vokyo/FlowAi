from langchain_core.tools import BaseTool, StructuredTool
from pydantic import BaseModel, Field

from flowai_agent.tools.client import BackendClient


class SearchProjectIssuesArgs(BaseModel):
    query: str | None = Field(
        default=None,
        max_length=100,
        description=(
            "One short word. It must appear exactly, ignoring case, in an issue's "
            "title or description, so a phrase rarely matches."
        ),
    )
    limit: int = Field(
        default=20, ge=1, le=20, description="How many issues to return."
    )


class GetProjectMembersArgs(BaseModel):
    pass


def build_tools(client: BackendClient) -> list[BaseTool]:
    async def search_project_issues(query: str | None, limit: int) -> str:
        result = await client.search_issues(query, limit)
        return result.model_dump_json()

    async def get_project_members() -> str:
        result = await client.list_members()
        return result.model_dump_json()

    return [
        StructuredTool.from_function(
            coroutine=search_project_issues,
            name="search_project_issues",
            description=(
                "Search the issues that already exist in the current project. Use it "
                "to find out whether some work is already being done, so the plan "
                "does not duplicate it. The keyword is matched as one exact piece of "
                "text, so an empty result only means no issue contains that text, not "
                "that the work is missing. If truncated is true, there are more "
                "matches: search again with a narrower keyword."
            ),
            args_schema=SearchProjectIssuesArgs,
        ),
        StructuredTool.from_function(
            coroutine=get_project_members,
            name="get_project_members",
            description=(
                "List the active members of the current project. Call it before "
                "suggesting assignees; only these userIds may be used."
            ),
            args_schema=GetProjectMembersArgs,
        ),
    ]
