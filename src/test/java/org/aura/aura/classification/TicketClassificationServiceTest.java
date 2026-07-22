package org.aura.aura.classification;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the semantic-validation/fallback ladder of {@link TicketClassificationService}:
 * stop_reason gating (refusal, max_tokens), the confidence floor, and clamping.
 *
 * <p>The Claude call is stubbed at the API-response seam, so the tests exercise OUR
 * decision logic — not the model and not the network. Content blocks are real SDK objects
 * wrapping raw JSON, which means the typed {@code text()} deserialization path (JSON →
 * record with enums) runs for real instead of being mocked away.
 */
class TicketClassificationServiceTest {

    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);

    private TicketClassificationService service() {
        ClassifierPromptProvider prompts = mock(ClassifierPromptProvider.class);
        when(prompts.systemPrompt()).thenReturn("test classifier prompt");
        return new TicketClassificationService(client, prompts);
    }

    // StructuredMessage is final; Mockito 5's inline mock maker (Boot's default) handles it.
    @SuppressWarnings("unchecked")
    private void stubResponse(StopReason stopReason, String json) {
        StructuredMessage<TicketClassification> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.ofNullable(stopReason));
        if (json != null) {
            // Real block, not a mock: text() must genuinely deserialize the JSON.
            TextBlock textBlock = TextBlock.builder().text(json).citations(List.of()).build();
            StructuredContentBlock<TicketClassification> block =
                    new StructuredContentBlock<>(TicketClassification.class, ContentBlock.ofText(textBlock));
            when(message.content()).thenReturn(List.of(block));
        } else {
            // EMPTY content, not unstubbed. This is what makes the stop_reason tests prove ORDERING:
            // if the gate ever stopped running first, these responses would fall through to the
            // no-text-block path and report MALFORMED_RESPONSE instead of REFUSED/TRUNCATED, and the
            // reason assertion would catch it.
            when(message.content()).thenReturn(List.of());
        }
        when(client.messages().create(any(StructuredMessageCreateParams.class))).thenReturn(message);
    }

    // Day 10: the expected reason is now part of every fallback assertion. The labels below are
    // IDENTICAL on all four fallback paths, so before ReviewReason existed these tests could only
    // prove "it fell back", never "it fell back for the right cause" — and the eval's DEGRADED
    // exclusion depends entirely on telling those causes apart.
    private static void assertIsFallback(ClassificationResult result, ReviewReason expectedReason) {
        assertThat(result.needsHumanReview()).isTrue();
        assertThat(result.reason()).isEqualTo(expectedReason);
        assertThat(result.classification()).isEqualTo(new TicketClassification(
                TicketCategory.OTHER, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.0));
    }

    @Test
    void refusalStopReason_fallsBackWithoutParsing() {
        // stop_reason=refusal arrives with EMPTY content — parsing first would throw.
        stubResponse(StopReason.REFUSAL, null);

        assertIsFallback(service().classify("some ticket"), ReviewReason.REFUSED);
    }

    @Test
    void maxTokensStopReason_fallsBackWithoutParsing() {
        // A max_tokens cutoff means truncated JSON; the gate must trip before .text().
        stubResponse(StopReason.MAX_TOKENS, null);

        assertIsFallback(service().classify("some ticket"), ReviewReason.TRUNCATED);
    }

    // The two stop_reason failures above share one gate but must NOT share one reason: a refusal is
    // the model declining, a max_tokens cutoff is our own cost fuse blowing. Different causes,
    // different fixes, so different constants.
    @Test
    void endTurnWithNoTextBlock_fallsBackAsMalformed() {
        stubResponse(StopReason.END_TURN, null);

        assertIsFallback(service().classify("some ticket"), ReviewReason.MALFORMED_RESPONSE);
    }

    @Test
    void lowConfidence_fallsBackForHumanReview() {
        // Well-formed, schema-valid answer — but below the 0.6 trust floor.
        stubResponse(StopReason.END_TURN,
                """
                {"category":"BILLING","urgency":"HIGH","intent":"REQUEST_ACTION","confidence":0.45}
                """);

        // LOW_CONFIDENCE, emphatically not DEPENDENCY_UNAVAILABLE: the model answered and was honest
        // about being unsure. The eval scores this ticket normally — calibration is a judgment worth
        // measuring, and excluding it as "degraded" would hide the model's best behaviour.
        assertIsFallback(service().classify("some ticket"), ReviewReason.LOW_CONFIDENCE);
    }

    @Test
    void confidentClassification_passesThroughWithoutHumanReview() {
        stubResponse(StopReason.END_TURN,
                """
                {"category":"RETURNS_AND_REFUNDS","urgency":"MEDIUM","intent":"GET_INFORMATION","confidence":0.92}
                """);

        ClassificationResult result = service().classify("How long do I have to return something?");

        assertThat(result.needsHumanReview()).isFalse();
        // The invariant: reason == NONE if and only if needsHumanReview == false.
        assertThat(result.reason()).isEqualTo(ReviewReason.NONE);
        assertThat(result.classification()).isEqualTo(new TicketClassification(
                TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.92));
    }

    @Test
    void outOfRangeConfidence_isClampedNotRejected() {
        // Schema guarantees a number, not a probability: 1.4 is semantically out of range
        // but the labels are still usable — clamp to 1.0 and keep the classification.
        stubResponse(StopReason.END_TURN,
                """
                {"category":"ACCOUNT","urgency":"CRITICAL","intent":"REPORT_PROBLEM","confidence":1.4}
                """);

        ClassificationResult result = service().classify("I think my account was hacked");

        assertThat(result.needsHumanReview()).isFalse();
        assertThat(result.reason()).isEqualTo(ReviewReason.NONE);
        assertThat(result.classification().confidence()).isEqualTo(1.0);
        assertThat(result.classification().category()).isEqualTo(TicketCategory.ACCOUNT);
    }
}
