# Proposal: Fix verified-scala3 substratum defects from the 2026-08-12 review

## Why

The `add-correctness-substratum` change (archived 2026-08-10) shipped the
correctness definition, evidence ledger, chain-state, checkpoint generation,
and hook tiers that constitute the verified-scala3 workflow's enforcement
substratum. A read-only review of that change
(`docs/openPoints/verified-scala3-substratum-review.md`, 2026-08-12) found
8 defects (D1–D8) ranging from critical to low, plus a prioritized roadmap
(§9) of 10 items. The review's verdict was ADOPT — the architecture is right
— but its entire agenda reduces to one sentence in the workflow's own
vocabulary: **make evidence production a side effect of ring execution
(capture), and make evidence consumption replayable (verify).**

Two defects are critical:

- **D1**: `chain-state.sh` discharges obligations on any ledger row matching
  the spec+obligation, regardless of `.exit` status. A recorded **failed** run
  (`"exit": 1`) discharges the obligation — directly contradicting the
  schema's own definition ("DISCHARGED — observed to run **green**"). Every
  discharged fixture in `tests/chain-state.bats` uses `--exit 0`; the
  "recorded red run" case is untested.

- **D2**: `hooks/gate.sh` computes chain state at `git rev-parse HEAD` for
  every active change. After spec N commits, HEAD advances and every prior
  spec's ledger rows read stale — the gate reports earlier specs
  **undischarged forever** unless every ring is re-run after every commit.
  This teaches dismissal of the invariant (alert fatigue) or imposes
  ruinous re-run cost.

The remaining defects (D3–D8) address the ledger's integrity (agent-supplied
exit codes, unreplayable rows, judgment-ring prose), parser divergence
(three parallel Proof-Obligations table parsers), and hygiene (dangling
references, dead code, heartbeat pollution).

## What Changes

Five specs, organized by the review's defect families and prioritized
roadmap (§9). Each spec is independently implementable and testable through
the workflow's own bats suite.

### Affected Capabilities

- `specs/discharge-fidelity/spec.md` — D1 (critical): `chain-state.sh`
  discharge check must filter `.exit == 0`; add `failed` reason-code so a
  recorded red run is visible as negative evidence. D2 (critical): per-spec
  baselines read from `implementation-progress.md` and/or unchanged-artifact
  forgiveness (`git diff --quiet <baseline> HEAD -- <artifact>`); expose
  `--forgive-unchanged` in `ledger.sh read` so gate and checkpoint share one
  discipline.
