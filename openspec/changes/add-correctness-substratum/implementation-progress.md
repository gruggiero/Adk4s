# Implementation Progress

**SINGLE SOURCE OF TRUTH for progress.** `tasks.md` is derived output,
regenerated from this file at every checkpoint (apply Step 13) — never
hand-maintained in parallel.

Change: `add-correctness-substratum` · 6 specs · risk **High** · all gate tiers
`separate` (12 human gates + 6 checkpoints).

Seeded unchecked 2026-08-08.

## Sequence

- [ ] 1. `specs/correctness-invariant/spec.md`
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

| Field | Value |
|-------|-------|
| Baseline SHA | _(Step 0)_ |
| Gate 1 — typed contract (waiver, needs explicit approval) | ☐ |
| Gate 2 — test oracle + polarity | ☐ |
| R0 parse / `bash -n` | |
| R1 shellcheck + shfmt | |
| R3 bats | |
| R8 adversarial (fresh context) | |
| Concept delta (expected: empty) | |
| Build-dependency delta (expected: host prerequisites reported) | |
| Commit | |

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
