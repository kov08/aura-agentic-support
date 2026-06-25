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
        // A fixed sessionId reused for every turn below — this is what makes the run multi-turn:
        // each chat() call appends to the same in-memory transcript keyed by this id.
        String sessionId = "demo-session-001";

        // A scripted customer journey: each later turn deliberately omits context (the order number,
        // the elapsed days) so the reply is only coherent if prior turns were remembered.
        String[][] script = {
                {"Hi, I need help with my recent order."},
                {"My order number is #ORG-4892. It hasn't arrived yet."},
                {"It's been 8 days. What are my options?"}
        };

        for (int i = 0; i < script.length; i++) {
            String userMessage = script[i][0];

            // chat() does the heavy lifting: appends the user turn, resends the full history to
            // Claude, and stores the reply — so each iteration grows the transcript by two messages.
            String response = service.chat(sessionId, userMessage);

            // i + 1 so the printed turn numbers are human-friendly (Turn 1..3, not 0..2).
            System.out.println("--- Turn " + (i + 1) + " ---");
            System.out.println("User: " + userMessage);
            System.out.println("AURA: " + response);
            System.out.println();
        }

        // Three turns × (one user + one assistant message) = six messages. Printing the count is a
        // cheap assertion that history accumulated correctly rather than resetting between turns.
        int historySize = service.getHistory(sessionId).size();
        System.out.println("History size: " + historySize + " messages (expected 6)");

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