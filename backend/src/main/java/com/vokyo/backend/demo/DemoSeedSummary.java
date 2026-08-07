package com.vokyo.backend.demo;

/**
 * What a seeding run wrote. Reported in the startup log and asserted by the
 * fresh-database check in CI.
 */
public record DemoSeedSummary(
        int members,
        int projects,
        int issues,
        int comments
) {
}
