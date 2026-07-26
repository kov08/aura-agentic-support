# AURA — Agentic Support

AURA is a customer-support agent for ShopFast (a sample e-commerce platform), built incrementally
on Spring Boot and the Claude Messages API. Each "Day" adds one capability on top of the last.

## Prerequisites

- JDK 25 (the toolchain the build targets; see `<java.version>` in [pom.xml](pom.xml))
- `ANTHROPIC_API_KEY` exported in your environment (the client reads it via `fromEnv()`)
- Docker running — the Redis cache (Day 9) and the integration tests (Day 11) use it

## Reproducibility (fresh clone → running)

The contract: a clean checkout reaches a verified, running agent in these exact steps. `verify` is the
gate — it runs the fast offline unit/integrity/scorer tests (Surefire) **and** the full-context
integration tests against a real Redis container and a local MockWebServer (Failsafe, needs Docker).

```bash
git clone <repo-url> && cd aura-agentic-support/aura
```

```bash
docker compose up -d
```

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

```bash
.\mvnw.cmd verify
```

```bash
.\mvnw.cmd spring-boot:run
```

Then one request (PowerShell shown; the app listens on `:8080`):

```bash
curl -s -X POST http://localhost:8080/api/v1/tickets/T-1001/resolve -H "Content-Type: application/json" -d "{\"message\":\"Where is my order #88231? It was due Tuesday.\"}"
```

Expected response shape (values vary; `outcome` is `RESOLVED`, or `ESCALATED_TO_HUMAN` when Claude is
degraded and the resolver fell back to a human):

```json
{
  "ticketId": "T-1001",
  "resolutionText": "…the customer-facing reply…",
  "outcome": "RESOLVED",
  "sourcesUsed": ["kb-…"],
  "classification": {
    "category": "ORDER_STATUS",
    "urgency": "HIGH",
    "intent": "GET_INFORMATION",
    "confidence": 0.93,
    "needsHumanReview": false
  }
}
```

### Request path (`POST /resolve`) — one layer per line, with the day it was built

```
POST /api/v1/tickets/{id}/resolve
  │
  ▼  TicketController ................ Day 5  · HTTP seam: @Valid, maps HTTP ⇄ domain, no business logic
  │
  ├─▶ TicketClassificationService ... Day 6  · native structured outputs (Haiku), runs FIRST as a cheap gate
  │      └ @CircuitBreaker ........... Day 8  · shared "anthropicApi" breaker (no retry — fallback is cheap)
  │
  ▼  CachedResolutionService ........ Day 9  · Redis cache-aside — a HIT returns here and skips everything below
  │      (MISS ↓)
  ▼  ResolverService.resolve ........ Day 4  · KB retrieve → augment → resolve (Sonnet)
  │      ├ @Retry + @CircuitBreaker .. Day 8  · transient allowlist retry, breaker, escalate-to-human fallback
  │      └ structured ResolverOutput . Day 10 · the escalate verdict is DATA, not prose
  │
  ▼  AnthropicClient (SDK) .......... Day 1  · shared OkHttp client bean, SDK retries disabled (maxRetries=0)
  │      └ base-url + timeout ........ Day 11 · configurable transport seam (prod endpoint, or MockWebServer in ITs)
  ▼
  Anthropic Messages API
```

Both the classifier and resolver calls go through the same SDK client, so the Day 11 base-url/timeout
seam redirects and time-bounds *every* upstream call from one place.

## Day 1 — Skeleton & First Call

A bootable Spring Boot skeleton plus a one-off `CommandLineRunner` smoke test that fires a single
synchronous request at the Claude API and prints the reply and token usage — just enough to prove
the wiring is alive before any service layer exists.

Run:

```bash
./mvnw spring-boot:run
```

## Day 2 — Multi-Turn Conversation

**What was added:** `ConversationService`, which manages per-session conversation history in a
`ConcurrentHashMap<String, List<MessageParam>>` (keyed by session id, with per-session locking so
unrelated sessions stay parallel) and tracks cumulative input/output token usage on every turn.
A scripted `ConversationRunner` drives a three-turn customer conversation to demonstrate that the
agent remembers earlier turns; the Day 1 smoke test is retained as a comment for progression.

The Messages API is stateless — there is no server-side session — so `ConversationService` holds the
only copy of the conversation and resends the entire history on every call.

Token accumulation is read straight off each response's `usage` block and added into two
`AtomicLong` counters (`cumulativeInputTokens` / `cumulativeOutputTokens`), so the running totals
stay correct even when turns arrive from concurrent request threads.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 3 — Externalized System Prompt & Guardrails

