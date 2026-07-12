package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.ResolverPromptProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResolverService {

    // One dependency (Claude), one shared instance name — the retry policy and the breaker policy in
    // application.yml both bind to "anthropicApi", so the config reads as two views of one dependency.
    private static final String CLAUDE = "anthropicApi";

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

        Message message = client.messages().create(paramsFor(ticket, hits));

        String answer = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        return new Resolution(answer, hits.stream().map(KbEntry::id).toList(), ResolutionStatus.RESOLVED);
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
        throw cause;
    }

    private Resolution escalated() {
        return new Resolution(
                "We couldn't answer this automatically right now, so your ticket has been escalated to a human agent.",
                List.of(),
                ResolutionStatus.ESCALATED_TO_HUMAN);
    }

    // Streaming (Day 7) shares the EXACT retrieve-augment step as the blocking path above, so a
    // streamed answer is grounded identically to a non-streamed one — only the transport (block
    // vs stream) differs. The streaming caller takes these params and opens createStreaming()
    // instead of create(); sources aren't returned here because the streaming contract surfaces
    // usage/stop_reason rather than the KB receipt (Day 9 will persist the full turn).
    public MessageCreateParams buildStreamingParams(String ticket) {
        return paramsFor(ticket, knowledgeBase.retrieve(ticket));
    }

    // Single source of truth for the resolution prompt: model, token cap, system prompt, and the
    // KB-augmented user turn. Both resolve() and buildStreamingParams() route through here so the
    // two transports can never drift apart in wording or configuration.
    private MessageCreateParams paramsFor(String ticket, List<KbEntry> hits) {
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
                .model(Model.CLAUDE_SONNET_4_5)
                .maxTokens(1024L)
                .system(prompts.systemPrompt())
                .addUserMessage(userTurn)
                .build();
    }
}
