package org.aura.aura.streaming;

import org.aura.aura.resolver.ResolverOutput;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Day 10 testing centerpiece. {@link StreamingReplyExtractor} is the only thing standing between
 * a structured-output JSON envelope and the customer's screen, and its entire difficulty is that a
 * network chunk can split anywhere — so these tests attack the split positions directly rather than
 * trusting a happy path.
 *
 * <p>Pure and deterministic: no Spring, no network, no API key. Runs in the DEFAULT {@code mvn test}
 * suite.
 */
class StreamingReplyExtractorTest {

    /** ONE backslash. Spelled out so the JSON fixtures below stay readable instead of leaning. */
    private static final String BS = "\\";

    /**
     * One reply exercising every hazard at once: an escaped quote (which must NOT terminate the
     * string), a newline escape, a literal escaped backslash, a {@code \\uXXXX} escape, and
     * braces/percent signs that are ordinary text rather than structure.
     */
    private static final String JSON =
            "{\"reply\":\"Line one." + BS + "n"
            + "He said " + BS + "\"hi" + BS + "\" about {braces} and 50%" + BS + BS + " caf" + BS + "u00e9.\","
            // Day 16: the envelope carries four fields now, and this fixture carries all four for a
            // reason that is not tidiness. `grounded` is a PRIMITIVE boolean, so an envelope missing
            // it does not quietly default to false — Jackson refuses the whole object. A fixture
            // still shaped like Day 10's two-field envelope would therefore test a parse that can
            // never happen in production, and would keep passing while the real one broke.
            + "\"citations\":[\"chunk-a\",\"chunk-b\"],\"escalate\":true,\"grounded\":true}";

    private static final String EXPECTED_REPLY =
            "Line one.\nHe said \"hi\" about {braces} and 50%\\ café.";

