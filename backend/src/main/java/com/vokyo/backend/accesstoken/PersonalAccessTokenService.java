package com.vokyo.backend.accesstoken;

import com.vokyo.backend.accesstoken.dto.AccessTokenResponse;
import com.vokyo.backend.accesstoken.dto.CreateAccessTokenRequest;
import com.vokyo.backend.accesstoken.dto.CreatedAccessTokenResponse;
import com.vokyo.backend.workspace.CurrentWorkspaceContext;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceAccessService;
import com.vokyo.backend.workspace.WorkspaceMembership;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Personal access tokens: created and revoked by their user in settings, and
 * presented by AI apps to the MCP endpoint. Each speaks for one user in one
 * workspace, expires after 30, 90 or 365 days, and is kept only as a hash. Changing
 * the password or signing out everywhere revokes them all; a plain logout does not.
 */
@Service
public class PersonalAccessTokenService {

    /** Makes a leaked token recognisable as ours, to people and to secret scanners. */
    static final String PREFIX = "flowai_pat_";
    static final int MAX_ACTIVE_TOKENS = 10;
    static final Set<Integer> LIFETIMES_IN_DAYS = Set.of(30, 90, 365);
    private static final int TOKEN_BYTES = 32;
    private static final Duration LAST_USED_INTERVAL = Duration.ofMinutes(5);

    private final PersonalAccessTokenRepository tokens;
    private final WorkspaceAccessService workspaceAccessService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public PersonalAccessTokenService(
            PersonalAccessTokenRepository tokens,
            WorkspaceAccessService workspaceAccessService,
            Clock clock
    ) {
        this.tokens = tokens;
        this.workspaceAccessService = workspaceAccessService;
        this.clock = clock;
    }

    @Transactional
    public CreatedAccessTokenResponse create(Jwt jwt, CreateAccessTokenRequest request) {
        if (!LIFETIMES_IN_DAYS.contains(request.lifetimeDays())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lifetimeDays must be 30, 90 or 365");
        }
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Instant now = clock.instant();
        if (tokens.countActiveByUserId(context.user().getId(), now) >= MAX_ACTIVE_TOKENS) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You can keep at most " + MAX_ACTIVE_TOKENS + " access tokens; revoke one first"
            );
        }

        String plainToken = PREFIX + randomPart();
        PersonalAccessToken token = tokens.save(new PersonalAccessToken(
                context.user(),
                context.membership(),
                request.name().strip(),
                hash(plainToken),
                now,
                now.plus(Duration.ofDays(request.lifetimeDays()))
        ));
        return new CreatedAccessTokenResponse(
                token.getId(), token.getName(), plainToken, token.getCreatedAt(), token.getExpiresAt());
    }

    /** The tokens of the current workspace membership that have not been revoked, newest first. */
    @Transactional(readOnly = true)
    public List<AccessTokenResponse> list(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        Instant now = clock.instant();
        return tokens.findUnrevokedByMembershipId(context.membership().getId()).stream()
                .map(token -> new AccessTokenResponse(
                        token.getId(),
                        token.getName(),
                        token.getCreatedAt(),
                        token.getExpiresAt(),
                        token.getLastUsedAt(),
                        !token.getExpiresAt().isAfter(now)
                ))
                .toList();
    }

    /** Revokes one of the current membership's tokens. Revoking it again is harmless. */
    @Transactional
    public void revoke(Jwt jwt, UUID tokenId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        PersonalAccessToken token = tokens.findByIdAndWorkspaceMembership_Id(tokenId, context.membership().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Access token was not found"));
        token.revoke(clock.instant());
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        tokens.revokeAllByUserId(userId, clock.instant());
    }

    /**
     * The user and workspace a presented token speaks for, or empty when it is not one
     * of ours, has been revoked or has expired, or its membership is no longer active.
     */
    @Transactional
    public Optional<AccessTokenPrincipal> authenticate(String presentedToken) {
        if (presentedToken == null || !presentedToken.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByTokenHash(hash(presentedToken))
                .filter(token -> token.isUsableAt(now))
                .filter(token -> token.getWorkspaceMembership().getStatus() == MembershipStatus.ACTIVE)
                .map(token -> {
                    token.markUsed(now, LAST_USED_INTERVAL);
                    WorkspaceMembership membership = token.getWorkspaceMembership();
                    return new AccessTokenPrincipal(
                            token.getId(),
                            token.getUser().getId(),
                            membership.getWorkspace().getId(),
                            membership.getId()
                    );
                });
    }

    private String randomPart() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plainToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