**What was added:** the system prompt is no longer a hardcoded string inside `ConversationService`.
It now lives at `src/main/resources/prompts/resolver_system_prompt.md` and is loaded by
`ResolverPromptProvider`, which reads the classpath resource **once at startup** and fails fast if it
is missing or unreadable (the Spring context refuses to boot rather than running with an empty
prompt). `ConversationService` injects the provider and calls `provider.systemPrompt()` when building
each request.

The prompt defines AURA's role, tone, and rules in tagged sections, plus three few-shot examples, and
seeds soft guardrails: never invent order/policy data, never claim actions it can't take, and escalate
to a human when uncertain rather than guessing.

Keeping the prompt in a resource file (rather than a Java literal) means it can be edited and reviewed
without recompiling, and it stays byte-stable across calls — the precondition for prompt-cache hits on
the shared prefix.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 4 — Knowledge Base & Grounded Resolver

**What was added:** a retrieval seam and a `ResolverService` that grounds answers in it, turning AURA
from "answer from the prompt" into a small retrieve-augment-generate loop.

The seam is the `KnowledgeBase` interface — `List<KbEntry> retrieve(String query)` — and the resolver
depends only on it, never on a concrete store. Today's implementation, `HardcodedKnowledgeBase`, holds a
handful of ShopFast facts in memory and retrieves them with a deliberately naive keyword filter (a
ticket matches an entry only when it contains a word from that entry's title). This is intentional: it
whiffs on paraphrases — "Can I send my purchase back for my money?" retrieves nothing — which is the
concrete argument for swapping in a semantic / embedding-backed `KnowledgeBase` later. The resolver
won't change when we do, because it only knows the interface.

`ResolverService.resolve(String ticket)` runs the loop: retrieve matching entries, inject them into the
user turn as `<knowledge_base>` context, call Claude, and return a `Resolution(answer, sourcesUsed)`.
`sourcesUsed` is the grounding receipt — the KB ids that backed the answer (e.g. `[kb-returns]`), or
empty when retrieval found nothing. Returning a record rather than a bare `String` means later days can
extend the result — category, urgency, token cost — without refactoring callers.

The few-shot example in the system prompt no longer embeds a specific return window; the figure was
replaced with a placeholder so the example teaches tone and shape while the actual fact comes from the
retrieved knowledge base instead of competing with it. `ConversationRunner` now drives the resolver.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 5 — REST API & Milestone 1

**What was added:** the resolver is now reachable over HTTP. `TicketController`
(`org.aura.aura.web`) exposes `ResolverService.resolve(...)` behind a validated, versioned endpoint;
DTOs (`org.aura.aura.web.dto`) define the wire contract and deliberately omit internal telemetry
(token counts, model); and a `@RestControllerAdvice` (`GlobalExceptionHandler`) maps failures to
RFC 9457 `ProblemDetail` bodies. Input is validated at the boundary, before any paid model call.

### Resolve a ticket
`POST /api/v1/tickets/{ticketId}/resolve`

Request:
```json
{ "message": "How long do I have to return an item?" }
```
Response `200`:
```json
{ "ticketId": "T-1001", "resolutionText": "...", "outcome": "RESOLVED", "sourcesUsed": ["kb-returns"] }
```
- Input is validated at the boundary (blank/oversized → `400` `application/problem+json`).
- Errors follow RFC 9457 `ProblemDetail`. `4xx` = client error (don't retry); `5xx` = server/upstream (retry).

**Milestone 1 (v0.1.0):** a callable, validated, grounded ticket-resolution endpoint.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 6 — Ticket Classification (Native Structured Outputs)

**What was added:** a classification layer that labels a ticket on three independent axes before
resolving it. The domain types live in `org.aura.aura.classification`: a `TicketClassification`
record (`category`, `urgency`, `intent`, plus a `double confidence`) backed by three closed enums
(`TicketCategory`, `TicketUrgency`, `TicketIntent`). Each record field carries a
`@JsonPropertyDescription` — those texts are not documentation, they travel *into* the JSON schema
the model sees and steer what it puts in each field.

`TicketClassificationService` calls `claude-haiku-4-5` (a cheap gate, not the resolver's larger
model) through the SDK's **native structured outputs** — `MessageCreateParams.builder()...
outputConfig(TicketClassification.class)`, the GA `output_config.format` surface. This is not tool
use and not prompt-begged JSON: the API derives a JSON schema from the record and enforces it
server-side, so the response can never arrive with a misspelled category or a missing field. The
classifier system prompt is externalized to `classifier_system_prompt.md` (same pattern as the
resolver prompt) and deliberately carries *semantics only* — the output shape is owned by the schema,
so restating it in the prompt would just create a second source of truth that could drift.

### Classify a ticket
`POST /api/v1/tickets/classify`

Request:
```json
{ "message": "My jacket arrived ripped. I want my $89 refunded right now." }
```
Response `200`:
```json
{ "category": "RETURNS_AND_REFUNDS", "urgency": "HIGH", "intent": "REQUEST_ACTION",
  "confidence": 0.95, "needsHumanReview": false }
```

The resolve flow now **classifies first, then resolves** — the cheap Haiku call runs ahead of the
expensive resolution (the routing/prioritization hook for later days), and the classification rides
along in the resolve response under a `classification` field.

### The reliability ladder

The point of the layer isn't the labels — it's that a low-confidence or failed classification is a
*safe* outcome, never a crash. `classify()` walks a fixed ladder, and every rung that can't produce a
trustworthy answer lands on the **same fallback** — `(OTHER, MEDIUM, GET_INFORMATION, 0.0,
needsHumanReview=true)`, logged at `WARN`, HTTP `200`:

1. **`stop_reason` before parsing.** On `refusal` the content is empty; on `max_tokens` it is
   truncated. The guard checks `stop_reason` *before* touching `.text()`, so a known API condition
   becomes a clean fallback instead of a raw parse exception surfacing as a `500`.
2. **Deserialize.** A clean `end_turn` is guaranteed by the schema to parse into the record.
3. **Semantic validation.** The schema guarantees *shape* (a number), not *meaning* (a probability):
   confidence is clamped to `[0, 1]`, and anything below the `0.6` floor falls back to a human.

No retries — this sits in front of a user-facing request, and its failure already has a safe answer,
so a second call would only double latency (transport-level resilience arrives Day 8). Removing the
`stop_reason` guard and starving `max_tokens` turns every truncated response into a `500`; with the
guard, the same requests return `200` with the fallback body — the ladder is exactly what converts an
upstream surprise into a controlled, human-routed outcome.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 7 — Streaming Resolution (Server-Sent Events)

**What was added:** a streaming twin of the resolve endpoint that pushes the answer to the client
token-by-token over Server-Sent Events, so a user sees words appear instead of waiting for the whole
Sonnet generation. The perceived-latency win is the entire point — the grounding and prompt are
identical to the blocking path; only the transport differs.

`TicketStreamingService` (`org.aura.aura.streaming`) returns an `SseEmitter` immediately and does the
real work on a dedicated executor (`StreamingAsyncConfig`), so the servlet thread is never blocked.
The pump reuses the exact retrieve-augment step as `ResolverService` (via `buildStreamingParams`) and
opens `client.messages().createStreaming(...)` inside a try-with-resources — `close()` cancels the
upstream generation on **any** exit, which is what stops paying for tokens the client will never read.

Every frame is a **named JSON event** so a client dispatches on the event name and parses one body
shape throughout (`org.aura.aura.streaming` DTOs):

- `classification` — emitted first, so a client can route/label the ticket before the answer begins.
- `delta` — one text chunk per `content_block_delta`; the same text is accumulated server-side.
- `done` — the terminal success frame: `stop_reason` (with `max_tokens` surfaced as *truncation data*,
  not an error), input/output token usage, and server-side elapsed ms.
- `error` — a single RFC 9457-shaped frame on an upstream failure.

The pump has **three exit paths**, each of which must complete the emitter or the connection hangs
until timeout: a clean end (`done`), a client disconnect (`send()` throws `IOException` → stop pumping,
let try-with-resources cancel upstream, complete quietly — no one is left to read an error), and an
upstream/API failure (deliver one `error` frame, then complete; deliberately **no retry**, since part
of the answer may already be on the wire and replaying would duplicate text). `@Valid` still runs on
the servlet thread *before* the emitter exists, so a bad request is a normal `400`
`application/problem+json`, never a half-opened stream.

### Stream a resolution
`POST /api/v1/tickets/{ticketId}/resolve/stream` → `text/event-stream`

```
event: classification
data: { "category": "RETURNS_AND_REFUNDS", "urgency": "LOW", ... }

event: delta
data: { "text": "ShopFast accepts returns " }

event: done
data: { "stopReason": "end_turn", "inputTokens": 62, "outputTokens": 141, "elapsedMs": 1840 }
```

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```

## Day 8 — Transport Resilience (Retry + Circuit Breaker)

**What was added:** Resilience4j retry and a circuit breaker on the Anthropic dependency, so a
transient blip is retried, a sustained outage fails fast, and either way the customer gets a
business-valid answer instead of a `5xx`. The annotations `@Retry` / `@CircuitBreaker` sit on
`ResolverService.resolve(...)` — a public method reached across a bean boundary, which is mandatory:
Spring implements them with an AOP proxy, so a self-invocation would silently disable both policies.
Both bind to one instance name, `anthropicApi` — one dependency, two policies — and the config in
`application.yml` reads as two views of the same thing.

- **Retry** uses an **allowlist** of transient SDK exceptions (`RateLimitException` 429,
  `InternalServerException` 5xx, `AnthropicIoException` connection/timeout). Anything not listed — a
  permanent `400/401/403/404`, or any unknown/future type — is **not** retried. That "unknown ⇒ don't
  retry" default *fails closed*: a denylist would fail open and eventually double-fire a future
  non-idempotent operation (the refund tool). Three total attempts, exponential backoff + jitter.
- **The SDK's own retries are disabled** (`maxRetries(0)`, ADR-012) so the app owns the single retry
  policy — otherwise SDK×app retries would multiply (3×3 = 9 calls per request, ADR-013).
- **Circuit breaker** records the *same* transient taxonomy (a permanent `400` is our bug, not Claude
  being down, so it must not trip the breaker). Count-based window of 10, opens at a 50% failure rate
  once it has seen ≥5 calls, stays open 30s.
- **Fallback = graceful degradation, not masking.** `escalateToHuman` fires on exactly two "Claude is
  unhealthy" paths — breaker `OPEN` (`CallNotPermittedException`) or a transient failure whose retries
  were exhausted — and returns a real `Resolution` with status `ESCALATED_TO_HUMAN` (HTTP `200`, a
  human is a better outcome than an error page). Everything else is **re-propagated**. The
  `fallbackMethod` sits on the outer `@Retry`, not the inner `@CircuitBreaker`, so it can't short-circuit
  retries before they run.

`Resolution` gained a `status` field (`RESOLVED` vs `ESCALATED_TO_HUMAN`) so a caller can tell a
degraded answer from a normal one. The classifier shares the same breaker (fast-fail during an outage)
but takes **no retry** — it is a cheap pre-gate whose failure already has a safe fallback, so a second
call would only add latency. `ResolverResilienceTest` proves the policy against a **real AOP-proxied
bean** (retry-then-succeed, no-retry-on-`400`, escalate-on-exhausted, escalate-on-breaker-open); a
plain `new ResolverService(...)` would exercise no resilience at all.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```
## Day 9 — Two-Layer Caching (Redis response cache + Anthropic prompt-prefix cache)

**What was added:** two independent caches that attack cost from opposite ends, plus the timeout
tuning that keeps the first one from becoming an availability dependency. The key insight is that the
two layers operate on **different denominators** and can never both fire on the same request.

### Layer 1 — Redis response cache (explicit cache-aside, ADR-018)

`CachedResolutionService` wraps the resolver: it builds a key, checks Redis, and only on a miss calls
`ResolverService.resolve(...)`. A hit skips the paid Sonnet call entirely. Deliberately hand-rolled
cache-aside — no `@Cacheable`/`spring-cache` — so the ordering and fail behaviour are explicit.

- **The cache is checked *before* the resilience stack, and that ordering is the design.** A hit is
  not a call to Anthropic, so it must not occupy a slot in the breaker's sliding window and must not be
  blocked when the breaker is `OPEN`. Outage behaviour: `OPEN` + hit => a real answer; `OPEN` + miss =>
  the Day 8 escalation. Because `CachedResolutionService` is a separate injected bean, the call to the
  resolver still crosses the AOP proxy, so Day 8's retry/breaker stay live on a miss.
- **The key is a full-request identity hash** (`CacheKeyFactory`, ADR-019): a SHA-256 over every
  answer-affecting input — resolver model id, the static system prompt, the trimmed ticket text,
  temperature, and `maxTokens` — joined in one canonical serialization. Change any of them and the key
  changes automatically (invalidation by construction); a readable `v1` version prefix is the manual
  bulk-invalidation lever. Hashing (vs. raw text) bounds key size **and** keeps customer PII out of the
  Redis keyspace, since keys leak into logs and `SCAN` output.
- **Fail-open (ADR-018).** `ResolutionCache` wraps every Redis op in a broad `try/catch`: a read
  failure — connection refused, timeout, corrupt JSON — degrades to a **miss**, and a write failure is
  a no-op. A cost optimisation must never become an availability dependency, so one broad catch mapping
  every failure to "behave as if uncached" is the honest design, not laziness.
- **Escalation fallbacks are never cached.** An ADR-014 `ESCALATED_TO_HUMAN` result is an *availability*
  answer, not a *knowledge* answer; caching it would keep escalating tickets for the full TTL after
  Anthropic recovers. Entries carry a 24h TTL (`aura.cache.ttl`, bound to a `Duration`).

### Layer 2 — Anthropic prompt-prefix cache (ADR-020)

The resolver request marks the static system prompt (rules + few-shot — the stable prefix) with an
ephemeral `cache_control` breakpoint, and the volatile ticket goes *after* it in `messages`. On a
hit, Anthropic serves that prefix ~90% cheaper; the breakpoint sits on the **last byte-identical
block**, because anything volatile at or before it would pay a cache write every call and never read.
The static prompt was expanded so the prefix clears Sonnet's ~1,024-token minimum (below it, caching
is a silent no-op). Each resolution logs `cacheCreationInputTokens` / `cacheReadInputTokens` for
observability. The classifier deliberately carries **no** breakpoint — Haiku's minimum cacheable
prefix is 4,096 tokens and this prompt is far below it, so a marker would imply a saving that
doesn't exist.

### Why the two layers never overlap

An identical ticket short-circuits at Redis and never reaches Anthropic, so a **prefix-cache read is
only observable on a *different* ticket** (Redis miss, warm Anthropic prefix). First call to a new
ticket: Redis miss + prefix write. Repeat: Redis hit, no model call at all. Different ticket: Redis
miss, but the prefix is read cheaply. The blocking `/resolve` path runs through Layer 1; the SSE
streaming path benefits from Layer 2 only (response caching for streams is a parking-lot item).

### Timeout tuning — fail-open without a time bound is fail-slow

Fail-open protects *correctness*, but Lettuce's default command timeout is **60 seconds**, so a
Redis that dies while the app holds a pooled connection makes each cache op (get + put) block for a
minute — turning a down cache into ~120s of added latency per request. Bounding both timeouts to
`250ms` collapses that to sub-second degradation:

```yaml
spring:
  data:
    redis:
      timeout: 250ms          # command reply wait — was the 60s default that caused the hang
      connect-timeout: 250ms  # TCP establishment — covers silent SYN drops
```

(250ms suits a localhost Redis; loosen it for networked Redis so a normal blip doesn't cause spurious
misses.)

### Run

Start Redis, then the app (the cache is fail-open, so the app also runs without Redis — just uncached):

```bash
docker compose up -d redis
./mvnw spring-boot:run
```

## Day 10 — Prompt Engineering II–III: Structured Escalation & a Golden-Set Eval Harness

**What was added:** a measurement rig for AURA's judgment — a hand-labelled golden set, a pure scorer,
and a runner that drives the real pipeline — plus the one production change that makes the agent's
escalation decision *measurable*, and a first refinement experiment run against the harness.

### The output change that made escalation gradeable

Until now the resolver returned prose, and "should a human take this?" existed only as words inside the
reply — impossible to assert on. `ResolverService` now uses the **same native structured outputs** as
the Day 6 classifier: a two-field `ResolverOutput(reply, escalate)` schema, enforced server-side. The
escalation verdict is data, not a phrase to grep for. Two escalation channels are kept deliberately
distinct: `Resolution.status == ESCALATED_TO_HUMAN` is a *dependency-health* signal (the Day 8
Resilience4j fallback, single writer), while `Resolution.escalate` is the *model's business judgment* —
graded by the eval, cached like any knowledge answer. Because the schema is enforced, the SSE path now
receives JSON on the wire; `StreamingReplyExtractor` (a pure chunk-by-chunk state machine) unwraps just
the `reply` string so the customer still sees words stream in, tolerant of any network chunk split.

### The harness

- **`golden-set-v1.json`** — 24 tickets, hand-reviewed, across seven slices (`clean`, `ambiguous`,
  `out_of_scope`, `injection`, `garbage`, `noisy`, `whiff`). Each carries strict structured labels
  (category / urgency / intent / escalate) and, on the high-stakes third, sparse `mustContain` /
  `mustNotContain` reply rules plus an `expectedSources` retrieval label. A canary token in the resolver
  prompt lets the injection slice detect a system-prompt leak as a mechanical string check.
- **`EvalScorer`** — a pure function (no Spring, no I/O), exhaustively unit-tested. Structured fields are
  graded strictly (exact enum, boolean escalate); reply prose is graded *only* by the substring rules.
  `expectedSources` is three-valued: `null` = ungraded, `[]` = strict "cite nothing", non-empty =
  `expected ⊆ actual` with extra citations as warnings, not failures.
- **`EvalRunner`** (`@Tag("eval")`) — drives the **inner** `ResolverService` and the classifier (never
  the Redis cache wrapper) over all 24 tickets sequentially, in production order, and writes a
  timestamped JSON + text report to `docs/evals/` stamped with the prompt-version triple. Outcomes are
  bucketed distinctly: **DEGRADED** (a dependency-down Resilience4j fallback, excluded from scores),
  **REJECTED** (the API refused the *input* with a 400 — the `garbage`-body probe firing, which is
  exactly what production's `@NotBlank` blocks upstream), and **ERRORED** (an our-side output failure).
  Only ERRORED hard-fails the run — score dips print, they never fail the build. That is the one
  assertion: with the schema enforced server-side, an unusable output means the call threw, which lands
  in ERRORED.
- **`GoldenSetIntegrityTest`** — runs in the *normal* suite (deterministic, no network, no key). It is
  the label-rot tripwire: a renamed enum or a dropped ticket breaks it loudly instead of silently
  invalidating a label.

Evals are not unit tests — unit tests assert logic, evals measure judgment — so Surefire keeps them
apart:

```bash
./mvnw test            # fast, free, offline: unit + integrity + scorer tests only
./mvnw test -Pevals    # the golden-set runner only (needs ANTHROPIC_API_KEY; makes ~48 live calls)
```

### The experiment: an explicit urgency rubric (classifier prompt v1 → v2)

The baseline (`docs/evals/eval-cls1-*`) showed urgency was the weakest structured field — the model was
guessing where LOW/MEDIUM/HIGH/CRITICAL boundaries sit. The single-variable refinement was to add an
explicit `<urgency_rubric>` to the classifier prompt: written rules mapping observable facts to levels
(money already lost → CRITICAL; a purchase or delivery blocked now → HIGH; degraded-but-workable →
MEDIUM; preference or curiosity → LOW), take-the-higher on conflict. Nothing else changed — resolver
prompt v3, temperature, and all other inputs were held fixed.

| Classifier field | Baseline (v1) | Experiment (v2) |
|---|---|---|
| category | 19/23 (82.6%) | 17/23 (73.9%) |
| **urgency** | **14/23 (60.9%)** | **18/23 (78.3%)** |
| intent | 16/23 (69.6%) | 15/23 (65.2%) |

Resolver stage (held constant) was steady: escalate 20→21/23 (vs a 65.2% majority-class floor), sources
10/11, and every reply-safety rule clean (0 `mustNot` violations, canary never leaked).

**Verdict: keep the rubric.** Urgency improved +4 tickets on exactly the boundary cases it targeted
(order-overdue → HIGH, cancel-before-ship → HIGH, damaged item → HIGH, unauthorised change → CRITICAL,
idle curiosity → LOW). Honest caveats, recorded so the next cycle starts clean: the run is a single
sample at `temperature=1.0`, so the small category/intent wobble is noise-consistent for an urgency-only
change rather than a real regression (e.g. a one-off `clean-05` category flip), and a confirmation run
would tighten the estimate; the "take the higher" rule flipped `ambiguous-01` (wrong-size **and**
double-charge) to CRITICAL/BILLING against its HIGH/RETURNS tie-break label — a label flagged here for
re-review, alongside `clean-06` and `injection-02` where the rubric's answer looked more defensible than
the original label. **Resolved in Day 11:** golden-set v2 wrote `labelingPolicy` first (an
`intentUnderInjection` law, a `categoryTieBreak` law, and an `urgencyRubric` frozen verbatim from this
classifier prompt) and relabelled all three tickets against that written law — see
`GoldenSetIntegrityTest` and `src/test/resources/evals/golden-set-v2.json`. The full before/after trail
lives in `docs/evals/`.

Run (same as Day 1):

```bash
./mvnw spring-boot:run
```
