package org.aura.aura.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retrieval POLICY, bound from {@code aura.retrieval.*}: how many candidates the search returns, and
 * how much of the prompt they are allowed to occupy.
 *
 * <p>Under {@code aura.*} rather than beside {@code voyage.*} for the same reason
 * {@link EmbeddingProperties} is: these are not properties of a provider. Voyage does not have an
 * opinion about how many chunks AURA shows Claude — that is AURA's own retrieval design, and the
 * blast radius of changing it is answer quality, not a wire contract.
 *
 * @param k                  how many candidates {@code findNearestWithDistance} returns. This is the
 *                           WIDTH of the candidate pool, not the number of chunks that reach the
 *                           model — packing and dedup both cut into it. Corpus-relative, and
 *                           deliberately so; see the comment on the property in application.yml.
 * @param contextTokenBudget the ceiling, in the SAME approximate token unit {@code kb_chunks
 *                           .token_count} is stored in, on the total size of the packed context
 *                           block. Enforced with integer arithmetic on the stored counts — nothing
 *                           tokenizes anything at request time.
 */
@Validated
@ConfigurationProperties(prefix = "aura.retrieval")
public record RetrievalProperties(

        @Positive(message = "aura.retrieval.k must be positive — a non-positive k asks the database "
                + "for no rows and produces an ungrounded answer with an empty source ledger")
        int k,

        @Positive(message = "aura.retrieval.context-token-budget must be positive — a zero budget "
                + "admits no chunk at all, which silently turns every RAG answer back into an "
                + "un-grounded one with no error anywhere")
        int contextTokenBudget
) {
}
