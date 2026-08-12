# Tasks

<!-- DERIVED OUTPUT — regenerated from implementation-progress.md at every
     checkpoint (apply Step 13). Do not hand-maintain: two hand-maintained
     trackers drift, which is why one is designated the source of truth.
     Seeded unchecked 2026-08-08. -->

Rings listed per section are only those the Ring Applicability table marks as
applying to **that** spec. R5/R6/R7/R9 apply to no spec in this change and are
recorded once, with impact, in `implementation-progress.md`.

## 1. correctness-invariant

Prerequisite work (first, because every later spec's tests depend on it):

- [x] Create `openspec/schemas/verified-scala3/tests/` and `tests/fixtures/`
- [x] Establish `.bats` naming and helper conventions — none exist for the 11 tracked scripts; document them in `tests/README.md`
- [x] Add tool install steps (`shellcheck`, `shfmt`, `bats`, `jq`) to `ci/github-actions.yml`, `ci/gitlab-ci.yml`, `ci/azure-pipelines.yml`
- [x] Add `shellcheck`, `shfmt -d` and `bats` invocations to the three CI templates
- [x] Annotate the 5 pre-existing shellcheck findings with reasons: `danger-scan.sh:62` SC2086 (intentional optional-arg idiom), `registry-check.sh:143,235,289` SC2016 (intentional awk quoting)
- [x] Verify `metals-call.sh:95` `init_resp` SC2034 — remove if genuinely dead, annotate if not

Spec cycle:

- [x] Step 0 — clean tree, record baseline SHA, inventory snapshot, verify Proof Obligations complete
- [x] Step 1 — typed-contract **waiver** presented for explicit human approval (no Scala; the normative text is the deliverable) → **GATE**
- [x] Step 2 — write `tests/correctness-invariant.bats` from the spec only; run for ORACLE POLARITY (red / green-by-design) → **GATE**
- [x] Step 3 (R0) — add the three-clause definition and operative rule to `schema.yaml` (version → 12, changelog entry) and `templates/spec.md`; amend `hooks/README.md` and `gate.sh`'s header for the prerequisite set; `schema.yaml` parses and `openspec status` resolves
- [x] Step 4 (R1) — `shellcheck` and `shfmt -d` clean on every changed script
- [x] Step 6 (R3) — `bats tests/correctness-invariant.bats` green; polarity confirmed (red → green)
- [x] Step 8 (R8) — adversarial review in a **fresh context**: does any workflow document still assert the superseded rule?
- [x] Step 12 — concept delta (expected **empty**); build-dependency delta must **report the host prerequisites** `jq`/`shellcheck`/`bats`/`shfmt`
- [x] Step 13 — update `implementation-progress.md`, regenerate this file, commit, **STOP**

## 2. evidence-ledger

- [x] Step 0 — baseline SHA, inventory snapshot, obligations complete
- [x] Step 1 — ledger record contract: field names, types, required set, `v=1`, append-only semantics → **GATE**
- [x] Step 2 — `tests/evidence-ledger.bats` from spec only: 4 enumerated properties + 16 scenarios; polarity run → **GATE**
- [x] Step 3 (R0) — `scanner/ledger.sh` with `append` and `read`; `jq` encode/decode; `bash -n` clean
- [x] Step 4 (R1) — `shellcheck` + `shfmt -d` clean; `danger-scan.sh` reports no unjustified pattern
- [x] Step 5 (R2) — prerequisite rule asserted: no JVM, no network; runs identically from a shell prompt and from a hook
- [x] Step 6 (R3) — bats green; polarity confirmed
- [x] Step 7 (**R4**) — commit `tests/fixtures/evidence-ledger-v1.jsonl`; assert it re-parses and is never regenerated to make a test pass; assert an unrecognised `v` yields undetermined, not skipped rows
- [x] Step 8 (R8) — fresh-context review: can a row be written bypassing the writer? can a modifying invocation succeed? does an unknown version silently drop rows?
- [x] Step 12 — concept delta (expected empty); build-dependency delta (expected none)
- [x] Step 13 — tracker, regenerate, commit, **STOP**

## 3. chain-state

- [x] Step 0 — baseline SHA, inventory snapshot, obligations complete
- [x] Step 1 — exit-code contract: `0` clean / `1` findings / `2` undetermined, and how `spec-lint.sh`'s two-way codes are interpreted → **GATE**
- [x] Step 2 — `tests/chain-state.bats` from spec only: 3 enumerated properties + 10 scenarios; polarity run → **GATE**
- [x] Step 3 (R0) — `scanner/chain-state.sh`; bound/resolved from `spec-lint.sh` (F7 / F9 via `--artifacts`), discharged from `ledger.sh`; `bash -n` clean
- [x] Step 4 (R1) — `shellcheck` + `shfmt -d` clean; danger scan clean
- [x] Step 5 (R2) — prerequisite rule; **no second implementation** of reachability or artifact resolution
- [x] Step 6 (R3) — bats green; polarity confirmed; agreement with `spec-lint.sh` asserted on the same input
- [x] Step 8 (R8) — fresh-context review: can undetermined evidence ever surface as a zero count? does any path emit a lone verdict instead of the three counts?
- [x] Step 12 — concept delta (expected empty); build-dependency delta (expected none)
- [x] Step 13 — tracker, regenerate, commit, **STOP**

## 4. gate-payload

- [x] Step 0 — baseline SHA, inventory snapshot, obligations complete
- [x] Step 1 — CLI + response-shape contract: `--event` / `--format` / `--repo` surface, payload schema, unchanged harness JSON shape → **GATE**
- [x] Step 2 — `tests/gate-payload.bats` from spec only: 4 enumerated properties + 13 scenarios; polarity run → **GATE**
- [x] Step 3 (R0) — payload = invariant + chain state + named unresolved list; run `spec-lint.sh --artifacts`; move fingerprinting from the pi adapter into `gate.sh`; wire prompt-submission in all three adapters; heartbeat write; `bash -n` clean
- [x] Step 4 (R1) — `shellcheck` + `shfmt -d` clean (note: `gate.sh` is clean at baseline, so any finding is attributable to this spec)
- [x] Step 5 (R2) — adapter **no-logic rule**: adapters decide when to ask and where to put the answer; all logic stays in `gate.sh`
- [x] Step 6 (R3) — bats green; polarity confirmed; gate exits `0` under every induced failure
- [x] Step 7 (**R4**) — harness response shape unchanged; cross-format property asserts the same facts in every `--format`
- [x] Step 8 (R8) — fresh-context review: can the payload report readiness while unresolved requirements exist? is the payload size tolerable at a realistic unresolved count?
- [x] Manual — harness-seam verification (2 obligations): confirm per harness that the prompt-submission event fires and `additionalContext` lands, using the heartbeat and the documented procedure
- [x] Step 12 — concept delta (expected empty); build-dependency delta (expected none)
- [x] Step 13 — tracker, regenerate, commit, **STOP**

## 5. checkpoint-from-ledger

- [x] Step 0 — baseline SHA, inventory snapshot, obligations complete
- [x] Step 1 — minimal contract: the render inputs and output shape; consumes the existing ledger and chain-state contracts → **GATE**
- [x] Step 2 — `tests/checkpoint-from-ledger.bats` from spec only: 3 enumerated properties + 9 scenarios; polarity run → **GATE**
- [x] Step 3 (R0) — `scanner/checkpoint.sh` renders from ledger rows + chain state; `schema.yaml` Step 13 changes from "present this structure" to "generate and present"; `bash -n` clean
- [x] Step 4 (R1) — `shellcheck` + `shfmt -d` clean; danger scan clean
- [x] Step 5 (R2) — prerequisite rule asserted
- [x] Step 6 (R3) — bats green; polarity confirmed; `tasks.md` regeneration is idempotent
- [x] Step 8 (R8) — fresh-context review: can a ring outcome appear that traces to no row? can a superseded-baseline row be presented as current?
- [x] Step 12 — concept delta (expected empty); build-dependency delta (expected none)
- [x] Step 13 — tracker, regenerate, commit, **STOP**

## 6. hook-tiers

- [x] Step 0 — baseline SHA, inventory snapshot, obligations complete
- [x] Step 1 — event + refusal contract: which events, which paths trigger which check, refusal output shape, bounded-refusal semantics → **GATE**
- [x] Step 2 — `tests/hook-tiers.bats` from spec only: 4 enumerated properties + 16 scenarios; polarity run → **GATE**
- [x] Step 3a (R0) — post-edit tier only: route spec edits to `spec-lint.sh`, production-source edits to `danger-scan.sh`; return findings without blocking; `bash -n` clean
- [x] Step 3b (R0) — completion gate: refuse a turn asserting completion while chain state is unresolved or undetermined; bounded to one refusal per turn
- [x] Step 4 (R1) — `shellcheck` + `shfmt -d` clean
- [x] Step 5 (R2) — adapter no-logic rule holds for the two new event tiers
- [x] Step 6 (R3) — bats green; polarity confirmed; path-trigger property exhaustive over the listed path set
- [x] Step 8 (R8) — fresh-context review: can the completion gate be satisfied without discharging anything? can the post-edit tier alter or reject an edit? can refusal recur and strand a session?
- [x] Manual — harness-seam verification (2 obligations): confirm per harness that post-edit findings reach the agent and that a refusal is honoured
- [x] Record in `hooks/README.md` the promotion from the non-blocking tier, with the usage evidence that justified it
- [x] Step 12 — concept delta (expected empty); build-dependency delta (expected none)
- [x] Step 13 — tracker, regenerate, commit, **STOP** — then final summary