    private static String feedAll(String... chunks) {
        StreamingReplyExtractor extractor = new StreamingReplyExtractor();
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) out.append(extractor.accept(chunk));
        return out.toString();
    }

    @Test
    void singleChunk_emitsUnescapedReplyOnly() {
        assertThat(feedAll(JSON)).isEqualTo(EXPECTED_REPLY);
    }

    // The core property: output must be independent of where the network happened to cut. Every
    // single-split point is tested, which necessarily covers mid-key, mid-colon, mid-escape,
    // mid-unicode-escape and mid-closing-quote without having to enumerate them by hand.
    @Test
    void splitAtEveryPosition_producesIdenticalOutput() {
        for (int i = 0; i <= JSON.length(); i++) {
            String head = JSON.substring(0, i);
            String tail = JSON.substring(i);

            assertThat(feedAll(head, tail))
                    .as("split after %d characters (%s | %s)", i, head, tail)
                    .isEqualTo(EXPECTED_REPLY);
        }
    }

    // Maximal fragmentation: one character per delta. If any state is held in a local instead of a
    // field, this is what exposes it.
    @Test
    void characterByCharacter_producesIdenticalOutput() {
        StreamingReplyExtractor extractor = new StreamingReplyExtractor();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < JSON.length(); i++) {
            out.append(extractor.accept(String.valueOf(JSON.charAt(i))));
        }
        assertThat(out.toString()).isEqualTo(EXPECTED_REPLY);
    }

    @Test
    void emptyAndNullChunks_areIgnored() {
        assertThat(feedAll("", null, JSON, "", null)).isEqualTo(EXPECTED_REPLY);
    }

    // SUPPRESSION, opening side: the envelope's scaffolding must never reach the customer. Feeding
    // everything up to and including the opening quote must emit absolutely nothing.
    @Test
    void nothingIsEmittedBeforeTheReplyStringOpens() {
        StreamingReplyExtractor extractor = new StreamingReplyExtractor();

        assertThat(extractor.accept("{")).isEmpty();
        assertThat(extractor.accept("\"rep")).isEmpty();
        assertThat(extractor.accept("ly\"")).isEmpty();
        assertThat(extractor.accept("  :  ")).isEmpty();
        assertThat(extractor.accept("\"")).isEmpty();
        // The first real character arrives only now.
        assertThat(extractor.accept("H")).isEqualTo("H");
    }

    // SUPPRESSION, closing side: once the reply's closing quote lands, the escalate field and the
    // closing brace are structure, not content — a leak here would print `,"escalate":true}` at the
    // end of every streamed answer.
    @Test
    void nothingIsEmittedAfterTheReplyStringCloses() {
        StreamingReplyExtractor extractor = new StreamingReplyExtractor();
        extractor.accept("{\"reply\":\"done\"");

        assertThat(extractor.isComplete()).isTrue();
        assertThat(extractor.accept(",\"escalate\":true")).isEmpty();
        assertThat(extractor.accept("}")).isEmpty();
        assertThat(feedAll(JSON)).doesNotContain("escalate");
    }

    // An escaped quote is the highest-consequence case: mistaking it for the terminator would
    // silently truncate every reply that quotes the customer back to themselves.
    @Test
    void escapedQuoteDoesNotTerminateTheReply() {
        String json = "{\"reply\":\"You wrote " + BS + "\"refund me" + BS + "\" and I hear you.\",\"escalate\":true}";

        assertThat(feedAll(json)).isEqualTo("You wrote \"refund me\" and I hear you.");
    }

    // A trailing escaped backslash must consume its own escape, so the very next quote still closes
    // the string. Getting this wrong runs the parser off the end of the document.
    @Test
    void escapedBackslashImmediatelyBeforeClosingQuote_stillTerminates() {
        String json = "{\"reply\":\"path is C:" + BS + BS + "\",\"escalate\":false}";

        assertThat(feedAll(json)).isEqualTo("path is C:\\");
        assertThat(feedAll(json)).doesNotContain("escalate");
    }

    @Test
    void unicodeEscapeSplitAcrossChunks_isReassembled() {
        // Split the four hex digits across three separate deltas.
        assertThat(feedAll("{\"reply\":\"caf", BS + "u", "00", "e", "9 time\"}"))
                .isEqualTo("café time");
    }

    // Surrogate pairs need no special handling — two consecutive code units append into one
    // character. Asserting it locks that reasoning in.
    @Test
    void surrogatePairEscape_producesASingleEmoji() {
        assertThat(feedAll("{\"reply\":\"ok " + BS + "ud83d" + BS + "ude00\"}"))
                .isEqualTo("ok 😀");
    }

    // Note BOTH inner quotes are escaped. An earlier draft of this fixture escaped only the opening
    // one, which is malformed JSON — and the extractor correctly stopped at the first unescaped
    // quote, exactly as a parser should. Keeping the note because the failure looked like an
    // extractor bug and was a fixture bug.
    @Test
    void replyContainingJsonLikeText_isTreatedAsPlainText() {
        String json = "{\"reply\":\"Send {" + BS + "\"order" + BS + "\":123} to support.\",\"escalate\":false}";

        assertThat(feedAll(json)).isEqualTo("Send {\"order\":123} to support.");
    }

    @Test
    void isComplete_isFalseUntilTheClosingQuoteArrives() {
        StreamingReplyExtractor extractor = new StreamingReplyExtractor();

        extractor.accept("{\"reply\":\"partial");
        assertThat(extractor.isComplete()).isFalse();

        extractor.accept("\"");
        assertThat(extractor.isComplete()).isTrue();
    }

    // END-OF-STREAM PARSE. The pump accumulates the raw JSON alongside forwarding and parses it once
    // the stream closes — that parse is both the schema-validity check and the only place the
    // streaming path learns the escalate verdict. These two assert the envelope the extractor was
    // reading is the same one Jackson deserializes.
    @Test
    void accumulatedEnvelopeParsesWithEscalateTrue() {
        ObjectMapper mapper = JsonMapper.builder().build();

        ResolverOutput output = mapper.readValue(JSON, ResolverOutput.class);

        assertThat(output.escalate()).isTrue();
        assertThat(output.reply()).isEqualTo(EXPECTED_REPLY);
        assertThat(output.citations()).containsExactly("chunk-a", "chunk-b");
        assertThat(output.grounded()).isTrue();
    }

    @Test
    void accumulatedEnvelopeParsesWithEscalateFalse() {
        ObjectMapper mapper = JsonMapper.builder().build();

        ResolverOutput output = mapper.readValue(
                "{\"reply\":\"All set — your order ships tomorrow.\",\"citations\":[\"chunk-a\"],"
                        + "\"escalate\":false,\"grounded\":true}", ResolverOutput.class);

        assertThat(output.escalate()).isFalse();
        assertThat(output.reply()).isEqualTo("All set — your order ships tomorrow.");
    }

    /**
     * The extractor is DONE at the reply's closing quote, so the two fields Day 16 added stream past
     * it and are suppressed like {@code escalate} always was. Worth an assertion rather than an
     * assumption: {@code citations} is the first ARRAY the envelope has ever carried, and its
     * elements are quoted strings — the one character class the state machine treats as structural.
     * A regression that let the machine re-enter IN_STRING on a citation id would paint chunk ids
     * across the customer's screen.
     */
    @Test
    void citationsAndGroundedNeverReachTheCustomer() {
        String envelope = "{\"reply\":\"Blue, in one size.\","
                + "\"citations\":[\"example-chunk-1\",\"example-chunk-2\"],"
                + "\"escalate\":false,\"grounded\":true}";

        assertThat(feedAll(envelope)).isEqualTo("Blue, in one size.");
    }

    /** The same, split so a chunk boundary falls inside a citation id. */
    @Test
    void citationsAreSuppressedEvenWhenAChunkBoundarySplitsAnId() {
        assertThat(feedAll("{\"reply\":\"Blue.\",\"citations\":[\"example-ch",
                "unk-1\"],\"escalate\":false,\"grounded\":true}"))
                .isEqualTo("Blue.");
    }
}
