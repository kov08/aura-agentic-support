package org.aura.aura.chunker;

import org.aura.aura.config.VoyageProperties;
import org.aura.aura.domain.Chunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a markdown knowledge-base document into embeddable {@link Chunk}s: structure-aware first
 * (markdown headings define the units), with a recursive character fallback for sections that are too
 * big to embed whole.
 *
 * <p>Pure logic, no I/O — the chunker is handed a string and a document name, so it is exercised by a
 * plain JUnit test with no Spring context, no network, and no Voyage key. The two size knobs are
 * injected from {@link VoyageProperties} rather than hardcoded, because they are properties of the
 * EMBEDDING MODEL, not of this algorithm.
 *
 * <h2>Why structure first</h2>
 * A fixed-size window over raw text is the naive approach and it cuts through the middle of the
 * sentence that answers the question. Markdown headings are the author's own statement of where one
 * idea ends and the next begins — free, hand-curated boundaries. We use them, and only fall back to
 * character splitting inside a section that is genuinely too large.
 *
 * <h2>The separator hierarchy</h2>
 * The fallback tries separators in a fixed order: <b>blank line → sentence end → space → hard
 * character cut</b>. That order is not arbitrary; it is ranked by how much meaning a cut destroys.
 * Cutting between paragraphs loses almost nothing. Cutting between sentences loses the link between
 * two consecutive claims. Cutting between words loses the sentence. Cutting mid-word loses the word
 * itself. So we always cut at the largest boundary that gets the piece under the cap, and drop to a
 * smaller one only when the larger one cannot. The hard cut exists purely as a base case — a
 * 3,000-character "paragraph" with no spaces (a base64 blob, a pathological table) must still
 * terminate.
 *
 * <h2>Why a character cap and not a token cap</h2>
 * The real constraint is Voyage's token limit per input, but there is no clean JVM build of Voyage's
 * tokenizer, so counting the true unit would mean shelling out to Python or shipping a guessed vocab.
 * Instead we approximate: at roughly 4 characters per token for English prose, a 2,000-character cap
 * is a proxy for ~500 tokens. This is an APPROXIMATION and is stated as one — text that is dense in
 * punctuation, code, or non-Latin script tokenizes worse than 4:1, so the proxy is deliberately set
 * far below the model's actual limit to absorb that error rather than to maximise chunk size.
 */
@Service
public class DocumentChunker {

    // A markdown ATX heading: 1-6 '#' then the title. setext headings (=== underlines) are not used
    // in kb/ and are not supported — an unsupported heading style degrades to "part of the previous
    // section's body", which is a correctness-preserving failure.
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");

    // kb/ files open with an HTML-comment maintainer note explaining WHY the file exists. That note is
    // metadata for humans, not policy anyone can be answered with, so it is stripped before chunking —
    // otherwise every document would contribute a high-scoring chunk about ADR-007a and the ingestion
    // pipeline, which is precisely the kind of retrieval noise that makes a RAG demo look broken.
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    // The separator hierarchy, in order of increasing damage (see class javadoc). Every pattern is
    // ZERO-WIDTH — it matches a position, not the separator characters — so no text is consumed by the
    // split and concatenating the pieces back together reproduces the input exactly. That property is
    // what lets us pack pieces with a plain join and still get the original prose out, whitespace and
    // all, instead of a version with the paragraph breaks silently deleted.
    private static final Pattern[] SEPARATORS = {
            Pattern.compile("(?<=\\n\\n)"),      // after a blank line — paragraph boundary
            Pattern.compile("(?<=[.!?])(?=\\s)"),// after sentence-ending punctuation, before its space
            Pattern.compile("(?<= )")            // after a space — word boundary
    };

    private final int maxChunkChars;
    private final int overlapChars;

    public DocumentChunker(VoyageProperties props) {
        this.maxChunkChars = props.maxChunkChars();
        this.overlapChars = props.overlapChars();
    }

