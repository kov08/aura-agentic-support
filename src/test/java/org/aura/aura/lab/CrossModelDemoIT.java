package org.aura.aura.lab;

import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.util.VectorMath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A LAB experiment, not a test of anything we ship. It deliberately reproduces the failure that the
 * Day 12 startup check is designed to prevent, in the one configuration where that check cannot see
 * it — and prints the result next to a legitimate run so the difference (or the lack of one) is
 * visible.
 *
 * <h2>The scenario</h2>
 * A sloppy migration. Someone re-points the QUERY lane at a new model and ships it, without
 * re-embedding the stored corpus — because re-embedding is the expensive, slow, easy-to-defer half of
 * the job. The store now holds {@code voyage-4-large} vectors while queries arrive as
 * {@code voyage-3.5-lite} vectors. Two different embedding spaces, compared as if they were one.
 *
 * <h2>Why {@link VoyageProperties}' family check does not catch this</h2>
 * It cannot, and that is the point of running this. The check validates the CONFIGURATION at startup.
 * In this scenario the configuration at any single moment is internally consistent — it is the DATA
 * that spans two eras, written by a config that was valid when it ran and has since been changed.
 * The check is a guard on a transition it never observes.
 *
 * <p>This class demonstrates that gap structurally as well as empirically: it builds a second client
 * from a {@code new VoyageProperties(...)} constructed DIRECTLY. Constructing the record bypasses
 * JSR-303 entirely — Bean Validation runs through Spring's binder, not through the canonical
 * constructor — so a cross-family pair that cannot boot the application assembles here without a
 * murmur. Any guard that lives only at startup has this shape.
 *
 * <h2>Why {@link VectorMath} does not catch it either</h2>
 * Both models emit 1024 dimensions, so the dimension guard is satisfied. That guard catches a SHAPE
 * mismatch; this is a SPACE mismatch. Same shape, unrelated coordinate systems, and no arithmetic
 * anywhere can tell the difference — cosine similarity is perfectly well-defined on two vectors that
 * have nothing to do with each other.
 *
 * <p>Tagged {@code manual} like {@link org.aura.aura.SemanticSearchDemoIT}, so it runs only under
 * {@code mvn verify -Pdemo} with a live key. It asserts almost nothing on purpose — its output is the
 * artifact, and its findings are recorded in the README rather than enforced by a green check.
 */
