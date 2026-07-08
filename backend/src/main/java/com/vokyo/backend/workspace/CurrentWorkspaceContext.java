package com.vokyo.backend.workspace;

import com.vokyo.backend.user.User;

public record CurrentWorkspaceContext(
        User user,
        Workspace workspace,
        WorkspaceMembership membership
) {
}
