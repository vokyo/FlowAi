package com.vokyo.backend.accesstoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    Optional<PersonalAccessToken> findByIdAndWorkspaceMembership_Id(UUID id, UUID workspaceMembershipId);

    @Query("""
            select token from PersonalAccessToken token
            where token.workspaceMembership.id = :membershipId and token.revokedAt is null
            order by token.createdAt desc
            """)
    List<PersonalAccessToken> findUnrevokedByMembershipId(@Param("membershipId") UUID membershipId);

    @Query("""
            select count(token) from PersonalAccessToken token
            where token.user.id = :userId and token.revokedAt is null and token.expiresAt > :now
            """)
    long countActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update PersonalAccessToken token
            set token.revokedAt = :now
            where token.user.id = :userId and token.revokedAt is null
            """)
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
