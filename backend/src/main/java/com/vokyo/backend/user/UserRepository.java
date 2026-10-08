package com.vokyo.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("select user.tokenVersion from User user where user.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") UUID id);

    /**
     * Ends every access token the user holds by raising the version they were issued
     * under. One statement in the database, so two raises at once both count and
     * nothing else about the user is written.
     */
    @Modifying
    @Query("update User user set user.tokenVersion = user.tokenVersion + 1 where user.id = :id")
    int revokeAccessTokens(@Param("id") UUID id);
}
