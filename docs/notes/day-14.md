# Day 14 — semantic retrieval on the live request path

Three ADRs, the weak spots they leave behind, the stories worth telling, and a card for revision.

> **On this file's existence.** ADRs have been referenced by number in code comments since Day 7
> (ADR-007a, -012, -014, -016, -018, -019, -020, -021) without ever being written down in the repo.
> Day 14 is the first day whose decisions are too tangled to carry in a comment header — the canary,
> the retrieval policy and the cache reposition each depend on the other two — so the numbered record
> starts here, at 033, continuing the sequence maintained alongside the build.

---

## ADR-033 — Prove the embedding space at boot, against a measured band

**Status:** accepted, live in production config
**Context.** `EmbeddingDimensionCheck` (Day 13) proves vectors are the right WIDTH. Nothing proved
they were in the right SPACE. The Day 12 lab measured what that gap costs: point the query lane at a
different model era without re-embedding the corpus and every similarity score collapses toward
random while the request succeeds, the dimensions match, the ranking looks like a ranking, and the
build stays green. There is no exception to catch, because every layer is behaving exactly as
specified.

**Decision.** At boot, re-embed one stored chunk through the production query lane
(`voyage-4-lite`/`query`) and compare it — using pgvector's own `<=>`, on the real table — against
its stored `voyage-4-large`/`document` vector. Outside a configured band, refuse to boot.

The band is **measured, and the rule was pre-registered before any numbers existed**:

```
band = [ observed_min − 0.5 × (max−min),  observed_max + 0.5 × (max−min) ]

n=20, 2026-08-04:  min=0.24288794  max=0.24363139  mean=0.24342135  spread=0.00074345
                →  [0.242516, 0.244003]
```

**Why not a threshold picked by taste.** A number chosen by intuition and calibrated in production is
not a guard, it is a source of pages. Pre-registering the rule is what stops "the band looked tight
so I widened it" — the honest version of which is "I had no rule".

**Consequences.**
- **Booting now requires Voyage to be reachable.** Accepted, not a bug to fix later. The alternative
  is an application that starts happily while retrieving from a space its queries no longer live in.
- The band is **two-sided**, and that earned itself within a day. The drill's lane flip measured
  **0.0669** — far BELOW the floor. A `distance <= max` guard, which is the shape most people write
  first, would have waved it straight through. The failure presents as the lanes agreeing *better*
  than they ever have.
- An empty corpus **skips** with a warning; a populated corpus missing the canary row **fails**. The
  corpus is ingested BY a boot, so failing on "no corpus yet" would make ingestion impossible to run.
- One INFO line per healthy boot makes the guard a free time series — the band can be re-derived from
  a month of boots without re-running the harness.

**Rejected:** asserting the configured model names, or the row's `embedding_model` column. Both
verify our configuration against itself, which is exactly the check that passes while the geometry
underneath has moved.

---

## ADR-034 — Retrieval policy: distance as data, packed under a token budget

**Status:** accepted
**Context.** Day 4's keyword knowledge base failed silently: reword "how long do I have to return
something?" as "can I get my money back for a hoodie?" and retrieval returned nothing, so Claude
answered from the system prompt with a number nobody had verified.

**Decision.**
1. **Distance is projected by the SQL that did the ordering**, never recomputed in the JVM. A locally
   rescored list can rank differently from the list it arrived in, and then the citation says 0.31
   while the row above it says 0.34 and nobody can explain the ordering. `VectorMath` survives only
   as a unit-test cross-check; it is on no request path.
2. **`k=8`, corpus-relative** — about a quarter of today's 33 chunks. k is the POOL width, not the
   number of chunks the model sees; it costs one `LIMIT` clause, so it can be generous.
3. **`context-token-budget=700`**, derived rather than picked:
   ```
   corpus: n=33  total=4860  min=67  median=133  avg=147.27  p90=189  max=499
   window from "4–5 average chunks":  589 … 736
   700 = 4.75 × avg  → 4 average chunks, 5 median chunks (observed live: 4 chunks / 599 tokens)
   ```
   What pinned it in the upper half is **max=499**: at the window floor of 589, a max-size top hit
   plus a median chunk is 632 and admits only ONE chunk; at 700 it still admits a second.
4. **Adjacency dedup by IDENTITY** — same `source_doc`, chunk indexes one apart. Never by text
   similarity: neighbouring chunks genuinely share bytes (the chunker prepends a 300-char overlap),
   so a similarity test would re-derive at request time a fact the schema already records exactly,
   and it would be a threshold, which is a tuning knob, which is a silent behaviour change. Freed
   budget is re-spent on the next DISTINCT chunk — dedup raises information density, not just cost.
