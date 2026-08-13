# Design: Fix verified-scala3 substratum defects

<!-- DESIGN artifact for the `fix-verified-scala3-substratum-review` change
     under the `verified-scala3` schema. Grounded in the dependency artifacts
     (proposal.md, capability-check.md, inventory-check.md, spec-lint.md) and
     the five specs (discharge-fidelity, evidence-capture,
     judgment-ring-integrity, fact-extraction-unification, workflow-hygiene).

     This is a WORKFLOW-SELF-CHANGE: the verified-scala3 workflow fixing its
     own enforcement tooling. No Scala source is touched. The "architecture"
     here is the bash/jq/python tooling architecture and the bootstrapping
     strategy for modifying the workflow while running it.

     DETECTED stack (openspec/capability-profile.md): no Scala stack changes.
     Affected files: bash scripts, jq contract programs, one Python script,
     YAML config, and bats tests in openspec/schemas/verified-scala3/. -->

## Bootstrapping Strategy

<!-- THE CENTRAL DESIGN PROBLEM: the verified-scala3 workflow is being asked
     to enforce itself on changes to its own enforcement tooling. This
     section addresses the self-reference directly. -->

### The problem

The verified-scala3 workflow enforces correctness via hooks (`gate.sh`) that
fire on every turn and scanners (`chain-state.sh`, `spec-lint.sh`,
`ledger.sh`) that run during the apply phase (Steps 0–13). This change
modifies those very scripts. Four concrete risks arise:

1. **Mid-change gate inconsistency.** The hooks fire on every turn. If
   `chain-state.sh` is half-modified (D1 fixed, D2 not yet), the next turn's
   gate runs a version that produces verdicts neither the old nor the
   intended-final code would produce. The gate could block or allow the
   wrong things.

2. **Scanner self-reference.** `spec-lint.sh`, `chain-state.sh`, and
   `ledger.sh` are invoked during the apply phase to produce evidence about
   the change — but the change *is* modifying those scripts. A ledger row
   recording "chain-state.sh passed" is evidence produced by the very script
   being modified.

3. **Schema.yaml apply-step drift.** The `evidence-capture` spec wires
   `ledger.sh run` into apply Steps 3–11. But the apply phase is currently
   *following* Steps 3–11 as defined by the current schema.yaml. If the
   apply instruction is modified mid-apply, which version is being executed?

4. **Bats tests vs. scripts co-evolve.** If a script and its bats test are
   modified in the same commit, a green run proves they agree with each
   other — not that the script agrees with the spec.

### The strategy

The workflow cannot fully enforce itself on its own modifications. Pretending
otherwise would be exactly the kind of claim outrunning its evidence that
the workflow exists to prevent. The strategy is:

**1. Bats-only verification for the self-change.**

The bats suite tests scripts in isolation with fixtures — it does not invoke
the live gate. Bats is the primary verification ring for this change. The
live gate/scanners are NOT relied upon for self-validation during the
change. This is honest: bats is the workflow's own test corpus, and it tests
the scripts against fixed fixtures, not the gate's live behavior on a
half-modified codebase.

**2. Batch changes per spec, test in isolation, commit.**

Each spec's modifications (script + bats cases) land as one commit. The bats
suite runs against the committed state, not a mid-edit state. Between
commits the working tree is clean. The implementation-order artifact
sequences the specs so that each commit is independently testable:

- `workflow-hygiene` first (D7/D8 — no behavioral changes to verdict logic,
  lowest risk of gate disruption)
- `discharge-fidelity` second (D1/D2 — fixes verdict logic; gate may produce
  different verdicts after this commit, but the verdicts are *more* correct)
- `judgment-ring-integrity` third (D4 — adds lint rules, no verdict changes)
- `fact-extraction-unification` fourth (D5 — structural change to fact
  extraction; gate path changes but degraded mode preserves old behavior)
