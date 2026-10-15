package com.vokyo.backend.security;

import com.vokyo.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    void aMissReadsTheDatabaseAndStoresTheVersionForFiveMinutes() {
        when(values.get(KEY)).thenReturn(null);
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(1));

        assertThat(cache.currentVersion(USER_ID)).contains(1);
        verify(redis).execute(any(RedisScript.class), eq(List.of(KEY)), eq("1"), eq("300000"));
    }

    @Test
    void aMissingUserIsNotStored() {
        when(values.get(KEY)).thenReturn(null);
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.empty());

        assertThat(cache.currentVersion(USER_ID)).isEmpty();
        verify(redis).opsForValue();
        verifyNoMoreInteractions(redis);
    }
}