5. **Over budget stops; it does not skip ahead** to a smaller lower-ranked chunk. Stopping keeps the
   packed set a prefix of the ranking, explicable in one sentence: the top of the ranking, as much of
   it as fits.
6. **Canonical assembly.** One byte representation per logical result: sorted by (distance, chunk id)
   rather than arrival order, fixed separators, no timestamps, content verbatim. The **distance is
   deliberately absent from the rendered bytes** — it is embedding-derived and not bit-reproducible,
   so hashing it would mint a new cache key per request.

**Consequences.** Retrieval is on the customer path, so Voyage and Postgres are now customer-facing
dependencies (see ADR-035). Prompt tokens after the cache breakpoint are bounded at ~700 + ticket.

---

## ADR-035 — Retrieve before you key, and degrade when retrieval fails

**Status:** accepted
**Context.** Through Day 13 the response-cache key was computed BEFORE anything was retrieved,
because there was nothing to retrieve: the knowledge base was three entries compiled into the jar, so
its content was already covered by the system-prompt bytes. Day 14 moved the knowledge into a
database that people edit, which breaks that coverage completely.

**Decision (a) — key AFTER retrieval, over the rendered context bytes; prefix `v1` → `v2`.**

A corrected refund window in `kb/` and a re-ingest would change every answer the system should give,
while every input to the old key stayed byte-identical — the stale answer served for a full 24h TTL,
confidently, with a citation attached. Keying on the retrieved bytes makes invalidation surgical: a
KB edit changes the bytes for exactly the tickets that document answers.

Not keyed: **chunk ids alone** (an in-place edit sails through — the id did not move) and **embedding
floats** (a nonce in disguise: the key would never repeat, and the cache would report a permanent 100%
miss while every component reported healthy).

Also upgraded to **length-prefixed field encoding**. v1 joined raw fields with `\n--\n` and called the
aliasing gap "not real here, we control the system prompt". That premise died: the context block is
assembled from documents anyone can edit and the ticket is customer-written, and `--` is one keystroke
from a markdown horizontal rule. `CacheKeyFactoryTest` demonstrates a concrete v1 collision.

**Decision (b) — retrieval failure degrades to ESCALATED_TO_HUMAN at HTTP 200 (Decision 5).**

Moving retrieval ahead of the cache also moved it OUTSIDE the Resilience4j stack, which wraps only
`resolver.resolve`. For one commit that meant a rate-limited embedding call produced a 500 while the
identical failure one layer down produced a human handoff at 200 — same customer, same class of
outage, two experiences, decided by which service happened to be unhealthy.

Allowlist, same taxonomy as Day 8, one layer up: `VoyageTransientException`,
`DataAccessResourceFailureException`, `QueryTimeoutException` degrade. Everything else rethrows —
notably `VoyagePermanentException` (a 401 is OUR bad key, and masking it would let a misconfigured
deployment escalate every ticket while reporting itself healthy).

**Consequences.**
- **A cache hit is no longer free.** It pays one Voyage query embedding and one pgvector search
  before it can discover it is a hit. Worth it — the expensive call is Sonnet, and a hit still skips
  that entirely — but the Day 9 "hit costs nothing" property is gone.
- **Nothing can be written to Redis on the failure path**, and not because we remembered: the key is
  a hash OF the retrieved bytes, so when retrieval fails there is no key. Unreachable beats guarded.
- Re-ingestion mints new chunk uuids, which appear in the rendered block, so a reload invalidates the
  whole keyspace. Correct today (the loader is wipe-and-reload); revisit when Day 15's upsert makes
  ids stable.
- This decision is what made the day's JDBC bounds affordable — a timeout is only as good as what
  happens when it fires, and "escalate to a human" is a far better consequence to size against than
  "return a 500". See *fallback-aware sizing* in `application.yml`.

---

## Weak spots

Honest list. None of these are hypothetical; each is either measured or structurally obvious.

1. **The band may be too tight for the real world.** Spread is ~0.0007 over 20 samples taken within
   one minute, on one machine, against one provider region. It will trip if Voyage updates a model
   behind a stable name — arguably correct, but it has never survived a provider-side change, and
   n=20-in-one-minute is not the same claim as n=20-across-a-week.
2. **The golden set's `expectedSources` are retired ids**, so the sources dimension is quarantined and
   currently measures nothing. Day 16 relabel.
3. **Retrieval quality is unmeasured.** The budget was derived from the corpus's size distribution and
   a rule of thumb, never from an outcome. No recall@k, no sweep at 500 vs 700 vs 900. The live demo's
   nearest hit was 0.4943 and a *Warranty* chunk ranked second for a returns question — plausible
   noise nobody has quantified.
