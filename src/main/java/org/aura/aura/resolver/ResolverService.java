package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.resilience.AnthropicTransientFailures;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResolverService {

    // One dependency (Claude), one shared instance name — the retry policy and the breaker policy in
    // application.yml both bind to "anthropicApi", so the config reads as two views of one dependency.
    private static final String CLAUDE = "anthropicApi";

    // The ANSWER-AFFECTING request shape, exposed as the SINGLE SOURCE OF TRUTH for both the request
    // (paramsFor) and the Day 9 cache key (CachedResolutionService → CacheKeyFactory). Deriving the key
    // from these same constants is what makes a code edit here self-invalidating: bump MAX_TOKENS and
    // every previously-truncated cached answer is orphaned by construction (ADR-019). The system prompt
    // is the fourth such input; it comes from the shared ResolverPromptProvider, which both this service
    // (paramsFor) and CachedResolutionService read — one source, so key and request can't drift.
    public static final String MODEL_ID = Model.CLAUDE_SONNET_4_5.asString();
    public static final long MAX_TOKENS = 2048L;
    // Set EXPLICITLY (1.0 is also the API default, so this is a no-op behaviourally) precisely so the
    // cache key can fold in a REAL request parameter rather than a fiction: temperature is
    // answer-affecting, and a silent default would be un-keyable.
    public static final double TEMPERATURE = 1.0;

    private final AnthropicClient client;
    private final ResolverPromptProvider prompts;
    private final KnowledgeBase knowledgeBase;
    // Injected only so the fallback can report the breaker's actual state (OPEN vs HALF_OPEN vs
    // FORCED_OPEN) in its WARN line — that log becomes the Day 24 "how often do we degrade" metric.
    private final CircuitBreakerRegistry circuitBreakers;

    public ResolverService (AnthropicClient client, ResolverPromptProvider prompts, KnowledgeBase knowledgeBase,
                            CircuitBreakerRegistry circuitBreakers){
        this.client = client;
        this.prompts = prompts;
        this.knowledgeBase = knowledgeBase;
        this.circuitBreakers = circuitBreakers;
    }

    // Retry + circuit breaker apply here, on a PUBLIC method reached across a bean boundary
    // (TicketController / ConversationRunner call it). That's mandatory: Spring implements these
    // annotations with an AOP proxy, and a self-invocation (this.resolve()) would bypass the proxy
    // and silently disable both policies. Resolve is safe to retry — it is a read (retrieve → ask),
    // with no side effect to duplicate.
    //
    // The fallback degrades to human escalation on the two "Claude is unhealthy" paths — breaker open,
    // or a transient failure whose retries were exhausted — and RE-PROPAGATES everything else. It keys
    // off the SAME transient allowlist as retry/record-exceptions: not on the list (a permanent 400/401,
    // or any unknown/future exception type) means "surface it", never "mask it as an escalation". See
    // escalateToHuman for the fail-closed detail.
    //
    // fallbackMethod sits on @Retry (the OUTER aspect), NOT @CircuitBreaker (inner). Order matters: the
    // aspects nest Retry(CircuitBreaker(call)), so a fallback on the inner breaker would catch the first
    // transient error and return BEFORE @Retry ever retried it — silently disabling retries. On the
    // outer @Retry, the fallback runs only after retries are spent (or a breaker-open rejection has
    // passed straight through), which is exactly when we want to decide "degrade vs surface".
    @Retry(name = CLAUDE, fallbackMethod = "escalateToHuman")
    @CircuitBreaker(name = CLAUDE)
    public Resolution resolve(String ticket){
        List<KbEntry> hits = knowledgeBase.retrieve(ticket);

        StructuredMessage<ResolverOutput> message = client.messages().create(paramsFor(ticket, hits));
        logUsage(message.usage());

        // stop_reason BEFORE parsing — the same gate the classifier uses (Day 6), and it matters MORE
        // now than it did when this method returned prose: on "refusal" the content is empty and on
        // "max_tokens" the JSON is truncated mid-object, so touching .text() first would surface both
        // as a raw Jackson parse exception with the actual cause lost.
        //
        // Unlike the classifier, a bad stop_reason here does NOT degrade to a safe answer. The resolver
        // has no neutral reply to fall back on — inventing one would break the prompt's own "never
        // invent, never claim you did something" rule — and ESCALATED_TO_HUMAN is reserved for
        // dependency health, which this is not. So it surfaces: "fail loud on our mistakes, degrade
        // only on the dependency's". IllegalStateException is deliberately absent from the transient
        // allowlist, so escalateToHuman rethrows it rather than masking it as a bogus outage.
        Optional<StopReason> stopReason = message.stopReason();
        if (stopReason.isEmpty() || !StopReason.END_TURN.equals(stopReason.get())) {
            throw new IllegalStateException("Resolver returned stop_reason="
                    + stopReason.map(StopReason::asString).orElse("<absent>") + " — no usable reply");
        }

        // .text() on the typed block deserializes straight into the record; the schema was enforced
        // server-side, so a clean end_turn is guaranteed to parse.
        ResolverOutput output = message.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(StructuredTextBlock::text)
                .orElseThrow(() -> new IllegalStateException("end_turn resolver response carried no text block"));

        // sourcesUsed is derived from RETRIEVAL, never read from the model — the grounding receipt
        // stays ours, which is what makes it trustworthy evidence rather than a claim (see ResolverOutput).
        return new Resolution(
                output.reply(),
                hits.stream().map(KbEntry::id).toList(),
                ResolutionStatus.RESOLVED,
                output.escalate());
    }

    // Circuit-breaker fallback. Two degrade paths — logged distinctly so Day 24 can count them as
    // separate metrics — plus a fail-closed rethrow for everything else:
    //
    //   1. CallNotPermittedException — the breaker is OPEN: Claude has failed enough that we've stopped
    //      calling it. A sustained outage. ("degraded: breaker open")
    //   2. A transient transport failure whose retries were exhausted — a single-request blip that
    //      didn't clear within the attempt budget. ("degraded: retries exhausted") The allowlist here
    //      is the SAME transient taxonomy as retry-exceptions / record-exceptions in application.yml.
    //
    // Both return a real, business-valid Resolution (HTTP 200, Day 5) rather than a 5xx — a human is a
    // better outcome for the customer than an error page.
    //
    // ALLOWLIST, not a denylist: we enumerate what degrades; anything not matched — a permanent
    // 400/401/404, or any unknown/future exception type — is RETHROWN and surfaces honestly. Note there
    // is deliberately NO "isPermanent(statusCode)" check: absence from the transient list IS the whole
    // decision, exactly like the retry allowlist. A denylist would fail OPEN, silently masking an
    // unrecognised error as a bogus outage escalation.
    @SuppressWarnings("unused") // invoked reflectively by the Resilience4j @CircuitBreaker aspect
    private Resolution escalateToHuman(String ticket, Throwable cause) throws Throwable {
        if (cause instanceof CallNotPermittedException) {
            var state = circuitBreakers.circuitBreaker(CLAUDE).getState();
            log.warn("Claude unavailable — circuit breaker '{}' is {}; escalating ticket to a human. cause={}",
                    CLAUDE, state, cause.toString());
            return escalated();
        }
        if (cause instanceof RateLimitException
                || cause instanceof InternalServerException
                || cause instanceof AnthropicIoException) {
            log.warn("Claude transient failure, retries exhausted; escalating ticket to a human. cause={}",
                    cause.toString());
            return escalated();
        }
        // Day 11: a hung response (client timeout mid-read) surfaces as AnthropicInvalidDataException
        // caused by a timeout, NOT AnthropicIoException. That is a dependency hang — fail fast to a
        // human, not a 5xx. A malformed body (same type, non-timeout cause) is excluded and still
        // rethrows below. NOT retried (see AnthropicTransientFailures): retrying a hang only stacks
        // timeouts before the same escalation.
        if (AnthropicTransientFailures.isReadTimeout(cause)) {
            log.warn("Claude response read timed out; escalating ticket to a human. cause={}",
                    cause.toString());
            return escalated();
        }
        throw cause;
    }

    private Resolution escalated() {
        return new Resolution(
                "We couldn't answer this automatically right now, so your ticket has been escalated to a human agent.",
                List.of(),
                ResolutionStatus.ESCALATED_TO_HUMAN,
                // BOTH channels true, and that is not redundancy. `status` records WHY (the dependency
                // was unhealthy); `escalate` records WHAT the caller must now do (route to a human), so
                // downstream code reading only `escalate` still behaves correctly during an outage.
                // No scoring collision with a model-chosen escalate=true: the eval detects these by
                // status == ESCALATED_TO_HUMAN and excludes them from scores as DEGRADED.
                true);
    }

    // ADR-020: prompt-cache observability. cacheReadInputTokens > 0 means the static system-prompt
    // prefix was served from Anthropic's 5-min ephemeral cache (a hit, ~90% cheaper on that prefix);
    // cacheCreationInputTokens > 0 means this call WROTE the prefix (a ~25% surcharge that the next
    // call within 5 min recoups). Both SDK fields are Optional — absent on an uncached call — so
    // .orElse(0L). This is best-effort telemetry: a resolution we already paid for and obtained must
    // never fail because usage couldn't be read, so a missing usage block degrades to "no log", never
    // an error (same fail-open spirit as the Day 9 cache).
    private void logUsage(Usage usage) {
        if (usage == null) return;
        log.info("resolver usage — inputTokens={}, cacheCreationInputTokens={}, cacheReadInputTokens={}",
                usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
    }

    // Streaming (Day 7) shares the EXACT retrieve-augment step as the blocking path above, so a
    // streamed answer is grounded identically to a non-streamed one — only the transport (block
    // vs stream) differs. The streaming caller takes these params and opens createStreaming()
    // instead of create(); sources aren't returned here because the streaming contract surfaces
    // usage/stop_reason rather than the KB receipt (Day 9 will persist the full turn).
    //
    // Day 10: rawParams() unwraps the typed params back to the plain MessageCreateParams that
    // createStreaming takes. The output_config is injected into the request body at build() time, so
    // it travels WITH the unwrapped params — the stream stays schema-enforced, and what arrives on
    // the wire is JSON, not prose. That is exactly why the pump can no longer forward text deltas
    // straight to the customer and needs StreamingReplyExtractor to unwrap the envelope.
    //
    // ResolverServiceTest asserts output_config is actually present on what this returns. That test
    // is not ceremony: if rawParams() ever dropped it, the request would silently revert to prose,
    // the extractor would match no "reply" key, and every SSE customer would get an EMPTY stream —
    // a silent total failure with no exception anywhere to catch it.
    public MessageCreateParams buildStreamingParams(String ticket) {
        return paramsFor(ticket, knowledgeBase.retrieve(ticket)).rawParams();
    }

    // Single source of truth for the resolution prompt: model, token cap, system prompt, and the
    // KB-augmented user turn. Both resolve() and buildStreamingParams() route through here so the
    // two transports can never drift apart in wording or configuration.
    //
    // Day 10 kept that invariant deliberately when the output became structured: ONE prompt, ONE
    // schema, two doors. The tempting alternative — structured output on the blocking path only —
    // looks cheaper and is a trap. The system prompt's few-shot examples now teach the JSON envelope,
    // so a streaming request without output_config would carry JSON-teaching examples with no schema
    // enforcement: the model emits JSON anyway, unenforced and unparseable, straight onto a
    // customer-facing SSE stream. Splitting the transports would have meant splitting the prompt too,
    // which means two cache prefixes (ADR-020) and a live drift seam.
    private StructuredMessageCreateParams<ResolverOutput> paramsFor(String ticket, List<KbEntry> hits) {
        String context = hits.isEmpty()
                ? "No matching knowledge-base entries found."
                : hits.stream()
                  .map(e -> "[" + e.id() + "]" + e.title() +": "+ e.content())
                  .collect(Collectors.joining("\n"));

        String userTurn = """
                <knowledge_base>
                %s
                </knowledge_base>

                customer ticket: %s
                """.formatted(context, ticket);

        return MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_5)   // MODEL_ID = this constant's .asString(); one source, no drift
                .maxTokens(MAX_TOKENS)
                .temperature(TEMPERATURE)
                // ADR-020: mark the static system prompt (rules + few-shot + hardcoded KB — the STABLE
                // prefix) with an ephemeral cache_control breakpoint. Replaces the plain .system(String).
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(prompts.systemPrompt())
                                // Breakpoint on the LAST block that is byte-identical across requests. Anything
                                // volatile (timestamp, ticket ID) at or before this line would mean paying a
                                // cache write on every call and never getting a read.
                                .cacheControl(CacheControlEphemeral.builder().build())  // ephemeral 5-min TTL; hits refresh it free
                                .build()))
                // Native structured outputs (ADR-021), same mechanism as the Day 6 classifier: the
                // schema is DERIVED from the ResolverOutput record and enforced server-side, so
                // `escalate` arrives as a real boolean instead of something we'd have to infer from
                // prose. This call is what re-types the builder to StructuredMessageCreateParams.
                //
                // Placed AFTER the cache_control breakpoint line for readability only — output_config
                // is a request-body field, not a content block, so it sits outside the cached prefix
                // and its position in this chain has no effect on what gets cached.
                .outputConfig(ResolverOutput.class)
                // The ticket goes in messages — AFTER the breakpoint, never cached.
                .addUserMessage(userTurn)
                .build();
    }
}
