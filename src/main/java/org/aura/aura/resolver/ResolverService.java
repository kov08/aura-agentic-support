package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import org.aura.aura.ResolverPromptProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ResolverService {

    private final AnthropicClient client;
    private final ResolverPromptProvider prompts;
    private final KnowledgeBase knowledgeBase;

    public ResolverService (AnthropicClient client, ResolverPromptProvider prompts, KnowledgeBase knowledgeBase){
        this.client = client;
        this.prompts = prompts;
        this.knowledgeBase = knowledgeBase;
    }

    public Resolution resolve(String ticket){
        List<KbEntry> hits = knowledgeBase.retrieve(ticket);

        Message message = client.messages().create(paramsFor(ticket, hits));

        String answer = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        return new Resolution(answer, hits.stream().map(KbEntry::id).toList());
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
