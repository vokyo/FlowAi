package com.vokyo.backend.workspace;

import com.vokyo.backend.auth.AuthSessionService;
import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.RegisterWithInvitationRequest;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.dto.AcceptWorkspaceInvitationRequest;
import com.vokyo.backend.workspace.dto.CreateWorkspaceInvitationRequest;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationCreatedResponse;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationPreviewResponse;
import com.vokyo.backend.workspace.dto.WorkspaceInvitationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vokyo.backend.auth.EmailAddressNormalizer.normalize;

@Service
public class WorkspaceInvitationService {

    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceInvitationTokenService tokenService;
    private final WorkspaceInvitationProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    public WorkspaceInvitationService(
            WorkspaceAccessService workspaceAccessService,
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceInvitationRepository invitationRepository,
            WorkspaceInvitationTokenService tokenService,
            WorkspaceInvitationProperties properties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthSessionService authSessionService
    ) {
        this.workspaceAccessService = workspaceAccessService;
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.tokenService = tokenService;
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
    }

    @Transactional
    public WorkspaceInvitationCreatedResponse createInvitation(
            Jwt jwt,
            CreateWorkspaceInvitationRequest request
    ) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        workspaceAccessService.requireCanManageInvitationRole(context, request.role());
        String email = normalize(request.email());

        userRepository.findByEmail(email)
                .flatMap(user -> membershipRepository.findByWorkspace_IdAndUser_Id(
                        context.workspace().getId(),
                        user.getId()
                ))
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .ifPresent(membership -> {
                    throw conflict("User is already a workspace member");
                });

        invitationRepository.findByWorkspace_IdAndEmailAndStatus(
                        context.workspace().getId(),
                        email,
                        WorkspaceInvitationStatus.PENDING
                )
                .ifPresent(invitation -> {
                    throw conflict("A pending workspace invitation already exists");
                });

        String token = tokenService.generateToken();
        WorkspaceInvitation invitation = invitationRepository.save(new WorkspaceInvitation(
                context.workspace(),
                context.user(),
                email,
                request.role(),
                tokenService.hashToken(token),
                Instant.now().plus(properties.invitationTtl())
        ));

        return toCreatedResponse(invitation, token);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse> listInvitations(Jwt jwt) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        workspaceAccessService.requireCanManageInvitations(context);

