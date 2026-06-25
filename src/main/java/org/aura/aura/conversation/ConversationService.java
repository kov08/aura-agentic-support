package org.aura.aura.conversation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Holds the multi-turn conversation state for AURA and brokers each turn to the Claude API.
 *
 * <p>The Messages API is stateless — there is no server-side session to attach to — so this
 * service is the only place conversation memory lives. Every call rebuilds the full context
 * window from the in-memory history before sending it.
 */
@Service
@Slf4j
public class ConversationService {

    // System prompt is fixed at compile time so it lives at the front of every request's prefix.
    // Keeping it constant (no interpolated dates/IDs) means it stays byte-identical across calls,
    // which is the precondition for prompt-cache hits on the shared prefix.
    private static final String SYSTEM_PROMPT = """
            You are AURA, a customer support agent for ShopFast, an e-commerce platform.
            You help customers with: order status and tracking, returns and refunds,
            account and billing questions.

            Rules:
            - Be concise, professional, and empathetic.
            - Never fabricate order data or tracking information.
            - If you don't know something, say so and offer to escalate.
            - Always confirm the customer's order number before discussing a specific order.
            """;

    // Pinned to the dated Haiku snapshot as a String literal: support traffic is high-volume and
    // latency-sensitive, so the cheapest/fastest tier fits. The dated ID (not the floating alias)
    // pins behavior so a model refresh can't silently change responses under us.
    private static final String MODEL = "claude-haiku-4-5-20251001";

    // ConcurrentHashMap because a web layer will call chat()/reset() from multiple request threads
    // concurrently; a plain HashMap could corrupt under concurrent structural modification.
    // NOTE: the inner List per session is NOT itself thread-safe — see the synchronized block in chat().
    private final Map<String, List<MessageParam>> sessions = new ConcurrentHashMap<>();

    // AtomicLong (not a plain long) because these counters are incremented from concurrent request
    // threads; atomic add-and-read avoids lost updates without a lock around the whole accumulation.
    private final AtomicLong cumulativeInputTokens = new AtomicLong(0);
    private final AtomicLong cumulativeOutputTokens = new AtomicLong(0);

    // Constructor injection (not field @Autowired) so the dependency is final, the object is fully
    // initialized once constructed, and the service is trivially unit-testable with a mock client.
    private final AnthropicClient client;

    public ConversationService(AnthropicClient client) {
        this.client = client;
    }

    /**
     * Runs one conversational turn for the given session and returns Claude's text reply.
     */
    public String chat(String sessionId, String userMessage) {
        // computeIfAbsent so a brand-new sessionId lazily gets its own history without a separate
        // "create session" call — the first message a customer sends implicitly starts the session.
        List<MessageParam> history = sessions.computeIfAbsent(sessionId, id -> new ArrayList<>());

        // Synchronize on the per-session list: ConcurrentHashMap protects the map, but two turns
        // racing on the SAME session would otherwise interleave appends and corrupt the transcript.
        // Per-session locking keeps unrelated sessions fully parallel.
        synchronized (history) {
            // Append the user turn BEFORE the call so the invariant holds: history always ends with
            // the latest user message, which is exactly what the next request must send.
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userMessage)
                    .build());

            // Pass the full history on every call — the API is stateless, so the context window is
            // rebuilt from scratch each request; the model only "remembers" what we resend here.
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    // maxTokens caps the OUTPUT only; 1024 is ample for a support reply and bounds cost.
                    .maxTokens(1024L)
                    // system is sent out-of-band from messages so it isn't mistaken for a user turn
                    // and stays a stable, cacheable prefix.
                    .system(SYSTEM_PROMPT)
                    .messages(history)
                    .build();

            // Blocking call — we need the whole reply before we can store it and answer the caller.
            Message response = client.messages().create(params);

            // content() is a list of typed blocks (text, thinking, tool_use, ...). Filter to text via
            // the Optional-returning .text() accessor so a non-text block can't throw, then join: a
            // single reply can span multiple text blocks and the caller wants one contiguous string.
            String reply = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            // Append the assistant reply IMMEDIATELY. If this step is skipped, the next turn sends a
            // history ending in an assistant-less user/user sequence — a broken, drifting transcript.
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(reply)
                    .build());

            // Read usage straight off the response — it's the authoritative billing/observability
            // source, not a client-side estimate.
            long inTokens = response.usage().inputTokens();
            long outTokens = response.usage().outputTokens();

            // addAndGet so the logged session totals reflect THIS turn's contribution atomically;
            // accumulate first, then log, so the numbers printed match what the counters now hold.
            long totalIn = cumulativeInputTokens.addAndGet(inTokens);
            long totalOut = cumulativeOutputTokens.addAndGet(outTokens);

            log.info("Turn [{}] — in: {}, out: {}. Session total — in: {}, out: {}",
                    sessionId, inTokens, outTokens, totalIn, totalOut);

            return reply;
        }
    }

    /**
     * Forgets all history for a session.
     */
    public void reset(String sessionId) {
        // remove() rather than clearing the list so a stale empty list isn't left behind holding
        // memory; the next chat() for this id will lazily recreate fresh state.
        sessions.remove(sessionId);
        log.info("Session {} reset", sessionId);
    }

    /**
     * Returns a read-only snapshot of a session's history (empty if the session is unknown).
     */
    public List<MessageParam> getHistory(String sessionId) {
        List<MessageParam> history = sessions.get(sessionId);
        // Return an empty list for an unknown session so callers never get null and can't NPE.
        if (history == null) {
            return Collections.emptyList();
        }
        // unmodifiableList so external callers can read but not mutate our internal transcript —
        // the only writer of session state is chat(), under the per-session lock.
        return Collections.unmodifiableList(history);
    }
}