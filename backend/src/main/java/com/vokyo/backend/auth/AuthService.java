package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.LoginRequest;
import com.vokyo.backend.auth.dto.RefreshTokenRequest;
import com.vokyo.backend.auth.dto.RegisterRequest;
import com.vokyo.backend.auth.dto.RegisterWithInvitationRequest;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceInvitationService;
import com.vokyo.backend.workspace.WorkspaceProvisioningService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static com.vokyo.backend.auth.EmailAddressNormalizer.normalize;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final WorkspaceInvitationService workspaceInvitationService;
    private final AuthSessionService authSessionService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            WorkspaceProvisioningService workspaceProvisioningService,
            WorkspaceInvitationService workspaceInvitationService,
            AuthSessionService authSessionService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.workspaceProvisioningService = workspaceProvisioningService;
        this.workspaceInvitationService = workspaceInvitationService;
        this.authSessionService = authSessionService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(CONFLICT, "Email is already registered");
        }

        User user = userRepository.save(new User(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim()
        ));

        WorkspaceMembership membership = workspaceProvisioningService.createOwnedWorkspace(
                user,
                request.workspaceName()
        );
        return authSessionService.issue(user, membership);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        WorkspaceMembership membership = findDefaultMembership(user);
        return authSessionService.issue(user, membership);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        return authSessionService.refresh(request.refreshToken());
    }

    @Transactional
    public AuthResponse registerWithInvitation(RegisterWithInvitationRequest request) {
        return workspaceInvitationService.registerWithInvitation(request);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private WorkspaceMembership findDefaultMembership(User user) {
        return membershipRepository.findByUser_IdAndStatusOrderByLastAccessedAtDescJoinedAtAsc(
                        user.getId(),
                        MembershipStatus.ACTIVE
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "No active workspace membership"));
    }

}
