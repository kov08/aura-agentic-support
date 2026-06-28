package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.aura.aura.ResolverPromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Happy-path test for {@link ResolverService}: a returns-phrased ticket should be grounded in the
 * knowledge base, and {@link Resolution#sourcesUsed()} is the receipt that proves it.
 *
 * <p>The Claude call is stubbed out — this isolates the resolver's retrieve-then-record wiring, not
 * the model's wording, so the test is deterministic and needs neither an API key nor the network.
 */
class ResolverServiceTest {

    @Test
    void resolve_returnsQuestion_recordsKnowledgeBaseSource() {
        // Deep stubs let client.messages().create(...) be mocked without naming the intermediate
        // service type. The response content is irrelevant here (sourcesUsed is derived from the
        // retrieval, not the reply), so an empty content list is enough.
        AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of());
        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message);

        ResolverPromptProvider prompts = mock(ResolverPromptProvider.class);
        when(prompts.systemPrompt()).thenReturn("test system prompt");

        // Real KB on purpose: the point is that a returns-phrased ticket actually retrieves
        // kb-returns through the naive keyword filter.
        ResolverService resolver = new ResolverService(client, prompts, new HardcodedKnowledgeBase());

        Resolution resolution = resolver.resolve("How long do I have to return something?");

        // The grounding receipt: kb-returns backed the answer. Empty would mean retrieval whiffed
        // and the answer came from the prompt alone.
        assertThat(resolution.sourcesUsed()).containsExactly("kb-returns");
    }
}
