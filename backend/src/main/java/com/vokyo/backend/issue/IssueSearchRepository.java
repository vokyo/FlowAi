package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The two ranked queries behind the agent's full-text and semantic issue search. Both
 * return only ids, in rank order, for active issues of one project; IssueSearchService
 * loads the issues.
 */
public interface IssueSearchRepository extends Repository<Issue, UUID> {

    @Query(value = """
            select issue.id
            from issues issue
            where issue.workspace_id = :workspaceId
              and issue.project_id = :projectId
              and issue.archived_at is null
              and issue.search_document @@ websearch_to_tsquery('english', :query)
            order by ts_rank(issue.search_document, websearch_to_tsquery('english', :query)) desc,
                     issue.created_at desc,
                     issue.id desc
            limit :maxResults
            """, nativeQuery = true)
    List<UUID> findIdsByFullText(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("query") String query,
            @Param("maxResults") int maxResults
    );

    @Query(value = """
            select embedding.issue_id
            from issue_embeddings embedding
            join issues issue on issue.id = embedding.issue_id
            where embedding.workspace_id = :workspaceId
              and embedding.project_id = :projectId
              and issue.archived_at is null
            order by embedding.embedding <=> cast(:queryVector as vector),
                     embedding.issue_id
            limit :maxResults
            """, nativeQuery = true)
    List<UUID> findIdsBySimilarity(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("queryVector") String queryVector,
            @Param("maxResults") int maxResults
    );
}
