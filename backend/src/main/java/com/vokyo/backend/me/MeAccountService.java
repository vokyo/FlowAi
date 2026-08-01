package com.vokyo.backend.me;

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
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public AccountService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
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
    }

    @Transactional
    public void revokeAllSessions(Jwt jwt) {
        refreshTokenService.revokeUserSessions(requireUser(jwt).getId());
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
