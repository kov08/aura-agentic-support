# AURA — Agentic Support

AURA is a customer-support agent for ShopFast (a sample e-commerce platform), built incrementally
on Spring Boot and the Claude Messages API. Each "Day" adds one capability on top of the last.

## Prerequisites

- JDK 21+
- `ANTHROPIC_API_KEY` exported in your environment (the client reads it via `fromEnv()`)

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