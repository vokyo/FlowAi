package com.vokyo.backend.auth;

import com.vokyo.backend.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Component
public class RefreshTokenReuseHandler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseHandler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;

    public RefreshTokenReuseHandler(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            MeterRegistry meterRegistry
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReuseDetected(RefreshToken reusedToken) {
        UUID membershipId = reusedToken.getWorkspaceMembership().getId();
        int revokedSessions = refreshTokenRepository.revokeAllByMembershipId(membershipId, Instant.now());
        // Whoever replayed it may hold an access token too, and it is not known which.
        userRepository.revokeAccessTokens(reusedToken.getUser().getId());

        meterRegistry.counter("flowai.authentication.refresh_token_reuse").increment();
        log.warn(
                "event=refresh_token_reuse_detected userId={} membershipId={} revokedSessions={}",
                reusedToken.getUser().getId(),
                membershipId,
                revokedSessions
        );
    }
}
