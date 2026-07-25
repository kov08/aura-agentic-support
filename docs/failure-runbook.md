# AURA — Failure Runbook

Operational drills for AURA's dependency-failure behaviour. The **automated** rows — happy path,
transient-recovery, budget-exhausted, permanent-no-retry, redis-down, and the hang→fallback conversion
— live as integration tests (`AnthropicTransportIT`, `RedisDegradationIT`) and run on every
`./mvnw verify`. This document covers the rows that are **not** worth automating: a timing-dependent
degradation that would make a flaky assertion, and a values review that only a human should sign off.

> Convention: each row records **how to trigger**, **what you should observe**, and **Last verified**.
> Re-verify the manual rows at each milestone tag — a runbook nobody re-runs is fiction.

---

## Row 1 — Redis slow-but-alive (the timing row that stays manual)

**Why manual.** The Day 9 cache fails *open*: if Redis is unreachable it degrades to a MISS
(`RedisDegradationIT` proves the *dead* case as a discrete assertion). But "slow-but-alive" is
different — Redis is up and the TCP connection succeeds, yet a command takes longer than its budget.
The correct behaviour is bounded by a **duration** (the 250 ms command timeout), and asserting on
elapsed time is exactly the flaky-test trap Day 11 avoids. So this is a drill, not a test.

**How to trigger** (local `docker compose` stack):

```bash
docker compose ps                      # confirm the redis container name (e.g. aura-redis-1)
docker pause aura-redis-1              # freeze Redis: connection alive, commands never answered
# ...issue a resolve request against a cache key that would normally hit...
docker unpause aura-redis-1            # restore
```

**What you should observe.**

- Each cache operation aborts at **~250 ms** with a `RedisCommandTimeoutException` (the
  `spring.data.redis.timeout: 250ms` command bound in [application.yml](../src/main/resources/application.yml)),
  NOT the 60 s Lettuce default. `ResolutionCache` catches it and logs
  `cache read failed — degrading to miss` / `cache write failed — response still served at full price`,
  then serves the answer from the model.
- **Added-latency ceiling while paused ≈ the GET timeout + the PUT timeout** (one read on the way in,
  one write on the way out) ≈ **~500 ms** on top of the normal model call — with `connect-timeout` and
  `timeout` both at 250 ms, a paused Redis cannot add more than that. The customer still gets a correct
  answer; they just pay full model price for it.
- After `docker unpause`, the next request caches normally again — no restart needed.

**Last verified: Day 9 · re-verify each milestone.**

---

## Row 2 — Production timeout VALUES review (mechanism vs numbers)

**Why manual.** The integration tests prove the *mechanism* — that a per-request timeout exists, is
honoured, and converts a hang into an escalation (`AnthropicTransportIT.it5_hangConvertedToFallbackOutcome`,
which runs at the **500 ms test value**). They deliberately say nothing about whether the **production
numbers** are the right numbers. Picking 30 s vs 20 s vs 45 s is a judgement call about real Anthropic
latency and customer patience — a config review item for a human, re-checked against real p99 latency
once Day 24 metrics exist.

**Current production values** (review each at milestone; update this table when they change):

| Setting | Value | Where set |
|---|---|---|
| Anthropic per-request timeout (SDK call timeout) | **30 s** | `aura.anthropic.timeout` — [application.yml](../src/main/resources/application.yml); applied in the `anthropicClient` bean in [AuraApplication.java](../src/main/java/org/aura/aura/AuraApplication.java) |
| SDK internal retries | **0** | `.maxRetries(0)` in the `anthropicClient` bean (ADR-012/016: Resilience4j owns retries; layered retries multiply) |
| Resilience4j retry — max attempts | **3** (1 + 2) | `resilience4j.retry.instances.anthropicApi.max-attempts` — [application.yml](../src/main/resources/application.yml) |
| Resilience4j retry — wait / backoff | **1 s**, ×2 exponential, jittered | same file |
| Circuit breaker — window / min-calls / threshold | **10 / 5 / 50%** | `resilience4j.circuitbreaker.instances.anthropicApi.*` — same file |
| Circuit breaker — open duration | **30 s** | same file |
| Redis connect / command timeout | **250 ms / 250 ms** | `spring.data.redis.*` — same file |
| Response cache TTL | **24 h** | `aura.cache.ttl` — same file |

**Test-profile overrides** (proof-of-mechanism only, never shipped):
`aura.anthropic.timeout: 500ms` and `resilience4j.retry...wait-duration: 50ms` in
[application-test.yml](../src/test/resources/application-test.yml) — SAME keys as prod, only faster, so
the integration tests exercise the exact production code path in milliseconds.

**Review checklist.**

- [ ] Is 30 s still above real Anthropic p99 latency but below customer-abandonment? (needs Day 24 metrics)
- [ ] Does `retry wait × max-attempts` (~3 s worst case) plus the 30 s timeout fit the caller's own budget?
- [ ] Are the breaker's 30 s open-duration and 250 ms Redis bounds still right for current traffic?

**Last verified: Day 11 · re-verify each milestone.**
