# Implementation Order

Generated from specs (6, all PASS at spec-lint), `design.md`, and the
proposal's verification strategy. Order is by concept dependency, with one
deliberate ordering constraint from design Decision 4.

## Dependency Analysis

The "concepts" here are workflow-level (no Scala types), so dependency means
*this spec consumes an artifact or contract the earlier one introduces*.

| # | Spec | Introduces | Depends On | Complexity |
|---|------|-----------|------------|------------|
| 1 | `specs/correctness-invariant/spec.md` | Correctness Invariant, Prerequisite Set, the `tests/` bats layout | (none — foundational) | medium |
| 2 | `specs/evidence-ledger/spec.md` | Evidence Ledger (JSON lines, versioned), Ledger writer | bats layout, Prerequisite Set (`jq`) | medium |
| 3 | `specs/chain-state/spec.md` | Chain State | Ledger writer (discharged clause), Correctness Invariant (the three clauses) | medium |
| 4 | `specs/gate-payload/spec.md` | Gate Heartbeat | Chain State, Correctness Invariant (payload text) | medium |
| 5 | `specs/checkpoint-from-ledger/spec.md` | Generated Checkpoint | Evidence Ledger, Chain State | medium |
| 6 | `specs/hook-tiers/spec.md` | Post-edit correction tier, Completion gate | Chain State | high |

**Complexity rationale** — the guide's "high" band is *new types AND complex
logic AND Ring 6/7/9*. Rings 6, 7 and 9 apply to no spec here, so by the
letter every spec is at most medium. `hook-tiers` is nonetheless recorded
**high**: it is the only spec that can refuse an action, and the guide's bands
were written for a Scala change where Ring 6/7/9 stood in for consequence.
Recording it medium would understate review depth for the one spec that can
block a session. Deviation stated rather than silently applied.

**Ordering note (design Decision 4)**: specs 4 and 5 are mutually independent
(4 needs 1+3, 5 needs 2+3). `gate-payload` goes first because it exercises
`chain-state` in real use — where a wrong count costs a wasted context block —
before `checkpoint-from-ledger` makes the same data load-bearing on a human
approval gate. `hook-tiers` is last by decision, not by dependency: the
blocking tier is adopted only after the non-blocking path has accrued usage.

## Ring Applicability

R5, R6, R7 and R9 are unavailable or inapplicable for every spec (no bash
mutation tooling; Stainless is Scala-only; no model checker; no telemetry
stack) — each recorded with its impact in `capability-check.md` and its
verdict in `design.md`'s triage table. R0 is the `bash -n` / parse substitute.

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----------|
| 1 | correctness-invariant | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | waiver (Scala) |
| 2 | evidence-ledger | ✅ | ✅ | ✅ | ✅ | **✅** | — | — | — | ✅ | — | **full** — ledger record contract |
| 3 | chain-state | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | **full** — exit-code contract |
| 4 | gate-payload | ✅ | ✅ | ✅ | ✅ | **✅** | — | — | — | ✅ | — | **full** — CLI + response-shape contract |
| 5 | checkpoint-from-ledger | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | minimal — consumes existing contracts |
| 6 | hook-tiers | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | **full** — event + refusal contract |

**R2 for spec 1 is `—`**: it ships text, CI config and a test directory, with
no script logic to hold to the prerequisite or adapter rules.

**R4 applies to two specs**: `evidence-ledger` (the persisted record, with a
committed `v=1` fixture) and `gate-payload` (the hook response shape, which
three harnesses already consume — the cross-format property is its
compatibility assertion).

**Typed contract — not a blanket waiver.** The proposal waives it for *Scala*
(none is written) but declares two real contracts. Per spec, the Step 1 gate
presents the contract named above; only spec 1 has nothing to present beyond
the normative text itself, and its waiver needs explicit human approval at
that gate.

## Expected Changed Files

Ring 5 is unavailable, so this table's consumers are the Ring 8 review diff and
the post-edit danger scan rather than mutation targeting.

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | correctness-invariant | `schema.yaml`, `templates/spec.md`, `hooks/README.md`, `hooks/gate.sh` (header comment only), `ci/github-actions.yml`, `ci/gitlab-ci.yml`, `ci/azure-pipelines.yml`, `tests/` (new), `tests/correctness-invariant.bats` |
| 2 | evidence-ledger | `scanner/ledger.sh` (new), `tests/evidence-ledger.bats` (new), `tests/fixtures/evidence-ledger-v1.jsonl` (new) |
| 3 | chain-state | `scanner/chain-state.sh` (new), `tests/chain-state.bats` (new) |
| 4 | gate-payload | `hooks/gate.sh`, `hooks/adapters/claude.settings.json`, `hooks/adapters/devin.hooks.v1.json`, `hooks/adapters/pi/verified-scala3-gate.ts`, `tests/gate-payload.bats` (new) |
| 5 | checkpoint-from-ledger | `scanner/checkpoint.sh` (new), `schema.yaml` (Step 13), `tests/checkpoint-from-ledger.bats` (new) |
| 6 | hook-tiers | `hooks/gate.sh`, all three adapters, `hooks/README.md` (promotion record), `tests/hook-tiers.bats` (new) |

