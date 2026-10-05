package com.vokyo.backend.issue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ranked issue search for the planning agent. Like IssueQueryService.searchActiveIssues
 * it does not check access, so callers must pass a project they resolved through an
 * access check.
 */
@Service
public class IssueSearchService {

    private final IssueSearchRepository issueSearchRepository;
    private final IssueRepository issueRepository;

    public IssueSearchService(IssueSearchRepository issueSearchRepository, IssueRepository issueRepository) {
        this.issueSearchRepository = issueSearchRepository;
        this.issueRepository = issueRepository;
    }

    /** Active issues of one project matching the query by full-text search, most relevant first. */
    @Transactional(readOnly = true)
    public List<Issue> searchByFullText(UUID workspaceId, UUID projectId, String query, int maxResults) {
        return loadInOrder(issueSearchRepository.findIdsByFullText(workspaceId, projectId, query, maxResults));
    }

    /** Active issues of one project whose embeddings are closest to the query's, closest first. */
    @Transactional(readOnly = true)
    public List<Issue> searchBySimilarity(UUID workspaceId, UUID projectId, float[] queryVector, int maxResults) {
        return loadInOrder(issueSearchRepository.findIdsBySimilarity(
            workspaceId, projectId, toVectorLiteral(queryVector), maxResults));
    }

    // findAllById returns rows in no particular order; the ranking lives in the id list.
    private List<Issue> loadInOrder(List<UUID> ids) {
        Map<UUID, Issue> issuesById = issueRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Issue::getId, Function.identity()));
        return ids.stream().map(issuesById::get).filter(Objects::nonNull).toList();
    }

    // pgvector parses a vector from text such as [0.1,0.2,0.3].
    static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
