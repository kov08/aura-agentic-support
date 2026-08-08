package org.aura.aura.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint's two obligations, tested as a pure function: the SAME document must hash the same
 * however it was checked out, and a DIFFERENT configuration must hash differently even when the
 * document has not moved.
 *
 * <p>No Spring, no database, no network, no temp files — the whole point of extracting this class is
 * that the rule about what counts as "the same document" is decidable from two strings.
 */
class DocumentFingerprinterTest {

    private static final String MODEL = "voyage-4-large";
    private static final int DIMENSION = 1024;

    private static final String BODY_LF = "# Refund Policy\n\nItems may be returned within 30 days.\n";
    private static final String BODY_CRLF = "# Refund Policy\r\n\r\nItems may be returned within 30 days.\r\n";

    // ---------------------------------------------------------------- same document, same hash

    @Test
    void aCrlfCheckoutAndAnLfCheckoutOfOneDocumentHashIdentically() {
        // THE test this component exists for. .gitattributes in this repository sets eol for exactly
        // two path patterns (/mvnw and *.cmd), so every kb/*.md is left to Git's default — checked out
        // CRLF on Windows and LF on Linux. Without the newline fold, the same commit ingested on a
        // developer's laptop and in CI disagrees about every single document, and the pipeline
        // re-embeds the entire corpus, for money, on each hop between them.
        assertThat(fingerprint(BODY_CRLF))
                .as("line endings are a property of the checkout, not of the document")
                .isEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void aByteOrderMarkDoesNotChangeADocumentsIdentity() {
        // A Windows editor saving as "UTF-8 with signature" prepends U+FEFF and changes nothing a
        // reader can see. Same document, and it must be the same fingerprint.
        // (char) 0xFEFF rather than the character itself: pasted in, it is invisible in the source and
        // this test would read as comparing a string to itself.
        assertThat(fingerprint((char) 0xFEFF + BODY_LF)).isEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void trailingWhitespaceIsNotADocumentChange() {
        // Editors disagree about stripping it on save, so it flips back and forth on lines nobody
        // edited. Both the per-line strip and the end-of-document strip are exercised here.
        String noisy = "# Refund Policy   \n   \nItems may be returned within 30 days.\t\n\n\n";
        assertThat(fingerprint(noisy)).isEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void normalizeLeavesInteriorTextExactlyAsWritten() {
        // The guard on the guard. Normalisation exists so two spellings of one document hash alike;
        // if it ever started collapsing interior blank lines or trimming leading indentation, two
        // genuinely different documents would collide and the second would never be re-ingested.
        String preserved = "para one\n\npara two\n    indented\n";
        assertThat(DocumentFingerprinter.normalize(preserved))
                .isEqualTo("para one\n\npara two\n    indented");
    }

    // ---------------------------------------------------------------- different config, different hash

    @Test
    void changingTheEmbeddingModelChangesTheFingerprint() {
        // The reason the fingerprint is not a plain content hash. Swap the document model and every
        // stored vector is in a space the new one does not share — but the FILES are untouched, so a
        // content-only hash reports the whole corpus unchanged and the store quietly holds two eras
        // at once. That is the exact failure the Day 12 cross-model lab measured: scores collapse
        // toward random while nothing throws.
        assertThat(DocumentFingerprinter.fingerprint(BODY_LF, "voyage-4-lite", DIMENSION))
                .isNotEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void changingTheVectorDimensionChangesTheFingerprint() {
        assertThat(DocumentFingerprinter.fingerprint(BODY_LF, MODEL, 512))
                .isNotEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void changingTheContentChangesTheFingerprint() {
        assertThat(fingerprint(BODY_LF.replace("30 days", "45 days")))
                .isNotEqualTo(fingerprint(BODY_LF));
    }

    @Test
    void theFieldSeparatorStopsAdjacentFieldsFromBleedingIntoEachOther() {
        // Without a separator between the hashed fields, content ending "…x" under model "voyage-4"
        // and content ending "…xvoyage" under model "-4" produce one byte stream and one hash — a
        // collision that makes a document permanently un-reingestable, silently. Constructed here so
        // that removing the NUL from DocumentFingerprinter fails a test instead of shipping.
        String a = DocumentFingerprinter.fingerprint("policy" + MODEL, "", DIMENSION);
        String b = DocumentFingerprinter.fingerprint("policy", MODEL, DIMENSION);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void theFingerprintIsAStableSha256Hex() {
        // Length and alphabet, not the value: pinning the literal digest here would make every
        // legitimate CHUNKER_VERSION bump fail this test for the wrong reason.
        assertThat(fingerprint(BODY_LF)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(fingerprint(BODY_LF))
                .as("the same inputs must always produce the same fingerprint — no clock, no salt")
                .isEqualTo(fingerprint(BODY_LF));
    }

    private static String fingerprint(String content) {
        return DocumentFingerprinter.fingerprint(content, MODEL, DIMENSION);
    }
}
