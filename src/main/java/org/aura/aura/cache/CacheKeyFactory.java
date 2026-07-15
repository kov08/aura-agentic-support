package org.aura.aura.cache;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// ADR-019: the Redis response cache key. Every ANSWER-AFFECTING input to a resolution is folded
// into one hash so that "same question, same model, same prompt" hits and anything else misses.
@Component
public class CacheKeyFactory {

    /**
     * Bump to v2 to bulk-invalidate every existing entry. This readable prefix is the
     * MANUAL invalidation lever; content changes need no lever at all — any edit to a
     * hashed field produces a different key automatically (invalidation by construction).
     */
    private static final String KEY_VERSION = "v1";
    private static final String SEP = "\n--\n";

    // ADR-019 mapping note. The prompt's template says: "if classification output feeds the resolver
    // request, the classifier's model ID + rendered system prompt are answer-affecting inputs and MUST
    // be fields here too." In AURA today they are NOT: TicketController classifies and resolves
    // independently — the resolver is called on the raw ticket text, and the classification only rides
    // along in the response (see TicketController.resolve). So the classifier's model/prompt are not
    // answer-affecting inputs to the RESOLUTION and are deliberately absent from this key. The day
    // classification starts steering the resolver prompt, both MUST become fields here — otherwise every
    // entry silently serves a resolution built from a since-changed classifier.
    public String resolutionKey(String resolverModelId,
                                String staticSystemPrompt, // the exact text that sits before the cache_control breakpoint
                                String ticketText,
                                double temperature,
                                long maxTokens) {
        // Canonical = ONE fixed serialization. Field order and separator are part of the
        // contract: change either and every live cache entry is silently orphaned.
        // Known theoretical gap, accepted consciously: two free-form fields could alias
        // each other across the separator; a length-prefixed encoding is the fully
        // rigorous fix, but we control the system prompt, so the risk is not real here.
        String canonical = String.join(SEP,
                resolverModelId,
                staticSystemPrompt,
                ticketText.trim(),                 // trim only: whitespace is noise, but case/punctuation carry MEANING —
                String.valueOf(temperature),       // aggressive normalization would serve answers to slightly different questions
                String.valueOf(maxTokens));        // params are answer-affecting: raise maxTokens without keying it and
                                                   // previously-truncated cached answers keep serving forever
        return "aura:resolution:" + KEY_VERSION + ":" + sha256Hex(canonical);
    }

    // Hashing (vs. raw text as key) bounds key size AND keeps customer PII out of the
    // Redis keyspace — keys leak into logs and SCAN output; ticket text must not.
    private String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is JVM-mandated; unreachable", e);
        }
    }
}
