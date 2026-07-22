package org.aura.aura.evals;

import com.anthropic.errors.BadRequestException;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.ClassifierPromptProvider;
import org.aura.aura.classification.TicketClassificationService;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolverService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden-set eval runner. NOT a unit test — an eval (D1a): it measures judgment, not logic, so it
 * is tagged {@code "eval"} and excluded from the default {@code mvn test} suite by Surefire. Run it
 * with {@code mvn test -Pevals}, which needs a live {@code ANTHROPIC_API_KEY}.
 *
 * <p>It drives the REAL production pipeline in production order — classify (Haiku) then resolve
 * (Sonnet) — for all 24 tickets, sequentially (no parallelism yet: rate-limit simplicity), scores
 * each with the pure {@link EvalScorer}, prints a report, and writes a timestamped JSON results file
 * to {@code docs/evals/} as the committed evidence trail.
 *
 * <p>Only ONE thing hard-fails the run: an ERRORED ticket (see the final assertion). Score dips never
 * fail — they print. A dependency outage does not fail either — it degrades and is counted. That is
 * D1a's "schema validity is the only hard assertion", reframed for structured outputs: with the
 * schema enforced server-side, the way an output becomes unusable is resolve() throwing (a refusal,
 * a truncation, a transport fault), which lands in the ERRORED bucket.
 */
