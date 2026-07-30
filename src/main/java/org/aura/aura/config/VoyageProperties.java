package org.aura.aura.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The Voyage embedding transport + chunking configuration, bound from {@code voyage.*} and validated
 * at startup.
 *
 * <p><b>Model names are configuration, not literals.</b> {@code voyage-4-large} and
 * {@code voyage-4-lite} never appear as string constants in the client — the same externalization
 * lesson as the system prompts (ADR-007a) and the Anthropic transport (Day 11). It matters more here
 * than it looks: every stored vector in pgvector is meaningful ONLY relative to the model that
 * produced it, so changing the family in this file is not a config tweak — it obsoletes the entire
 * stored corpus and requires a full re-embed. Keeping the name in one validated place is what makes
 * that consequence visible at review time instead of discoverable in production.
 *
 * <p><b>Fail-fast on a missing key</b> (the Day 11 pattern): {@code apiKey} is {@code @NotBlank}, so a
 * blank or absent {@code VOYAGE_API_KEY} fails the context at binding time with a message naming the
 * env var — instead of booting healthy and 401-ing on the first embedding call.
 */
@Validated
@ConfigurationProperties(prefix = "voyage")
public record VoyageProperties(

        @NotBlank(message = "VOYAGE_API_KEY must be set (it binds to voyage.api-key) — "
                + "AURA cannot embed documents or queries without an API key")
        String apiKey,

        String baseUrl,

        // The premium model, used on the ONE-TIME offline ingestion lane (Day 15). Higher quality per
        // vector, paid for once per document.
        String documentModel,

        // The economy model, used on the per-ticket online lane. Paid for on every customer query, so
        // its cost is multiplied by traffic — see VoyageEmbeddingClient for the asymmetric routing.
        String queryModel,

        Duration connectTimeout,
        Duration readTimeout,

        int maxChunkChars,
        int overlapChars
) {

    /**
     * The shared-embedding-space family both models must belong to. Asymmetric embedding — a different
     * model for documents than for queries — is legal ONLY when both models were trained to project
     * into the SAME vector space, which for Voyage means the same major family generation.
     */
    static final String FAMILY_PREFIX = "voyage-4";

    public VoyageProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.voyageai.com";
        if (documentModel == null || documentModel.isBlank()) documentModel = "voyage-4-large";
        if (queryModel == null || queryModel.isBlank()) queryModel = "voyage-4-lite";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
        // Primitives bind to 0 when the property is absent (a slice test that sets only the key), so
        // "not configured" and "explicitly zero" are indistinguishable here — both are read as "use the
        // default". Nothing useful is lost: a zero chunk cap has no valid reading at all, and a zero
        // overlap is a deliberate retrieval regression that should be a change to the chunker's design,
        // not something a stray unset property can switch on silently.
        if (maxChunkChars <= 0) maxChunkChars = 2000;
        if (overlapChars <= 0) overlapChars = 300;
    }

    /**
     * The family-boundary rule, turned from a soft convention into a HARD startup check.
     *
     * <p>Using two different models on the two lanes is the whole point of the asymmetric design — but
     * it is safe only inside one shared embedding space. Mix families (say {@code voyage-4-large} for
     * documents and {@code voyage-3-lite} for queries) and NOTHING fails: the request succeeds, the
     * dimensions may even match, cosine similarity returns a number, and the top-k results are
     * silently meaningless. There is no exception to catch and no test that naturally notices, because
     * every layer is behaving exactly as specified. That is the worst failure shape there is, so we
     * pay for it at startup: a family mismatch refuses to boot.
     */
    @AssertTrue(message = "voyage.document-model and voyage.query-model must belong to the same "
            + FAMILY_PREFIX + " family — asymmetric models are only comparable inside one shared "
            + "embedding space, and a cross-family pair yields silently meaningless similarity scores")
    public boolean isModelFamilyConsistent() {
        return documentModel.startsWith(FAMILY_PREFIX) && queryModel.startsWith(FAMILY_PREFIX);
    }

    /**
     * The chunker packs each sub-chunk to {@code maxChunkChars - overlapChars} and then prepends the
     * overlap, so an overlap at or above the cap leaves no room for content and the split would never
     * terminate usefully.
     */
    @AssertTrue(message = "voyage.overlap-chars must be smaller than voyage.max-chunk-chars — "
            + "the chunker reserves the overlap budget out of the chunk cap")
    public boolean isOverlapSmallerThanChunkCap() {
        return overlapChars < maxChunkChars;
    }
}
