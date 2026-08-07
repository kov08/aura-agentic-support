package org.aura.aura.ingest;

import org.aura.aura.chunker.DocumentChunker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * The identity of an ingested document: a hash over what it SAYS plus how it would be TURNED INTO
 * VECTORS. Pure — no Spring, no I/O, no clock — so the whole thing is exercised by a plain JUnit
 * class with nothing stubbed.
 *
 * <h2>Why the configuration is inside the hash</h2>
 * The naive fingerprint is a content hash, and it answers the wrong question. "Has this file
 * changed?" is not what the pipeline needs to know; it needs "would re-ingesting this document today
 * produce what is already in the store?" — and the answer is no when the file is byte-identical but
 * the embedding model was swapped, or the vector width changed, or the chunker learned to split
 * differently. A content-only hash reports "unchanged" for all three, and the store quietly holds a
 * corpus from two eras at once. The Day 12 lab measured what that costs: every similarity score
 * collapses toward random while nothing throws.
 *
 * <p>So the hash covers four things, and the set is chosen so that anything which changes the OUTPUT
 * changes the fingerprint:
 *
 * <ul>
 *   <li><b>normalised content</b> — what will be chunked</li>
 *   <li><b>{@link DocumentChunker#CHUNKER_VERSION}</b> — how it will be cut up</li>
 *   <li><b>the embedding model id</b> — which space the vectors will land in</li>
 *   <li><b>the dimension</b> — how wide they will be</li>
 * </ul>
 *
 * <p>Conspicuously absent: mtime, file size, inode, the absolute path. Those are properties of a
 * FILESYSTEM, not of a document, and they all change on a fresh {@code git clone} that restores
 * byte-identical bytes. A fingerprint built on any of them re-embeds the entire corpus, for money,
 * every time someone checks the repository out.
 *
 * @see IngestionPlan the pure diff these fingerprints feed
 */
public final class DocumentFingerprinter {

    /**
     * The byte separating the four hashed fields.
     *
     * <p>Concatenating them raw would make the hash ambiguous in a way that is easy to miss and
     * impossible to debug: content ending {@code "…abc"} under model {@code "voyage-4-large"} and
     * content ending {@code "…abcvoyage-4"} under model {@code "-large"} produce the same byte
     * stream and therefore the same fingerprint. NUL is the separator because it is the one byte
     * that cannot appear in any of the four fields — model ids and version strings are identifiers,
     * and a NUL inside a markdown document would mean it is not a text file.
     */
    private static final byte FIELD_SEPARATOR = 0;

    /**
     * The byte-order mark, as the character a {@code Files.readString} leaves at index 0.
     *
     * <p>Written as a code point rather than pasted in as a character, because the character is
     * invisible: a source line containing it looks exactly like a source line that does not, which is
     * the same property that makes it worth stripping in the first place. (A {@code '\}{@code uFEFF'}
     * literal would be equally correct and equally unreadable — javac replaces unicode escapes before
     * it lexes, so both spellings reach the compiler as the same invisible character.)
     */
    private static final char BOM = (char) 0xFEFF;

    private DocumentFingerprinter() {
    }

    /**
     * The fingerprint: SHA-256, hex, over {@code normalize(content)}, the chunker version, the
     * embedding model id and the vector dimension.
     *
     * <p>SHA-256 rather than something cheaper is not a security claim — nobody is attacking this
     * corpus. It is a collision claim: a fingerprint collision does not produce an error, it produces
     * a document that is silently never re-ingested, and the cost of that is unbounded while the cost
     * of a strong hash over a few kilobytes is unmeasurable.
     *
     * @param content          the document's raw text, exactly as read from disk
     * @param embeddingModelId the model the DOCUMENT lane will embed with — the premium one, never
     *                         the query model. Storing a fingerprint stamped with the query model
     *                         would make every document look stale against the store forever.
     * @param dimension        the configured vector width ({@code aura.embedding.dimension})
     */
    public static String fingerprint(String content, String embeddingModelId, int dimension) {
        MessageDigest digest = sha256();
        update(digest, normalize(content));
        update(digest, DocumentChunker.CHUNKER_VERSION);
        update(digest, embeddingModelId);
        // The dimension is hashed as its DECIMAL TEXT, not as four big-endian bytes. Both work; text
        // is chosen because it is the form the number takes everywhere else it appears (a YAML
        // property, a `vector(1024)` type, a log line), and a fingerprint whose inputs can all be
        // reproduced by echoing strings into `sha256sum` is one an operator can verify by hand.
        update(digest, Integer.toString(dimension));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Reduces a document to the form two machines can agree on: BOM stripped, newlines folded to
     * {@code \n}, trailing whitespace removed from every line and from the end of the document.
     *
     * <h2>Each step is a real defect, not defensive tidying</h2>
     * <ul>
     *   <li><b>Newlines.</b> This repository's {@code .gitattributes} sets {@code eol} for exactly
     *       two path patterns, so every {@code kb/*.md} is left to Git's default — which on Windows
     *       checks out CRLF and on Linux checks out LF. Without this fold, the same commit ingested
     *       on a developer's laptop and in CI has different fingerprints for every document, and the
     *       pipeline re-embeds the whole corpus on each hop between them. That is the single most
     *       likely way this component fails in practice, which is why the CRLF-equals-LF case is the
     *       first test in the suite.</li>
     *   <li><b>The BOM.</b> A Windows editor saving as "UTF-8 with signature" prepends {@code U+FEFF}
     *       and changes nothing a reader can see. Same document, different hash. (This project has
     *       already paid once for an invisible encoding difference — the root {@code .env} was UTF-16
     *       and broke {@code docker compose} with no useful error.)</li>
     *   <li><b>Trailing whitespace.</b> Editors disagree about stripping it on save, so it flips back
     *       and forth on lines nobody edited.</li>
     * </ul>
     *
     * <h2>This is an IDENTITY, not the ingested content</h2>
     * The chunker is handed the RAW string, never this one. Normalisation exists so two spellings of
     * the same document hash alike; it must not become a quiet rewrite of what gets embedded. Keeping
     * the two apart means a change here can only ever cause a re-embed, never a corpus that differs
     * from the files on disk.
     *
     * <p>One consequence follows from that separation and is accepted: a file whose ONLY change is
     * trailing whitespace keeps its fingerprint and is not re-ingested, so the stored chunks still
     * carry the old spacing. The vectors are the same either way — the difference is invisible to the
     * embedding model and to a reader — so paying a full re-embed to update it would be buying
     * nothing.
     */
    public static String normalize(String content) {
        String text = content;
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        // lines() splits on \n, \r\n AND a lone \r, so the fold and the split are one operation
        // rather than a replace() pass followed by a split that could disagree with it. It also drops
        // the terminators, which is why the pieces are re-joined with the newline we have chosen.
        return text.lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                // Trailing BLANK LINES survive the join (they are empty elements, not terminators),
                // so the document-level strip is a second, distinct step from the per-line one.
                .stripTrailing();
    }

    private static void update(MessageDigest digest, String field) {
        digest.update(field.getBytes(StandardCharsets.UTF_8));
        digest.update(FIELD_SEPARATOR);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every conforming JVM ships SHA-256, so this is unreachable rather than unhandled. It is
            // wrapped instead of declared because forcing every caller to catch an impossibility
            // would push a checked exception all the way up through the pipeline for nothing.
            throw new IllegalStateException("SHA-256 is required by the Java platform and is missing", e);
        }
    }
}
