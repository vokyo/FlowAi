package com.vokyo.backend.me;

import com.vokyo.backend.auth.dto.UserResponse;
import com.vokyo.backend.user.User;
import com.vokyo.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class MeService {

    private final UserRepository userRepository;

    public MeService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current user not found"));

        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
