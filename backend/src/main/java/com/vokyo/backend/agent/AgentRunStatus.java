package com.vokyo.backend.agent;

/**
 * How a planning run ended. The agent answers every finished run with 200 and one
 * of these; only PLANNED carries a plan.
 */
public enum AgentRunStatus {
    PLANNED,
    INSUFFICIENT_INFO,
    FAILED
}