- `evidence-capture` last (D3/D6 — new subcommands, no changes to existing
  verdict logic until the apply instruction is updated in a follow-up
  change)

**3. Accept that the live gate is unreliable during the change.**

The gate's verdicts on this change are not evidence (the gate is being
modified). The bats suite is the evidence. This is documented in the
spec-lint artifact and recorded as a ledger row with `ring: "manual"` and
an artifact pointing to the spec-lint report. The workflow's own "recorded
limitation" corollary applies: the limitation is established with a date
(2026-08-12) and a mechanism (bats-only verification, gate verdicts
excluded), and a later change re-tests the gate's verdicts rather than
inheriting the limitation.

**4. Re-install hooks and re-run the full gate only after all specs are
complete.**

Once the final commit lands and the bats suite is green, re-install hooks
from the modified schema and run the gate against the finished change. That
is the first point where the gate's verdict is meaningful. If the gate
disagrees with the bats suite at that point, the disagreement is a finding
to investigate, not a reason to revert.

**5. The `evidence-capture` spec (D3) is the hardest case.**

It modifies the apply instruction itself (wiring `ledger.sh run` into
Steps 3–11). For this spec, the bootstrapping is:

- Implement `ledger.sh run`/`verify`, test via bats, commit.
- Do NOT use `ledger.sh run` to record evidence for this change. The
  evidence for this change is bats results recorded via the existing
  `ledger.sh append` mode.
- The new `run` mode is used starting with the *next* change. This change
  ships the capability but does not consume it on itself.

This is the same pattern as a compiler bootstrap: build the new compiler
with the old compiler, then use the new compiler for the next build.

### What this strategy does NOT claim

- It does not claim the gate is correct after the change. It claims the bats
  suite is green and the gate is re-installed.
- It does not claim the workflow enforced itself on this change. It claims
  the workflow's own test corpus (bats) verified the scripts in isolation.
- It does not claim the new `ledger.sh run` mode has been exercised in
  production. It claims it has been exercised in bats fixtures.

## Tool Architecture

### File map

| File | Spec(s) | Changes |
|------|---------|---------|
| `scanner/chain-state.sh` | discharge-fidelity, fact-extraction-unification | D1: add `.exit == 0` filter to discharge jq clause; add `failed` reason-code. D2: read per-spec baselines from `implementation-progress.md`. D5: consume `openspec-graph.py export` JSON instead of awk parsing; add degraded mode fallback. |
| `scanner/ledger.sh` | discharge-fidelity, evidence-capture, judgment-ring-integrity, workflow-hygiene | D2: add `--forgive-unchanged` flag to `read` mode. D3: add `run` subcommand (capture mode) and `verify` subcommand (replay mode); add `sha256`/`digest`/`wallTime` fields. D4: add command/exit disagreement lint in `verify`. D8: remove dead `update\|delete\|rewrite\|edit)` case arm. |
| `scanner/spec-lint.sh` | fact-extraction-unification, workflow-hygiene | D5: add `--format json` output mode. D7: fix INSTRUCTION DRIFT remediation reference to `scanner/install-skills.sh`. |
| `scanner/openspec-graph.py` | fact-extraction-unification | D5: add `export` subcommand emitting JSON topology for chain-state consumption. |
| `scanner/install-skills.sh` | fact-extraction-unification | D5: add python3 to `--check-installed` prerequisite set. |
| `hooks/gate.sh` | discharge-fidelity, workflow-hygiene | D2: use per-spec baselines instead of `git rev-parse HEAD`. D8: parse `cwd` with jq not sed; write heartbeat after relevance guard; cap named-unresolved list at N. |
| `scanner/ledger-record-contract.jq` | evidence-capture, judgment-ring-integrity | D3: extend with optional `sha256`/`digest`/`wallTime` fields. D4: extend with `artifact` requirement for `manual`/`R8` rows. |
| `scanner/chain-state-report-contract.jq` | discharge-fidelity | D1: change discharge clause to filter `.exit == 0`; add `failed` reason-code. |
| `schema.yaml` | workflow-hygiene, fact-extraction-unification | D8: YAML anchors for invariant banner (`&invariant`/`*invariant`). D5: add python3 to declared prerequisites. D3: add `ledger.sh run` to apply Steps 3–11 instructions (but NOT consumed by this change — see bootstrapping). |
| `tests/discharge-fidelity.bats` | discharge-fidelity | New bats file: 11 scenarios for D1+D2. |
| `tests/evidence-capture.bats` | evidence-capture | New bats file: 13 scenarios for D3+D6. |
| `tests/judgment-ring-integrity.bats` | judgment-ring-integrity | New bats file: 7 scenarios for D4. |
| `tests/fact-extraction.bats` | fact-extraction-unification | New bats file: 10 scenarios for D5. |
| `tests/workflow-hygiene.bats` | workflow-hygiene | New bats file: 9 scenarios for D7+D8. |

