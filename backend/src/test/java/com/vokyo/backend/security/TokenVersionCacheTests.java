package com.vokyo.backend.security;

import com.vokyo.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class TokenVersionCacheTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String KEY = "token-version:" + USER_ID;

    private final UserRepository users = mock(UserRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    // The cache's clock, in nanoseconds, moved by hand so no test waits.
    private final AtomicLong now = new AtomicLong();
    private final TokenVersionCache cache = cacheWith(true);

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
    @SuppressWarnings("unchecked") // any(RedisScript.class) is a raw type
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

    @Test
    void aRedisFailureFallsBackToTheDatabaseAndIsLogged(CapturedOutput output) {
        when(values.get(KEY)).thenThrow(new QueryTimeoutException("Redis did not answer"));
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(2));

        assertThat(cache.currentVersion(USER_ID)).contains(2);
        assertThat(output.getAll()).contains("event=token_version_cache_read_failed");
        // Writing back to a Redis that has just failed would only wait out another timeout.
        verify(redis).opsForValue();
        verifyNoMoreInteractions(redis);
    }

    @Test
    void aValueThatIsNotANumberFallsBackToTheDatabase() {
        when(values.get(KEY)).thenReturn("abc");
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(2));

        assertThat(cache.currentVersion(USER_ID)).contains(2);
    }

    @Test
    void aDatabaseFailureIsNotMistakenForARedisFailure() {
        DataAccessResourceFailureException databaseDown = new DataAccessResourceFailureException("database down");
        when(values.get(KEY)).thenReturn(null);
        when(users.findTokenVersionById(USER_ID)).thenThrow(databaseDown);

        assertThatThrownBy(() -> cache.currentVersion(USER_ID)).isSameAs(databaseDown);
        verify(users, times(1)).findTokenVersionById(USER_ID);
    }

    @Test
    void aRedisFailureLeavesRedisAloneForThirtySeconds() {
        when(values.get(KEY))
                .thenThrow(new QueryTimeoutException("Redis did not answer"))
                .thenReturn("2");
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(2));

        assertThat(cache.currentVersion(USER_ID)).contains(2);
        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(cache.currentVersion(USER_ID)).contains(2);
        verify(values, times(1)).get(KEY);

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(cache.currentVersion(USER_ID)).contains(2);
        verify(values, times(2)).get(KEY);
    }

    @Test
    void aValueThatIsNotANumberDoesNotLeaveRedisAlone() {
        when(values.get(KEY)).thenReturn("abc");
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(2));

        cache.currentVersion(USER_ID);
        cache.currentVersion(USER_ID);

        verify(values, times(2)).get(KEY);
    }

    @Test
    @SuppressWarnings("unchecked") // any(RedisScript.class) is a raw type
    void aFailedWriteBackAlsoLeavesRedisAlone() {
        when(values.get(KEY)).thenReturn(null);
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(1));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new QueryTimeoutException("Redis did not answer"));

        assertThat(cache.currentVersion(USER_ID)).contains(1);
        assertThat(cache.currentVersion(USER_ID)).contains(1);
        verify(values, times(1)).get(KEY);
    }

    @Test
    void redisSwitchedOffIsNeverAsked() {
        when(users.findTokenVersionById(USER_ID)).thenReturn(Optional.of(2));

        assertThat(cacheWith(false).currentVersion(USER_ID)).contains(2);
        verifyNoInteractions(redis);
    }

    private TokenVersionCache cacheWith(boolean redisEnabled) {
        return new TokenVersionCache(
                users,
                redis,
                new TokenVersionCacheProperties(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                redisEnabled,
                now::get
        );
    }
}
