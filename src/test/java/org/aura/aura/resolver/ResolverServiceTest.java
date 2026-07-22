package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.aura.aura.ResolverPromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResolverService}'s wiring: what it retrieves, what it copies from the model,
 * and what it derives itself.
 *
 * <p>The Claude call is stubbed at the API-response seam, so these exercise OUR logic rather than the
 * model's wording — deterministic, and needing neither an API key nor the network. Content blocks are
 * real SDK objects wrapping raw JSON, so the typed {@code text()} deserialization path (JSON → record)
 * runs for real instead of being mocked away. Constructed directly (no Spring proxy), so the
 * {@code @Retry}/{@code @CircuitBreaker} annotations do NOT fire here; that behaviour is proven
 * separately in {@link ResolverResilienceTest}.
 */
class ResolverServiceTest {

    private static final String RETURNS_TICKET = "How long do I have to return something?";

    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);

    private ResolverService resolver() {
        ResolverPromptProvider prompts = mock(ResolverPromptProvider.class);
        when(prompts.systemPrompt()).thenReturn("test system prompt");
        // Real KB on purpose: the point of several tests below is that a returns-phrased ticket
        // actually retrieves kb-returns through the naive keyword filter.
        return new ResolverService(
                client, prompts, new HardcodedKnowledgeBase(), CircuitBreakerRegistry.ofDefaults());
    }

    // StructuredMessage is final; Mockito 5's inline mock maker (Boot's default) handles it.
    @SuppressWarnings("unchecked")
    private void stubResponse(StopReason stopReason, String json) {
        StructuredMessage<ResolverOutput> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.ofNullable(stopReason));
        // Real block, not a mock: text() must genuinely deserialize the envelope.
        TextBlock textBlock = TextBlock.builder().text(json).citations(List.of()).build();
        StructuredContentBlock<ResolverOutput> block =
                new StructuredContentBlock<>(ResolverOutput.class, ContentBlock.ofText(textBlock));
        when(message.content()).thenReturn(List.of(block));
        when(client.messages().create(any(StructuredMessageCreateParams.class))).thenReturn(message);
    }

    // The grounding receipt: kb-returns backed the answer. Empty would mean retrieval whiffed and the
    // answer came from the prompt alone.
    @Test
    void resolve_returnsQuestion_recordsKnowledgeBaseSource() {
        stubResponse(StopReason.END_TURN, "{\"reply\":\"Within 30 days.\",\"escalate\":false}");

        Resolution resolution = resolver().resolve(RETURNS_TICKET);

        assertThat(resolution.answer()).isEqualTo("Within 30 days.");
        assertThat(resolution.sourcesUsed()).containsExactly("kb-returns");
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
    }

    // The Day 10 point of the whole migration: the escalation verdict is now DATA copied from the
    // model, not prose a human has to read. Before this, both of these tickets returned RESOLVED and
    // were indistinguishable to any caller.
    @Test
    void resolve_copiesTheModelsEscalateVerdict() {
        stubResponse(StopReason.END_TURN,
                "{\"reply\":\"I'm escalating this to a specialist.\",\"escalate\":true}");

        Resolution escalating = resolver().resolve(RETURNS_TICKET);

        assertThat(escalating.escalate()).isTrue();
        // Still RESOLVED: the model chose to escalate on a HEALTHY call. ESCALATED_TO_HUMAN would mean
        // the dependency failed, which is a different fact on a different channel.
        assertThat(escalating.status()).isEqualTo(ResolutionStatus.RESOLVED);
    }

    @Test
    void resolve_carriesEscalateFalseThrough() {
        stubResponse(StopReason.END_TURN, "{\"reply\":\"Within 30 days.\",\"escalate\":false}");

        assertThat(resolver().resolve(RETURNS_TICKET).escalate()).isFalse();
    }

    // sourcesUsed is DERIVED from retrieval, never read from the model — that is what makes it
    // evidence rather than a claim, and it is why sourcesUsed is absent from ResolverOutput's schema.
    // Here retrieval whiffs entirely, and the receipt is honestly empty even though the model replied
    // with full confidence.
    @Test
    void resolve_sourcesUsedReflectsRetrieval_notTheModelsConfidence() {
        stubResponse(StopReason.END_TURN,
                "{\"reply\":\"ShopFast accepts returns within 30 days.\",\"escalate\":false}");

        Resolution resolution = resolver().resolve("Who is ShopFast's CEO?");

        assertThat(resolution.sourcesUsed()).isEmpty();
    }

    // A truncated or refused response has no usable reply, and the resolver has no honest neutral
    // answer to invent — so it surfaces rather than degrading. ESCALATED_TO_HUMAN is reserved for
    // dependency health and would be a lie here.
    @Test
    void resolve_surfacesRatherThanDegradingOnBadStopReason() {
        stubResponse(StopReason.MAX_TOKENS, "{\"reply\":\"truncated mid-sent");

        assertThatThrownBy(() -> resolver().resolve(RETURNS_TICKET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max_tokens");
    }

    // GUARD TEST, not ceremony. Both transports share paramsFor so the prompt and schema can't drift,
    // and the streaming path unwraps those params via rawParams(). If output_config failed to survive
    // that unwrapping, the streamed request would silently revert to prose, StreamingReplyExtractor
    // would never match a "reply" key, and every SSE customer would receive an EMPTY stream — a total
    // failure with no exception anywhere to catch it. This assertion is the only thing standing
    // between that bug and production.
    @Test
    void buildStreamingParams_carriesOutputConfigSoTheStreamStaysSchemaEnforced() {
        MessageCreateParams params = resolver().buildStreamingParams(RETURNS_TICKET);

        assertThat(params.outputConfig()).isPresent();
    }
}
