package org.aura.aura.evals;

import org.aura.aura.classification.TicketCategory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The label-rot tripwire. Runs in the NORMAL {@code mvn test} suite — it is deterministic, touches no
 * network, and needs no API key: it validates the golden set's SHAPE, never the model's judgment.
 *
 * <p>Its job is to fail LOUDLY the day a schema or enum change silently invalidates a hand-written
 * label. If a category constant is renamed, {@link GoldenSetLoader#load()} throws while deserializing
 * the now-illegal string; if a ticket is dropped or an id duplicated, the count/uniqueness assertions
 * catch it. Without this test those rot's would surface only as a mystifying score drop weeks later.
 *
 * <p>It deliberately does NOT check that a label is CORRECT — only a human review can do that (it is
 * what makes the set "golden"). This checks that every label is well-formed and legal.
 */
class GoldenSetIntegrityTest {

    private static final int EXPECTED_TICKET_COUNT = 36;   // 24 legacy + Day 16's 12 grounding tickets
    // Bumped 1 -> 2 with golden-set-v2: the labelingPolicy legislation plus four relabels decided by it.
    // Bumped 2 -> 3 with golden-set-v3: the three grounding classes, their law, and expectedCitations.
    // The set version and labelingPolicy.version are intentionally the SAME number and move together —
    // a set carrying policy vN is exactly the set relabelled under law vN — so one constant checks both.
    private static final int GOLDEN_SET_VERSION = 3;

    // The legal slice values. `whiff` joined the set when clean-04 was split out into its own
    // retrieval-failure family; keeping the list here means an unknown slice fails this test loudly.
    //
    // Day 16's three carry more weight than the rest: GroundingClass.of() reads the slice string to
    // decide HOW a ticket is graded, so a typo'd slice would not merely mislabel a report row, it
    // would silently drop the ticket out of the grounding dimension entirely. That is the case
    // groundingSlicesAreGradable below exists to make impossible.
    private static final Set<String> LEGAL_SLICES = Set.of(
            "clean", "ambiguous", "out_of_scope", "injection", "garbage", "noisy", "whiff",
            "answerable", "unanswerable", "trap");

    private final GoldenSet goldenSet = GoldenSetLoader.load();

    @Test
    void parsesCleanly() {
        // Loading again inside the assertion makes the failure message point at parsing specifically —
        // an illegal enum value or malformed JSON throws here, before any other assertion runs.
        assertThatCode(GoldenSetLoader::load).doesNotThrowAnyException();
        assertThat(goldenSet.goldenSetVersion()).isEqualTo(GOLDEN_SET_VERSION);
        assertThat(goldenSet.tickets()).isNotNull();
    }

    // The labelingPolicy is v2's written law. This asserts only its PRESENCE and SHAPE (present, right
    // version, no blank rule) — never that the rules are wise, which only human review decides. A v2 set
    // that lost its policy, or shipped a rule as an empty string, is a rot this catches loudly.
    @Test
    void labelingPolicyIsPresentAndComplete() {
        LabelingPolicy policy = goldenSet.labelingPolicy();
        assertThat(policy).as("labelingPolicy must be present on a v%d set", GOLDEN_SET_VERSION).isNotNull();
        assertThat(policy.version())
                .as("labelingPolicy.version travels with goldenSetVersion")
                .isEqualTo(GOLDEN_SET_VERSION);
        assertThat(policy.intentUnderInjection()).as("intentUnderInjection law").isNotBlank();
        assertThat(policy.categoryTieBreak()).as("categoryTieBreak law").isNotBlank();
        assertThat(policy.groundingClassRule()).as("groundingClassRule law (v3)").isNotBlank();
        // urgencyRubric is a frozen verbatim copy of the classifier-v2 <urgency_rubric>; its provenance
        // stamp lives in a separate field so the copy stays character-comparable to the prompt block.
        // Shape only — that the copy is faithful is a human-diff decision, not something this can judge.
        assertThat(policy.urgencyRubric()).as("urgencyRubric law").isNotBlank();
        assertThat(policy.urgencyRubricSource()).as("urgencyRubric provenance stamp").isNotBlank();
    }

    @Test
    void hasExactlyExpectedTicketCount() {
        assertThat(goldenSet.tickets()).hasSize(EXPECTED_TICKET_COUNT);
    }

    @Test
    void everyIdIsUnique() {
        List<String> ids = goldenSet.tickets().stream().map(EvalTicket::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyCategoryAppearsAtLeastTwice() {
        Map<TicketCategory, Integer> counts = new HashMap<>();
        for (EvalTicket t : goldenSet.tickets()) {
            counts.merge(t.expected().category(), 1, Integer::sum);
        }
        // Every category that appears must appear >= 2; and every category MUST appear (no gaps), so
        // the set exercises the full taxonomy the classifier can emit.
        for (TicketCategory category : TicketCategory.values()) {
            assertThat(counts.getOrDefault(category, 0))
                    .as("category %s must appear at least twice", category)
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void everySliceIsLegal() {
        for (EvalTicket t : goldenSet.tickets()) {
            assertThat(LEGAL_SLICES)
                    .as("ticket %s has slice '%s'", t.id(), t.slice())
                    .contains(t.slice());
        }
    }

    // Enum legality for category/urgency/intent is enforced at deserialization (an illegal value
    // can't load), so here we only need to confirm they are present — a null would mean the JSON
    // omitted the field entirely.
    @Test
    void everyStructuredLabelIsPresent() {
        for (EvalTicket t : goldenSet.tickets()) {
            ExpectedResult e = t.expected();
            assertThat(e.category()).as("%s category", t.id()).isNotNull();
            assertThat(e.urgency()).as("%s urgency", t.id()).isNotNull();
            assertThat(e.intent()).as("%s intent", t.id()).isNotNull();
        }
    }

    // Every must/mustNot entry must be a non-blank string. A blank rule would match every reply (for
    // mustContain) or forbid every reply (for mustNot) — a silently-passing or silently-failing
    // assertion that measures nothing.
    @Test
    void everyRuleStringIsNonBlank() {
        for (EvalTicket t : goldenSet.tickets()) {
            for (String rule : t.expected().mustContain()) {
                assertThat(rule).as("%s mustContain entry", t.id()).isNotBlank();
            }
            for (String rule : t.expected().mustNotContain()) {
                assertThat(rule).as("%s mustNotContain entry", t.id()).isNotBlank();
            }
        }
    }

    // expectedSources is three-valued: null (ungraded) and [] (strict) are both legal, so this does
    // NOT assert non-empty. It only asserts that WHEN ids are listed, none is blank — a blank id could
    // never match a real KB id and would make the subset check unsatisfiable.
    @Test
    void everyListedSourceIdIsNonBlank() {
        for (EvalTicket t : goldenSet.tickets()) {
            List<String> sources = t.expected().expectedSources();
            if (sources == null) continue;
            for (String id : sources) {
                assertThat(id).as("%s expectedSources entry", t.id()).isNotBlank();
            }
        }
    }

    // ---- Day 16: the grounding classes ----------------------------------------------------------

    /**
     * Every grounding slice must map to a {@link GroundingClass}, and the mapping is what turns a
     * string in JSON into a grading rule. A slice that failed to map would not fail anything — the
     * ticket would simply be excluded from the grounding dimension and the report would show a
     * smaller denominator, which reads exactly like a set that was always that size.
     */
    @Test
    void groundingSlicesAreGradable() {
        for (EvalTicket t : goldenSet.tickets()) {
            boolean isGroundingTicket = t.id().startsWith("answerable-")
                    || t.id().startsWith("unanswerable-")
                    || t.id().startsWith("trap-");
            assertThat(GroundingClass.of(t.slice()).isPresent())
                    .as("%s (slice '%s') must%s be gradable by the grounding dimension",
                            t.id(), t.slice(), isGroundingTicket ? "" : " NOT")
                    .isEqualTo(isGroundingTicket);
        }
    }

    /**
     * Each class carries enough label to be gradable AT ALL, which is a different assertion from
     * "the label is right".
     *
     * <ul>
     *   <li>ANSWERABLE and TRAP need a {@code mustContain} fact, or "did it state the corpus's value"
     *       has nothing to check and every answered ticket passes vacuously.</li>
     *   <li>TRAP additionally needs a {@code mustNotContain} prior — without it a trap is just an
     *       answerable ticket, and the one thing traps exist to detect is unmeasured.</li>
     *   <li>UNANSWERABLE needs NEITHER, and must not be given one: the correct outcome is a refusal
     *       with no reply text, so a fragment rule over that reply could only ever grade the canned
     *       escalation wording.</li>
     * </ul>
     */
    @Test
    void eachGroundingClassCarriesTheLabelItsRuleNeeds() {
        for (EvalTicket t : goldenSet.tickets()) {
            GroundingClass groundingClass = GroundingClass.of(t.slice()).orElse(null);
            if (groundingClass == null) continue;
            ExpectedResult e = t.expected();

            switch (groundingClass) {
                case ANSWERABLE -> assertThat(e.mustContain())
                        .as("%s is answerable, so it must name the corpus fact to look for", t.id())
                        .isNotEmpty();
                case TRAP -> {
                    assertThat(e.mustContain())
                            .as("%s is a trap, so it must name the corpus value", t.id()).isNotEmpty();
                    assertThat(e.mustNotContain())
                            .as("%s is a trap, so it must name the generic-prior value", t.id()).isNotEmpty();
                    assertThat(e.expectedCitations())
                            .as("%s is a trap, so it must name the chunk that carries the value", t.id())
                            .isNotNull().isNotEmpty();
                }
                case UNANSWERABLE -> {
                    assertThat(e.mustContain())
                            .as("%s is unanswerable — a correct run writes no reply to match against", t.id())
                            .isEmpty();
                    assertThat(e.escalate())
                            .as("%s is unanswerable, so the labelled escalate verdict must be true", t.id())
                            .isTrue();
                }
            }
        }
    }

    /** Same non-blank rule as expectedSources, for the same unsatisfiable-label reason. */
    @Test
    void everyExpectedCitationIsNonBlank() {
        for (EvalTicket t : goldenSet.tickets()) {
            List<String> citations = t.expected().expectedCitations();
            if (citations == null) continue;
            for (String breadcrumb : citations) {
                assertThat(breadcrumb).as("%s expectedCitations entry", t.id()).isNotBlank();
            }
        }
    }
}
