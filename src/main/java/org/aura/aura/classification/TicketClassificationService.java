package org.aura.aura.classification;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
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
