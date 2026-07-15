package org.aura.aura.cache;

import org.aura.aura.resolver.Resolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

// ADR-018: the fail-open Redis wrapper around a resolution. The cached unit is the DOMAIN type
// Resolution (the prompt calls it "ResolutionResponse"; the wire DTO of that name is a narrower
// projection that drops sourcesUsed/status, so the domain type is what must round-trip). Every
// method here treats Redis as OPTIONAL: it is a cost optimisation, and a cost optimisation must
// never become an availability dependency.
@Component
public class ResolutionCache {
    private static final Logger log = LoggerFactory.getLogger(ResolutionCache.class);

    private final StringRedisTemplate redis;   // auto-configured by the starter (spring.data.redis.*)
    private final ObjectMapper objectMapper;   // INJECT Boot's mapper (modules/config already applied) — never `new ObjectMapper()`
    private final Duration ttl;

    public ResolutionCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${aura.cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<Resolution> get(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) { log.info("cache MISS key={}", key); return Optional.empty(); }
            var cached = objectMapper.readValue(json, Resolution.class);
            log.info("cache HIT key={}", key);   // log the HASH, never the ticket text — PII discipline again
            return Optional.of(cached);
        } catch (Exception e) {
            // FAIL-OPEN. Day 8 taught PRECISE exception classification because different
            // failures need different retry behavior. Here every failure mode — connection
            // refused, timeout, corrupted JSON — maps to ONE business behavior: "miss".
            // A broad catch is therefore the honest design, not laziness. A cost
            // optimization must never become an availability dependency.
            log.warn("cache read failed — degrading to miss. key={}", key, e);
            return Optional.empty();
        }
    }

    public void put(String key, Resolution value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("cache write failed — response still served at full price. key={}", key, e);
        }
    }
}
