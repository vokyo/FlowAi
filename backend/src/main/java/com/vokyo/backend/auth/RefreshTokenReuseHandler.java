package com.vokyo.backend.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reacts to a refresh token that is presented after it was already rotated away.
 *
 * <p>Rotation by itself does not stop a stolen refresh token from being used. What it
 * guarantees is that the theft becomes <em>observable</em>: each token is accepted
 * exactly once, so as soon as two parties hold the same one, whichever presents it
 * second lands here. Without this handler that moment is indistinguishable from any
 * other bad token, and the attacker — who already rotated into a fresh token — keeps
 * a working session for the full refresh-token lifetime while the real user is the
 * one signed out.
 *
 * <p>Acting on the signal is what turns rotation into a defence: it shrinks the
 * attacker's window from the refresh-token lifetime down to the access-token
 * lifetime.
 *
 * <p>Every live session for the membership is revoked, not only the chain that
 * leaked, because a token row carries no chain identity to follow. That signs the
 * user out of their other devices too. The blunt version is deliberate: it needs no
 * schema change, and one extra sign-in is a fair price for a suspected theft.
 */
@Component
public class RefreshTokenReuseHandler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseHandler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final MeterRegistry meterRegistry;

    public RefreshTokenReuseHandler(
            RefreshTokenRepository refreshTokenRepository,
            MeterRegistry meterRegistry
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Revokes every live session belonging to the reused token's membership.
     *
     * <p>Runs in its own transaction on purpose. The caller rejects the request by
     * throwing, which rolls the surrounding transaction back, and a revocation that
     * rolled back with it would leave the attacker's session untouched — the one
     * outcome this path exists to prevent.
     *
     * <p>The caller also holds a row lock on the reused token. That row is already
     * revoked, so the {@code revoked_at is null} predicate in the bulk update skips
     * it and the two transactions never contend for the same row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReuseDetected(RefreshToken reusedToken) {
        UUID membershipId = reusedToken.getWorkspaceMembership().getId();
        int revokedSessions = refreshTokenRepository.revokeAllByMembershipId(membershipId, Instant.now());

        meterRegistry.counter("flowai.authentication.refresh_token_reuse").increment();
        log.warn(
                "event=refresh_token_reuse_detected userId={} membershipId={} revokedSessions={}",
                reusedToken.getUser().getId(),
                membershipId,
                revokedSessions
        );
    }
}
