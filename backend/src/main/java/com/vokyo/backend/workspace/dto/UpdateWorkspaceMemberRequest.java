package com.vokyo.backend.workspace.dto;

import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceRole;

public record UpdateWorkspaceMemberRequest(
        WorkspaceRole role,
        MembershipStatus status
) {
}
