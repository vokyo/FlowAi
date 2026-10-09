package com.vokyo.backend.me;

import com.vokyo.backend.accesstoken.PersonalAccessTokenService;
import com.vokyo.backend.auth.RefreshTokenService;
import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.me.dto.ChangePasswordRequest;
import com.vokyo.backend.me.dto.UpdateProfileRequest;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class MeAccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PersonalAccessTokenService accessTokenService;

    public MeAccountService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            PersonalAccessTokenService accessTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenService = accessTokenService;
    }

    @Transactional
    public UserResponse updateProfile(Jwt jwt, UpdateProfileRequest request) {
        User user = requireUser(jwt);
        user.changeDisplayName(request.displayName().trim());
        return toResponse(user);
    }

    @Transactional
    public void changePassword(Jwt jwt, ChangePasswordRequest request) {
        User user = requireUser(jwt);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different");
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenService.revokeUserSessions(user.getId());
        // A password change is how a user takes an account back, so tokens made for
        // AI apps go too, in case whoever had the account made one.
        accessTokenService.revokeAllForUser(user.getId());
    }

    @Transactional
    public void revokeAllSessions(Jwt jwt) {
        User user = requireUser(jwt);
        refreshTokenService.revokeUserSessions(user.getId());
        accessTokenService.revokeAllForUser(user.getId());
    }

    private User requireUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
