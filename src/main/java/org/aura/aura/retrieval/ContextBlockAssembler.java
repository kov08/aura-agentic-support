package org.aura.aura.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Renders a surviving chunk set into the ONE byte representation it is allowed to have, and emits the
 * matching source ledger from the same pass.
 *
 * <h2>Why "canonical" is load-bearing and not a style preference</h2>
 * Decision 4 hashes these bytes into the response-cache key. A hash is only a useful identity if the
 * same logical input always produces the same bytes — so every source of incidental variation has to
 * be designed out rather than hoped away:
 *
 * <ul>
 *   <li><b>Order is computed, not inherited.</b> The rows are sorted here by (distance, chunk id)
 *       rather than rendered in whatever order they arrived. Retrieval already delivers them in rank
 *       order, so in practice the sort changes nothing — and that is exactly the point. It makes the
 *       output a function of the SET, so a caller that reorders its list (a future parallel fetch, a
 *       merge of two searches, a test) cannot silently mint a second cache key for one result.</li>
 *   <li><b>The tie-break is total.</b> Two chunks at identical distance is not exotic — duplicated
 *       boilerplate across two policy documents produces it. Sorting on distance alone leaves their
 *       relative order to the sort's stability and therefore to arrival order, which is the thing
 *       being eliminated. Chunk id is arbitrary but total, which is all a tie-break has to be.</li>
 *   <li><b>No timestamps, no counters, no request ids.</b> Anything that varies per call would make
 *       every key unique and the cache would report a 100% miss rate while looking healthy.</li>
 *   <li><b>Content is rendered VERBATIM.</b> Not trimmed, not normalised. Tempting, and wrong: any
 *       normalisation is a class of edit the cache key cannot see, so an edit that only changes
 *       trailing whitespace would keep serving the pre-edit answer. Verbatim means every byte of the
 *       corpus is inside the hash.</li>
 *   <li><b>The DISTANCE is not rendered.</b> It is in the ledger, on the wire, and in the logs — but
 *       not in these bytes, and that omission is deliberate rather than an oversight. Distance is a
 *       property of the query embedding, and embeddings are not reproducible to the last bit: the
 *       Day 14 canary measurement watched the same sentence return distances that differed in the
 *       fourth decimal place across identical calls. Put that number in the block and it lands in
 *       the cache key, and the key stops repeating — a cache that reports a permanent 100% miss rate
 *       while every component in it is working. It is also information the MODEL has no use for; a
 *       cosine distance is only meaningful relative to other distances it cannot see.</li>
 * </ul>
 *
 * <h2>The frame</h2>
 * One {@code <document>} element per chunk, id and breadcrumb as attributes, content as the body.
 * XML-ish tags rather than markdown headings or numbered prose because the boundary between "this is
 * reference material" and "this is the customer talking" has to be unambiguous to the model, and
 * because attributes give the citation handles a place to live that is clearly not part of the
 * policy text.
 */
@Component
public class ContextBlockAssembler {

    static final String OPEN = "<documents>";
    static final String CLOSE = "</documents>";

    /**
     * The canonical order. Any total order would do for the tie-break; what matters is that it is
     * total and that it depends only on the chunks themselves, never on how they arrived.
     */
    private static final Comparator<RetrievedChunk> CANONICAL =
            Comparator.comparingDouble(RetrievedChunk::distance)
                    .thenComparing(chunk -> chunk.chunkId().toString());

    public ContextBlock assemble(List<RetrievedChunk> survivors) {
        // Sorted ONCE, then used for both outputs. This is the mechanism behind ContextBlock's
        // promise that the rendered bytes and the ledger describe the same set in the same order:
        // there is only one list, so there is nothing for them to disagree about.
        List<RetrievedChunk> canonical = survivors.stream().sorted(CANONICAL).toList();

        StringBuilder out = new StringBuilder(OPEN);
        for (RetrievedChunk chunk : canonical) {
            out.append('\n')
                    .append("<document id=\"").append(escapeAttribute(chunk.chunkId().toString()))
                    .append("\" breadcrumb=\"").append(escapeAttribute(chunk.breadcrumb()))
                    .append("\">\n")
                    .append(chunk.content())        // verbatim — see the class javadoc
                    .append("\n</document>");
        }
        out.append('\n').append(CLOSE);

        // An empty survivor set still renders a frame ("<documents>\n</documents>") rather than an
        // empty string or a "no results" sentence. The frame is a stable, hashable representation of
        // "retrieval found nothing", and it lets the grounding instruction in the system prompt do its
        // job unmodified: the model is looking at an empty document list, which is unambiguous.
        return new ContextBlock(out.toString(), canonical.stream().map(RetrievedChunk::toSourceRef).toList());
    }

    /**
     * Attribute escaping, in the order that matters: {@code &} first, or the ampersands introduced by
     * the later replacements get escaped a second time.
     *
     * <p>The BODY is deliberately not escaped — see the class javadoc on rendering content verbatim.
     * That leaves one honest gap worth naming rather than discovering: a chunk whose text literally
     * contained {@code </document>} would close the element early. Today the corpus is ours, written
     * by us, in {@code kb/}, so this is a theoretical hole rather than an attack surface. It stops
     * being theoretical the moment ingestion accepts a document AURA did not write, which is a Day 15
     * concern and wants a real answer then (a delimiter the content cannot contain, or escaping the
     * body and accepting the readability cost) rather than a guess now.
     */
    private static String escapeAttribute(String value) {
        // `>` is deliberately NOT escaped, and that is a decision about readability rather than an
        // omission. Inside an attribute value only `"` can terminate the attribute and only `<` can
        // confuse the tag, so escaping `>` buys no safety — and it costs a lot here specifically,
        // because every breadcrumb in this corpus is a `>`-separated heading path. Escaping it would
        // turn "Refund Policy > International Orders" into "Refund Policy &gt; International Orders"
        // on every chunk of every request: more tokens, and a citation handle a human reading the
        // prompt has to mentally decode.
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;");
    }
}
