package com.vokyo.backend.agent;

/**
 * Where a run that produced a plan stands. Only a reviewing run can be revised,
 * approved or cancelled; the other two are final.
 */
public enum AgentRunState {
    REVIEWING,
    APPROVED,
    CANCELLED
}
