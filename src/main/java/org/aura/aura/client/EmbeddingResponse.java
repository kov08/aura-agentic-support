package org.aura.aura.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The wire response for {@code POST /v1/embeddings}.
 *
 * <p><b>Vectors are {@code float[]}, not {@code Double[]} or {@code List&lt;Double&gt;}.</b> Three
 * reasons, in ascending order of importance: a primitive float is 4 bytes against 8 for a double and
 * ~16 for a boxed Double, so the in-memory corpus is a quarter of the size; a primitive array has no
 * per-element pointer chase, which matters for the brute-force scan in the Day 12 demo; and
 * pgvector's storage type is float4, so anything wider is precision we would compute, ship, and then
 * throw away at the database boundary tomorrow. Matching the destination type here means no lossy
 * conversion appears later in a place where it looks like a bug.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is deliberate on both records: a provider
 * adding a field to its response is a routine, backwards-compatible event, and it must not fail our
 * deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(
        List<Datum> data,
        String model,
        Usage usage
) {

    /**
     * One embedded input. {@code index} is the position of the corresponding entry in the request's
     * {@code input} list — the API does not promise the response array is ordered, so the client sorts
     * by it rather than assuming.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Datum(float[] embedding, int index) {}

    /** Token accounting, kept so the ingestion run (Day 15) can log what a full re-embed actually cost. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("total_tokens") long totalTokens) {}
}