    /**
     * @param markdown  the raw document text
     * @param sourceDoc the document's name (e.g. {@code "refund-policy.md"}); it is also the breadcrumb
     *                  for any content that appears before the first heading, including a document with
     *                  no headings at all
     */
    public List<Chunk> chunk(String markdown, String sourceDoc) {
        if (markdown == null || markdown.isBlank()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        int position = 0;

        for (Section section : sections(normalize(markdown), sourceDoc)) {
            List<String> parts = split(section.text());
            if (parts.size() == 1) {
                // The common case: the author's own section is already an embeddable unit. It keeps a
                // clean breadcrumb with no "(part i/n)" suffix, so a citation reads as the section it is.
                chunks.add(new Chunk(parts.getFirst(), section.breadcrumb(), sourceDoc, position++));
            } else {
                for (int i = 0; i < parts.size(); i++) {
                    // Sub-chunks SHARE the section's breadcrumb and differ only by the part suffix: they
                    // are the same idea, and retrieval should treat them as siblings, not as unrelated
                    // documents that happen to sit near each other.
                    String breadcrumb = section.breadcrumb() + " (part " + (i + 1) + "/" + parts.size() + ")";
                    chunks.add(new Chunk(parts.get(i), breadcrumb, sourceDoc, position++));
                }
            }
        }
        return List.copyOf(chunks);
    }

    // ---------------------------------------------------------------- structure pass

    private record Section(String breadcrumb, String text) {}

    /**
     * Splits the document into heading-delimited sections. A section's text runs from just after its
     * heading line to the next heading of ANY level — so an H2 that contains H3s owns only its own
     * introductory prose, and each H3 becomes its own section. That is the standard reading of a
     * document outline, and it is what makes the breadcrumb a real path rather than a label.
     *
     * <p>A heading with no body (a pure container, like "## Delivery Problems" followed immediately by
     * "### Lost Parcels") produces NO chunk. Embedding a heading with no content would store a vector
     * for a string of two words that competes with the real answers on every query.
     */
    private List<Section> sections(String text, String sourceDoc) {
        List<Section> sections = new ArrayList<>();
        List<String> path = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        // Content before the first heading (and the whole of a heading-less document) is the ROOT
        // section, breadcrumbed by the file name — the only naming we have when the author gave none.
        String breadcrumb = sourceDoc;

        for (String line : text.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (!heading.matches()) {
                body.append(line).append('\n');
                continue;
            }
            // The heading CLOSES the previous section before it opens the next one.
            flush(sections, breadcrumb, body);

            int level = heading.group(1).length();
            while (path.size() >= level) path.removeLast();
            // A skipped level (H1 straight to H3) leaves a hole; fill it with a blank rather than
            // mis-parenting the deeper heading, and filter blanks out of the rendered path.
            while (path.size() < level - 1) path.add("");
            path.add(heading.group(2));
            breadcrumb = path.stream().filter(p -> !p.isEmpty()).collect(Collectors.joining(" > "));
        }
        flush(sections, breadcrumb, body);
        return sections;
    }

    private void flush(List<Section> sections, String breadcrumb, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (!text.isEmpty()) sections.add(new Section(breadcrumb, text));
    }

    private String normalize(String markdown) {
        return HTML_COMMENT.matcher(markdown).replaceAll("")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("\n{3,}", "\n\n");   // one blank line is a paragraph break; three is noise
    }

    // ---------------------------------------------------------------- recursive fallback

    /**
     * The recursive character fallback, run WITHIN one section — never across a heading. Overlap that
     * crossed a heading would smuggle text from one topic into the chunk of another, and the
     * breadcrumb would then be a lie about what the chunk contains.
     */
    private List<String> split(String text) {
        if (text.length() <= maxChunkChars) return List.of(text);
        return withOverlap(pack(atomize(text, 0)));
    }

    // Effective packing cap. We pack to (cap - overlap) and then prepend the overlap, which is what
    // makes "every chunk is under the cap" true BY CONSTRUCTION rather than by hoping the overlap
    // happens to fit. It costs chunk density; it buys a guarantee.
    private int packCap() {
        return maxChunkChars - overlapChars;
    }

    /**
     * Recursively breaks text into "atoms" — pieces guaranteed to fit the packing cap — descending the
     * separator hierarchy only as far as each oversized piece actually requires. A piece that already
     * fits is returned untouched at whatever level it was reached, so most atoms are whole paragraphs
     * and only the genuinely huge ones get cut into sentences or words.
     */
    private List<String> atomize(String text, int level) {
        if (text.length() <= packCap()) return List.of(text);
        if (level >= SEPARATORS.length) return hardCut(text);

        String[] pieces = SEPARATORS[level].split(text);
        // The separator is absent from this text (a paragraph with no blank lines in it, prose with no
        // sentence punctuation). Splitting achieved nothing, so drop a level rather than recursing on
        // an unchanged string — that check is what stops the recursion from spinning.
        if (pieces.length <= 1) return atomize(text, level + 1);

        List<String> atoms = new ArrayList<>();
        for (String piece : pieces) atoms.addAll(atomize(piece, level + 1));
        return atoms;
    }

    // Base case. Only reached by text with no blank line, no sentence end, and no space inside the cap.
    private List<String> hardCut(String text) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += packCap()) {
            pieces.add(text.substring(i, Math.min(text.length(), i + packCap())));
        }
        return pieces;
    }

    // Greedy packing: fill the current chunk until the next atom would overflow the cap, then start a
    // new one. Greedy (not balanced) on purpose — a balanced split would make every chunk smaller than
    // it needs to be, and chunk boundaries are the thing we are trying to have FEWER of.
    private List<String> pack(List<String> atoms) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String atom : atoms) {
            if (!current.isEmpty() && current.length() + atom.length() > packCap()) {
                packed.add(current.toString());
                current.setLength(0);
            }
            current.append(atom);   // every atom is <= packCap, so `current` can never exceed it
        }
        if (!current.isEmpty()) packed.add(current.toString());
        return packed;
    }

    /**
     * Prepends ~{@code overlapChars} of the previous sub-chunk to each subsequent one.
     *
     * <p>Overlap exists because the boundary is arbitrary from the reader's point of view: a question
     * whose answer straddles the cut ("…up to 20 USD" in one chunk, "once the item is received" in the
     * next) matches neither chunk well without it. Repeating the tail costs storage and buys recall.
     */
    private List<String> withOverlap(List<String> packed) {
        List<String> out = new ArrayList<>(packed.size());
        out.add(packed.getFirst().strip());
        for (int i = 1; i < packed.size(); i++) {
            String prefix = overlapTail(packed.get(i - 1));
            String body = packed.get(i).strip();
            out.add(prefix.isEmpty() ? body : prefix + " " + body);
        }
        return out;
    }

    private String overlapTail(String previous) {
        // One character of the budget pays for the space that joins the overlap to the body, which is
        // what keeps (overlap + body) at or under maxChunkChars exactly.
        int budget = overlapChars - 1;
        if (budget <= 0) return "";

        String tail = previous.length() <= budget
                ? previous
                : previous.substring(previous.length() - budget);
        // Snap FORWARD to the next whitespace so the repeated text never begins mid-word — a truncated
        // leading token is noise in the embedding, and shortening the overlap is the cheap fix.
        for (int i = 0; i < tail.length(); i++) {
            if (Character.isWhitespace(tail.charAt(i))) return tail.substring(i).strip();
        }
        return "";   // no boundary anywhere in the budget (one very long token) — no overlap
    }
}
