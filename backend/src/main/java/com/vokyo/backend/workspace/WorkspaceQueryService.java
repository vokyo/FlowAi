package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.workspace.dto.WorkspaceMemberResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceQueryService {

    private final WorkspaceMembershipRepository membershipRepository;

    public WorkspaceQueryService(WorkspaceMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getCurrentWorkspace(Jwt jwt) {
        WorkspaceMembership membership = getCurrentMembership(jwt);
        Workspace workspace = membership.getWorkspace();

        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getSlug(),
                membership.getRole().name()
        );
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getCurrentWorkspaceMembers(Jwt jwt) {
        UUID workspaceId = UUID.fromString(jwt.getClaimAsString("workspaceId"));

        return membershipRepository.findByWorkspace_Id(workspaceId)
                .stream()
                .sorted(Comparator.comparing(WorkspaceMembership::getJoinedAt))
                .map(this::toMemberResponse)
                .toList();
    }

    private WorkspaceMembership getCurrentMembership(Jwt jwt) {
        UUID membershipId = UUID.fromString(jwt.getClaimAsString("membershipId"));
        return membershipRepository.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Current workspace membership not found"));
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
