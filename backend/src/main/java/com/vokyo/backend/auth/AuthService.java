package com.vokyo.backend.auth;

import com.vokyo.backend.auth.dto.AuthResponse;
import com.vokyo.backend.auth.dto.LoginRequest;
import com.vokyo.backend.auth.dto.RefreshTokenRequest;
import com.vokyo.backend.auth.dto.RegisterRequest;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.auth.dto.WorkspaceResponse;
import com.vokyo.backend.security.JwtService;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import com.vokyo.backend.workspace.MembershipStatus;
import com.vokyo.backend.workspace.Workspace;
import com.vokyo.backend.workspace.WorkspaceMembership;
import com.vokyo.backend.workspace.WorkspaceMembershipRepository;
import com.vokyo.backend.workspace.WorkspaceRepository;
import com.vokyo.backend.workspace.WorkspaceRole;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class AuthService {

    private static final int SLUG_RANDOM_SUFFIX_LENGTH = 8;

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(CONFLICT, "Email is already registered");
        }

        User user = userRepository.save(new User(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim()
        ));

        Workspace workspace = workspaceRepository.save(new Workspace(
                user,
                request.workspaceName().trim(),
                generateUniqueWorkspaceSlug(request.workspaceName())
        ));

        WorkspaceMembership membership = membershipRepository.save(new WorkspaceMembership(
                workspace,
                user,
                WorkspaceRole.OWNER
        ));

        return buildAuthResponse(user, membership);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        WorkspaceMembership membership = findDefaultMembership(user);
        return buildAuthResponse(user, membership);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenService.requireActiveToken(request.refreshToken());
        refreshToken.revoke();

        User user = refreshToken.getUser();
        WorkspaceMembership membership = findDefaultMembership(user);
        String accessToken = jwtService.generateAccessToken(user, membership);
        String newRefreshToken = refreshTokenService.createRefreshToken(user);

        return toAuthResponse(accessToken, newRefreshToken, user, membership);
    }

    private AuthResponse buildAuthResponse(User user, WorkspaceMembership membership) {
        String accessToken = jwtService.generateAccessToken(user, membership);
        String refreshToken = refreshTokenService.createRefreshToken(user);
        return toAuthResponse(accessToken, refreshToken, user, membership);
    }

    private AuthResponse toAuthResponse(
            String accessToken,
            String refreshToken,
            User user,
            WorkspaceMembership membership
    ) {
        Workspace workspace = membership.getWorkspace();
        return new AuthResponse(
                accessToken,
                refreshToken,
                new UserResponse(user.getId(), user.getEmail(), user.getDisplayName()),
                new WorkspaceResponse(
                        workspace.getId(),
                        workspace.getName(),
                        workspace.getSlug(),
                        membership.getRole().name()
                )
        );
    }

    private WorkspaceMembership findDefaultMembership(User user) {
        return membershipRepository.findByUser_IdAndStatus(user.getId(), MembershipStatus.ACTIVE)
                .stream()
                .min(Comparator.comparing(WorkspaceMembership::getJoinedAt))
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "No active workspace membership"));
    }

    private String generateUniqueWorkspaceSlug(String workspaceName) {
        String baseSlug = slugify(workspaceName);
        String slug = baseSlug;

        while (workspaceRepository.existsBySlug(slug)) {
            slug = baseSlug + "-" + UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, SLUG_RANDOM_SUFFIX_LENGTH);
        }

        return slug;
    }

    private String slugify(String value) {
        String slug = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        if (slug.isBlank()) {
            return "workspace";
        }

        return slug;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
