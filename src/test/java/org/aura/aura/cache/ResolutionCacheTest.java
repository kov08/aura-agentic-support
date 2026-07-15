package org.aura.aura.cache;

import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Fail-open contract for {@link ResolutionCache} (ADR-018): every Redis failure mode — a dead
 * connection, a corrupted entry, a failed write — collapses to ONE business behaviour, "as if the
 * cache weren't there", and nothing propagates. Redis is a real ObjectMapper; only the template is mocked.
 */
@ExtendWith(MockitoExtension.class)
class ResolutionCacheTest {

    private static final String KEY = "aura:resolution:v1:deadbeef";

    // Boot injects its configured (Jackson 3) mapper in production; a plain one round-trips the simple
    // Resolution record fine here — no time/PII types involved.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private ResolutionCache cache() {
        return new ResolutionCache(redis, objectMapper, Duration.ofHours(24));
    }

    // A dead Redis connection on read must degrade to a MISS, not surface a 5xx to the caller.
    @Test
    void redisReadFailureDegradesToMiss() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(cache().get(KEY)).isEmpty();
    }

    // A corrupted (non-JSON) entry must also degrade to a MISS rather than throw a parse exception.
    @Test
    void corruptedEntryDegradesToMiss() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("}{ not valid json at all");

        assertThat(cache().get(KEY)).isEmpty();
    }

    // A failed write is swallowed: the caller already has a full-price answer to return.
    @Test
    void redisWriteFailureDoesNotPropagate() {
        Resolution value = new Resolution("answer", List.of("kb-returns"), ResolutionStatus.RESOLVED);
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache().put(KEY, value)).doesNotThrowAnyException();
    }
}
