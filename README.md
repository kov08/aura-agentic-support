# AURA — Agentic Support

AURA is a customer-support agent for ShopFast (a sample e-commerce platform), built incrementally
on Spring Boot and the Claude Messages API. Each "Day" adds one capability on top of the last.

## Prerequisites

- JDK 25 (the toolchain the build targets; see `<java.version>` in [pom.xml](pom.xml))
- `ANTHROPIC_API_KEY` exported in your environment (the client reads it via `fromEnv()`)
- `VOYAGE_API_KEY` exported in your environment (Day 12 embeddings). Both keys are validated at
  startup, so a missing one fails the context immediately rather than on the first call — see
  `.env.example` for the names
- Docker running — the Redis cache (Day 9), the Postgres/pgvector store (Day 13), and the integration
  tests (Days 11/13) use it

## Reproducibility (fresh clone → running)

The contract: a clean checkout reaches a verified, running agent in these exact steps. `verify` is the
gate — it runs the fast offline unit/integrity/scorer tests (Surefire) **and** the full-context
integration tests against real Redis and Postgres/pgvector containers plus a local MockWebServer
(Failsafe, needs Docker).

Note the asymmetry between the two containers `docker compose up -d` starts. Redis is a cost layer and
the app fails *open* when it is missing. Postgres is the system of record and the app does not start
without it, because Flyway and Hibernate's schema validation both run at boot (Day 13).

```bash
git clone <repo-url> && cd aura-agentic-support/aura
```

```bash
docker compose up -d
```

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

