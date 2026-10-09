package com.vokyo.backend.accesstoken;

import java.util.UUID;

/** Who a valid personal access token speaks for: one user, in one workspace. */
public record AccessTokenPrincipal(UUID tokenId, UUID userId, UUID workspaceId, UUID membershipId) {
}
