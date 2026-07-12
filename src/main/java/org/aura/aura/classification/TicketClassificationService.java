package org.aura.aura.classification;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Classification via NATIVE structured outputs (output_config.format), not tool use and
// not prompt-begged JSON: the API enforces the TicketClassification schema server-side,
// so the failure modes shrink to the ones handled explicitly below — a refusal, a token
// cutoff, or a semantically bad (low/out-of-range confidence) but well-formed answer.
@Slf4j
@Service
public class TicketClassificationService {

    // Below this we don't trust the label enough to act on it. 0.6 is a starting point,
    // not a law — tune it against real traffic once Day 24 metrics exist.
    static final double CONFIDENCE_FLOOR = 0.6;

    private final AnthropicClient client;
    private final ClassifierPromptProvider prompts;

    public TicketClassificationService(AnthropicClient client, ClassifierPromptProvider prompts) {
        this.client = client;
        this.prompts = prompts;
    }

    // Shares the SAME "anthropicApi" circuit breaker as ResolverService.resolve — classify and resolve
    // hit one dependency (the Anthropic API), so one breaker should carry its health and both call
    // sites should fast-fail together. classify runs FIRST in the request, so leaving it unprotected
    // made it the weakest link: a Claude outage threw here and 502'd the whole request before resolve's
    // own resilience was ever reached. NOTE: no @Retry — see onClaudeUnavailable for why.
    @CircuitBreaker(name = "anthropicApi", fallbackMethod = "onClaudeUnavailable")
    public ClassificationResult classify(String ticketText) {
        // Haiku, not Sonnet: classification is a cheap gate that runs before EVERY
        // resolution, so it must be fast and cheap; the closed enum schema does the
        // heavy lifting, leaving little for a bigger model to add.
        // maxTokens 256: the JSON is ~60 tokens; the cap is a cost fuse, and blowing
        // it is treated as a failed classification below rather than retried.
        // outputConfig(TicketClassification.class) derives the JSON schema from the
        // record and returns a TYPED params builder — deserialization comes for free.
        StructuredMessageCreateParams<TicketClassification> params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .maxTokens(256L)
                .system(prompts.systemPrompt())
                .outputConfig(TicketClassification.class)
                .addUserMessage(ticketText)
                .build();

        // No retries by design: this sits in front of a user-facing request, so a second
        // model call doubles worst-case latency for a component whose failure already has
        // a safe answer — the fallback. Resilience (Day 8) belongs at the transport layer.
        StructuredMessage<TicketClassification> response = client.messages().create(params);

        // stop_reason BEFORE parsing: on "refusal" content is empty and on "max_tokens"
        // it is truncated — touching .text() first would turn both into raw parse
        // exceptions and lose the actual cause.
        Optional<StopReason> stopReason = response.stopReason();
        if (stopReason.isEmpty() || !StopReason.END_TURN.equals(stopReason.get())) {
            return fallback("stop_reason=" + stopReason.map(StopReason::asString).orElse("<absent>"));
        }

        // .text() on the typed block deserializes straight into the record — the schema
        // was enforced server-side, so a clean end_turn is guaranteed to parse.
        Optional<TicketClassification> parsed = response.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(StructuredTextBlock::text);
        if (parsed.isEmpty()) {
            return fallback("end_turn response carried no text block");
        }

        return validate(parsed.get());
    }

    // Semantic validation: the schema guarantees SHAPE (a number), not MEANING (a
    // probability). Clamp out-of-range values rather than reject — a 1.2 signals an
    // over-eager model, not an unusable classification.
    private ClassificationResult validate(TicketClassification raw) {
        double confidence = Math.clamp(raw.confidence(), 0.0, 1.0);
        if (confidence < CONFIDENCE_FLOOR) {
            return fallback("confidence %.2f below floor %.2f".formatted(confidence, CONFIDENCE_FLOOR));
        }
        TicketClassification classification = confidence == raw.confidence()
                ? raw
                : new TicketClassification(raw.category(), raw.urgency(), raw.intent(), confidence);
        return new ClassificationResult(classification, false);
    }

    // Circuit-breaker fallback. classify() already OWNS a safe degraded answer — the human-review
    // ClassificationResult below — so a Claude transport failure, or a tripped shared breaker, routes
    // straight to it instead of surfacing as a 5xx and killing the request before resolve() is reached.
    //
    // ALLOWLIST, same taxonomy as application.yml (429 / 5xx / connection), plus the breaker-open
    // CallNotPermittedException. Everything else — a permanent 400/401/403/404/422, or any unknown
    // throwable — is RETHROWN: silently relabelling a broken API key as "route to a human" forever
    // would bury a real operational bug behind a WARN log. Fail loud on our mistakes, degrade only on
    // the dependency's.
    //
    // No @Retry on classify by design: it is a cheap pre-gate whose failure already has a safe answer,
    // so a second model call would only add latency in front of every user request. The shared breaker
    // still gives it fast-fail during an outage for free — resolve()'s retries feed the same breaker.
    @SuppressWarnings("unused") // invoked reflectively by the Resilience4j @CircuitBreaker aspect
    private ClassificationResult onClaudeUnavailable(String ticketText, Throwable cause) throws Throwable {
        if (cause instanceof CallNotPermittedException
                || cause instanceof AnthropicIoException
                || cause instanceof RateLimitException
                || cause instanceof InternalServerException) {
            return fallback("Claude unavailable (" + cause.getClass().getSimpleName() + ")");
        }
        throw cause;
    }

    // One fallback for every failure path: neutral labels, confidence 0.0 (we know
    // nothing), needsHumanReview=true. WARN not ERROR — the request still succeeds,
    // it just gets a human instead of automation.
    private ClassificationResult fallback(String reason) {
        log.warn("Ticket classification fell back to human review: {}", reason);
        return new ClassificationResult(
                new TicketClassification(
                        TicketCategory.OTHER, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.0),
                true);
    }
}