```powershell
$env:VOYAGE_API_KEY = "pa-..."
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

## Day 12 — RAG foundations: a real corpus, a chunker, and embeddings

Day 4's knowledge base matched keywords, and Day 4's own transcript recorded its failure mode: a
reworded return question retrieved nothing and the model fell back on the system prompt. Day 12 lays
the groundwork for fixing that with meaning-based retrieval instead of string overlap.

### The corpus is data now

`kb/` holds three real ShopFast policies (refund, shipping, warranty) as committed markdown. This is
the ADR-007a endgame: **domain facts never live in prompts.** A policy in a prompt string makes a
policy change a code change, hides it from whoever owns it, and puts it somewhere retrieval cannot
see. `kb/` is what Day 15's ingestion pipeline reads. One section — refund policy's *International
Orders* — is deliberately oversized so the recursive fallback runs on genuine prose, and a test keeps
it that way.

### `DocumentChunker` — structure first, characters last

Markdown headings are the author's own statement of where one idea ends, so they define the chunks,
and the full heading path becomes a **breadcrumb** (`Refund Policy > International Orders`) that is
embedded alongside the body — a chunk stripped of its heading is nearly meaningless on its own. Only
a section over the cap falls back to character splitting, and it descends a hierarchy ranked by how
much meaning a cut destroys: **blank line → sentence end → space → hard cut.** Consecutive sub-chunks
share ~300 characters of overlap, never across a heading, and each is labelled `(part i/n)` under the
shared section path. The 2,000-character cap is a stated *approximation* of ~500 tokens (~4 chars per
token) — there is no clean JVM build of Voyage's tokenizer, so the proxy is set well below the real
limit rather than tuned against it.

### `VoyageEmbeddingClient` — two lanes, two models

One HTTP call underneath, two methods on top, and the split is the point:

| Lane | Method | Model | Cost shape |
|---|---|---|---|
| Offline ingestion | `embedDocuments` | `voyage-4-large` | paid **once** per document |
| Per-ticket query | `embedQuery` | `voyage-4-lite` | paid on **every** ticket |

Spending the better model where the cost is amortised and the cheaper one where it recurs is the Day 20
cost thesis one layer below the classifier/resolver split. Both the model *and* the required
`input_type` are derived from the method the caller chose, so a mismatched pair is unrepresentable —
important because a mismatch produces perfectly valid vectors that simply retrieve worse, with no error
anywhere. For the same reason, `VoyageProperties` refuses to boot if the two model names leave the
`voyage-4` family: asymmetric models are comparable only inside one shared embedding space, and a
cross-family pair is silently meaningless rather than loudly broken.

Failures are mapped by an **allowlist**: 408/429/5xx and transport faults become
`VoyageTransientException`; everything else — 400, 401, 422, and anything unknown — becomes
`VoyagePermanentException` and is not retried. Resilience4j (`instances.voyage`) is the single retry
owner, exactly as the Anthropic SDK is pinned to `maxRetries(0)`; retry is safe on *both* lanes here
only because embedding is an idempotent pure read, which the Day 17 refund tool will not be.

One transport detail worth recording, because it is the Day 11 trap on a different client: a timeout
that strikes while the response **body** is streaming does not surface as `ResourceAccessException` —
the headers already arrived, so Spring is inside response extraction and reports a plain
`RestClientException`. Mapping only the obvious type would have left the most realistic outage shape (a
provider that accepts the request and then stalls) classified as permanent and never retried.

### The demo

`SemanticSearchDemoIT` chunks the real `kb/` files, embeds them once, and ranks three queries by cosine
similarity over a brute-force in-memory scan — which is precisely what pgvector replaces on Day 13.
The off-topic control query ("Do you sell scuba diving gear?") still returns a ranked "best" match:
cosine scores are **relative, not calibrated**, so relevance gating is a separate decision, not a free
property of retrieval. It is billable and manual, so it is tagged and excluded like the evals:

```bash
./mvnw verify          # unit + integration tests; the demo is excluded
```

```bash
./mvnw verify -Pdemo   # the semantic-search demo only (needs VOYAGE_API_KEY; makes live calls)
```

### Lab: what the family check does *not* protect (`lab/CrossModelDemoIT`)

`VoyageProperties` refuses to boot on a cross-family pair, and that check earns its keep — setting
`query-model: voyage-3.5-lite` fails the context at binding time, on property
`voyage.modelFamilyConsistent`, before Tomcat binds a port and before any HTTP call exists.

But it validates the **configuration**, and the dangerous state is in the **data**: a migration that
re-points the query lane without re-embedding the corpus leaves a store of `voyage-4-large` vectors
being searched by `voyage-3.5-lite` queries. At every instant the config is internally valid. The
check is a guard on a transition it never observes. `lab/CrossModelDemoIT` stages exactly that, by
constructing `VoyageProperties` directly — the canonical constructor bypasses JSR-303, because Bean
Validation runs through Spring's binder — and ranks an 8-chunk index both ways. Measured:

| | legitimate | mixed-era |
|---|---|---|
| top-1 score | 0.3730 (`Refund Policy > Standard Refund Window`) | −0.0293 (`Shipping Policy > Lost Parcels`) |
| spread, top-1 to last | 0.1527 | 0.0263 |
| all 8 scores | 0.220 … 0.373 | −0.056 … −0.029 |

**Nothing throws, nothing warns, the build is green.** Both vectors are 1024-dimensional, so
`VectorMath`'s guard is satisfied — it catches a *shape* mismatch, not a *space* mismatch. The only
trace anywhere is an INFO line correctly naming a different model, which is accurate and is not an
alert.

The signal that does work is a **canary**: embed one fixed probe string through both lanes and
compare. Measured on the same sentence —

| probe comparison | cosine |
|---|---|
| `voyage-4-large`(doc) vs `voyage-4-lite`(query) — healthy | **0.6900** |
| `voyage-4-large`(doc) vs `voyage-3.5-lite`(query) — mixed era | **−0.0247** |
| `voyage-4-lite`(query) vs `voyage-3.5-lite`(query) | 0.0025 |

Two asymmetric models in one space agree at 0.69 on identical text, not 1.0 — each lane carries a
different internal instruction, so the healthy value has to be *measured*, never assumed. Across
eras it collapses to zero. That 0.71 gap is enormous next to the ~0.095 window available for a
relevance threshold, which makes an ingestion-time canary a far better-conditioned check than
anything score-based. Day 13/15 should store the corpus's embedding model alongside the vectors and
assert this probe on startup.

One caution on precision: identical back-to-back runs returned 0.3739 and 0.3730 for the same
top-1. Voyage is not bit-reproducible, so no threshold should be pinned to three decimals.

## Day 13 — pgvector: the corpus becomes a database

Day 12 ended with a brute-force scan: every chunk held in an `ArrayList`, every query compared against
every one of them by a hand-written cosine loop, re-embedded from scratch on every run. Day 13 replaces
that loop with `ORDER BY embedding <=> ?` and the list with a table. The win is not that Postgres is
faster at arithmetic — at 33 chunks nothing is slow. It is that the corpus no longer has to fit in the
JVM or be paid for again on every restart.

### The schema, and the one thing it buys

`kb_chunks` is the Day 12 `Chunk` record plus its vector, given durability, a uniqueness rule, and an
ordering operator. Two columns carry more weight than their size suggests.

`embedding vector(1024)` puts the dimension in the **type**, not in a check constraint, because
pgvector makes it part of the type. That is the single most valuable property of this schema: a
512-dimension vector is not stored-and-mis-ranked, it is *refused*. Everything else here could be
reimplemented in application code; this cannot, because application code is exactly what would have
the bug.

`embedding_model text NOT NULL` records which model produced each vector — per row, not per deployment,
because a corpus can legitimately be mid-migration with half of it re-embedded. The Day 12 lab measured
what its absence costs: re-point the query lane at a different model era without re-embedding, and
every score collapses toward zero while nothing throws, nothing warns, and the build stays green.

Day 12 asked for two things here, and this is one of them. The column now exists, so the stale-corpus
state is *representable* — but nothing yet reads it, so the state is still not *detected*. The
ingestion-time canary (embed one fixed probe string through both lanes and compare against the stored
model) remains open, and it is the half that actually raises an alarm. Storing provenance is the
precondition; checking it is the feature.

`UNIQUE (source_doc, chunk_index)` makes a re-ingestion a *conflict* rather than a silent duplicate.
Without it, running the loader twice doubles the corpus and every query returns the same passage twice
at the top — a retrieval defect that presents as a ranking defect. Day 15's upsert will target this
constraint; today it is the tripwire that makes the missing upsert loud.

### Two writers is one too many

Flyway **writes** the schema; Hibernate (`ddl-auto=validate`) only **checks** it. Nothing generates DDL
at runtime. The alternatives are both worse in the same direction: `update` silently mutates the schema
behind Flyway's back — two writers, one schema, no history — and `none` makes drift undetectable until
the query that touches the missing column. `open-in-view=false` for the adjacent reason: OSIV holds a
session open across the whole request, hiding lazy-loading queries behind the service layer and pinning
a connection for the request's full duration rather than for the duration of the work.

### No vector index, on purpose

HNSW and IVFFlat are **approximate** nearest-neighbour structures: they buy speed by agreeing to
sometimes not return the true top-k. At tens of chunks a sequential scan is both exact and instant, so
an index would trade away recall for a latency win that does not exist — and it would do it invisibly,
because a wrong top-k looks exactly like a right one. That makes adding one a decision with a real
trade-off, not a performance tweak, so it gets an ADR when a *measurement* says the scan has become the
latency budget. The trigger is written into the migration: p95 scan latency becoming a visible share of
the per-ticket budget, which at these vector sizes means the low tens of thousands of chunks.

A consequence worth knowing before that day: an HNSW index is built **for one distance operator**, so
choosing `<=>` also fixes which index can ever serve the query. That is why `ChunkRepository` writes the
operator out in the SQL instead of hiding it behind a JPQL function — `<=>` cosine, `<->` L2, `<#>`
negative inner product, and swapping one for another changes every ranking while breaking nothing that
would fail a test. Note the direction, too: `<=>` is a **distance**, so ascending is
most-similar-first — the opposite sort from Day 12's cosine *similarity*.

