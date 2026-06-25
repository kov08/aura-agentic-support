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