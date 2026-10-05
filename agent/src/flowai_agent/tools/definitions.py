from langchain_core.tools import BaseTool, StructuredTool
from pydantic import BaseModel, Field

from flowai_agent.tools.client import BackendClient, SearchMode


class SearchProjectIssuesArgs(BaseModel):
    query: str | None = Field(default=None, max_length=100)
    limit: int = Field(
        default=20, ge=1, le=20, description="How many issues to return."
    )


# How each search mode matches, told to the model through the tool, so the
# system prompt does not depend on the mode.
QUERY_DESCRIPTIONS: dict[SearchMode, str] = {
    "keyword": (
        "One short word. It must appear exactly, ignoring case, in an issue's "
        "title or description, so a phrase rarely matches."
    ),
    "fulltext": (
        "One to three English words. Each must appear in an issue's title or "
        "description in some form (limits matches limit)."
    ),
    "semantic": (
        "A few words describing the work you are looking for, in any language."
    ),
}

SEARCH_DESCRIPTIONS: dict[SearchMode, str] = {
    "keyword": (
        "Search the issues that already exist in the current project. Use it to "
        "find out whether some work is already being done, so the plan does not "
        "duplicate it. The keyword is matched as one exact piece of text, so an "
        "empty result only means no issue contains that text, not that the work "
        "is missing: try synonyms, the singular or plural form, or the language "
        "the issues are written in. If truncated is true, there are more "
        "matches: search again with a narrower keyword."
    ),
    "fulltext": (
        "Search the issues that already exist in the current project. Use it to "
        "find out whether some work is already being done, so the plan does not "
        "duplicate it. Words are matched in their English base form and every "
        "word must appear, so an empty result only means no issue has all of "
        "them: try fewer or other words. Synonyms and other languages do not "
        "match. If truncated is true, there are more matches."
    ),
    "semantic": (
        "Search the issues that already exist in the current project. Use it to "
        "find out whether some work is already being done, so the plan does not "
        "duplicate it. Issues come back ordered by how close their meaning is to "
        "the query, whatever words or language they use, and a search always "
        "returns up to limit issues even when none is related: read the titles "
        "and decide which ones matter. Search separately for different parts of "
        "the goal rather than for close variants of the same words. truncated "
        "only means that less similar issues exist."
    ),
}


def search_args(mode: SearchMode, max_results: int) -> type[SearchProjectIssuesArgs]:
    """The search arguments as the model sees them: how a query is worded for
    this mode, and limit capped at the real maximum."""

    class ModeSearchProjectIssuesArgs(SearchProjectIssuesArgs):
        query: str | None = Field(
            default=None, max_length=100, description=QUERY_DESCRIPTIONS[mode]
        )
        limit: int = Field(
            default=max_results,
            ge=1,
            le=max_results,
            description="How many issues to return.",
        )

    return ModeSearchProjectIssuesArgs


class GetProjectMembersArgs(BaseModel):
    pass


def build_tools(client: BackendClient, max_results: int = 20) -> list[BaseTool]:
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
            description=SEARCH_DESCRIPTIONS[client.search_mode],
            args_schema=search_args(client.search_mode, max_results),
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