@Tag("manual")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrossModelDemoIT {

    private static final String REFUND_QUERY = "Can I get my money back for a hoodie I bought two weeks ago?";

    // The previous-generation model standing in for "the era the corpus was NOT embedded in". Same
    // 1024 dimensions as voyage-4-large, which is exactly what makes the mismatch undetectable.
    private static final String STALE_QUERY_MODEL = "voyage-3.5-lite";

    // An 8-chunk mini-corpus: breadcrumb + body, in the same shape Chunk.embeddingInput() produces.
    // Hand-written rather than read from kb/ so the experiment has a fixed, inspectable field of 8
    // candidates — 3 refund, 3 shipping, 2 warranty — and the ranking is easy to read at a glance.
    private static final List<String> CORPUS = List.of(
            "Refund Policy > Standard Refund Window\nCustomers have 30 days from the delivery date to request a refund. The delivery date is the date recorded by the carrier.",
            "Refund Policy > Items We Cannot Refund\nPerishable goods, personalised items, digital downloads once revealed, opened hygiene products, and items marked Final Sale.",
            "Refund Policy > Refund Processing Times\nInspection takes up to 3 business days after the item arrives. Cards take 5-10 business days; PayPal 1-3; store credit is immediate.",
            "Shipping Policy > Delivery Speeds and Costs\nStandard is 3-5 business days and free over 35 USD. Express is 2 business days. International Economy is 7-21 business days.",
            "Shipping Policy > Order Processing\nOrders placed before 2:00 PM local warehouse time ship the same business day. Orders after the cut-off ship the next business day.",
            "Shipping Policy > Lost Parcels\nA domestic parcel with no movement for 7 business days is declared lost. We reship at no charge or refund in full, at the customer's choice.",
            "Warranty Policy > Coverage Period\nEvery product carries a 12-month limited warranty from the delivery date against defects in materials and workmanship.",
            "Warranty Policy > Not Covered\nAccidental damage, cosmetic wear, misuse, unauthorised repair, and consumables that wear out by design are not covered.");

    @Autowired VoyageEmbeddingClient legitimate;   // the wired bean: voyage-4-large / voyage-4-lite
    @Autowired VoyageProperties props;

    @Test
    void crossFamilyQueryAgainstAFourEraIndex() {
        assertThat(props.apiKey())
                .as("CrossModelDemoIT makes REAL Voyage calls — export a real VOYAGE_API_KEY")
                .isNotEqualTo("test-key");

        // ---- The "old index": embedded once with the document model, exactly as production would ----
        List<float[]> index = legitimate.embedDocuments(CORPUS);

        // ---- Query side A: legitimate. voyage-4-lite, the configured pair. ----
        float[] legitQuery = legitimate.embedQuery(REFUND_QUERY);

        // ---- Query side B: the sloppy migration. A client whose properties were never validated. ----
        // No Spring, no binder, no @AssertTrue. This object is illegal by our own stated law and
        // constructs without complaint, because the law is enforced at a boundary this code walks past.
        VoyageEmbeddingClient stale = new VoyageEmbeddingClient(new VoyageProperties(
                props.apiKey(), props.baseUrl(),
                props.documentModel(),      // documents unchanged — nobody re-embedded them
                STALE_QUERY_MODEL,          // queries re-pointed — the whole migration, as shipped
                props.connectTimeout(), props.readTimeout(),
                props.maxChunkChars(), props.overlapChars()));
        float[] staleQuery = stale.embedQuery(REFUND_QUERY);

        System.out.printf("%n=== CROSS-MODEL LAB — index=%s (dim=%d) ===%n",
                props.documentModel(), index.getFirst().length);
        System.out.printf("query lane A (legitimate) = %s (dim=%d)%n", props.queryModel(), legitQuery.length);
        System.out.printf("query lane B (stale)      = %s (dim=%d)%n", STALE_QUERY_MODEL, staleQuery.length);
        System.out.println("query: " + REFUND_QUERY);

        List<Ranked> legit = rank(legitQuery, index);
        List<Ranked> broken = rank(staleQuery, index);

        System.out.printf("%n%-8s %-46s | %-8s %-46s%n", "LEGIT", "breadcrumb", "STALE", "breadcrumb");
        System.out.println("-".repeat(114));
        for (int i = 0; i < CORPUS.size(); i++) {
            System.out.printf("%-8.4f %-46s | %-8.4f %-46s%n",
                    legit.get(i).score(), head(legit.get(i).text()),
                    broken.get(i).score(), head(broken.get(i).text()));
        }

        System.out.printf("%nspread (top-1 minus last):  legit=%.4f   stale=%.4f%n",
                legit.getFirst().score() - legit.getLast().score(),
                broken.getFirst().score() - broken.getLast().score());
        System.out.printf("top-1 agreement: %s%n",
                legit.getFirst().text().equals(broken.getFirst().text()) ? "SAME chunk" : "DIFFERENT chunk");
        System.out.printf("stale top-1 is a refund chunk: %s%n",
                broken.getFirst().text().startsWith("Refund"));
        // ---- The canary measurement -------------------------------------------------------------
        // Embed the SAME sentence a third time, as a DOCUMENT on the legitimate lane. That gives the
        // healthy cross-lane number: what "one shared embedding space, two asymmetric models" actually
        // scores when it is working. Asymmetric models do NOT agree perfectly on identical text — each
        // lane carries a different internal instruction — so the healthy value is well under 1.0, and
        // guessing it would be worthless. Measuring it turns "add a canary" into a calibrated floor.
        float[] probeAsDocument = legitimate.embedDocuments(List.of(REFUND_QUERY)).getFirst();
        System.out.printf("%ncanary — cosine over the SAME sentence across lanes:%n");
        System.out.printf("   HEALTHY  %s(doc) vs %s(query) = %.4f%n",
                props.documentModel(), props.queryModel(),
                VectorMath.cosineSimilarity(probeAsDocument, legitQuery));
        System.out.printf("   BROKEN   %s(doc) vs %s(query) = %.4f%n",
                props.documentModel(), STALE_QUERY_MODEL,
                VectorMath.cosineSimilarity(probeAsDocument, staleQuery));
        System.out.printf("   query-lane drift: %s vs %s = %.4f%n",
                props.queryModel(), STALE_QUERY_MODEL,
                VectorMath.cosineSimilarity(legitQuery, staleQuery));

        // The only assertion, and it is about the ABSENCE of a safety net, not about a score: both
        // vectors are 1024-dimensional, so nothing in our code has grounds to object. If a future
        // model changed dimensions this would fail and the whole experiment would become impossible to
        // stage — which is itself the finding, and worth failing loudly to learn.
        assertThat(staleQuery.length)
                .as("the mismatch is only invisible while the dimensions happen to agree")
                .isEqualTo(legitQuery.length);
    }

    private record Ranked(String text, double score) {}

    private static List<Ranked> rank(float[] query, List<float[]> index) {
        List<Ranked> scored = new ArrayList<>(index.size());
        for (int i = 0; i < index.size(); i++) {
            scored.add(new Ranked(CORPUS.get(i), VectorMath.cosineSimilarity(index.get(i), query)));
        }
        return scored.stream().sorted(Comparator.comparingDouble(Ranked::score).reversed()).toList();
    }

    private static String head(String chunkText) {
        String breadcrumb = chunkText.substring(0, chunkText.indexOf('\n'));
        return breadcrumb.length() <= 46 ? breadcrumb : breadcrumb.substring(0, 45) + "…";
    }
}