- `specs/evidence-capture/spec.md` — D3 (high): `ledger.sh run` capture mode
  (script executes the command, records its own `$?`, stdout/stderr digest,
  wall time). D6 (medium): `ledger.sh verify` replay mode (re-execute each
  row's command, compare exits, report `replay matches | diverges |
  manual/unreplayable`). Artifact content hash (`sha256` field) at record
  time. Wire `ledger.sh run` into apply Steps 3–11 so evidence is a side
  effect of running the ring.
- `specs/judgment-ring-integrity/spec.md` — D4 (high): `manual`/R8 ledger
  rows must name a resolvable report artifact (the R8 report file), verified
  F9-style, hashed (D3). Lint: if `command` parses as a runnable shell
  command, replay must reproduce the recorded exit; the archived ledger
  already contains a row where `grep` with no matches exits 1 but the
  recorded exit is 0.
- `specs/fact-extraction-unification/spec.md` — D5 (medium): retire the
  three-parser divergence (spec-lint awk, chain-state awk, openspec-graph.py)
  by promoting `openspec-graph.py` to the single fact extractor: chain-state
  becomes a jq predicate over the graph's JSON `export` output, deleting
  chain-state's awk re-parsing and the `unattributable` escape hatch (the
  graph resolves ordinal/title/property sources uniformly). python3 goes
  through the v12 prerequisite mechanism (declare, install in CI); bash
  chain-state remains as the degraded gate-path mode (mirroring
  concept-scanner's grep fallback). Sub-fix: `spec-lint.sh --format json`
  output for machine consumption, retiring the regex-over-human-prose
  fragility class.
- `specs/workflow-hygiene/spec.md` — D7 (low): fix dangling
  `sync-skills.sh` reference in spec-lint's INSTRUCTION DRIFT remediation
  (real script is `scanner/install-skills.sh`); add bats property: every
  tool-name in scanner messages resolves to a tracked file. D8 (low): dead
  duplicate `update|delete|rewrite|edit)` case arm in `ledger.sh`; jq for
  `cwd` parse in `gate.sh`; heartbeat written after relevance guard (not
  before); YAML anchors for duplicated invariant banner (`&invariant`/
  `*invariant`); cap the payload's named-unresolved list at N with "+M more"
  (§7.1); normalize remaining two-way-exit scripts to tri-state.

### Out of Scope

- **§5 graph graduation** (test→requirement edges, temporal commit nodes,
  spec-lint mechanical audit) — large effort, separate change; this change
  promotes `openspec-graph.py` to the single fact extractor (the first
  graduation step from §5.1) but does not add test→req edges, temporal
  nodes, or the spec-lint mechanical audit.
- **§8.1–8.4 standing process** (replay CI, git-notes binding, escape
  ledger, red-team corpus) — these are workflow-level process changes, not
  tooling fixes; separate changes.
- **§4 no-JVM constraint / Scala scripts question** — no Scala rewrite of
  bash tools; the review recommends porting decision logic to jq, which is
  in scope only where a spec's fix naturally does so (fact-extraction-
  unification's `--format json`).
- **No Scala production code changes** — this change modifies only the
  workflow's own bash/jq/python tooling in
  `openspec/schemas/verified-scala3/` and its bats test suite.

## Approach

This is a **workflow-self-change**: the verified-scala3 workflow fixing its
own tooling. The affected files are bash scripts, jq contract programs, a
Python prototype, YAML, and bats tests in
`openspec/schemas/verified-scala3/`. No Scala source is touched.

The verification rings map differently for a bash/jq change than for a
Scala change:

- **Ring 0** (compile) → bats tests run (the bash equivalent of "it
  compiles"); `shellcheck` and `shfmt` pass on modified scripts.
- **Ring 1** (lint) → `shellcheck` + `shfmt` (the workflow's own declared
  CI prerequisites); no Scalafix/WartRemover.
- **Ring 2** (architecture) → N/A — no Scala module dependencies.
- **Ring 3** (property/scenario tests) → bats scenario tests in
  `openspec/schemas/verified-scala3/tests/` — MANDATORY. Each defect fix
  gets a bats case that would fail before the fix and passes after.
- **Ring 4** (wire/persistence) → ledger JSONL record format compatibility
  (applies to `evidence-capture` which adds `sha256`/`digest`/`wallTime`
  fields and `run`/`verify` modes; and `discharge-fidelity` which adds
  `failed` reason-code). New fields must not break existing ledger readers.
- **Ring 5** (mutation) → N/A — no Stryker4s for bash.
- **Ring 6** (formal) → N/A — no PureScala kernel.
- **Ring 7** (model checking) → N/A.
- **Ring 8** (adversarial review) → MANDATORY — fresh-context reviewer
  checks each fix against the review document's defect description.
- **Ring 9** (telemetry) → N/A.

The bats suite (`openspec/schemas/verified-scala3/tests/`) already has
~3,300 lines covering the workflow's 11 scripts. Each spec adds cases
following the existing conventions (enumerate-don't-claim-sampled, assert
exit codes, fixtures never regenerated).

## Correctness Risk Level

**Risk**: high — the substratum is the workflow's enforcement layer; a
defect in it silently undermines every correctness claim the workflow makes.
D1 and D2 are critical (wrong discharge verdicts, permanent false-red
indictment). D3 changes the ledger's trust model (from agent-asserted to
script-observed). The review itself was produced by the workflow's own
fresh-context Ring 8 mechanism, demonstrating that the substratum's defects
are the highest-leverage targets.

## Verification Strategy

- [x] Ring 0: Compilation — bats tests run; `shellcheck` + `shfmt` pass on
  modified scripts (the bash equivalent of "it compiles")
- [x] Ring 1: Lint — `shellcheck` (declared prerequisite, CI-enforced);
  `shfmt -d` (declared prerequisite, CI-enforced). No Scalafix/WartRemover
  (no Scala code in this change).
- [ ] Ring 2: Architecture — N/A (no Scala module dependencies; this change
  touches only `openspec/schemas/verified-scala3/` tooling, not
  `adk4s-*` modules)
- [x] Ring 3: Property-based tests — MANDATORY. bats scenario tests in
  `openspec/schemas/verified-scala3/tests/`. Each defect fix gets a bats
  case that would fail before the fix and passes after. No CONCURRENT
  behavior (bash scripts are sequential).
- [x] Ring 4: Wire/persistence compatibility — ledger JSONL record format:
  new fields (`sha256`, `digest`, `wallTime`, `failed` reason-code) must
  not break existing ledger readers (`chain-state.sh`, `checkpoint.sh`,
  `gate.sh`). Backward-compat test: existing `evidence-ledger.jsonl`
  fixtures decode unchanged with new code.
- [ ] Ring 5: Mutation testing — N/A (Stryker4s targets Scala; no bash
  mutation testing tool in the declared prerequisite set)
- [ ] Ring 6: Formal verification — N/A (no PureScala kernel; bash/jq
  logic is tested via bats scenarios, not Stainless)
- [ ] Ring 7: Model checking — N/A (no distributed/event-driven invariants)
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
  reviewer; runs BEFORE Rings 5/6/7 in the apply sequence). The reviewer
  checks each fix against the review document's defect description and
  verifies the fix doesn't introduce a new escape route.
- [ ] Ring 9: Telemetry — N/A (no telemetry stack detected; otel4s NOT
  PRESENT per capability-profile)

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/discharge-fidelity/spec.md` | Full | Changes the discharge verdict logic (chain-state jq clause) and the baseline-forgiveness discipline (ledger.sh `--forgive-unchanged`); both are "evaluator logic" in the workflow's terms — wrong verdicts are the defect. |
| `specs/evidence-capture/spec.md` | Full | Introduces new `ledger.sh run` (capture) and `ledger.sh verify` (replay) modes, new ledger record fields (`sha256`, `digest`, `wallTime`), and wires capture into apply Steps 3–11. Persistence/serialization change to the ledger format. |
| `specs/judgment-ring-integrity/spec.md` | Full | Introduces a new lint rule (command/exit disagreement) and a new requirement (manual rows name resolvable hashed artifacts). Changes the ledger record contract for `ring: "manual"` rows. |
| `specs/fact-extraction-unification/spec.md` | Full | Promotes `openspec-graph.py` to the single fact extractor (chain-state becomes a jq predicate over its JSON export), introduces `spec-lint.sh --format json` (new public interface for machine consumption), and adds python3 as a declared prerequisite. Structural change to the fact-extraction pipeline. |
| `specs/workflow-hygiene/spec.md` | Minimal | Fixes a dangling reference, removes dead code, reorders heartbeat write, adds YAML anchors, caps payload list. Signatures of touched code only — no new domain types. |

