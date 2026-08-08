package org.aura.aura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shared, STATELESS fixtures for the Day 11 transport integration tests (AnthropicTransportIT,
 * RedisDegradationIT): it only builds canned wire messages and drives the controller. The MockWebServer
 * and Redis lifecycle deliberately stay in each IT class, because they differ — RedisDegradationIT kills
 * its Redis mid-test, so the two must never share a container.
 *
 * <p>Named neither {@code *Test} nor {@code *IT} on purpose, so neither Surefire nor Failsafe tries to
 * run it as a test class.
 */
final class AnthropicMessages {

    private AnthropicMessages() {}

    static final ObjectMapper MAPPER = new ObjectMapper();

    // The canned resolver reply, asserted verbatim on the happy/recovery paths to prove the structured
    // payload round-tripped through the SDK parser, the resolver, and the controller unchanged.
    static final String RESOLVER_REPLY =
            "Your order #88231 shipped Wednesday and should arrive within two business days.";

    /**
     * A shape-valid Anthropic Messages API 200 response. Native structured outputs (output_config)
     * return the schema payload as the TEXT of an ordinary {@code text} content block with
     * {@code stop_reason=end_turn}; the SDK's {@code StructuredMessage} then deserializes that text into
     * our record ({@code TicketClassification} / {@code ResolverOutput}). The envelope mirrors the
     * documented Messages API response (docs.anthropic.com/en/api/messages) — no captured fixture exists
     * in the repo to copy from, so it is built to the documented schema and validated by round-tripping
     * through the SDK's own parser (IT-1 asserting RESOLVED is that live check).
     */
    static MockResponse ok200(String model, String structuredPayload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_it");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        ObjectNode block = root.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", structuredPayload);   // Jackson escapes the inner JSON as a string value
        root.put("stop_reason", "end_turn");
        root.putNull("stop_sequence");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 100);
        usage.put("output_tokens", 40);
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(writeString(root));
    }

    // Classifier (Haiku) success: a confident, well-formed TicketClassification (>= the 0.6 floor).
    static MockResponse classifierOk() {
        return ok200("claude-haiku-4-5",
                "{\"category\":\"ORDER_STATUS\",\"urgency\":\"HIGH\",\"intent\":\"GET_INFORMATION\",\"confidence\":0.95}");
    }

    /**
     * Resolver (Sonnet) success: a {@code ResolverOutput} carrying a customer reply, escalate=false,
     * and — since Day 16 — a grounding verdict plus the ids it claims to have used.
     *
     * <p>The ids are a PARAMETER rather than a constant because the G4 gate checks them against the
     * chunks retrieval actually supplied for that request, which every calling test controls
     * separately. A hardcoded id here would be a foreign citation in every scenario, and the whole
     * transport suite would escalate for a reason that has nothing to do with transport.
     *
     * <p>All four fields are always present: {@code grounded} is a primitive boolean, so an envelope
     * that omits it is a hard parse failure rather than a silent false.
     */
    static MockResponse resolverOk(String... citedChunkIds) {
        String citations = Arrays.stream(citedChunkIds)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return ok200("claude-sonnet-4-5",
                "{\"reply\":\"" + RESOLVER_REPLY + "\",\"citations\":" + citations
                        + ",\"escalate\":false,\"grounded\":true}");
    }

    // A resolver success whose BODY is delayed — the "hang" simulation. The delay lives in the script;
    // the test asserts an OUTCOME, never elapsed time.
    static MockResponse resolverOkDelayed(Duration delay, String... citedChunkIds) {
        return resolverOk(citedChunkIds).setBodyDelay(delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    // An Anthropic error response. The SDK maps by HTTP status: 429 -> RateLimitException,
    // 5xx incl. 529 overloaded -> InternalServerException, 4xx (e.g. 400) -> BadRequestException.
    static MockResponse error(int status, String type, String message) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}");
    }

    // Drive the REAL controller over HTTP (no Mockito): POST /api/v1/tickets/{id}/resolve. The client
    // is built by each IT with an error handler that does NOT throw on 4xx/5xx, so IT-4 can inspect the
    // 500 problem-detail response as data (Spring Boot 4 removed TestRestTemplate; RestClient from
    // spring-web is the no-extra-dependency replacement).
    static ResponseEntity<String> resolve(RestClient client, String ticketId, String message) {
        return client.post()
                .uri("/api/v1/tickets/{id}/resolve", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message))
                .retrieve()
                .toEntity(String.class);
    }

    // Read one top-level string field out of the JSON response body.
    static String field(ResponseEntity<String> resp, String name) {
        try {
            JsonNode body = MAPPER.readTree(resp.getBody());
            return body.path(name).asText();
        } catch (Exception e) {
            throw new RuntimeException("response body was not JSON: " + resp.getBody(), e);
        }
    }

    private static String writeString(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
