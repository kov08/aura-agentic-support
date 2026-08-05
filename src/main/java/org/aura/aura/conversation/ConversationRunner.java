package org.aura.aura.conversation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import org.aura.aura.resolver.CachedResolutionService;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Drives a scripted three-turn conversation against {@link ConversationService} once the Spring
 * context is ready, so the Day 2 multi-turn memory can be observed end-to-end without a web layer.
 *
 * <p>The same sessionId is reused across all turns, which is the whole point: it proves the service
 * accumulates context server-side and that turn 3 can "see" the order number from turn 2.
 *
 * <p>@Profile("!test"): this is a dev-time demo that fires a LIVE Claude call on startup. Excluding it
 * from the "test" profile keeps it out of any @SpringBootTest context (e.g. AuraApplicationTests),
 * which would otherwise make a real API call — and fail with a 401 whenever no key is present (CI).
 */
@Profile("!test")
@Component
public class ConversationRunner implements CommandLineRunner {

    // Constructor injection (not field @Autowired) so the dependency is final and Spring fails fast
    // at startup if the service can't be wired, rather than NPE-ing on first use.
//    private final ConversationService service;

    // The raw client, injected alongside the service so the temporary breakTest() below can build a
    // deliberately malformed request and hit the API directly, bypassing ConversationService (which
    // would never let an invalid transcript through).
//    private final AnthropicClient client;

//    public ConversationRunner(ConversationService service, AnthropicClient client) {
//        this.service = service;
//        this.client = client;
//    }

    // Day 14: the demo drives CachedResolutionService, not ResolverService. That is the bean the
    // controller calls, so what this prints is the ACTUAL customer path — retrieve, key, cache-aside,
    // ask — rather than a shortcut through the middle of it. A demo that exercises a path production
    // does not use can only ever prove that the path production does not use works.
    private final CachedResolutionService resolutions;
    public ConversationRunner(CachedResolutionService resolutions){
        this.resolutions = resolutions;
    }

    @Override
    public void run(String... args) throws Exception {
        // Tickets fed through the Day 4 resolver. Each is resolved independently — resolve() is
        // stateless (retrieve → inject → ask), so there's no cross-ticket context to isolate.
        String[] tickets = {
                "How long do I have to return something?"
//                "Hi, what's your return policy? I bought a jacket last week.",
//                "Where is my order #88231? It still hasn't arrived.",
//                "This is ridiculous. Just refund me $200 right now."
        };

        for (int i = 0; i < tickets.length; i++) {
            // Semantic retrieval from pgvector, then the cache-aside resolve. Stateless per ticket.
            Resolution resolution = resolutions.resolve(new ResolveTicketRequest(tickets[i]));

            System.out.println("=== Example ticket " + (i + 1) + " ===");
            System.out.println("User: " + tickets[i]);
            System.out.println("AURA: " + resolution.answer());
            // The grounding LEDGER: what was put in front of the model, with the distance that ranked
            // it. Printing the distance rather than just the breadcrumb is what makes this readable as
            // evidence — cosine distance is relative and never calibrated, so retrieval always returns
            // a best match, and the number is the only thing that says whether "best" meant anything.
            System.out.println("Sources provided:");
            if (resolution.sourcesProvided().isEmpty()) {
                System.out.println("   (none — this answer is ungrounded)");
            }
            resolution.sourcesProvided().forEach(source -> System.out.printf(Locale.ROOT,
                    "   %.4f  %s  [%s]%n", source.distance(), source.breadcrumb(), source.chunkId()));
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