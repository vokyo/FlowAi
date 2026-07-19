package com.vokyo.backend.ai.suggestion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AiSuggestionRepository
        extends JpaRepository<AiSuggestion, UUID> {

    Optional<AiSuggestion>
    findByWorkspace_IdAndCreatedByUser_IdAndId(
            UUID workspaceId,
            UUID userId,
            UUID suggestionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select suggestion
        from AiSuggestion suggestion
        where suggestion.workspace.id = :workspaceId
          and suggestion.createdByUser.id = :userId
          and suggestion.id = :suggestionId
        """)
    Optional<AiSuggestion> findOwnedByIdForUpdate(
            UUID workspaceId,
            UUID userId,
            UUID suggestionId
    );
}