package com.vokyo.backend.security;

import com.vokyo.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenVersionCacheTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String KEY = "token-version:" + USER_ID;

    private final UserRepository users = mock(UserRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final TokenVersionCache cache = new TokenVersionCache(
            users,
            redis,
            new TokenVersionCacheProperties(Duration.ofMinutes(5), Duration.ofSeconds(30)),
            true
    );

    @BeforeEach
    void connectRedis() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void aHitReturnsTheVersionInRedisWithoutReadingTheDatabase() {
        when(values.get(KEY)).thenReturn("3");

        assertThat(cache.currentVersion(USER_ID)).contains(3);
        verifyNoInteractions(users);
    }

    @Test
    void aMissReadsTheDatabase() {
        when(values.get(KEY)).thenReturn(null);
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(1));

        assertThat(cache.currentVersion(USER_ID)).contains(1);
    }
}
