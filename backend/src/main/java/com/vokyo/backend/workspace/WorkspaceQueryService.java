package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.workspace.dto.WorkspaceMemberResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class WorkspaceQueryService {

    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceAccessService workspaceAccessService;

    public WorkspaceQueryService(
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceAccessService workspaceAccessService
    ) {
        this.membershipRepository = membershipRepository;
        this.workspaceAccessService = workspaceAccessService;
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getCurrentWorkspace(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);

        return new WorkspaceResponse(
                context.workspace().getId(),
                context.workspace().getName(),
                context.workspace().getSlug(),
                context.membership().getRole().name()
        );
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getCurrentWorkspaceMembers(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);

        return membershipRepository.findByWorkspace_Id(context.workspace().getId())
                .stream()
                .sorted(Comparator.comparing(WorkspaceMembership::getJoinedAt))
                .map(this::toMemberResponse)
                .toList();
    }

    private WorkspaceMemberResponse toMemberResponse(WorkspaceMembership membership) {
        return new WorkspaceMemberResponse(
                membership.getId(),
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                membership.getRole().name(),
                membership.getStatus().name(),
                membership.getJoinedAt()
        );
    }
}
