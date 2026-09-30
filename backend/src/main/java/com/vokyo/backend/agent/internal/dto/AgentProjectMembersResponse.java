package com.vokyo.backend.agent.internal.dto;

import com.vokyo.backend.project.ProjectRole;

import java.util.List;
import java.util.UUID;

public record AgentProjectMembersResponse(
        List<Item> items,
        boolean truncated
) {

    public record Item(
            UUID userId,
            String displayName,
            ProjectRole role
    ) {
    }
}
