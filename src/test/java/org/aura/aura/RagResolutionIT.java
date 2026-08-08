package org.aura.aura;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.redis.testcontainers.RedisContainer;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.resolver.CachedResolutionService;
import org.aura.aura.resolver.EscalationCause;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;
import org.aura.aura.resolver.ResolverOutput;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.store.KbFixtures;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 14 end to end: a KB-dependent ticket through the REAL request path — real Postgres, real
 * pgvector operator, real RetrievalService, real packing and dedup, real prompt assembly, real
 * cache-aside against a real Redis.
 *
 * <h2>The two things that are faked, and why exactly those two</h2>
 * Voyage and Anthropic. Both are stubbed at their Java boundaries because both are billable network
 * calls whose OUTPUT is not what this test is about: the embedding model's opinion of which chunk is
 * nearest is measured by the manual demo, and Claude's wording is measured by the eval harness.
 * Everything BETWEEN them — the part AURA actually wrote — runs for real.
 *
 * <p>Faking Voyage also buys determinism that no amount of care could otherwise get. The vectors below
 * are hand-built unit and diagonal vectors, so the ranking is arithmetic rather than a model's
 * judgement, and this test can never fail because a provider retrained something.
 */
@ActiveProfiles("test")   // canary off (no live Voyage at boot), ConversationRunner suppressed
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RagResolutionIT extends PostgresBackedContext {

    // A FRESH Redis per run, rather than whatever happens to be on localhost:6379. Determinism: this
    // class asserts hit-vs-miss counts, and a cache that survived a previous run would turn the first
    // assertion into a hit and the resolver-called-once check into a failure that reproduces only on
    // the second execution.
    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private static final String TICKET = "Can I get my money back for a hoodie I bought two weeks ago?";
    private static final int DIM = KbChunk.EMBEDDING_DIMENSION;

    /** The ticket's "embedding". Fixed, so every distance below is a number this test chose. */
    private static final float[] QUERY_VECTOR = unit(0);

    @MockitoBean VoyageEmbeddingClient voyage;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) AnthropicClient anthropic;

    @Autowired CachedResolutionService resolutions;
    @Autowired ChunkRepository chunks;
    @Autowired DocumentRepository documents;
    @Autowired JdbcTemplate jdbc;

    /**
     * Leave the shared corpus as it was found. {@link PostgresBackedContext} hands every full-context
     * class ONE container, so rows written here outlive this class — and the classes that follow
     * (AnthropicTransportIT, RedisDegradationIT) assume an empty {@code kb_chunks} and stub their
     * embedding client accordingly. Cleaning up on the way out is what keeps the suite's result
     * independent of the order JUnit happens to pick.
     */
    @org.junit.jupiter.api.AfterAll
    static void clearSharedCorpus(@Autowired ChunkRepository chunks,
                                  @Autowired DocumentRepository documents) {
        chunks.deleteAllInBatch();
        // The parent rows go too, or the next class in the shared container inherits a ledger
        // describing a corpus that is no longer there.
        documents.deleteAllInBatch();
    }

    /**
     * FIXED chunk ids, and Day 16 is what forced them to be.
     *
     * <p>Until the grounding gates landed, a chunk's uuid was something only the ledger looked at and
     * {@code UUID.randomUUID()} was fine. Now a scripted answer has to CITE one, and G4 checks the
     * citation against the ids the request actually carried — so the id has to be knowable before the
     * request is made. Written down rather than captured after the save, so the canned response and
     * the fixture read as one contract instead of one deriving itself from the other.
     */
    private static final UUID REFUND_0 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID REFUND_1 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID SHIPPING_0 = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID WARRANTY_0 = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    @BeforeEach
    void seedCorpusAndStubs() {
        chunks.deleteAllInBatch();
        chunks.saveAll(List.of(
                // d = 0.0     — the answer
                chunk(REFUND_0, "refund-policy.md", 0, "Refund Policy > Standard Refund Window",
                        "Customers have 30 days from the delivery date to request a refund.", unit(0)),
                // d ≈ 0.2929  — refund#0's NEIGHBOUR: ranks second, and is dropped by adjacency dedup
                chunk(REFUND_1, "refund-policy.md", 1, "Refund Policy > How To Start A Return",
                        "Start a return from your order history and print the prepaid label.", diagonal(2)),
                // d ≈ 0.4226  — a distinct document, and the chunk that inherits the freed budget
                chunk(SHIPPING_0, "shipping-policy.md", 0, "Shipping Policy > Delivery Speeds",
                        "Standard delivery is 3-5 business days and free over 35 USD.", diagonal(3)),
                // d = 1.0     — ranks last and does not fit; the budget stops before it
                chunk(WARRANTY_0, "warranty-policy.md", 0, "Warranty Policy > Coverage Period",
                        "Every product carries a 12-month limited warranty.", unit(1))));

        when(voyage.embedQuery(anyString())).thenReturn(QUERY_VECTOR);
        stubClaude("You're within the 30-day window.", REFUND_0);
    }

    // ---------------------------------------------------------------- retrieval reaches the wire

    @Test
    void aKbDependentTicketIsGroundedAndTheLedgerCarriesRealDistances() {
        Resolution resolution = resolutions.resolve(new ResolveTicketRequest(TICKET));

        assertThat(resolution.answer()).isEqualTo("You're within the 30-day window.");
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);

        assertThat(resolution.sourcesProvided())
                .as("refund#1 is adjacent to refund#0 and is dropped; warranty#0 does not fit the budget")
                .extracting(SourceRef::breadcrumb)
                .containsExactly(
                        "Refund Policy > Standard Refund Window",
                        "Shipping Policy > Delivery Speeds");

        // The distances are POSTGRES'S, projected by the same <=> that ordered the rows — not a JVM
        // recomputation and not a placeholder. The expected values are pure trigonometry on the
        // vectors above: identical direction is distance 0, and 1 - 1/sqrt(3) ≈ 0.4226 for the
        // three-axis diagonal.
        assertThat(resolution.sourcesProvided().get(0).distance()).isCloseTo(0.0, within(1e-6));
        assertThat(resolution.sourcesProvided().get(1).distance()).isCloseTo(0.42265, within(1e-4));

        // Every entry names a chunk that really exists — the ledger is traceable back to bytes.
        assertThat(resolution.sourcesProvided())
                .allSatisfy(ref -> assertThat(chunks.findById(ref.chunkId())).isPresent());
    }

    // ---------------------------------------------------------------- the shape of the request

    @Test
    void theResolverIsCalledWithDocumentsAfterTheBreakpointAndTheTicketLast() {
        resolutions.resolve(new ResolveTicketRequest(TICKET));

        MessageCreateParams sent = captureRequest().rawParams();

        // BEFORE the breakpoint: the static system block, carrying the grounding instruction and the
        // cache_control marker — and NOT a single retrieved byte, which is what keeps the prefix
        // byte-stable across tickets and therefore cacheable at 0.1x.
        List<TextBlockParam> system = sent.system().orElseThrow().textBlockParams().orElseThrow();
        assertThat(system).hasSize(1);
        assertThat(system.getFirst().cacheControl()).isPresent();
        assertThat(system.getFirst().text()).contains("Answer only from the provided documents");
        // The assertion is "no RETRIEVED byte reached the prefix", and as of Day 16 it has to be
        // written that way rather than as doesNotContain("<documents>"): the prompt's few-shot
        // examples now show a real <documents> block, because a few-shot that teaches citations has
        // to show the ids being cited. Testing for the literal tag would now fail on the prompt's own
        // (invented, ExampleCo) content while a genuine leak of THIS request's corpus went unnoticed —
        // which is the assertion inverted. The chunk text is what must never be in the cached prefix.
        assertThat(system.getFirst().text())
                .doesNotContain("Customers have 30 days from the delivery date")
                .doesNotContain("Standard delivery is 3-5 business days");

        // AFTER the breakpoint: the documents, then the ticket.
        String userTurn = sent.messages().getFirst().content().asString();
        assertThat(userTurn.indexOf("<documents>")).isLessThan(userTurn.indexOf("customer ticket:"));
        assertThat(userTurn).endsWith(TICKET);

        // The survivors are in there, with their citation handles as attributes...
        assertThat(userTurn)
                .contains("Customers have 30 days from the delivery date")
                .contains("breadcrumb=\"Refund Policy > Standard Refund Window\"")
                .contains("Standard delivery is 3-5 business days");
        // ...and the deduped sibling and the over-budget chunk are genuinely absent from the request,
        // not merely absent from the ledger. A response that cites two chunks while the prompt carried
        // four would make the ledger a decoration.
        assertThat(userTurn)
                .doesNotContain("print the prepaid label")
                .doesNotContain("12-month limited warranty");
    }

    // ---------------------------------------------------------------- Decision 4, end to end

    @Test
    void anIdenticalTicketAgainstAnUnchangedCorpusHitsTheCache() {
        resolutions.resolve(new ResolveTicketRequest(TICKET));
        Resolution second = resolutions.resolve(new ResolveTicketRequest(TICKET));

        assertThat(second.answer()).isEqualTo("You're within the 30-day window.");
        // The expensive call happened ONCE. Retrieval ran twice — that is the accepted cost of keying
        // on retrieved bytes — but Sonnet, which is where the money is, was paid for once.
        verify(anthropic.messages(), times(1)).create(any(StructuredMessageCreateParams.class));
    }

    @Test
    void editingAChunksContentInvalidatesJustThatTicketsEntry() {
        // THE Decision 4 assertion, and the reason the key moved to after retrieval at all.
        //
        // The corpus is edited IN PLACE: same row, same id, same embedding, one different sentence.
        // Everything the pre-Day-14 key hashed — model, prompt, ticket, generation params — is
        // byte-identical across these two requests. Under that key the second request would have been
        // served the pre-edit answer, confidently and with a citation, for the rest of the 24h TTL.
        resolutions.resolve(new ResolveTicketRequest(TICKET));

        jdbc.update("UPDATE kb_chunks SET content = ? WHERE source_doc = ? AND chunk_index = ?",
                "Customers have 45 days from the delivery date to request a refund.",
                "refund-policy.md", 0);
        stubClaude("You're within the 45-day window.", REFUND_0);

        Resolution afterEdit = resolutions.resolve(new ResolveTicketRequest(TICKET));

        assertThat(afterEdit.answer())
                .as("changed retrieved bytes -> changed key -> a real second call, not a stale hit")
                .isEqualTo("You're within the 45-day window.");
        verify(anthropic.messages(), times(2)).create(any(StructuredMessageCreateParams.class));
    }

    // ---------------------------------------------------------------- Day 16: grounding + the cache

    /**
     * WORK ITEM 5 end to end: a grounding refusal is stored, and a repeat of the same ticket is
     * served from Redis without paying Sonnet again.
     *
     * <p>Unit tests pin the policy against a mocked cache; this pins it against a real one, through
     * the real key. The distinction it rests on is invisible to {@code status} — both this and an
     * Anthropic outage are ESCALATED_TO_HUMAN — so if the cache gate ever slipped back to keying on
     * status, every refusal would silently cost a full model call forever and nothing would fail.
     */
    @Test
    void aGroundingRefusalIsServedFromCacheOnTheSecondIdenticalTicket() {
        stubClaudeRaw("{\"reply\":\"\",\"citations\":[],\"escalate\":true,\"grounded\":false}");

        Resolution first = resolutions.resolve(new ResolveTicketRequest(TICKET));
        Resolution second = resolutions.resolve(new ResolveTicketRequest(TICKET));

        assertThat(first.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(second.status()).isEqualTo(ESCALATED);
        assertThat(second.escalationCause()).isEqualTo(EscalationCause.UNGROUNDED);
        assertThat(second.answer()).isEqualTo(first.answer());
        // ONE model call for two tickets. "The knowledge base does not answer this" is a fact about
        // the corpus, not about this minute, and the Decision 4 key is what makes storing it safe:
        // re-ingesting a document that covers the question changes what this ticket retrieves, which
        // changes the key, which orphans the entry.
        verify(anthropic.messages(), times(1)).create(any(StructuredMessageCreateParams.class));
    }

    /** An availability escalation is the opposite policy, and it must stay that way. */
    @Test
    void anUnreadableOutputEscalationIsNotServedFromCache() {
        stubClaudeRaw("{\"reply\": \"truncated mid-");

        resolutions.resolve(new ResolveTicketRequest(TICKET));
        resolutions.resolve(new ResolveTicketRequest(TICKET));

        // Three attempts per request (the gate-0 retry budget), twice: nothing was stored, so the
        // second ticket re-ran the whole thing rather than inheriting one bad generation for a day.
        verify(anthropic.messages(), times(6)).create(any(StructuredMessageCreateParams.class));
    }

    private static final ResolutionStatus ESCALATED = ResolutionStatus.ESCALATED_TO_HUMAN;

    // ---------------------------------------------------------------- fixtures

    @SuppressWarnings("unchecked")
    private StructuredMessageCreateParams<ResolverOutput> captureRequest() {
        ArgumentCaptor<StructuredMessageCreateParams<ResolverOutput>> captor =
                ArgumentCaptor.forClass(StructuredMessageCreateParams.class);
        verify(anthropic.messages()).create(captor.capture());
        return captor.getValue();
    }

    /** A grounded Day 16 envelope citing {@code cited} — the ids must be ones retrieval supplied. */
    private void stubClaude(String reply, UUID... cited) {
        String ids = java.util.Arrays.stream(cited)
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        stubClaudeRaw("{\"reply\":\"" + reply + "\",\"citations\":" + ids
                + ",\"escalate\":false,\"grounded\":true}");
    }

    @SuppressWarnings("unchecked")
    private void stubClaudeRaw(String envelopeJson) {
        StructuredMessage<ResolverOutput> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(java.util.Optional.of(StopReason.END_TURN));
        // A real SDK block wrapping real JSON, so the typed deserialization path runs rather than
        // being mocked away.
        TextBlock textBlock = TextBlock.builder().text(envelopeJson).citations(List.of()).build();
        when(message.content()).thenReturn(List.of(
                new StructuredContentBlock<>(ResolverOutput.class, ContentBlock.ofText(textBlock))));
        when(anthropic.messages().create(any(StructuredMessageCreateParams.class))).thenReturn(message);
    }

    /**
     * 300 tokens each, against a 700-token budget: two chunks fit, a third does not. Deliberately
     * uniform so the packing outcome is decided by the ranking and the dedup rule rather than by an
     * accident of chunk sizes.
     */
    private KbChunk chunk(UUID id, String doc, int index, String breadcrumb, String content, float[] vector) {
        // Day 15: kb_chunks.document_id is a NOT NULL foreign key, so the parent row has to exist and
        // be flushed before this chunk can be inserted. Non-static now for exactly that reason — it
        // needs the repository.
        return new KbChunk(id, KbFixtures.documentId(documents, doc), doc, index,
                breadcrumb, content, 300, vector, "voyage-4-large");
    }

    /** 1 on one axis, 0 elsewhere. Against unit(0): distance 0 for axis 0, distance 1 for any other. */
    private static float[] unit(int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1.0f;
        return vector;
    }

    /** 1 on the first {@code axes} axes. Against unit(0) the cosine is 1/sqrt(axes). */
    private static float[] diagonal(int axes) {
        float[] vector = new float[DIM];
        for (int i = 0; i < axes; i++) vector[i] = 1.0f;
        return vector;
    }
}
