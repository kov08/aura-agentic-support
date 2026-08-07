package org.aura.aura.ingest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diff, tested as arithmetic on two maps. The easiest test target in the codebase, and
 * deliberately so: the decision "which documents need work" is where ingestion's real branching
 * lives, and pulling it out of the orchestration means these cases need no mocks, no temp files and
 * no Spring context.
 */
class IngestionPlanTest {

    /**
     * ONE fixture driving all four sets at once, rather than four fixtures driving one each.
     *
     * <p>Four separate tests would each pass against an implementation that gets the others wrong —
     * a classifier that puts everything in {@code added} passes the "added" test. What has to hold is
     * that every path lands in EXACTLY ONE set, and only a fixture with all four populated
     * simultaneously can check that.
     */
    @Test
    void oneCorpusDrivesAllFourSets() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put("warranty-policy.md", "fp-warranty");   // in both, same hash    -> unchanged
        disk.put("refund-policy.md", "fp-refund-v2");    // in both, hash moved   -> changed
        disk.put("returns-policy.md", "fp-returns");     // disk only             -> added
        // shipping-policy.md is absent from disk       // ledger only            -> deleted

        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("warranty-policy.md", "fp-warranty");
        stored.put("refund-policy.md", "fp-refund-v1");
        stored.put("shipping-policy.md", "fp-shipping");

        IngestionPlan plan = IngestionPlan.of(disk, stored);

        assertThat(plan.added()).containsExactly("returns-policy.md");
        assertThat(plan.changed()).containsExactly("refund-policy.md");
        assertThat(plan.unchanged()).containsExactly("warranty-policy.md");
        assertThat(plan.deleted()).containsExactly("shipping-policy.md");

        // The partition property the four assertions above cannot state on their own: every path
        // mentioned by either side appears once and only once across the four sets.
        assertThat(plan.added().size() + plan.changed().size()
                + plan.unchanged().size() + plan.deleted().size())
                .as("every path on either side must land in exactly one set")
                .isEqualTo(4);
    }

    @Test
    void anUnchangedCorpusIsANoOpAndCostsNothingToEmbed() {
        Map<String, String> both = Map.of("refund-policy.md", "fp", "shipping-policy.md", "fp2");

        IngestionPlan plan = IngestionPlan.of(both, both);

        assertThat(plan.isNoOp()).isTrue();
        assertThat(plan.toEmbed())
                .as("this empty list is the idempotency guarantee — nothing to embed, nothing to bill")
                .isEmpty();
        assertThat(plan.unchanged()).containsExactly("refund-policy.md", "shipping-policy.md");
    }

    @Test
    void aFirstRunAgainstAnEmptyLedgerIsAllNew() {
        IngestionPlan plan = IngestionPlan.of(
                Map.of("refund-policy.md", "fp", "shipping-policy.md", "fp2"), Map.of());

        assertThat(plan.added()).containsExactly("refund-policy.md", "shipping-policy.md");
        assertThat(plan.changed()).isEmpty();
        assertThat(plan.unchanged()).isEmpty();
        assertThat(plan.deleted()).isEmpty();
        assertThat(plan.storedCount())
                .as("nothing was in the ledger, so the guard has nothing to protect")
                .isZero();
    }

    @Test
    void anEmptyScanAgainstAPopulatedLedgerDeletesEverything() {
        // Not a judgement about whether this SHOULD happen — that is the pipeline's guard, and it
        // refuses. This asserts only that the plan describes the situation truthfully, which is the
        // input the guard needs in order to refuse for the right reason.
        IngestionPlan plan = IngestionPlan.of(Map.of(), Map.of("a.md", "fp1", "b.md", "fp2"));

        assertThat(plan.deleted()).containsExactly("a.md", "b.md");
        assertThat(plan.toEmbed()).isEmpty();
        assertThat(plan.isNoOp()).isFalse();
    }

    @Test
    void aRenameIsReportedAsOneAdditionAndOneDeletion() {
        // Worth pinning because it is the case that looks like a bug in a report. There is no rename
        // detection and there should not be: two files with identical content have identical
        // fingerprints, so "detecting" it would mean matching on content and then guessing which
        // deletion pairs with which addition. Reporting both plainly costs one re-embed of a document
        // that already exists and never guesses wrong.
        IngestionPlan plan = IngestionPlan.of(
                Map.of("refunds.md", "fp"), Map.of("refund-policy.md", "fp"));

        assertThat(plan.added()).containsExactly("refunds.md");
        assertThat(plan.deleted()).containsExactly("refund-policy.md");
        assertThat(plan.unchanged()).isEmpty();
    }

    @Test
    void everySetIsSortedRegardlessOfIterationOrder() {
        // Nothing downstream requires it, which is exactly why it is asserted: it is the property
        // that makes two runs over one corpus comparable, and it is the first thing a refactor to a
        // HashMap would silently take away.
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put("z.md", "1");
        disk.put("a.md", "1");
        disk.put("m.md", "1");

        assertThat(IngestionPlan.of(disk, Map.of()).added()).containsExactly("a.md", "m.md", "z.md");
    }

    @Test
    void toEmbedListsNewDocumentsBeforeChangedOnes() {
        IngestionPlan plan = IngestionPlan.of(
                Map.of("new.md", "fp-new", "old.md", "fp-v2"), Map.of("old.md", "fp-v1"));

        assertThat(plan.toEmbed()).containsExactly("new.md", "old.md");
    }
}