4. **No relevance floor.** Cosine distance is relative and never calibrated: an off-topic question
   still returns a confident ranked list. The grounding instruction is the only thing standing between
   that and a fabricated answer. (Day 16.)
5. **A `</document>` in corpus text would break the frame.** Content is rendered verbatim and
   unescaped. Theoretical while we write every document; not theoretical the moment ingestion accepts
   one we did not.
6. **`socketTimeout=2` applies to the ingestion path too.** Fine at 33 chunks, wrong the day a
   long-running statement exists — and the tempting fix (relax it) silently un-bounds the customer
   path.
7. **One shared Postgres across full-context tests.** `RagResolutionIT` now cleans up after itself, but
   that is a convention, not a mechanism — the next class that seeds rows has to remember.
8. **The canary probes ONE chunk.** It proves the space, not the corpus: 32 of 33 chunks could be
   re-embedded under a different model and this guard would still pass.

---

## Storybank

- **"The guard lied in exactly the failure it existed to catch."** The canary correctly refused the
  boot on a flipped lane — and reported `voyage-4-lite/query` when the client had just sent
  `/document`, because the lane was a literal in a format string. Its likely-causes list sent the
  reader to the model config and the re-ingestion history, both of which were fine. Fixed by routing
  the call and the report through one expression, so there is no second place for them to disagree.
  *Guards protect paths, not intentions — including the paths inside their own error messages.*
- **The two-sided band.** The lane flip measured 0.0669 against a floor of 0.2425. The obvious guard
  (`distance <= max`) would have passed it. Some failures look like things getting better.
- **Where you hash decides what can invalidate you.** Same ticket, same model, same prompt, one word
  edited in a policy document — and the pre-Day-14 key was byte-identical. The fix was not a shorter
  TTL, it was moving one line.
- **The assertion that never ran.** The test written to catch a call-site lane flip did fail when the
  flip happened — via `NoSuchElementException`, because Mockito returns an empty list by default. The
  suite was relying on a default to catch a production defect; the designed `verify(never())` was
  unreachable. Stub the wrong path to SUCCEED, and the failure names the defect.
- **Three requests per minute.** The band harness died twice at exactly three samples. Two runs, two
  rates, same count — that is a rate ceiling, not a burst limit. The fix was to slow the harness, not
  to raise production's retry budget to suit a measurement script.
- **73% of the distance was the lane, not the model.** Same-lane/different-model measured 0.0669;
  different-lane/different-model measured 0.2436. `input_type` does far more work than the model tier
  — which is why the canary has teeth: it measures the thing that breaks silently.

---

## Revision card

| | |
|---|---|
| **Canary pairing** | stored `voyage-4-large`/`document` vs fresh `voyage-4-lite`/`query`, distance by pgvector `<=>` |
| **Band** | `[0.242516, 0.244003]` — n=20, rule `[min−0.5·spread, max+0.5·spread]`, pre-registered |
| **Lane flip measures** | 0.0669 — BELOW the floor. Two-sided bands exist for this |
| **k / budget** | 8 candidates; 700 tokens ≈ 4 avg / 5 median chunks; `max=499` is what pinned it |
| **Dedup** | same doc, `|Δindex| == 1`, by identity — against PACKED chunks only, so 0 and 2 both survive |
| **Budget rule** | over budget STOPS (prefix of the ranking); dedup CONTINUES (freed budget re-spent) |
| **Canonical block** | sort (distance, id) · verbatim content · **no distance in the bytes** |
| **Cache key** | `aura:resolution:v2:` + SHA-256, length-prefixed, over model + prompt id/version + **context bytes** + ticket + params |
| **Never in the key** | chunk ids alone (edits sail through) · embedding floats (permanent miss) |
| **Prompt layout** | grounding line + rules BEFORE the `cache_control` breakpoint (0.1×); documents then ticket AFTER |
| **Ledger** | `sourcesProvided` = `{chunkId, breadcrumb, distance}`, written ONLY by the assembler. Model prose is narration |
| **Degrade paths (3)** | breaker open · retries exhausted · **retrieval unavailable** — all → `ESCALATED_TO_HUMAN` @ 200 |
| **DB bounds** | hikari `connection-timeout: 2000` (ms, `long`) · pgjdbc `socketTimeout: 2` (**seconds**) |
| **Why affordable** | both map to exceptions on Decision 5's allowlist → escalation, not a 500 |
| **Suite** | 168 unit (offline) + 21 IT (Docker). Full app contexts now need Postgres |

**One-sentence summary.** Day 14 put semantic search on the customer path and spent most of its
effort on the three things that would otherwise have failed silently: proving the embedding space at
boot against a measured band, making the retrieved bytes the cache's identity, and giving retrieval
the same degrade path Claude already had.
