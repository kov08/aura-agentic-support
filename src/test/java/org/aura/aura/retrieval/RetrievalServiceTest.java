package org.aura.aura.retrieval;

import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.RetrievalProperties;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.NearestChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retrieval policy, stated one test per rule. Everything is mocked at the two real boundaries —
 * the embedding HTTP call and the SQL — so what runs here is AURA's packing logic and nothing else:
 * no Voyage, no Postgres, no key, no Docker.
 *
 * <p>Note what is deliberately NOT asserted anywhere below: which chunks a real query would rank
 * where. That is a property of an embedding model, it changes when a provider retrains, and it is not
 * ours to pin. These tests fix the rules that ARE ours — the lane, the pool width, the packing order,
 * the budget, the dedup — by handing the service a ranking and checking what it does with it.
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    private static final String TICKET = "Can I get my money back for a hoodie?";
    private static final int K = 8;
    private static final int BUDGET = 700;   // the production value; three 300-token chunks do not fit

    @Mock VoyageEmbeddingClient voyage;
    @Mock ChunkRepository chunks;

    private RetrievalService service() {
        return new RetrievalService(voyage, chunks, new ContextBlockAssembler(),
                new RetrievalProperties(K, BUDGET));
    }

    // ---------------------------------------------------------------- the lane, at the new call site

    @Test
    void embedsTheTicketOnTheQueryLaneAndNeverTheDocumentLane() {
        // VoyageEmbeddingClientTest already pins that embedQuery sends voyage-4-lite/query on the
        // wire. This is the OTHER half of the same guard, and the half Day 14 newly needs: that the
        // live request path calls that method rather than the document one. A lane flip here would
        // produce perfectly valid vectors that simply retrieve worse — no exception, no failing
        // assertion anywhere else in the suite, just quietly degraded answers.
        when(voyage.embedQuery(TICKET)).thenReturn(new float[]{0.1f, 0.2f});
        when(chunks.findNearestWithDistance(anyString(), eq(K))).thenReturn(List.of());

        // THE WRONG LANE IS STUBBED TO SUCCEED, and that is the whole point of this line.
        //
        // Day 14's drill flipped the call site to embedDocuments and this test did fail — but by
        // accident, not by design: an unstubbed embedDocuments returns Mockito's default EMPTY list,
        // and RetrievalService's .getFirst() blew up with NoSuchElementException before the verify
        // below ever executed. The suite was relying on a Mockito default to catch a production lane
        // flip, and the assertion written for exactly that job was unreachable.
        //
        // With the wrong lane made to work, a flipped call site now runs cleanly all the way to the
        // verify — so the failure is "never wanted embedDocuments here, but invoked", which names the
        // defect, instead of a stack trace that names a collection.
        //
        // lenient(): on the CORRECT code path this stub is deliberately never used, which strict-stub
        // checking would otherwise report as an unnecessary stubbing.
        lenient().when(voyage.embedDocuments(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));

        service().retrieve(TICKET);

        // never() FIRST, so it is the assertion that reports. Both are designed and both hold on the
        // correct path, but only one of them names the DEFECT: "never wanted embedDocuments here, but
        // invoked" points straight at the flipped lane, where "wanted but not invoked: embedQuery"
        // makes the reader work out which call took its place.
        verify(voyage, never()).embedDocuments(any());
        verify(voyage).embedQuery(TICKET);
    }

    @Test
    void asksTheDatabaseForExactlyKCandidates() {
        when(voyage.embedQuery(TICKET)).thenReturn(new float[]{0.1f});
        when(chunks.findNearestWithDistance(anyString(), eq(K))).thenReturn(List.of());

        service().retrieve(TICKET);

        // k is the POOL width and it is configuration, not a literal: the LIMIT the database sees has
        // to be the number in application.yml, or tuning that property tunes nothing.
        verify(chunks).findNearestWithDistance(anyString(), eq(K));
    }

    // ---------------------------------------------------------------- packing

    @Test
    void packsInRankOrderAndStopsAtTheBudget() {
        stub(row("a.md", 0, 300, 0.10),
             row("b.md", 0, 300, 0.20),
             row("c.md", 0, 300, 0.30));   // 900 > 700

        ContextBlock block = service().retrieve(TICKET);

        assertThat(block.sourcesProvided())
                .as("two 300-token chunks fit in a 700-token budget; the third does not")
                .extracting(SourceRef::breadcrumb)
                .containsExactly("a.md#0", "b.md#0");
    }

    @Test
    void stoppingAtTheBudgetDoesNotSkipAheadToASmallerLowerRankedChunk() {
        // The behaviour that separates "stop" from "skip and keep looking". c.md would FIT in the
        // remaining 100 tokens — and is still excluded, because admitting it would put a lower-ranked
        // chunk in the context while a higher-ranked one was left out on a size technicality, and the
        // packed set would stop being explicable as "the top of the ranking, as much as fits".
        stub(row("a.md", 0, 300, 0.10),
             row("b.md", 0, 300, 0.20),
             row("big.md", 0, 500, 0.30),   // does not fit in the remaining 100 -> stop here
             row("c.md", 0, 50, 0.40));     // would have fitted

        ContextBlock block = service().retrieve(TICKET);

        assertThat(block.sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("a.md#0", "b.md#0");
    }

    @Test
    void distancesArePreservedOntoTheResults() {
        // Decision 3A end to end: the number the SQL projected is the number that reaches the wire.
        // Nothing recomputes it, rounds it, or replaces it with a similarity.
        stub(row("a.md", 0, 100, 0.1234), row("b.md", 0, 100, 0.5678));

        assertThat(service().retrieve(TICKET).sourcesProvided())
                .extracting(SourceRef::distance)
                .containsExactly(0.1234, 0.5678);
    }

    // ---------------------------------------------------------------- adjacency dedup

    @Test
    void dropsTheLowerRankedAdjacentSiblingAndSpendsTheFreedBudgetOnTheNextDistinctChunk() {
        // THE dedup test, and both halves matter. Dropping refund#1 is the saving; admitting
        // shipping#0 in its place is the POINT — the budget is re-spent on material the context does
        // not already contain, so dedup raises information density rather than merely lowering cost.
        //
        // Without dedup this budget would hold refund#0 + refund#1 (600 of 700) and stop, so the
        // model would see one passage twice and the shipping policy not at all.
        stub(row("refund-policy.md", 0, 300, 0.10),
             row("refund-policy.md", 1, 300, 0.20),      // adjacent to a packed chunk -> dropped
             row("shipping-policy.md", 0, 300, 0.30));   // ...and takes the freed 300 tokens

        ContextBlock block = service().retrieve(TICKET);

        assertThat(block.sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("refund-policy.md#0", "shipping-policy.md#0");
    }

    @Test
    void adjacencyIsCheckedAgainstPackedChunksOnlySoAGapSurvives() {
        // refund#2 is adjacent to refund#1 — which was DROPPED, so it is not in the context and
        // cannot be duplicated by anything. Chaining the exclusion through a dropped chunk would
        // discard a genuinely distinct passage for being near something nobody is going to see.
        // Indexes 0 and 2 share no text: the chunker's overlap reaches one neighbour, not two.
        stub(row("refund-policy.md", 0, 100, 0.10),
             row("refund-policy.md", 1, 100, 0.20),   // dropped: adjacent to #0
             row("refund-policy.md", 2, 100, 0.30));  // kept: |2-0| = 2

        assertThat(service().retrieve(TICKET).sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("refund-policy.md#0", "refund-policy.md#2");
    }

    @Test
    void chunksAtTheSameIndexInDifferentDocumentsAreNotSiblings() {
        // Adjacency is (document, index) — both halves. Two documents whose chunk 0 and chunk 1 both
        // rank well are two different policies, not one passage seen twice.
        stub(row("refund-policy.md", 0, 100, 0.10),
             row("shipping-policy.md", 1, 100, 0.20));

        assertThat(service().retrieve(TICKET).sourcesProvided()).hasSize(2);
    }

    @Test
    void anEmptyCorpusYieldsAnEmptyBlockRatherThanAFailure() {
        // Retrieval finding nothing is a legitimate outcome, not an error: the grounding instruction
        // in the system prompt is what turns it into an honest "I don't have that" plus an escalation.
        stub();

        ContextBlock block = service().retrieve(TICKET);

        assertThat(block.isEmpty()).isTrue();
        assertThat(block.rendered()).isEqualTo("<documents>\n</documents>");
    }

    // ---------------------------------------------------------------- fixtures

    private void stub(NearestChunk... rows) {
        when(voyage.embedQuery(TICKET)).thenReturn(new float[]{0.1f, 0.2f});
        when(chunks.findNearestWithDistance(anyString(), eq(K))).thenReturn(List.of(rows));
    }

    /**
     * A hand-built projection row. Breadcrumb doubles as the assertion handle ({@code "a.md#0"}) so
     * failures read as "which chunk survived" rather than as a uuid comparison.
     */
    private static NearestChunk row(String doc, int index, int tokens, double distance) {
        return new Row(UUID.randomUUID(), doc, index, doc + "#" + index,
                "content of " + doc + "#" + index, tokens, distance);
    }

    private record Row(UUID id, String doc, int index, String crumb, String body, int tokens, double dist)
            implements NearestChunk {
        @Override public UUID getChunkId() { return id; }
        @Override public String getSourceDoc() { return doc; }
        @Override public int getChunkIndex() { return index; }
        @Override public String getBreadcrumb() { return crumb; }
        @Override public String getContent() { return body; }
        @Override public int getTokenCount() { return tokens; }
        @Override public double getDistance() { return dist; }
    }
}
