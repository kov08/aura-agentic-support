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