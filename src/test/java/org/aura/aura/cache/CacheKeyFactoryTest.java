package org.aura.aura.cache;

import org.aura.aura.retrieval.ContextBlockAssembler;
import org.aura.aura.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v2 cache key (Decision 4). Each test is one property the key must have for the cache to be
 * both correct and useful — and several of them are properties whose absence produces no error at
 * all, only a wrong answer or a permanent miss.
 */
class CacheKeyFactoryTest {

    private static final String MODEL = "claude-sonnet-4-5";
    private static final String PROMPT_ID = "resolver_system_prompt.md";
    private static final int PROMPT_VERSION = 4;
    private static final String SYSTEM = "system prompt bytes";
    private static final String TICKET = "How long do I have to return something?";

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final CacheKeyFactory keys = new CacheKeyFactory();
    private final ContextBlockAssembler assembler = new ContextBlockAssembler();

    @Test
    void keysCarryTheV2Prefix() {
        // The prefix is the readable, manual invalidation lever, and v2 specifically means "these were
        // computed by a different function from v1's". A key that still said v1 while hashing an extra
        // field would share a keyspace with entries built before RAG existed — and a lookup that
        // succeeds against a value built by another function is the worst possible cache hit.
        assertThat(key(block(chunk(ID_A, "refund", "thirty days", 0.1))))
                .startsWith("aura:resolution:v2:");
    }

    // ---------------------------------------------------------------- what MUST change the key

    @Test
    void editingAChunksContentChangesTheKeyEvenThoughItsIdDidNot() {
        // THE reason the key hashes bytes rather than ids. An in-place correction to a policy — the
        // single most likely KB change there is — leaves every id untouched. Key on ids and the
        // pre-edit answer keeps being served, confidently, with a citation attached, for a full 24h.
        String before = key(block(chunk(ID_A, "Refund Policy", "Customers have 30 days.", 0.1)));
        String after = key(block(chunk(ID_A, "Refund Policy", "Customers have 45 days.", 0.1)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void aDifferentSetOfRetrievedChunksChangesTheKey() {
        String one = key(block(chunk(ID_A, "Refund Policy", "thirty days", 0.1)));
        String two = key(block(
                chunk(ID_A, "Refund Policy", "thirty days", 0.1),
                chunk(ID_B, "Shipping Policy", "five days", 0.2)));

        assertThat(two).isNotEqualTo(one);
    }

    @Test
    void aBumpedPromptVersionChangesTheKey() {
        String v4 = keys.resolutionKey(MODEL, PROMPT_ID, 4, SYSTEM, "<documents>\n</documents>",
                TICKET, 1.0, 2048L);
        String v5 = keys.resolutionKey(MODEL, PROMPT_ID, 5, SYSTEM, "<documents>\n</documents>",
                TICKET, 1.0, 2048L);

        assertThat(v5).isNotEqualTo(v4);
    }

    @Test
    void lengthPrefixingDefeatsTheSeparatorAliasingV1WasExposedTo() {
        // A REAL collision under v1's encoding, not a hypothetical one. v1 joined raw fields with
        // "\n--\n"; these two field pairs join to a byte-identical string, so they would have shared a
        // cache entry — one ticket served another ticket's answer.
        //
        // v1's comment called this risk "not real here, we control the system prompt". Day 14 killed
        // that premise: the context block is assembled from documents anyone can edit, "--" is one
        // keystroke from a markdown horizontal rule, and the ticket is written by a customer.
        String separatorInsideTheContext = keys.resolutionKey(
                MODEL, PROMPT_ID, PROMPT_VERSION, SYSTEM, "a\n--\nbc", "d", 1.0, 2048L);
        String separatorInsideTheTicket = keys.resolutionKey(
                MODEL, PROMPT_ID, PROMPT_VERSION, SYSTEM, "a", "bc\n--\nd", 1.0, 2048L);

        assertThat(separatorInsideTheTicket).isNotEqualTo(separatorInsideTheContext);
    }

    // ---------------------------------------------------------------- what must NOT change the key

    @Test
    void theSameLogicalRetrievalInADifferentRowOrderProducesTheSameKey() {
        // Canonicality, carried through to its consequence. If arrival order reached the key, one
        // logical result would have several keys and the cache would miss forever while looking
        // healthy in every metric except the bill.
        RetrievedChunk a = chunk(ID_A, "Refund Policy", "thirty days", 0.1);
        RetrievedChunk b = chunk(ID_B, "Shipping Policy", "five days", 0.2);

        assertThat(key(block(b, a))).isEqualTo(key(block(a, b)));
    }

    @Test
    void embeddingDerivedDistancesDoNotReachTheKey() {
        // THE float assertion, stated behaviourally. Distance is the one embedding-derived number
        // anywhere near this path, and embeddings are not bit-reproducible: the Day 14 band
        // measurement observed the same sentence returning distances that differed in the fourth
        // decimal place across identical calls. Let that into the key and every request mints a new
        // one — a 100% miss rate that raises no error, logs no warning, and shows up only as a cost
        // line that never comes down.
        String run1 = key(block(chunk(ID_A, "Refund Policy", "thirty days", 0.2431)));
        String run2 = key(block(chunk(ID_A, "Refund Policy", "thirty days", 0.2437)));

        assertThat(run2).isEqualTo(run1);
    }

    @Test
    void noVectorCanEvenBePassedToTheKeyFactory() {
        // The structural half of the same claim: the behavioural test above shows floats do not
        // change the key today, this shows they CANNOT reach it — there is no parameter one could be
        // passed through. A future edit that wanted to hash an embedding would have to change this
        // signature, which is a visible diff rather than a quiet addition.
        Method resolutionKey = Arrays.stream(CacheKeyFactory.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("resolutionKey"))
                .findFirst()
                .orElseThrow();

        assertThat(resolutionKey.getParameterTypes())
                .as("no array, and no float — a vector has no way in")
                .noneMatch(Class::isArray)
                .noneMatch(type -> type == float.class || type == Float.class);
    }

    // ---------------------------------------------------------------- fixtures

    private String key(String renderedBlock) {
        return keys.resolutionKey(MODEL, PROMPT_ID, PROMPT_VERSION, SYSTEM, renderedBlock,
                TICKET, 1.0, 2048L);
    }

    private String block(RetrievedChunk... chunks) {
        return assembler.assemble(List.of(chunks)).rendered();
    }

    private static RetrievedChunk chunk(UUID id, String breadcrumb, String content, double distance) {
        return new RetrievedChunk(id, "doc.md", 0, breadcrumb, content, 10, distance);
    }
}