### Data flow (after this change)

```
spec-lint.sh --format json ──→ JSON findings ──┐
                                                ├──→ chain-state.sh ──→ JSON report
openspec-graph.py export ──→ JSON topology ────┘         (bound/resolved/discharged)
                                                         + failed reason-code
                                                         + per-spec baselines
                                                         + --forgive-unchanged
                                                                │
                                                                ├──→ hooks/gate.sh (per-turn)
                                                                │     - per-spec baselines
                                                                │     - capped payload
                                                                │     - heartbeat after guard
                                                                │
                                                                └──→ scanner/checkpoint.sh (Step 13)

ledger.sh append ──→ JSONL row (legacy, agent-supplied exit)
ledger.sh run    ──→ JSONL row (capture, script-observed exit + sha256 + digest + wallTime)
ledger.sh read   ──→ JSONL rows (filtered by change/baseline, optionally --forgive-unchanged)
ledger.sh verify ──→ replay report (match/diverge/manual + command/exit disagreement)
```

### jq contract changes

**`ledger-record-contract.jq`**: The new fields (`sha256`, `digest`,
`wallTime`) are OPTIONAL. The contract uses jq's `?` operator for these
fields so legacy rows (without them) still validate. The `artifact` field
becomes REQUIRED for rows with `ring: "manual"` or `ring: "R8"` (but remains
optional for other rings — backward compat).

**`chain-state-report-contract.jq`**: The `failed` reason-code is added to
the allowed reason values. The discharge clause changes from "any matching
row" to "any matching row with `.exit == 0`". The report shape (JSON
fields) is unchanged — existing consumers (gate.sh, checkpoint.sh) read the
same fields.

## Effect Boundaries

N/A — no Scala effect types. All "effects" are bash process exits and file
I/O. The boundary is between:
- **Pure jq logic** (contract validation, discharge computation) — testable
  via bats with fixed JSON inputs.
- **Side-effecting bash** (file reads, git commands, process execution) —
  testable via bats with fixture directories and mock git repos.

## Type-Driven Invalid-State Prevention

N/A — no Scala types. The equivalent for bash/jq is:
- **Exit-code discipline**: every script uses tri-state exits (0 = clean,
  1 = finding, 2 = undetermined). D8 normalizes remaining two-way-exit
  scripts to tri-state.
- **jq contract validation**: `ledger-record-contract.jq` rejects rows that
  don't match the schema, making invalid ledger rows unwriteable (the append
 /run modes validate before writing).
- **Reason-code enumeration**: discharge reasons are `ok`, `undischarged`,
  `failed`, `unresolved`, `unbound` — a closed set enforced by the jq
  contract.

## Refined-Type Strategy

N/A — no Scala refined types. The bash equivalent is argument validation:
- `ledger.sh run` validates that `--exit` is NOT passed (the script observes
  the exit).
- `ledger.sh read --forgive-unchanged` validates that `--baseline` is also
  passed (forgiveness requires a baseline to compare against).
