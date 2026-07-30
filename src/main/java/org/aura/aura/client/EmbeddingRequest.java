package org.aura.aura.client;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The wire request for {@code POST /v1/embeddings}.
 *
 * <p>Voyage's API is snake_case; Java is camelCase. The mapping is declared explicitly with
 * {@code @JsonProperty} rather than switched on globally with a snake_case naming strategy, because a
 * global strategy would silently rewrite the field names of every other DTO in the app — including
 * ones whose wire format is already correct. One annotation on the one field that needs it is a local
 * decision with a local blast radius.
 *
 * @param input     the texts to embed; a list even for a single query, because the API takes a batch
 * @param model     the model id, which differs per lane (see {@link VoyageEmbeddingClient})
 * @param inputType {@code "document"} or {@code "query"} — see {@link EmbeddingInputType}
 */
public record EmbeddingRequest(
        List<String> input,
        String model,
        @JsonProperty("input_type") String inputType
) {}