### One number, three languages, checked at every boot

`1024` is written down three times, in three places that cannot read each other: `vector(1024)` in the
migration, `@Array(length = 1024)` on `KbChunk`, and `aura.embedding.dimension` in `application.yml`. A
migration cannot read a Java constant and Hibernate cannot read SQL, so the duplication is
irreducible — which makes the interesting question not "how do we avoid three copies" but "what happens
when they disagree."

`ddl-auto=validate` already pins the entity to the column. `EmbeddingDimensionCheck` closes the triangle
by comparing the live column against the configured value and refusing to boot on a mismatch. It is a
Flyway `AFTER_MIGRATE` callback rather than an `ApplicationRunner` for three reasons: it runs at the one
moment the schema is guaranteed current, so there is no bean-ordering question; throwing fails the
context *before* Tomcat binds a port, where a runner would fail after the server is up and would not run
under `@SpringBootTest` at all; and it takes no `DataSource` in its constructor, so it is simply never
invoked in the many test contexts that have no database, instead of needing a conditional to keep it
from exploding.

The catalog query behind it is verified **empirically**, not from documentation. pgvector is documented
to store the dimension raw in `atttypmod` with none of the `VARHDRSZ` offset `varchar` adds; the test
asserts that against a real migrated column (`atttypmod` = 1024, `format_type` = `vector(1024)`), so a
future encoding change fails in a test named after the claim rather than leaving the startup check
quietly comparing the wrong number.

### The locale trap in eleven lines of string building

`VectorLiterals` exists because the read and write paths do not share a mechanism: Hibernate binds the
array natively, but a native query parameter carries no entity type, so the query vector must arrive as
pgvector's text form and be `CAST(? AS vector)` on the far side.

It uses `Float.toString`, not a formatter. `String.format`, `DecimalFormat` and `NumberFormat` all use
`Locale.getDefault()`, and under a comma-decimal locale `0.1` formats as `0,1` — which inside a
comma-separated list does not fail, it parses as **two elements**. A 1024-element vector becomes a
2048-element one and Postgres rejects it with a dimension error naming entirely the wrong problem. The
code is correct in `en-US` and corrupt in most of Europe and South America, and no amount of local
testing finds it, because the default locale is ambient state tests inherit rather than set.
`Float.toString` is locale-independent by specification *and* emits the shortest decimal that
round-trips, so it is both locale-proof and lossless. Pinned under `de-DE`, `fr-FR`, `pt-BR`.

