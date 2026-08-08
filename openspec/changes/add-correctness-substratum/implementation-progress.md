# Implementation Progress

**SINGLE SOURCE OF TRUTH for progress.** `tasks.md` is derived output,
regenerated from this file at every checkpoint (apply Step 13) — never
hand-maintained in parallel.

Change: `add-correctness-substratum` · 6 specs · risk **High** · all gate tiers
`separate` (12 human gates + 6 checkpoints).

Seeded unchecked 2026-08-08.

## Sequence

- [x] 1. `specs/correctness-invariant/spec.md`
- [ ] 2. `specs/evidence-ledger/spec.md`
- [ ] 3. `specs/chain-state/spec.md`
- [ ] 4. `specs/gate-payload/spec.md`
- [ ] 5. `specs/checkpoint-from-ledger/spec.md`
- [ ] 6. `specs/hook-tiers/spec.md`

## Per-spec state

<!-- Baseline SHA is recorded at each spec's Step 0 on a clean tree; every diff
     that spec's rings compute is against it. Ring cells: ✅ green, ❌ failing,
     ⏭️ skipped-with-impact, — not applicable, blank not yet run. -->

### 1. correctness-invariant

**Step 0 complete 2026-08-08** — tree clean; baseline recorded; registry gate
passed (`registry-check: OK`, 740 tokens / 15 spec refs); inventory snapshot
taken at `inventory-snapshots/correctness-invariant-before.md`.
Concept check: this spec's "Concepts Used" tables declare **none** — no Scala
type is read or written, so there is nothing to import and nothing to avoid
recreating. Proof Obligations verified complete at spec-lint (F4/F6/F7/F8
clean). Public-type-change impact scan: **not applicable** — no public type is
aliased, widened, or has its variant set changed. No MUST-CONFIRM items.

| Field | Value |
|-------|-------|
| Baseline SHA | `291eb22a7434f550b028f13b3be067e24ae7af49` |
| Gate 1 — typed contract (waiver) | ☑ **approved** 2026-08-08 |
| Gate 2 — test oracle + polarity | ☑ **approved** 2026-08-08 (17 RED / 3 green-by-design) |
| R0 parse / `bash -n` | ✅ YAML parses; `openspec validate --strict` valid; all changed scripts `bash -n` clean |
| R1 shellcheck + shfmt | ✅ shellcheck 0 findings across 11 `.sh` + 1 `.bash`; shfmt `-i 2 -ci` clean on the added file |
| R3 bats | ✅ **24/24**; polarity confirmed (17 red→green, 3 green-by-design held) |
| R8 adversarial | ✅ after fixing **1 FAIL** — see below. `fresh-context: NO` |
| Concept delta | ✅ **EMPTY** — before/after scanner snapshots byte-identical |
| Build-dependency delta | ✅ `build.sbt`/`project/` empty; **host prerequisites reported**: `jq`, `shellcheck`, `bats`, `shfmt` |
| Commit | ✅ (see checkpoint) |

**Ring 8 finding (FAIL → fixed).** R1 requires the definition be stated *"so
that it is carried into every artifact instruction"*. It was written into the
schema `description`, and all nine presence tests passed — but the CLI does
**not** emit `description`:
`openspec instructions apply | grep -c "CLAIM OUTRUN"` returned **0**.
The definition was present and unreachable: documentation, not a substratum,
and the tests could not see the difference because they grepped the file
directly. Fixed by adding the invariant to the **apply instruction**, which is
emitted (verified: 1 match, and 1 match for an unrelated change, so it is not
hard-coded to this one). Four REACHABILITY tests added — they assert the text
reaches an agent rather than that it exists on disk.

**Ring 8 finding (open, for human decision).** The property
"no workflow document asserts the superseded rule" matches only jq-phrasings.
A document asserting the old rule *without naming jq* would pass — e.g.
`templates/concept-registry/README.md:68` says registry-check is
"dependency-free — bash + git grep". That statement is **accurate about that
one script**, so it is not a violation, but the property's blind spot is real.
Widening it changes an approved oracle, so it is referred to the human rather
than done silently.

**Ring 8 fresh-context: NO.** The schema mandates a reviewer with no
implementation context. This session's operating rules forbid spawning a
subagent unless the user asks, so the review was performed by the author
context — the confirmation-bias setup the ring exists to defeat. It still
found a FAIL, but the human should weigh the verdicts accordingly, and may
re-run `openspec-adversarial-review` in a fresh session.

### 2. evidence-ledger

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: ledger record contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · **R4** · R8 | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 3. chain-state

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: exit-code contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 4. gate-payload

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: CLI + response-shape contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · **R4** · R8 | |
| Harness-seam manual verification (2 obligations) | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 5. checkpoint-from-ledger

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (minimal) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

### 6. hook-tiers

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (full: event + refusal contract) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 · R1 · R2 · R3 · R8 | |
| Harness-seam manual verification (2 obligations) | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: none) | |
| Commit | |

## Rings not run, with impact

Recorded once for the whole change; restated at each checkpoint.

| Ring | Status | Impact |
|------|--------|--------|
| R5 mutation | ⏭️ unavailable | No bash mutation tooling. Surviving-mutant evidence is unavailable for every script; R8 and the enumerated properties carry that weight |
| R6 formal | — not applicable | Stainless is Scala-only. All six triage candidates carry a stated verdict in `design.md`; `chain-state` is the closest call and the reason is recorded |
| R7 model checking | ⏭️ unavailable | No TLA+/Apalache |
| R9 telemetry | — not applicable | No telemetry stack |

## Standing limitations

Not defects. Restated here so a checkpoint cannot silently present them as
coverage.

1. Ring 3 properties are **enumerated over finite listed domains**, not
   sampled — no generator or shrinker exists for bash.
2. The **harness seam** is manually verified once per supported harness
   (4 obligations, specs 4 and 6).
3. The **ledger is forgeable in-process** — recording command and exit status
   makes a row re-checkable, not unforgeable (specs 2 and 5).
4. **Completion-claim detection is approximate** — reads turn text; bounded
   refusal is the mitigation (spec 6).