## Existing Concepts to Reuse

This change modifies the workflow's own tooling, not adk4s Scala code. The
"concepts" here are the workflow's bash/jq tools and their contracts, as
defined by the archived `add-correctness-substratum` change's specs and the
schema.yaml changelog.

| Concept | Kind | Location | Notes |
|---------|------|----------|-------|
| `ledger.sh` | bash script | `openspec/schemas/verified-scala3/scanner/ledger.sh` | Append-only JSONL evidence store; `append` mode (agent-supplied exit); `read` mode (jq query). D3 adds `run` (capture) and D6 adds `verify` (replay). |
| `chain-state.sh` | bash script | `openspec/schemas/verified-scala3/scanner/chain-state.sh` | Computes bound/resolved/discharged per requirement. D1 fixes the discharge jq clause; D2 adds baseline forgiveness. |
| `spec-lint.sh` | bash script | `openspec/schemas/verified-scala3/scanner/spec-lint.sh` | F7 (bound) / F9 (resolved) verdicts via awk table parsing. D5 adds `--format json`; D7 fixes the INSTRUCTION DRIFT remediation reference. |
| `gate.sh` | bash script | `openspec/schemas/verified-scala3/hooks/gate.sh` | Per-turn hook: UserPromptSubmit injection, PostToolUse correction, Stop-tier refusal. D2 fixes baseline computation; D8 fixes cwd parse + heartbeat ordering. |
| `checkpoint.sh` | bash script | `openspec/schemas/verified-scala3/scanner/checkpoint.sh` | Step 13 ring results from ledger rows. Already inspects `.exit` (unlike chain-state — D1). |
| `openspec-graph.py` | Python script | `openspec/schemas/verified-scala3/scanner/openspec-graph.py` | Req→oblig→artifact→code topology graph. D5 promotes it to the single fact extractor: chain-state becomes a jq predicate over its JSON `export` output. |
| `ledger-record-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq` | Validates ledger row shape. D3/D4 extend with `sha256`/`digest`/`wallTime` fields. |
| `chain-state-report-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/chain-state-report-contract.jq` | Self-validating report contract. D1/D2 change the discharge clause. |
| `evidence-ledger.jsonl` | JSONL data | `openspec/changes/archive/2026-08-10-add-correctness-substratum/evidence-ledger.jsonl` | Archived ledger from the substratum change's own run. D4 found a disagreeing row (grep exit 1 recorded as exit 0). |
| bats suite | test corpus | `openspec/schemas/verified-scala3/tests/` | ~3,300 lines covering the workflow's 11 scripts. Each spec adds cases. |
| `schema.yaml` | YAML config | `openspec/schemas/verified-scala3/schema.yaml` | Workflow definition, invariant banner, apply steps. D8 adds YAML anchors for the duplicated banner. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `ledger.sh run` (capture mode) | new bash subcommand | Script executes the command, records its own `$?`, stdout/stderr digest, wall time — converts "agent asserts an exit" into "script observes an exit" (D3). |
| `ledger.sh verify` (replay mode) | new bash subcommand | Re-executes each row's command, compares exits, reports `replay matches \| diverges \| manual/unreplayable` per row (D6). |
| `--forgive-unchanged` flag | new `ledger.sh read` option | A run remains discharged until the artifact it ran changes (`git diff --quiet <baseline> HEAD -- <artifact>`) — semantically the right definition of stale evidence (D2). |
| `failed` reason-code | new discharge verdict | A recorded red run is visible as negative evidence rather than indistinguishable from no evidence (D1). |
| `sha256` / `digest` / `wallTime` fields | new ledger record fields | Artifact content hash at record time → "the artifact that ran green is the artifact present" becomes checkable without re-running (D3); stdout/stderr digest + wall time for replay diagnostics. |
| `spec-lint.sh --format json` | new output mode | Machine interface for chain-state consumption, retiring the regex-over-human-prose fragility class (D5 sub-fix). |
| `openspec-graph.py export` (JSON export mode) | new Python subcommand | Emits the req→oblig→artifact→code topology as JSON for chain-state to consume as a jq predicate; the single fact extraction point that retires the three-parser divergence (D5). |
| python3 as declared prerequisite | prerequisite declaration | python3 added to the v12 declared prerequisite set (declare, install in CI); bash chain-state remains as the degraded gate-path mode when python3 is unavailable. |
| command/exit disagreement lint | new ledger validation rule | If `command` parses as a runnable shell command, replay must reproduce the recorded exit; catches the archived ledger's grep-exit-1-recorded-as-0 row (D4). |
| YAML `&invariant`/`*invariant` anchors | YAML config change | Enforce identity of the duplicated invariant banner (9 copies in schema.yaml) — prevents drift (D8). |

