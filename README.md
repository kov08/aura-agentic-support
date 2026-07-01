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