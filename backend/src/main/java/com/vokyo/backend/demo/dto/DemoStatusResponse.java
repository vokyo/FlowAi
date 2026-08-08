package com.vokyo.backend.demo.dto;

/**
 * Tells an unauthenticated visitor whether this deployment carries the demo
 * workspace, and how to sign into it.
 *
 * <p>The credentials are returned in the clear on purpose. This is one shared
 * account on a throwaway dataset whose password is published in the README; the
 * whole point of it is that anyone can use it. Nothing here is a secret, and
 * nothing else in the system trusts this account.
 *
 * <p>Both fields are null when the demo is off, so a deployment that never
 * enabled seeding hands out nothing at all.
 */
public record DemoStatusResponse(
        boolean enabled,
        String email,
        String password
) {

    public static DemoStatusResponse disabled() {
        return new DemoStatusResponse(false, null, null);
    }
}
