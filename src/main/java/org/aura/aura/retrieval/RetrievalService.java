package org.aura.aura.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.RetrievalProperties;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.NearestChunk;
import org.aura.aura.util.VectorLiterals;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ticket text in, a grounded context block out. This is the piece that puts semantic search on the
 * live request path.
 *
 * <p>Four steps, and each one is a decision rather than plumbing: embed the ticket on the QUERY lane,
 * ask Postgres for the {@code k} nearest chunks with their distances, pack as many as the token
 * budget allows in rank order while dropping adjacent siblings, and render the survivors into their
 * one canonical form.
 *
 * <h2>What this replaces</h2>
 * {@code HardcodedKnowledgeBase.retrieve} — a keyword filter over three hand-written entries. Day 4's
 * ConversationRunner already measured its failure mode: reword "how long do I have to return
 * something?" as "can I get my money back for a hoodie?" and retrieval returned nothing, so Claude
 * answered from the system prompt with a number nobody had verified. Everything below exists because
 * that failure is silent.
 */
@Slf4j
@Service
public class RetrievalService {

    private final VoyageEmbeddingClient voyage;
    private final ChunkRepository chunks;
    private final ContextBlockAssembler assembler;
    private final RetrievalProperties props;

    public RetrievalService(VoyageEmbeddingClient voyage,
                            ChunkRepository chunks,
                            ContextBlockAssembler assembler,
                            RetrievalProperties props) {
        this.voyage = voyage;
        this.chunks = chunks;
        this.assembler = assembler;
        this.props = props;
    }

    public ContextBlock retrieve(String ticketText) {
        // (1) THE QUERY LANE. embedQuery, never embedDocuments — the model and the input_type are both
        // decided by which method is called, which is the whole reason the client exposes two. Getting
        // this wrong produces perfectly valid vectors that simply retrieve worse, with nothing to
        // catch it; RetrievalServiceTest asserts the lane at this call site for exactly that reason.
        float[] queryVector = voyage.embedQuery(ticketText);

        // (2) The search. The distance comes back PROJECTED by the same SQL that did the ordering
        // (Decision 3A) — no JVM-side rescoring, so the rank and the reported number are one fact.
        List<NearestChunk> candidates =
                chunks.findNearestWithDistance(VectorLiterals.toLiteral(queryVector), props.k());

        // (3) + (4)
        List<RetrievedChunk> survivors = pack(candidates);
        ContextBlock block = assembler.assemble(survivors);

        log.info("retrieval — k={}, candidates={}, survivors={}, tokens={}/{}, nearest={}",
                props.k(), candidates.size(), survivors.size(),
                survivors.stream().mapToInt(RetrievedChunk::tokenCount).sum(),
                props.contextTokenBudget(),
                candidates.isEmpty() ? "n/a" : candidates.getFirst().getDistance());

        return block;
    }

    /**
     * Rank-order packing with adjacency dedup.
     *
     * <p>Two rejection rules that behave differently on purpose:
     *
     * <ul>
     *   <li><b>Adjacent sibling → skip and keep going.</b> The candidate shares a document with an
     *       already-packed chunk at a neighbouring index, so it is largely the same prose (the
     *       chunker's 300-character overlap guarantees it). It contributes little and costs its full
     *       token count. Dropping it does not just save budget — the freed budget is immediately
     *       available to the next DISTINCT chunk further down the ranking, which is why this is a
     *       density improvement and not an economy measure. Two near-copies of one passage become one
     *       passage plus a genuinely different one.</li>
     *   <li><b>Over budget → stop.</b> Not "skip and look for something smaller". Skipping would
     *       admit a lower-ranked chunk over a higher-ranked one on a size technicality, and the
     *       packed set would stop being a prefix of the ranking — so "why is chunk 6 in the context
     *       and chunk 4 not?" would have an answer involving arithmetic nobody can see from the
     *       result. Stopping keeps the rule explicable in one sentence: the top of the ranking, as
     *       much of it as fits.</li>
     * </ul>
     *
     * <p>Adjacency is checked only against chunks that were actually PACKED, never against ones
     * already dropped. So indexes 3 and 5 both survive when 4 was dropped: 3 and 5 share no text, and
     * chaining the exclusion through a dropped chunk would discard a passage for being near something
     * that is not in the context.
     */
    private List<RetrievedChunk> pack(List<NearestChunk> candidates) {
        List<RetrievedChunk> packed = new ArrayList<>();
        int spent = 0;

        for (NearestChunk row : candidates) {
            RetrievedChunk candidate = RetrievedChunk.from(row);

            if (packed.stream().anyMatch(candidate::isAdjacentTo)) {
                log.debug("retrieval dedup — dropped {}#{} (adjacent to a higher-ranked sibling)",
                        candidate.sourceDoc(), candidate.chunkIndex());
                continue;
            }

            if (spent + candidate.tokenCount() > props.contextTokenBudget()) break;

            packed.add(candidate);
            spent += candidate.tokenCount();
        }

        if (packed.isEmpty() && !candidates.isEmpty()) {
            // The one case where "stop at budget" produces an empty context from a non-empty search:
            // the nearest chunk alone is bigger than the whole budget. That is a misconfiguration
            // (budget too small, or a chunk far outside the corpus's size distribution), and it
            // degrades to an ungrounded answer — so it gets a WARN rather than passing as a normal
            // empty result. The grounding instruction still keeps the reply honest; this line is what
            // tells an operator why every answer suddenly says "I don't have that".
            log.warn("retrieval packed NOTHING — nearest chunk needs {} tokens but "
                            + "aura.retrieval.context-token-budget is {}; this ticket is answered ungrounded",
                    candidates.getFirst().getTokenCount(), props.contextTokenBudget());
        }

        return packed;
    }
}
