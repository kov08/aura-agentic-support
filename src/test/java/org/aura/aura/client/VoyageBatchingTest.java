package org.aura.aura.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch sizing rule, tested as arithmetic — no client, no properties, no HTTP.
 *
 * <p>Worth its own class because the failure it prevents is not an exception. A batch that breaches
 * the provider's per-request caps fails WHOLE: 128 chunks are re-sent on retry and, if the breach is
 * structural rather than transient, never succeed at all. That is a sizing bug whose symptom appears
 * on the day a document grows, not on the day the sizing was written.
 */
class VoyageBatchingTest {

    @Test
    void aSmallCorpusIsOneCall() {
        assertThat(VoyageEmbeddingClient.batches(inputs(10, 100)))
                .as("a knowledge-base document chunks to single digits — it must not be split")
                .hasSize(1);
    }

    @Test
    void theInputCountCapClosesABatch() {
        // 300 tiny inputs: nowhere near the token budget, so this exercises the count cap alone.
        List<List<String>> batches = VoyageEmbeddingClient.batches(inputs(300, 10));

        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(batch ->
                assertThat(batch).hasSizeLessThanOrEqualTo(VoyageEmbeddingClient.MAX_INPUTS_PER_CALL));
        assertThat(batches.get(0)).hasSize(VoyageEmbeddingClient.MAX_INPUTS_PER_CALL);
        assertThat(batches.getLast()).hasSize(300 - 2 * VoyageEmbeddingClient.MAX_INPUTS_PER_CALL);
    }

    @Test
    void theTokenBudgetClosesABatchBeforeTheInputCountDoes() {
        // 8,000-character inputs are ~2,000 estimated tokens each, so 30 of them exceed the 60K
        // budget long before 128 inputs is reached. Both caps are real bounds and either can bind
        // first; a rule that only checked the count would send a request the provider rejects.
        List<List<String>> batches = VoyageEmbeddingClient.batches(inputs(60, 8_000));

        assertThat(batches).hasSizeGreaterThan(1);
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch).hasSizeLessThan(VoyageEmbeddingClient.MAX_INPUTS_PER_CALL);
            assertThat(estimatedTokens(batch))
                    .isLessThanOrEqualTo(VoyageEmbeddingClient.MAX_ESTIMATED_TOKENS_PER_CALL);
        });
    }

    @Test
    void everyInputSurvivesInOrderAcrossBatchBoundaries() {
        // The property the whole zip-by-index contract rests on. Lose one input, or reorder two, and
        // every chunk from that point on is stored with a neighbour's vector — a corpus that loads
        // cleanly, queries cleanly, and cites the wrong passage forever.
        List<String> inputs = IntStream.range(0, 500).mapToObj(i -> "chunk-" + i).toList();

        assertThat(VoyageEmbeddingClient.batches(inputs).stream().flatMap(List::stream).toList())
                .containsExactlyElementsOf(inputs);
    }

    @Test
    void oneOversizedInputIsSentAloneRatherThanDropped() {
        // Unreachable through the chunker, which caps a chunk at 2,000 characters — but "unreachable
        // today" is not a base case. Silently dropping the input would leave a document short of one
        // chunk with nothing to indicate it.
        String huge = "x".repeat(VoyageEmbeddingClient.MAX_ESTIMATED_TOKENS_PER_CALL * 8);
        List<String> inputs = List.of("small", huge, "also small");

        List<List<String>> batches = VoyageEmbeddingClient.batches(inputs);

        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(inputs);
        assertThat(batches)
                .as("the oversized input must occupy a batch by itself, not drag a neighbour over "
                        + "the cap with it")
                .anySatisfy(batch -> assertThat(batch).containsExactly(huge));
    }

    @Test
    void anEmptyInputListProducesNoBatchesAndThereforeNoCalls() {
        // A document that chunks to nothing must not reach the provider with an empty request, which
        // embed() rejects outright. The pipeline short-circuits before this, so this is the second
        // line of defence rather than the first.
        assertThat(VoyageEmbeddingClient.batches(List.of())).isEmpty();
    }

    @Test
    void theCapsStayUnderWhatTheProviderDocuments() {
        // Pinned so a future "let's make batches bigger" edit has to argue with the documented
        // ceilings rather than with an empty diff. Voyage documents 1,000 texts per request, and a
        // per-model token cap whose tightest value — 120K — belongs to voyage-4-large, which IS the
        // document lane this batching serves.
        assertThat(VoyageEmbeddingClient.MAX_INPUTS_PER_CALL).isLessThanOrEqualTo(1_000);
        assertThat(VoyageEmbeddingClient.MAX_ESTIMATED_TOKENS_PER_CALL).isLessThanOrEqualTo(120_000);
    }

    private static int estimatedTokens(List<String> batch) {
        return batch.stream().mapToInt(input -> Math.ceilDiv(input.length(), 4)).sum();
    }

    private static List<String> inputs(int count, int charsEach) {
        return IntStream.range(0, count).mapToObj(i -> "x".repeat(charsEach)).toList();
    }
}
