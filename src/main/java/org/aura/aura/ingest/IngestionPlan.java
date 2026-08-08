package org.aura.aura.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What one ingestion run intends to do, computed by diffing the corpus on disk against the document
 * ledger in the database. Every path on either side lands in exactly one of four sets.
 *
 * <p>A pure function of two maps — no Spring, no repository, no clock, no network. That is the point
 * of extracting it: the decision "which documents need work" is the part of ingestion with real
 * branching and real edge cases, and separating it from the part with I/O means those edges are
 * tested by constructing two {@code Map}s and asserting on four lists. The orchestration around it
 * still needs mocks; this does not.
 *
 * @param added     on disk, not in the ledger — never ingested (the spec's "new"; {@code new} is a
 *                  keyword, which is the whole reason for the rename)
 * @param changed   in both, with different fingerprints — the content moved, or the configuration
 *                  that turns it into vectors did
 * @param unchanged in both, same fingerprint — the set this whole component exists to make big, and
 *                  the one that costs nothing
 * @param deleted   in the ledger, not on disk — the file is gone and its chunks must go with it
 */
public record IngestionPlan(List<String> added,
                            List<String> changed,
                            List<String> unchanged,
                            List<String> deleted) {

    public IngestionPlan {
        added = List.copyOf(added);
        changed = List.copyOf(changed);
        unchanged = List.copyOf(unchanged);
        deleted = List.copyOf(deleted);
    }

    /**
     * Diffs a scan against the ledger.
     *
     * <p>Both arguments are {@code path -> fingerprint}. They are the same shape on purpose: the disk
     * side computes fingerprints from file content and the current configuration, the stored side
     * reads them out of {@code kb_documents}, and the comparison is then string equality rather than
     * a rule. All of the judgement lives in {@link DocumentFingerprinter}; none of it lives here.
     *
     * <p>Every output list is SORTED. Nothing downstream requires it, which is exactly why it is
     * worth doing: a run's log, its {@code IngestReport}, and the order documents are embedded in all
     * become reproducible, so two runs over the same corpus can be diffed against each other and the
     * differences mean something.
     */
    public static IngestionPlan of(Map<String, String> disk, Map<String, String> stored) {
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        for (String path : new TreeSet<>(disk.keySet())) {
            String storedFingerprint = stored.get(path);
            if (storedFingerprint == null) {
                added.add(path);
            } else if (Objects.equals(storedFingerprint, disk.get(path))) {
                unchanged.add(path);
            } else {
                changed.add(path);
            }
        }

        for (String path : new TreeSet<>(stored.keySet())) {
            if (!disk.containsKey(path)) {
                deleted.add(path);
            }
        }

        return new IngestionPlan(added, changed, unchanged, deleted);
    }

    /**
     * The documents that need embedding, added before changed.
     *
     * <p>The two sets are treated identically downstream — both end in the same delete-then-insert
     * swap — but they are kept apart in the record because they mean different things to a human
     * reading a report. "3 changed" after a policy edit is expected; "3 new" after the same edit
     * means someone renamed a file, and the deleted count will confirm it.
     */
    public List<String> toEmbed() {
        List<String> paths = new ArrayList<>(added.size() + changed.size());
        paths.addAll(added);
        paths.addAll(changed);
        return List.copyOf(paths);
    }

    /** True when the store already matches the corpus — the steady state, and the cheap one. */
    public boolean isNoOp() {
        return added.isEmpty() && changed.isEmpty() && deleted.isEmpty();
    }

    /** How many documents the ledger held when this plan was computed. */
    public int storedCount() {
        return changed.size() + unchanged.size() + deleted.size();
    }
}
