package org.aura.aura.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The embedding SPACE, bound from {@code aura.embedding.*} — as distinct from
 * {@link VoyageProperties}, which owns the embedding TRANSPORT.
 *
 * <p>The split is not cosmetic. Which model AURA calls is a property of the provider and can change
 * without touching a byte of stored data; how WIDE the vectors are is a property of AURA's own schema,
 * and changing it invalidates the entire corpus. Two different blast radii, two different config
 * blocks.
 *
 * <h2>Why this is one field and still worth a class</h2>
 * The number 1024 exists in three places that cannot see each other: {@code vector(1024)} in
 * {@code V2__create_kb_chunks.sql}, {@code @Array(length = 1024)} on {@code KbChunk}, and this
 * property. A migration cannot read a Java constant and Hibernate cannot read SQL, so the duplication
 * is irreducible — which makes the interesting question not "how do we avoid three copies" but "what
 * happens when they disagree". The answer is deliberately not "nothing": {@code @Positive} catches the
 * nonsense values at binding time, and
 * {@link org.aura.aura.store.EmbeddingDimensionCheck} compares this value against the live column at
 * every boot. This class is the named, validated place that check has something to compare against.
 */
@Validated
@ConfigurationProperties(prefix = "aura.embedding")
public record EmbeddingProperties(

        @Positive(message = "aura.embedding.dimension must be a positive vector width — it is the "
                + "declared dimension of kb_chunks.embedding and of every vector Voyage returns")
        int dimension
) {
}