## Risks and Mitigations

- **D2 baseline-forgiveness false negatives.** If `git diff --quiet` says
  "unchanged" but a transitive dependency changed (e.g., a shared helper
  script), the forgiven evidence may be stale. Mitigation: the forgiveness
  rule applies per-artifact; transitive dependencies are a separate concern
  (the graph graduation in §5 would address this, but is out of scope).
  Document the limitation in the spec.

- **D3 `ledger.sh run` changes the apply instruction.** Wiring capture into
  apply Steps 3–11 changes the workflow's own instruction text — a
  meta-change. Mitigation: the instruction change is itself a spec
  requirement (in `evidence-capture`), tested via the
  `correctness-invariant.bats` suite that renders artifact instructions
  through the `openspec` CLI.

- **D5 python3 on the gate path.** Promoting `openspec-graph.py` to the
  single fact extractor adds python3 as a runtime dependency for
  chain-state (currently bash+jq only on the gate path). If python3 is
  unavailable, the gate degrades. Mitigation: python3 goes through the v12
  declared prerequisite mechanism (declare, install in CI, assert with
  `--check-installed`); bash chain-state remains as the degraded gate-path
  mode (mirroring concept-scanner's grep fallback when Metals is down). The
  degraded mode is explicitly documented, not silent.

- **Ring 8 reviewing the workflow's own fixes.** The adversarial reviewer
  checks fixes to the adversarial review mechanism itself — a
  self-reference. Mitigation: the reviewer works from the review document's
  defect descriptions (fixed, external input), not from the fix
  implementation, so the self-reference is bounded.

- **Ledger format backward compatibility.** New fields (`sha256`, `digest`,
  `wallTime`) must not break existing ledger readers. Mitigation: Ring 4
  backward-compat test with the archived `evidence-ledger.jsonl` fixtures;
  jq's `?` operator for optional fields in existing contracts.
