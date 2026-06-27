package org.aura.aura.conversation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives a scripted three-turn conversation against {@link ConversationService} once the Spring
 * context is ready, so the Day 2 multi-turn memory can be observed end-to-end without a web layer.
 *
 * <p>The same sessionId is reused across all turns, which is the whole point: it proves the service
 * accumulates context server-side and that turn 3 can "see" the order number from turn 2.
 */
@Component
public class ConversationRunner implements CommandLineRunner {

    // Constructor injection (not field @Autowired) so the dependency is final and Spring fails fast
    // at startup if the service can't be wired, rather than NPE-ing on first use.
    private final ConversationService service;

    // The raw client, injected alongside the service so the temporary breakTest() below can build a
    // deliberately malformed request and hit the API directly, bypassing ConversationService (which
    // would never let an invalid transcript through).
    private final AnthropicClient client;

    public ConversationRunner(ConversationService service, AnthropicClient client) {
        this.service = service;
        this.client = client;
    }

    @Override
    public void run(String... args) throws Exception {
        // Three INDEPENDENT tickets, one per scenario the system prompt's examples cover:
        // return-policy, where-is-my-order, and an angry refund demand. Each gets its OWN session
        // id so context can't bleed between unrelated customers — this is a behavior check on the
        // in-distribution cases, not a multi-turn memory demo.
        String[] tickets = {
                "Hi, what's your return policy? I bought a jacket last week.",
                "Where is my order #88231? It still hasn't arrived.",
                "This is ridiculous. Just refund me $200 right now."
        };

        for (int i = 0; i < tickets.length; i++) {
            // Fresh session per ticket: ticket 2 must not see ticket 1's transcript.
            String sessionId = "example-ticket-" + (i + 1);
            String response = service.chat(sessionId, tickets[i]);

            System.out.println("=== Example ticket " + (i + 1) + " ===");
            System.out.println("User: " + tickets[i]);
            System.out.println("AURA: " + response);
            System.out.println();
        }

        // TEMPORARY: demonstrate what the API does when the alternating-turn invariant is violated.
//        breakTest();
    }

    /**
     * TEMPORARY diagnostic — delete once the failure mode has been observed.
     *
     * <p>Sends a transcript with two consecutive USER messages straight to the API. The Messages API
     * requires roles to strictly alternate user/assistant/user/…, so the server rejects this with an
     * HTTP 400 (invalid_request_error) instead of returning a completion. This is exactly the failure
     * {@link ConversationService} is designed to prevent by always appending the assistant reply
     * before the next user turn.
     */
//    private void breakTest() {
//        // INTENTIONAL BREAK: violate the alternating-turn invariant
//        // Two consecutive user messages — watch the 400
//        List<MessageParam> brokenHistory = List.of(
//                MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("First.").build(),
//                MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("Also user.").build() // ← wrong
//        );
//
//        // Same model/limits shape as a real turn; only the message sequence is malformed.
//        MessageCreateParams params = MessageCreateParams.builder()
//                .model("claude-haiku-4-5-20251001")
//                .maxTokens(1024L)
//                .messages(brokenHistory)
//                .build();
//
//        System.out.println("--- breakTest: firing invalid two-user-message request ---");
//        try {
//            // Fire directly via the injected client, bypassing ConversationService entirely.
//            Message response = client.messages().create(params);
//            // Unreachable in practice — the server rejects the request before any completion is made.
//            System.out.println("breakTest unexpectedly SUCCEEDED: " + response);
//        } catch (AnthropicServiceException e) {
//            // Catch so the deliberate failure is observable without crashing the JVM. statusCode()
//            // is the HTTP status (expected 400); the message carries the API's invalid_request_error.
//            System.out.println("breakTest got expected API error — HTTP " + e.statusCode());
//            System.out.println(e.getMessage());
//        }
//    }
}