package com.vokyo.backend.issue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    List<IssueComment> findByIssue_IdOrderByCreatedAtAsc(UUID issueId);

    long countByIssue_Id(UUID issueId);
}
