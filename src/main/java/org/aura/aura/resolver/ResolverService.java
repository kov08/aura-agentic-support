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

        // Context
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

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_5)
                .maxTokens(1024L)
                .system(prompts.systemPrompt())
                .addUserMessage(userTurn)
                .build();

        Message message = client.messages().create(params);

        String answer = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        return new Resolution(answer, hits.stream().map(KbEntry::id).toList());
    }
}