- `chain-state.sh` validates that `--baseline` is passed (required, not
  optional — the v12 fix that closed the "no baseline" escape hatch).

## IDL Model Layout

N/A — no Smithy IDL. The machine interface is `spec-lint.sh --format json`
and `openspec-graph.py export`, both emitting JSON documents consumed by
chain-state via jq.

## Compatibility Story

### Ledger format backward compatibility

The ledger JSONL format gains three optional fields (`sha256`, `digest`,
`wallTime`) and one new requirement (`artifact` required for `manual`/`R8`
rows). Existing ledger files (including the archived
`evidence-ledger.jsonl`) remain readable because:

1. New fields are optional — jq's `?` operator handles their absence.
2. The `artifact` requirement applies only to new rows written with
   `ring: "manual"` or `ring: "R8"`. Existing rows without `artifact` are
   still readable by `ledger.sh read` (the requirement is enforced at write
   time by `ledger.sh append`/`run`, not at read time).
3. `chain-state.sh` and `checkpoint.sh` read the same JSON fields as before;
   the discharge clause change (`.exit == 0` filter) is a stricter
   predicate, not a schema change.

### Gate behavior change

After D1, the gate will report some previously-discharged obligations as
`failed` (if they had recorded red runs) or `undischarged` (if they had no
green runs). This is a behavior change — but it is the correct behavior. Any
change that relied on the old (wrong) discharge verdict will see new red in
the gate. This is expected and is the point of the fix.

After D2, the gate will stop reporting committed specs as undischarged
(because it uses per-spec baselines instead of HEAD). This is a behavior
change that reduces false red — strictly an improvement.

### Degraded mode

When python3 is unavailable, `chain-state.sh` falls back to awk parsing
(current behavior). The gate continues to function. The trace output
documents the degradation. This is not a silent fallback — it is an
explicit, documented degraded mode.

## Verification Approach Per Spec

| Spec | Ring 0 | Ring 1 | Ring 3 | Ring 4 | Ring 8 |
|------|--------|--------|--------|--------|--------|
| discharge-fidelity | bats run | shellcheck + shfmt | 11 bats scenarios (failed run, green run, mixed, per-spec baselines, --forgive-unchanged) | ledger JSONL compat (archived fixtures decode unchanged) | adversarial: D1/D2 fix matches review defect descriptions |
| evidence-capture | bats run | shellcheck + shfmt | 13 bats scenarios (run/verify/sha256/digest/wallTime/backward-compat) | ledger JSONL compat (mixed legacy+capture rows) | adversarial: D3/D6 fix matches review defect descriptions |
| judgment-ring-integrity | bats run | shellcheck + shfmt | 7 bats scenarios (manual artifact, disagreement lint) | ledger contract compat (manual rows with artifact) | adversarial: D4 fix matches review defect descriptions |
| fact-extraction-unification | bats run | shellcheck + shfmt | 10 bats scenarios (export JSON, --format json, degraded mode) | N/A (no persistence format change) | adversarial: D5 fix matches review defect descriptions |
| workflow-hygiene | bats run | shellcheck + shfmt | 9 bats scenarios (drift ref, dead arm, cwd jq, heartbeat, YAML anchors, payload cap) | N/A (no persistence format change) | adversarial: D7/D8 fix matches review defect descriptions |

### Ring 8 (adversarial review) specifics

The adversarial reviewer works from the review document's defect
descriptions (`docs/openPoints/verified-scala3-substratum-review.md`), not
from the fix implementation. For each defect (D1–D8), the reviewer checks:

1. Does the fix address the defect as described in the review?
2. Does the fix introduce a new escape route (a way to satisfy the bats
   tests while violating the English spec)?
3. Does the fix's bootstrapping strategy hold (does the bats-only
   verification actually test what the gate would test)?

The self-reference (Ring 8 reviewing fixes to the Ring 8 mechanism) is
bounded because the reviewer works from the fixed external input (the review
document), not from the fix implementation.