`hooks/gate.sh` is touched by specs 1, 4 and 6, and all three adapters by 4 and
6. Each spec's Ring 8 diff is against **its own** Step 0 baseline, so the
per-spec commit discipline is what keeps those diffs disjoint. Its shellcheck
baseline is clean today, so any finding in that file is attributable to this
change.

## Human Gate Tier

The proposal's correctness risk is **High**. The `combined` tier requires
complexity=simple **AND** risk=low, so no spec qualifies — this is a mechanical
consequence, not a judgement.

| # | Spec | Tier | Justification |
|---|------|------|---------------|
| 1 | correctness-invariant | separate | risk=high (proposal); complexity=medium |
| 2 | evidence-ledger | separate | risk=high; complexity=medium; R4 applies |
| 3 | chain-state | separate | risk=high; complexity=medium |
| 4 | gate-payload | separate | risk=high; complexity=medium; R4 applies |
| 5 | checkpoint-from-ledger | separate | risk=high; complexity=medium |
| 6 | hook-tiers | separate | risk=high; complexity=high |

Twelve human gates plus six checkpoints. That is the cost of the High rating,
and it is the intended trade: this change ships enforcement that becomes the
source of truth for correctness claims, so a wrong one manufactures false
confidence with more authority than the prose it replaces.

## Per-spec prerequisite work

Recorded here because it must land inside its spec's cycle, not before it.

| # | Spec | Prerequisite tasks (first in that spec's sequence) |
|---|------|---------------------------------------------------|
| 1 | correctness-invariant | Create `tests/` and `tests/fixtures/`; establish `.bats` naming and helper conventions (none exist for the 11 tracked scripts); add tool install + `shellcheck`/`shfmt`/`bats` steps to the three CI templates; annotate the 5 pre-existing shellcheck findings with reasons (4 intentional, 1 possible dead variable in `metals-call.sh:95` — verify before removing) |
| 2 | evidence-ledger | none beyond spec 1 |
| 3 | chain-state | none |
| 4 | gate-payload | none |
| 5 | checkpoint-from-ledger | none |
| 6 | hook-tiers | none |

## Standing limitations carried from spec-lint

Restated so the apply phase does not mistake them for coverage:

1. **Ring 3 properties are enumerated, not sampled** — no generator or
   shrinker exists for bash. Weaker than Ring 3 on the Scala side.
2. **The harness seam is manually verified**, once per supported harness
   (4 obligations across specs 4 and 6).
3. **The ledger is forgeable in-process** — accepted limit, specs 2 and 5.
4. **Completion-claim detection is approximate** — accepted limit, spec 6,
   mitigated by bounded refusal.

## Implementation Sequence

Process in this exact order. For each spec: baseline SHA + inventory snapshot →
typed contract (gate) → test oracle with polarity run (gate) → implement
through the applicable rings, Ring 8 before 5/6/7 → concept delta +
build-dependency delta → checkbox, regenerate `tasks.md`, commit → **stop for
human validation**.

The concept delta is expected to be **empty for every spec** — no Scala source
is in scope, so the before/after scanner snapshots should be identical, and any
difference is a defect. The build-dependency delta is empty for `build.sbt` and
`project/`, but **not** overall: the host-tool prerequisites (`jq`,
`shellcheck`, `bats`, `shfmt`) must be reported at spec 1's Step 12.

- [ ] 1. `specs/correctness-invariant/spec.md` — define correctness as an unbroken evidence chain; repair the two documents asserting the superseded dependency rule; establish the bats layout and CI
- [ ] 2. `specs/evidence-ledger/spec.md` — append-only versioned JSON-lines record of discharged obligations; supplies the third clause
- [ ] 3. `specs/chain-state/spec.md` — bound / resolved / discharged counts plus the named unresolved list; undetermined is never zero
- [ ] 4. `specs/gate-payload/spec.md` — per-turn injection of the invariant and the live unresolved count; fingerprint suppression; heartbeat
- [ ] 5. `specs/checkpoint-from-ledger/spec.md` — the checkpoint is generated from recorded rows, not authored
- [ ] 6. `specs/hook-tiers/spec.md` — post-edit correction, then the one blocking gate at turn completion