        return invitationRepository.findByWorkspace_IdOrderByCreatedAtDesc(context.workspace().getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public WorkspaceInvitationCreatedResponse reissueInvitation(Jwt jwt, UUID invitationId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        workspaceAccessService.requireCanManageInvitations(context);
        WorkspaceInvitation invitation = requireManagedInvitation(context, invitationId);
        workspaceAccessService.requireCanManageInvitationRole(context, invitation.getRole());

        if (invitation.getStatus() != WorkspaceInvitationStatus.PENDING) {
            throw conflict("Only pending workspace invitations can be reissued");
        }

        String token = tokenService.generateToken();
        invitation.reissue(
                tokenService.hashToken(token),
                Instant.now().plus(properties.invitationTtl())
        );
        return toCreatedResponse(invitation, token);
    }

    @Transactional
    public void revokeInvitation(Jwt jwt, UUID invitationId) {
        CurrentWorkspaceContext context = workspaceAccessService.requireCurrentContext(jwt);
        workspaceAccessService.requireCanManageInvitations(context);
        WorkspaceInvitation invitation = requireManagedInvitation(context, invitationId);
        workspaceAccessService.requireCanManageInvitationRole(context, invitation.getRole());

        if (invitation.getStatus() == WorkspaceInvitationStatus.ACCEPTED) {
            throw conflict("Accepted workspace invitations cannot be revoked");
        }

        invitation.revoke(Instant.now());
    }

    @Transactional(readOnly = true)
    public WorkspaceInvitationPreviewResponse previewInvitation(String token) {
        WorkspaceInvitation invitation = invitationRepository.findByTokenHash(tokenService.hashToken(token))
                .orElseThrow(() -> notFound("Workspace invitation not found"));
        return toPreviewResponse(invitation);
    }

    @Transactional
    public AuthResponse acceptInvitation(
            Jwt jwt,
            String token,
            AcceptWorkspaceInvitationRequest request
    ) {
        User user = requireUser(jwt);
        WorkspaceInvitation invitation = requirePendingInvitationForUpdate(token);

        if (!invitation.getEmail().equals(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace invitation belongs to another email");
        }

        WorkspaceMembership membership = activateMembership(invitation, user);
        invitation.accept(user, Instant.now());
        return authSessionService.rotateTo(request.refreshToken(), user, membership);
    }

    @Transactional
    public AuthResponse registerWithInvitation(RegisterWithInvitationRequest request) {
        WorkspaceInvitation invitation = requirePendingInvitationForUpdate(request.token());
        String email = normalize(request.email());
        if (!invitation.getEmail().equals(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must match the workspace invitation");
        }
        if (userRepository.existsByEmail(email)) {
            throw conflict("Email is already registered");
        }

        User user = userRepository.save(new User(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim()
        ));
        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                invitation.getWorkspace(),
                user,
                invitation.getRole()
        ));
        invitation.accept(user, Instant.now());
        return authSessionService.issue(user, membership);
    }

    private WorkspaceMembership activateMembership(WorkspaceInvitation invitation, User user) {
        var existingMembership = membershipRepository.findByWorkspace_IdAndUser_Id(
                invitation.getWorkspace().getId(),
                user.getId()
        );
        if (existingMembership.isEmpty()) {
            return membershipRepository.save(new WorkspaceMembership(
                    invitation.getWorkspace(),
                    user,
                    invitation.getRole()
            ));
        }

        WorkspaceMembership membership = existingMembership.get();
        if (membership.getStatus() == MembershipStatus.ACTIVE) {
            throw conflict("User is already a workspace member");
        }

        membership.activate(invitation.getRole());
        return membership;
    }

    private WorkspaceInvitation requirePendingInvitationForUpdate(String token) {
        WorkspaceInvitation invitation = invitationRepository.findByTokenHashForUpdate(tokenService.hashToken(token))
                .orElseThrow(() -> notFound("Workspace invitation not found"));

        if (invitation.getStatus() != WorkspaceInvitationStatus.PENDING) {
            throw conflict("Workspace invitation is no longer pending");
        }
        if (invitation.isExpired(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Workspace invitation has expired");
        }

        return invitation;
    }

    private WorkspaceInvitation requireManagedInvitation(
            CurrentWorkspaceContext context,
            UUID invitationId
    ) {
        return invitationRepository.findByWorkspaceIdAndIdForUpdate(
                        context.workspace().getId(),
                        invitationId
                )
                .orElseThrow(() -> notFound("Workspace invitation not found"));
    }

    private User requireUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));
    }

    private WorkspaceInvitationResponse toResponse(WorkspaceInvitation invitation) {
        return new WorkspaceInvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole().name(),
                responseStatus(invitation),
                invitation.getExpiresAt(),
                invitation.getAcceptedAt(),
                invitation.getCreatedAt()
        );
    }

    private WorkspaceInvitationCreatedResponse toCreatedResponse(
            WorkspaceInvitation invitation,
            String token
    ) {
        return new WorkspaceInvitationCreatedResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole().name(),
                responseStatus(invitation),
                invitation.getExpiresAt(),
                invitation.getAcceptedAt(),
                invitation.getCreatedAt(),
                token
        );
    }

    private WorkspaceInvitationPreviewResponse toPreviewResponse(WorkspaceInvitation invitation) {
        return new WorkspaceInvitationPreviewResponse(
                invitation.getWorkspace().getName(),
                invitation.getEmail(),
                invitation.getRole().name(),
                responseStatus(invitation),
                invitation.getExpiresAt()
        );
    }

    private String responseStatus(WorkspaceInvitation invitation) {
        if (invitation.isExpired(Instant.now())) {
            return "EXPIRED";
        }
        return invitation.getStatus().name();
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