### The database is opt-in under test

Adding `spring-boot-starter-data-jpa` puts `DataSourceAutoConfiguration` into **every** context,
including the many that have never touched a database. Handing them all a container would put a hard
Docker prerequisite on the fast, free, offline `mvn test` suite — the wrong trade, since the DB-less
tests outnumber the DB tests. So absence is the default and presence is declared:
`application-test.yml` excludes `DataSourceAutoConfiguration` (Hibernate JPA, Flyway, Spring Data
repositories and the transaction manager are all conditional on a `DataSource` bean, so they back off
behind it), and the two Postgres tests opt back in with
`@SpringBootTest(properties = "spring.autoconfigure.exclude=")`. Three slice tests activate no profile
at all and carry the exclusion on their own `@EnableAutoConfiguration`.

`mvn test` therefore remains 116 tests, offline, no Docker. Voyage is never called: retrieval *quality*
belongs in the billable manual demo, but retrieval *mechanics* are properties of Postgres and this
schema and should be provable for free on every build.

### Two Boot 4 traps, both silent

Boot 4 split the monolithic `spring-boot-autoconfigure` jar into per-technology modules, and both
consequences fail without an error message.

`org.flywaydb:flyway-core` on its own **does nothing**. `FlywayAutoConfiguration` now lives in
`org.springframework.boot:spring-boot-flyway`, which only `spring-boot-starter-flyway` pulls in. With
the bare library the build resolves, compiles, and boots with Flyway silently never running; the first
symptom is Hibernate complaining about a missing table, several layers from the actual mistake.

`DataSourceAutoConfiguration` moved to `org.springframework.boot.jdbc.autoconfigure`. A stale FQN in
`spring.autoconfigure.exclude` does **not** error — Boot only rejects an exclusion whose class is on the
classpath but is not an auto-configuration, so a name resolving to nothing is skipped in silence. The
wrong package reads exactly like the right one until a test tries to reach `localhost:5432`.

### Measured

Live `-Pdemo` run over the real 33-chunk corpus, retrieving from pgvector:

| query | top-1 | distance |
|---|---|---|
| "Can I get my money back for a hoodie I bought two weeks ago?" | `Refund Policy > Standard Refund Window` | 0.6273 |
| "How long does delivery to Canada take?" | `Shipping Policy > Delivery Speeds and Costs` | 0.5231 |
| "Do you sell scuba diving gear?" *(control)* | `Shipping Policy > Restricted Destinations` | 0.7456 |

The paraphrased refund question — no shared vocabulary with the policy — lands in the right document,
which is the Day 4 keyword failure staying fixed. The off-topic control still returns three ranked hits:
distances are **relative, not calibrated**, and moving the search into a database did not change that.
Relevance gating remains a separate decision (Day 16), not a free property of retrieval.

### Drill: what happens when you edit an applied migration

One letter changed inside a `--` comment in `V2`, uncommitted, then a restart against the existing
Compose volume:

```
Caused by: org.flywaydb.core.api.exception.FlywayValidateException:
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 2
-> Applied to database : 732889598
-> Resolved locally    : -848807693
```

It fails at **boot**, inside `flywayInitializer`, which `entityManagerFactory` depends on — so
`ddl-auto=validate` never even gets a turn. And it names the migration and its ledger row, not a table:
the *schema is perfectly fine*. What is wrong is a disagreement between a file and the record of that
file having been run.

The same corrupted file passes the Testcontainers suite. A fresh container has no
`flyway_schema_history`, so there is no prior claim to contradict — Flyway applies V1/V2 and records the
new checksum. **This class of defect is structurally invisible to the integration suite and always will
be.** `PgVectorSchemaIT` proves these migrations build a correct schema *from nothing*; it cannot prove
they are compatible with the databases that already ran them, and every environment that matters is the
second kind.

The fix is `git checkout --` on the migration. **Not `flyway repair`**, which rewrites
`flyway_schema_history` to match whatever is now on disk: the ledger is *correct* — it faithfully
records what ran — and repair resolves the disagreement by destroying the evidence. It also cannot tell
a comment from a `DROP COLUMN`, and it only fixes the one database it is run against, so every other
environment still fails. Repair is right when the *ledger* is wrong (clearing a failed non-transactional
migration; realigning after a deliberate Flyway upgrade that changed the checksum algorithm). This was
the opposite. **The rule: once a migration is in the history, the file is read-only — forever. Need a
change? Write V3.**

A corollary, also measured: Flyway checksums line-by-line, so CRLF/LF churn on a Windows checkout does
*not* trip validation — but one letter in a comment does.

### Commands

```bash
docker compose up -d
```

```bash
./mvnw verify
```

```bash
./mvnw spring-boot:run -Daura.kb.load=true
```

```bash
./mvnw verify -Pdemo
```