@Tag("eval")
@ActiveProfiles("test")   // suppresses ConversationRunner (@Profile("!test")), which would fire its own live call
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EvalRunner {

    private final TicketClassificationService classifier;

    // D2: the runner injects the INNER ResolverService bean — the one CachedResolutionService wraps
    // (ADR-019) — NOT the caching wrapper. Eval traffic must never read from or write to Redis: a
    // cached answer would score a PAST prompt version, and a cache WRITE would pollute production
    // with eval traffic. Anthropic prefix caching stays on (it changes billing, not output).
    private final ResolverService resolver;

    // Stages are called INDEPENDENTLY, mirroring TicketController.resolve (TicketController:48): there,
    // classification does not feed the resolver — the resolver runs on the raw ticket text and the
    // label only rides along in the response. So the runner does the same. The consequence is that the
    // DOWNSTREAM_OF_MISCLASSIFICATION bucket is structurally zero today: no data path lets a bad label
    // corrupt a reply. It goes live the day Day 19 wires the stages together.
    private final ResolverPromptProvider resolverPrompts;
    private final ClassifierPromptProvider classifierPrompts;

    private final EvalScorer scorer = new EvalScorer();

    @Autowired
    EvalRunner(TicketClassificationService classifier, ResolverService resolver,
               ResolverPromptProvider resolverPrompts, ClassifierPromptProvider classifierPrompts) {
        this.classifier = classifier;
        this.resolver = resolver;
        this.resolverPrompts = resolverPrompts;
        this.classifierPrompts = classifierPrompts;
    }

    @Test
    void runGoldenSet() {
        GoldenSet goldenSet = GoldenSetLoader.load();

        List<Graded> graded = new ArrayList<>();
        List<Errored> errored = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();

        for (EvalTicket ticket : goldenSet.tickets()) {
            try {
                // Production order, stages independent (see field comment). Both calls hit the live API.
                ClassificationResult classification = classifier.classify(ticket.customerMessage());
                Resolution resolution = resolver.resolve(ticket.customerMessage());
                TicketScore score = scorer.score(ticket, classification, resolution);
                graded.add(new Graded(ticket, classification, resolution, score));
            } catch (BadRequestException e) {
                // REJECTED — the API refused our REQUEST as malformed (HTTP 400 invalid_request_error):
                // an INPUT-contract rejection, NOT an our-side output-integrity failure. garbage-02 (a
                // whitespace-only " ") lands here — the Anthropic API rejects a blank user message,
                // which is exactly the input production's @NotBlank guard rejects before any call. The
                // probe drives the service directly (R3), so the API's own rejection IS its finding.
                // Recorded, excluded from scores, and NOT a build failure: the errored gate is about
                // the integrity of OUR output, not the API declining our input. A HIGH rejected count
                // would instead mean a request-building bug — which is why it is reported prominently.
                rejected.add(new Rejected(ticket.id(), ticket.slice(), firstLine(e.getMessage())));
            } catch (Exception e) {
                // ERRORED — an our-side failure (e.g. the resolver's fail-loud IllegalStateException on
                // a bad stop_reason). Own bucket, excluded from scores, and the run continues to ticket
                // 24 rather than aborting. Distinct from DEGRADED (dependency down) and REJECTED (bad input).
                errored.add(new Errored(ticket.id(), ticket.slice(),
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        Aggregate agg = aggregate(goldenSet, graded, errored, rejected);

        String report = renderConsole(agg, graded, errored, rejected);
        System.out.println(report);

        Path resultsFile = writeResultsJson(agg, graded, errored, rejected);
        // Also persist the human-readable console report next to the JSON: Surefire can capture test
        // stdout, and re-running to recover a report costs 48 live calls.
        Path reportFile = sibling(resultsFile, ".txt");
        try {
            Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing eval report text", e);
        }
        System.out.println("\nEval results written to: " + resultsFile.toAbsolutePath());
        System.out.println("Eval report written to:  " + reportFile.toAbsolutePath());

        // THE ONE HARD ASSERTION. Everything else is measurement. An errored ticket is an integrity
        // failure on our side; the report above already lists which and why.
        assertThat(errored)
                .as("eval integrity: no ticket may error (schema/transport/our-side failures). See report above.")
                .isEmpty();
    }

    // ---- aggregation ----------------------------------------------------------------------------

    private Aggregate aggregate(GoldenSet goldenSet, List<Graded> graded, List<Errored> errored,
                               List<Rejected> rejected) {
        int total = goldenSet.tickets().size();

        long classifierDegraded = graded.stream().filter(g -> g.score().classifierDegraded()).count();
        long resolverDegraded = graded.stream().filter(g -> g.score().resolverDegraded()).count();
        long fullyDegraded = graded.stream().filter(g -> g.score().fullyDegraded()).count();

        // Pass rate is over tickets with at least one graded stage.
        List<Graded> scorable = graded.stream().filter(g -> !g.score().fullyDegraded()).toList();
        long passed = scorable.stream().filter(g -> g.score().passed()).count();

        // Per-field accuracy, each over its own applicable denominator.
        Accuracy category = accuracy(graded, g -> !g.score().classifierDegraded(),
                g -> Boolean.TRUE.equals(g.score().categoryMatch()));
        Accuracy urgency = accuracy(graded, g -> !g.score().classifierDegraded(),
                g -> Boolean.TRUE.equals(g.score().urgencyMatch()));
        Accuracy intent = accuracy(graded, g -> !g.score().classifierDegraded(),
                g -> Boolean.TRUE.equals(g.score().intentMatch()));
        Accuracy escalate = accuracy(graded, g -> !g.score().resolverDegraded(),
                g -> Boolean.TRUE.equals(g.score().escalateMatch()));
        Accuracy sources = accuracy(graded,
                g -> g.score().sourcesResult().grade() != TicketScore.SourcesResult.Grade.NOT_GRADED,
                g -> g.score().sourcesResult().grade() == TicketScore.SourcesResult.Grade.PASS);

        // Escalate majority-class floor: a lazy model answering the more common label on every ticket
        // scores this without understanding anything. Judge escalate accuracy against THIS, not zero.
        long escalateTrueLabels = scorable.stream().filter(g -> g.ticket().expected().escalate()).count();
        long escalateFloorHits = Math.max(escalateTrueLabels, scorable.size() - escalateTrueLabels);

        // Reply-text rules are a resolver-stage dimension: how many rule-carrying tickets had a
        // mustContain miss or a mustNot violation (over resolver-graded tickets only).
        List<Graded> resolverGraded = graded.stream().filter(g -> !g.score().resolverDegraded()).toList();
        int replyRuleTickets = (int) resolverGraded.stream()
                .filter(g -> !g.ticket().expected().mustContain().isEmpty()
                        || !g.ticket().expected().mustNotContain().isEmpty())
                .count();
        int mustContainFail = (int) resolverGraded.stream()
                .filter(g -> !g.score().mustContainMisses().isEmpty()).count();
        int mustNotFail = (int) resolverGraded.stream()
                .filter(g -> !g.score().mustNotViolations().isEmpty()).count();

        Map<String, Accuracy> perCategory = new LinkedHashMap<>();
        scorable.stream().map(g -> g.ticket().expected().category().name()).distinct().sorted()
                .forEach(cat -> perCategory.put(cat, passRate(scorable,
                        g -> g.ticket().expected().category().name().equals(cat))));

        Map<String, Accuracy> perSlice = new LinkedHashMap<>();
        scorable.stream().map(g -> g.ticket().slice()).distinct().sorted()
                .forEach(slice -> perSlice.put(slice, passRate(scorable,
                        g -> g.ticket().slice().equals(slice))));

        return new Aggregate(
                total, scorable.size(), (int) passed,
                (int) classifierDegraded, (int) resolverDegraded, (int) fullyDegraded,
                errored.size(), rejected.size(),
                category, urgency, intent, escalate, sources,
                new Accuracy((int) escalateFloorHits, scorable.size()),
                replyRuleTickets, mustContainFail, mustNotFail,
                perCategory, perSlice);
    }

    private Accuracy accuracy(List<Graded> graded, Predicate<Graded> applicable, Predicate<Graded> hit) {
        List<Graded> applicableRows = graded.stream().filter(applicable).toList();
        int hits = (int) applicableRows.stream().filter(hit).count();
        return new Accuracy(hits, applicableRows.size());
    }

    private Accuracy passRate(List<Graded> scorable, Predicate<Graded> inGroup) {
        List<Graded> group = scorable.stream().filter(inGroup).toList();
        int passed = (int) group.stream().filter(g -> g.score().passed()).count();
        return new Accuracy(passed, group.size());
    }

    // ---- console report -------------------------------------------------------------------------

    private String renderConsole(Aggregate agg, List<Graded> graded, List<Errored> errored,
                                List<Rejected> rejected) {
        StringBuilder b = new StringBuilder();
        String rule = "=".repeat(78);
        b.append(rule).append("\n");
        b.append("AURA GOLDEN-SET EVAL — run report\n");
        b.append(rule).append("\n");
        b.append(String.format("resolverPromptVersion=%d  classifierPromptVersion=%d  goldenSetVersion=%d%n",
                resolverPrompts.promptVersion(), classifierPrompts.promptVersion(), loadGoldenVersion()));
        b.append("timestamp=").append(LocalDateTime.now()).append("\n\n");

        b.append(String.format("tickets: %d total  |  %d scored  |  %d rejected  |  %d errored%n",
                agg.total(), agg.scored(), agg.rejected(), agg.errored()));
        b.append(String.format("degraded (excluded from scores): classifier=%d  resolver=%d  fully=%d%n",
                agg.classifierDegraded(), agg.resolverDegraded(), agg.fullyDegraded()));
        b.append("  DEGRADED = a Resilience4j fallback (dependency down). ERRORED = our-side output failure.\n");
        b.append("  REJECTED = the API refused our INPUT (HTTP 400) — the input-contract probe firing.\n");
        b.append("  DOWNSTREAM_OF_MISCLASSIFICATION = structurally zero — stages independent (TicketController:48).\n\n");

        // Reported per STAGE (amendment 7): the two services are graded independently, so their
        // accuracy is presented independently. category/urgency/intent come from the classifier (Haiku);
        // escalate/sources/reply-rules come from the resolver (Sonnet).
        b.append("CLASSIFIER STAGE (Haiku) — structured-field accuracy, strict exact match\n");
        b.append(String.format("  category : %s%n", agg.category()));
        b.append(String.format("  urgency  : %s%n", agg.urgency()));
        b.append(String.format("  intent   : %s%n", agg.intent()));
        b.append("\n");
        b.append("RESOLVER STAGE (Sonnet)\n");
        b.append(String.format("  escalate : %s   [majority-class floor: %s — judge against THIS, not zero]%n",
                agg.escalate(), agg.escalateFloor()));
        b.append(String.format("  sources  : %s   (graded tickets only; null labels excluded)%n", agg.sources()));
        b.append(String.format("  reply    : %d rule-carrying ticket(s); %d with a mustContain miss, %d with a mustNot violation%n",
                agg.replyRuleTickets(), agg.replyMustContainFail(), agg.replyMustNotFail()));
        b.append("\n");

        b.append("PER-CATEGORY pass rate (by label category)\n");
        agg.perCategory().forEach((cat, acc) -> b.append(String.format("  %-22s %s%n", cat, acc)));
        b.append("\n");

        b.append("PER-SLICE pass rate\n");
        agg.perSlice().forEach((slice, acc) -> b.append(String.format("  %-14s %s%n", slice, acc)));
        b.append("\n");

        b.append(String.format("OVERALL TICKET PASS RATE: %s%n%n",
                new Accuracy(agg.passed(), agg.scored())));

        // Failed + errored detail.
        b.append("FAILURES\n");
        boolean any = false;
        for (Graded g : graded) {
            TicketScore s = g.score();
            if (s.fullyDegraded() || s.passed()) continue;
            any = true;
            b.append(renderFailure(g));
        }
        for (Errored e : errored) {
            any = true;
            b.append(String.format("  [%s] %-13s ERRORED — %s%n", e.id(), e.slice(), e.error()));
        }
        if (!any) b.append("  (none — every scored ticket passed)\n");

        if (!rejected.isEmpty()) {
            b.append("\nREJECTED (input-contract probe — API refused the input, excluded from scores)\n");
            for (Rejected r : rejected) {
                b.append(String.format("  [%s] %-13s %s%n", r.id(), r.slice(), r.reason()));
            }
        }
        b.append(rule).append("\n");
        return b.toString();
    }

    private String renderFailure(Graded g) {
        TicketScore s = g.score();
        StringBuilder b = new StringBuilder();
        b.append(String.format("  [%s] %s%n", s.ticketId(), s.slice()));
        var expected = g.ticket().expected();
        var actualClass = g.classification().classification();

        if (!s.classifierDegraded()) {
            if (Boolean.FALSE.equals(s.categoryMatch())) {
                b.append(String.format("      category: expected %s, actual %s%n",
                        expected.category(), actualClass.category()));
            }
            if (Boolean.FALSE.equals(s.urgencyMatch())) {
                b.append(String.format("      urgency:  expected %s, actual %s%n",
                        expected.urgency(), actualClass.urgency()));
            }
            if (Boolean.FALSE.equals(s.intentMatch())) {
                b.append(String.format("      intent:   expected %s, actual %s%n",
                        expected.intent(), actualClass.intent()));
            }
        }
        if (!s.resolverDegraded()) {
            if (Boolean.FALSE.equals(s.escalateMatch())) {
                b.append(String.format("      escalate: expected %s, actual %s%n",
                        expected.escalate(), g.resolution().escalate()));
            }
            if (s.sourcesResult().isFailure()) {
                b.append(String.format("      sources:  missing=%s extra=%s (actual=%s)%n",
                        s.sourcesResult().missing(), s.sourcesResult().extra(), g.resolution().sourcesUsed()));
            }
            for (TicketScore.RuleViolation miss : s.mustContainMisses()) {
                b.append(String.format("      mustContain MISS: \"%s\" not in reply%n", miss.fragment()));
            }
            for (TicketScore.RuleViolation v : s.mustNotViolations()) {
                b.append(String.format("      mustNot VIOLATION: \"%s\" — context: %s%n",
                        v.fragment(), v.context()));
            }
        }
        return b.toString();
    }

    // ---- JSON results file ----------------------------------------------------------------------

    private Path writeResultsJson(Aggregate agg, List<Graded> graded, List<Errored> errored,
                                 List<Rejected> rejected) {
        // No Date.now() restriction here — this is ordinary JUnit code, not a Workflow script.
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));

        List<Map<String, Object>> ticketRows = new ArrayList<>();
        for (Graded g : graded) ticketRows.add(ticketRow(g));
        for (Rejected r : rejected) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("slice", r.slice());
            row.put("outcome", "REJECTED");
            row.put("reason", r.reason());
            ticketRows.add(row);
        }
        for (Errored e : errored) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("slice", e.slice());
            row.put("outcome", "ERRORED");
            row.put("error", e.error());
            ticketRows.add(row);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resolverPromptVersion", resolverPrompts.promptVersion());
        root.put("classifierPromptVersion", classifierPrompts.promptVersion());
        root.put("goldenSetVersion", loadGoldenVersion());
        root.put("timestamp", LocalDateTime.now().toString());
        root.put("counts", counts(agg));
        Map<String, Object> classifierStage = new LinkedHashMap<>();
        classifierStage.put("category", agg.category().toRatio());
        classifierStage.put("urgency", agg.urgency().toRatio());
        classifierStage.put("intent", agg.intent().toRatio());
        root.put("classifierStage", classifierStage);

        Map<String, Object> resolverStage = new LinkedHashMap<>();
        resolverStage.put("escalate", agg.escalate().toRatio());
        resolverStage.put("escalateMajorityClassFloor", agg.escalateFloor().toRatio());
        resolverStage.put("sources", agg.sources().toRatio());
        resolverStage.put("replyRuleTickets", agg.replyRuleTickets());
        resolverStage.put("replyMustContainFail", agg.replyMustContainFail());
        resolverStage.put("replyMustNotFail", agg.replyMustNotFail());
        root.put("resolverStage", resolverStage);
        root.put("perCategoryPassRate", ratios(agg.perCategory()));
        root.put("perSlicePassRate", ratios(agg.perSlice()));
        root.put("overallPassRate", new Accuracy(agg.passed(), agg.scored()).toRatio());
        root.put("tickets", ticketRows);

        try {
            Path dir = Path.of("docs", "evals");
            Files.createDirectories(dir);
            // Self-describing name: the version triple is IN the filename, so the docs/evals trail
            // reads at a glance (cls1 = baseline classifier, cls2 = urgency-rubric experiment, ...)
            // without opening each file. Not every run is a "baseline" — the prefix must not imply it.
            Path file = dir.resolve(String.format("eval-cls%d-res%d-gs%d-%s.json",
                    classifierPrompts.promptVersion(), resolverPrompts.promptVersion(), loadGoldenVersion(), stamp));
            Files.writeString(file, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing eval results", e);
        }
    }

    private Map<String, Object> ticketRow(Graded g) {
        TicketScore s = g.score();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.ticketId());
        row.put("slice", s.slice());
        row.put("outcome", s.fullyDegraded() ? "DEGRADED" : (s.passed() ? "PASS" : "FAIL"));
        row.put("classifierDegraded", s.classifierDegraded());
        row.put("resolverDegraded", s.resolverDegraded());
        row.put("categoryMatch", s.categoryMatch());
        row.put("urgencyMatch", s.urgencyMatch());
        row.put("intentMatch", s.intentMatch());
        row.put("escalateMatch", s.escalateMatch());
        row.put("sourcesGrade", s.sourcesResult().grade().name());
        row.put("sourcesMissing", s.sourcesResult().missing());
        row.put("sourcesExtra", s.sourcesResult().extra());
        row.put("mustContainMisses", s.mustContainMisses().stream().map(TicketScore.RuleViolation::fragment).toList());
        row.put("mustNotViolations", s.mustNotViolations().stream()
                .map(v -> Map.of("fragment", v.fragment(), "context", v.context())).toList());
        // Actuals, so a committed result is self-contained evidence without a re-run.
        row.put("actualCategory", g.classification().classification().category().name());
        row.put("actualUrgency", g.classification().classification().urgency().name());
        row.put("actualIntent", g.classification().classification().intent().name());
        row.put("actualEscalate", g.resolution().escalate());
        row.put("actualSources", g.resolution().sourcesUsed());
        return row;
    }

    private Map<String, Object> counts(Aggregate agg) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("total", agg.total());
        c.put("scored", agg.scored());
        c.put("passed", agg.passed());
        c.put("rejected", agg.rejected());
        c.put("errored", agg.errored());
        c.put("classifierDegraded", agg.classifierDegraded());
        c.put("resolverDegraded", agg.resolverDegraded());
        c.put("fullyDegraded", agg.fullyDegraded());
        c.put("downstreamOfMisclassification", 0);
        return c;
    }

    private Map<String, String> ratios(Map<String, Accuracy> src) {
        Map<String, String> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v.toRatio()));
        return out;
    }

    private int loadGoldenVersion() {
        return GoldenSetLoader.load().goldenSetVersion();
    }

    private static Path sibling(Path file, String newExtension) {
        String name = file.getFileName().toString().replaceFirst("\\.json$", "") + newExtension;
        return file.resolveSibling(name);
    }

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    // ---- small value types ----------------------------------------------------------------------

    private record Graded(EvalTicket ticket, ClassificationResult classification,
                          Resolution resolution, TicketScore score) {}

    private record Errored(String id, String slice, String error) {}

    private record Rejected(String id, String slice, String reason) {}

    // The Anthropic 400 body is a full JSON line; keep the report readable by taking its first line.
    private static String firstLine(String message) {
        if (message == null) return "HTTP 400 invalid_request_error";
        int nl = message.indexOf('\n');
        return nl >= 0 ? message.substring(0, nl) : message;
    }

    private record Accuracy(int hits, int total) {
        @Override
        public String toString() {
            double pct = total == 0 ? 0.0 : 100.0 * hits / total;
            return String.format("%d/%d (%.1f%%)", hits, total, pct);
        }
        String toRatio() {
            return toString();
        }
    }

    private record Aggregate(
            int total, int scored, int passed,
            int classifierDegraded, int resolverDegraded, int fullyDegraded, int errored, int rejected,
            Accuracy category, Accuracy urgency, Accuracy intent, Accuracy escalate, Accuracy sources,
            Accuracy escalateFloor,
            int replyRuleTickets, int replyMustContainFail, int replyMustNotFail,
            Map<String, Accuracy> perCategory, Map<String, Accuracy> perSlice) {}
}
