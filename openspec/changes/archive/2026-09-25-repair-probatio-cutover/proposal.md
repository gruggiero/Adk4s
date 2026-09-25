# Proposal: Repair the probatio cutover and finish the replacement

## Why

The `complete-probatio-cutover` change was archived on 2026-09-20 with the exit
criterion *"`sbt probatioOracleDiff` reports no bats file worse than the predecessor
control — VERDICT PROCEED"*. That criterion was discharged by a harness that never
executes the predecessor.

### Measured state (2026-09-20, recorded in `docs/openPoints/probatio-cutover-gap-analysis.md`)

The 282-test bats oracle, run three ways in the same repository at `817d185`:

| Arm | Pass | Fail |
|---|---|---|
| Predecessor control — a detached worktree with the five `*.predecessor.bak` files restored | 264 | **18** |
| Live probatio (the shims) | 252 | **30** |
| `sbt probatioOracleDiff`, reported "predecessor arm" | — | **30** |
| `sbt probatioOracleDiff`, reported "ported arm" | — | **30** |

`DifferentialHarness.runSuite`
(`workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarness.scala:54`)
builds an environment map and runs `bats` **in place**. It never copies a tree, never
writes a shim, and never reads a `*.predecessor.bak` — although its own doc comment at
line 7, and the `strangler-migration-protocol` concept's Synchronizations section, both
state that it "materialises two seam-configured scanner trees". Both arms therefore
execute the same on-disk shims. The only difference is five `*_OVERRIDE` variables,
which are sub-tool *stubbing* seams (read by both the predecessor gate and probatio's
`SubcommandEntrypoints` to choose what the gate calls), not implementation-selection
seams — and which the tests themselves set 36 times in `hook-tiers.bats` alone,
overriding the harness's values. `GATE_OVERRIDE` is read by nothing.

`hasRegression=false` is therefore structurally guaranteed: `CutoverVerdict.Proceed` is
unfalsifiable by construction. The proof is in the harness's own output — it reports
`workflow-hygiene.bats: pred=6` where the real predecessor fails **0**, and
`chain-state.bats: pred=8` where the real predecessor fails **6**.

**Against the real control, probatio is 12 tests worse across 4 files.** The
`cutover-gate` criterion ("no file is worse") is violated and the correct verdict is
REVERT, not the recorded PROCEED.

### The regressions, and why two of them are urgent

| Finding | Tests | Defect |
|---|---|---|
| D2a | `chain-state` #11, #23 | A spec-lint that cannot run yields exit **1** plus a report asserting `total:1, bound:1, resolved:1` — where the predecessor yields exit **2**, UNDETERMINED. The tool emits a *measurement* derived from an invocation that did not happen. |
| D2b | `ambient-capture-wiring` #29 | The completion tier does not refuse when a green ledger row has no witness: exit 0 where the predecessor exits 1. A blocking Stop-hook enforcement tier silently passes. |
| D2c | `workflow-hygiene` #5, #6, #9, #10 | probatio rejects `--event user-prompt-submit` with exit 1; the predecessor routes any unrecognised event to the injection tier at exit 0. |
| D2d | `fact-extraction` D5 #2, #6, #9 | `chain-state` neither consumes `openspec-graph.py export` JSON nor emits the degraded-mode trace. |
| D2e | `workflow-hygiene` D7, D8-cwd | Two tests assert over the *source text* of the bash implementation and cannot pass against a 2-line shim. |

D2a and D2b are the same defect class the invariant exists to remove: a claim
outrunning its evidence, and an enforcement tier reporting success while enforcing
nothing. They are live in every session.

### Two further inert mechanisms

- **`non-goals-guard` R-X1 is unenforced.** `NonGoalsGuardSpec.scala:350` hardcodes
  `openspec/changes/complete-probatio-cutover/specs`; archiving moved it. The suite is
  red (`probatio-core` 583/584) and the feature freeze has no enforcing test. It went
  red in the same commit that declared the change complete.
- **Nothing runs the oracle in CI.** `.github/workflows/` holds only
  `release-probatio.yml`. No job runs the bats suite, the differential, or the probatio
  test suites — which is how both a red guard and a 12-test regression reached an
  archived, checkpoint-approved change.

### Why this cannot be deferred

The predecessor is still on disk, so the workflow is recoverable. But the *certification*
is not: every claim of parity made since 2026-08-29 rests on a comparison of a run
against itself. Repairing the measurement is a prerequisite for any honest statement
about the rest, which is why it is spec 1 and everything else is ordered behind it.

## What Changes

This change repairs the cutover's measurement, closes every regression it hid, restores
the guards that should have caught it, completes the remaining swaps, and finishes the
v14 rename except for one item whose coupling makes it unsafe here.

It remains a **port, not a redesign**: `non-goals-guard` R-X1 holds. No F-check is
added, no verdict is altered, no workflow feature is introduced. Every behavioural
requirement below either restores predecessor behaviour or ports an existing predecessor
tool. Success is defined as **the bats oracle at parity with a genuine predecessor
control** — measured by a harness that has been proven to run two different
implementations — not as "tests we wrote pass".

### Affected Capabilities

- `specs/differential-harness-integrity/spec.md` — the comparison SHALL execute the
  predecessor in one arm and the port in the other, at every seam the cutover swapped,
  with a guard that fails when both arms resolve to identical bytes. `ToolId` gains
  `Ledger` and `Checkpoint` (today `SwapOrder` declares `LedgerFirst` while `ToolId` has
  no `Ledger` — the two disagree inside one test package). Also retargets the two
  source-grepping oracle tests (D2e) at the probatio implementation, so the suite can
  again distinguish a stale test from a new regression.
- `specs/chain-state-undetermined-fidelity/spec.md` — a sub-tool that could not run
  yields UNDETERMINED and **no report**; the bound/resolved/discharged measurement is
  unconstructible without the invocation that produced it (D2a).
- `specs/completion-witness-refusal/spec.md` — the completion tier refuses when a green
  ledger row has no witness, at predecessor exit-code parity (D2b).
- `specs/gate-event-compatibility/spec.md` — the predecessor's permissive event
  fallback is restored: a recognised tier name dispatches to its tier, any other value
  routes to the injection tier at exit 0 (D2c).
- `specs/graph-tool-port/spec.md` — `openspec-graph.py` (626 lines, 5 subcommands) is
  ported to probatio; `graph` returns to the `Subcommand` surface; `chain-state` regains
  its fact-extraction seam and its degraded-mode trace (D2d).
- `specs/feature-freeze-guard-integrity/spec.md` — the feature-freeze guard resolves its
  corpus rather than hardcoding a change directory, and survives archival (D5).
- `specs/install-tool-surface-parity/spec.md` — `install-skills` and `install-hooks`
  reach predecessor surface parity before their shims are swapped (D3).
- `specs/ledger-checkpoint-cutover/spec.md` — `ledger` and `checkpoint` are swapped
  under the repaired harness, which is the first time the oracle will have executed
  them at all (D3).
- `specs/workflow-delivery-hygiene/spec.md` — shims resolve relatively instead of
  hardcoding one developer's checkout; a CI job runs the oracle, the differential and
  the probatio suites (D7).
- `specs/schema-rename-completion/spec.md` — skill stamps, tutorial docs, CI templates,
  harness adapters, `SchemaPolicy.migrateCache` wiring, and the schema-version oracle
  assertion are brought to v14. The **directory rename is deferred**, with its coupling
  recorded as the reason (D6).
- `specs/unported-tool-register/spec.md` — the tools that remain on their predecessor
  implementations are enumerated in a machine-checked register: every executable under
  `scanner/` and `hooks/` is either named in `Subcommand` or named in the register, with
  its blocker. No third state (D4).

### Out of Scope

- **`registry-check.sh`, `scan.sh` + `concept-scanner.scala`, `impact-scan.sh`,
  `removal-audit.sh`, `metals call|stop`.** These six remain on their predecessor
  implementations. `scan.sh`/`concept-scanner.scala` are gated on the `native-packaging`
  R-N5 scalameta spike, which has still not been run; the Metals recipes are not
  hook-invoked and not exercised by the oracle. This change does not port them — it
  makes the non-port *explicit and machine-checked* via `unported-tool-register`, so an
  unported tool is recorded rather than forgotten. Their ports are a later change.
  `openspec-graph.py` has been **removed** from this list and is ported by
  `specs/graph-tool-port/spec.md`.
- **The schema directory rename** (`openspec/schemas/verified-scala3/` →
  `.../probatio/`). OpenSpec derives the schema name from the *directory*, not from
  `schema.yaml`'s `name: probatio`. `openspec/config.yaml` pins `schema: verified-scala3`
  and 18 archived changes plus this one pin it in their `.openspec.yaml`. Renaming the
  directory without a resolution alias breaks schema resolution for every archived
  change. `schema-rename-completion` records this coupling and the alias design as the
  deferral's stated reason; the rename itself is a later change.
- Any new F-check, verdict change, or workflow feature (`non-goals-guard` R-X1).
- Any schema change beyond what v14 already declares (`non-goals-guard` R-X2).
- Retiring the predecessor `.sh` files. By the measurement above the green criterion has
  not held at all, so the revert target stays on disk.

## Approach

**Repair the measurement first, then re-measure, then fix what the measurement shows.**

1. **Make the differential real.** Materialise two trees, restore `*.predecessor.bak` in
   the predecessor arm, and assert the arms differ before running. Extend the seam set
   to every swapped tool plus `Ledger` and `Checkpoint`. The exit criterion of this spec
   is that the harness reproduces the hand-measured control (18 predecessor failures),
   not that it returns PROCEED. Until this holds, every number downstream conflates
   "at parity" with "compared against itself".

2. **Re-measure, then close the regressions in oracle-failure order** — D2c (4 tests,
   one restored fallback) → D2d/graph port (3 tests, the largest port) → D2a (2 tests,
   the UNDETERMINED collapse) → D2b (1 test, the completion refusal). The two enforcement
   holes (D2a, D2b) are smallest in test count and largest in consequence; they are
   sequenced after the cheap wins only because each spec's exit criterion is its own bats
   file at control parity, and the cheap wins reduce the noise in that signal.

3. **Restore the guards before the swaps.** A repaired `NonGoalsGuardSpec` and a CI job
   are what make steps 4–5 self-checking rather than self-certifying.

4. **Widen, then swap.** `install-skills` and `install-hooks` reach surface parity
   before their shims move; `ledger` and `checkpoint` swap under the repaired harness.

5. **Finish the rename** except the directory, recording the coupling.

6. **Record the residue.** The six unported tools become a checked register rather than
   a paragraph in an archived proposal.

The ordering is deliberate: (1) is a prerequisite for any honest claim about (2)–(5),
and (3) is a prerequisite for trusting that (4)–(5) did not quietly widen scope.

## Correctness Risk Level

**Risk**: **high** — this change repairs enforcement mechanisms that are currently inert
or holed, and its failure modes are all silent-success: a harness that compares a run to
itself, a tool that reports a measurement it did not take, a refusal tier that exits 0.
Each has already shipped once in this migration. It also touches verdict-producing logic
(`chain-state`'s UNDETERMINED boundary, the completion tier's refusal predicate) where a
mis-fix changes what the workflow *concludes*, not merely what it prints. The graph port
adds three markdown parsers whose misparse would silently shrink the reachability set —
the confidently-wrong failure mode the tool itself was written to avoid.

## Verification Strategy

- [x] Ring 0: Compilation — `probatioScalacOptions` (`-Werror`, exhaustiveness
      escalation, discard/init checks) on `probatio-core` and `probatio-cli`
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan on the
      diff; shellcheck on the regenerated shims and the CI job
- [x] Ring 2: Architecture — `probatioDependencyLint`; R-ARCH1 (no cats / cats-effect /
      fs2 / llm4s / workflows4s / adk4s on the `workflow/*` classpath); the graph model
      and its parsers stay in `probatio-core` free of file I/O and `System.getenv`
      (`NoIOInProbatioCore`), with all reading in the `probatio-cli` adapter
- [x] Ring 3: Property-based tests — MANDATORY. munit + Hedgehog 0.13.1
      (`HedgehogSuite`, integrated shrinking, no `Arbitrary`). Additionally the **bats
      differential oracle is the acceptance suite** (`migration-protocol` R-M1): each
      spec's exit criterion is its bats file at parity with the *genuine* predecessor
      control produced by spec 1.
      **CONCURRENCY note** (check 18 APPLIES per the session banner): this change runs
      subprocesses — the differential harness spawns two full bats suites, `ledger run`
      and `gate post-bash` observe process exit codes, and the graph tool reads a tree
      of files. The capability profile's detected deterministic kit is cats-effect
      `TestControl`, which is **not available here**: R-ARCH1 forbids cats-effect on the
      `workflow/*` classpath. Determinism is therefore obtained the way the existing
      probatio specs obtain it — recorded process outcomes and an injected clock seam,
      never wall-clock sleeps. This is a stated substitution, not a waiver.
- [x] Ring 4: Wire/persistence compatibility — REQUIRED. The three `.jq` contracts
      (`ledger-record-contract.jq`, `chain-state-report-contract.jq`,
      `gate-hookjson-contract.jq`) remain the wire format and must still conform after
      the chain-state and gate changes. The graph port's `export` JSON is a **new** wire
      format: its node/edge shape must round-trip and must match the predecessor's
      `openspec-graph.py export` output on the repository's own corpus.
- [x] Ring 5: Mutation testing — Stryker4s 0.21.0. `stryker4s.conf` currently pins the
      spec-9 PASS B set of **six** files (`packaging/{BudgetVerdict,LatencyMeasurement,
      ReleaseCheck,ReleaseManifestIO,ReleaseValidator}.scala`, `cli/SubcommandWiring.scala`)
      and has **no `test-filter` key**; `mutate` must be retargeted per spec before each
      run, with a `test-filter` added only where a spec needs one. Thresholds as
      configured are `high=90`, `low=80`, `break=0` — `break=0` means Stryker never fails
      the build, so a spec claiming Ring 5 green must read the **score**, not the exit
      code. **Stryker collects coverage for MAIN sources only**, so the specs whose
      implementation lives in test sources (`probatio.migration`, `probatio.guard` —
      specs 1, 6 and part of 8) require the documented move-to-main-and-back procedure.
      Target: **90%** on `probatio-core` decision logic (chain-state boundary, graph
      reachability), **80%** on `probatio-cli` adapters.
      <!-- Corrected 2026-09-20 by capability-check: the previous text repeated the
           profile's stale ONE-file + test-filter claim instead of reading the conf. -->

- [x] Ring 6: Formal verification — APPLIES. Two kernels sit at the centre of this
      change. `ChainStateKernel` already exists in `probatio-verified` and is extended
      with the UNDETERMINED boundary (spec 2) — the property that no report is
      constructible from a non-running sub-tool. The graph's **reachability closure**
      (requirement → obligation → artifact, and the `obligations` audit's "every
      requirement reaches an enforcement artifact") is a transitive-closure algorithm
      with a pure PureScala kernel, mirrored per `templates/verified-mirror.md` with a
      mandatory bridge property binding shipped code to the model. Stainless 0.9.9.3,
      smt-z3 fallback, `probatio-verified` pinned to Scala 3.7.2. Invoke directly
      (`sbt -J-Xmx6g 'set probatio-verified/stainlessEnabled := true'
      'probatio-verified/compile'`) — the `ring6` alias is broken under sbt 1.12.
- [ ] Ring 7: Model checking — not applicable. The graph is a reachability computation
      over a static corpus, not a distributed or event-ordering invariant; the ledger's
      append-only discipline is the only ordering property and Ring 6 covers it.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY, fresh context, per spec,
      before Rings 5/6. **Reviewer standing instruction for this change**: for every
      requirement, check whether the *shipped artifact* satisfies it when invoked as a
      subprocess, and — for spec 1 specifically — whether the mechanism under review can
      return a passing verdict when the thing it measures is absent. The argv defect, the
      fabricated banner, and the self-comparing harness all survived a prior Ring 8
      because the tests and the reviewer inspected functions rather than the product.
- [ ] Ring 9: Telemetry — no telemetry stack in `workflow/*`.

## Typed Contract Decision

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/differential-harness-integrity/spec.md` | full | `ToolId` gains variants; the harness's arm-materialisation gains a type that makes "both arms are the same tree" unconstructible |
| `specs/chain-state-undetermined-fidelity/spec.md` | full | changes the result algebra at the UNDETERMINED boundary — a report must become unconstructible without its producing invocation |
| `specs/completion-witness-refusal/spec.md` | full | changes a blocking tier's decision function and its exit-code mapping |
| `specs/gate-event-compatibility/spec.md` | full | changes the event-parse signature from total-over-six to a tier/fallback split |
| `specs/graph-tool-port/spec.md` | full | introduces the graph node/edge ADTs, three parsers, five query operations, and a new wire format; `Subcommand` regains a case |
| `specs/feature-freeze-guard-integrity/spec.md` | minimal | test-corpus resolution; no production type changes |
| `specs/install-tool-surface-parity/spec.md` | full | widens two public CLI surfaces (new flags, dry-run/apply mode, agent selection) |
| `specs/ledger-checkpoint-cutover/spec.md` | minimal | shim swap + seam wiring; `LedgerCmd`/`CheckpointCmd` signatures are unchanged |
| `specs/workflow-delivery-hygiene/spec.md` | minimal | shim resolution wiring and CI configuration; `ShimGenerator` signature unchanged |
| `specs/schema-rename-completion/spec.md` | minimal | wires the existing `SchemaPolicy.migrateCache` kernel to an adapter; the rest is content |
| `specs/unported-tool-register/spec.md` | full | introduces the register type and the total "named or registered, no third state" classification |

## Existing Concepts to Reuse

Populated from `openspec/concept-inventory.md` (363 typed rows, PRESENT) and
`openspec/concepts/` (37 concepts, PRESENT).

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| Strangler migration protocol | behavioural concept | `openspec/concepts/strangler-migration-protocol.md` | **modified** — its Synchronizations already declare that `DifferentialHarness` "materialises two seam-configured scanner trees"; the code does not. Spec 1 brings the code to the concept; the concept's prose is already correct |
| Conformance property-test contract | behavioural concept | `openspec/concepts/conformance-property-test-contract.md` | reuse as-is for the graph export's bidirectional equivalence with the predecessor |
| `Outcome[+A]` | enum (`Ran`/`Finding`/`Undetermined`) | `probatio.core` | reuse as-is — the three-way protocol is what spec 2 restores, not changes |
| `ChainState` / `ChainStateReport` / `ChainStateUndetermined` | object / case classes | `probatio.core` | reuse; spec 2 tightens the boundary between the last two |
| `ChainState.Requirement` / `RequirementVerdict` / `UnresolvedReason` | case classes / enum | `probatio.core` | reuse as-is |
| `LintReport` / `CheckOutcome` / `CheckId` | class / enums | `probatio.core` | reuse as the graph's requirement source |
| `SpecDocument` / `SpecDocumentParser` | case class / object | `probatio.core` | **reuse for the graph port** — the spec-side parser already exists; the port adds only the concepts-registry and inventory parsers |
| `GateEvent` / `GateDecision` / `BlockReason` / `SpecPhase` | enums | `probatio.core` | reuse; spec 4 changes the parse, not the algebra |
| `GateDecisions` / `PredecessorCheck` / `GrantWaiver` / `RefusalBudget` | objects | `probatio.core` | reuse; spec 3 wires the completion tier's witness predicate |
| `Ledger` / `LedgerRecord` / `Validator` / `ContractViolation` | object / class / object / hierarchy | `probatio.core` | reuse as-is for spec 8 |
| `CheckpointEngine` / `CheckpointReport` / `RingEvidence` / `ReplayVerdict` | object / case classes / enum | `probatio.core` | reuse as-is for spec 8 |
| `SchemaPolicy` (`migrateCache`, `CacheState`, `resolveHookEnv`) | object / case class | `probatio.core` | **reuse — `migrateCache` and `CacheState` are shipped, verified and have no production caller.** Spec 10 wires them; it does not re-implement them |
| `DriftScan` / `InstallRootScan` / `DriftWarning` / `InstallRoots` | object / types | `probatio.core` | reuse as-is (six roots, correct) |
| `BannerEngine` / `BannerInputs` / `RepositoryFacts` | object / classes | `probatio.core` | reuse as-is |
| `Subcommand` / `MulticallDispatch` / `CliError` / `HelpRegistry` / `ExitCode` | enum / objects | `probatio.cli` | reuse; `Subcommand` regains `Graph` — consistent with `cli-entrypoint-contract`'s rule ("a tool that has no implementation is not nameable"), because spec 5 gives it one |
| `CliContext` / `StdoutRenderer[A]` / `SubcommandWiring` | case class / typeclass / object | `probatio.cli` | reuse and extend |
| `GateCmd` / `SpecLintCmd` / `ChainStateCmd` / `LedgerCmd` / `CheckpointCmd` / `ReconcileCmd` / `DangerScanCmd` / `MetalsCmd` / `InstallSkillsCmd` / `InstallHooksCmd` | objects (`run: Array[String] → Outcome[Int]`) | `probatio.cli` | reuse and extend; a `GraphCmd` joins them in spec 5. (`SubcommandEntrypoints` is the **file** these live in, not a type — corrected 2026-09-20 by inventory-check) |
| `GateStateDir` / `GateStateDirReader` / `HarnessPayloadReader` | case class / objects | `probatio.cli` | reuse as-is |
| `ShimGenerator` / `InstallResolver` / `BinaryResolution` / `Platform` | objects | `probatio.plugin`, `probatio.packaging` | reuse; spec 9 changes what resolution they are given, not their signatures |
| `ToolId` (migration) / `SeamConfiguration` / `MigrationState` / `SwapOrder` | enums / case classes | `probatio.migration` (test-only) | reuse and extend — `SwapOrder` already declares `LedgerFirst`; `ToolId` must catch up |
| `DifferentialHarness` / `DifferentialResult` / `FileComparison` / `CutoverGate` / `CutoverVerdict` / `GateRecord` | object / case classes / enum | `probatio.migration` (test-only) | reuse the comparison and verdict algebra as-is — the defect is in `runSuite`'s arm construction, not in `diff` or `decide`. `DifferentialHarness.verifySuiteDigests` already exists and stays. **These six were absent from the inventory until 2026-09-20** — added by this change's inventory-check with `cutover-gate` provenance |
| `OracleGreenGate` / `ShimSwap` | object / case class | `probatio.migration` (test-only) | reuse as-is |
| `OracleGreenCheck` / `SkillDocLintCheck` | **final classes extending a munit suite**, not objects | `probatio.migration` (test-only) | reuse by instantiation, as `OracleGreenGate` already does (`new OracleGreenCheck()`). Kind corrected 2026-09-20 by inventory-check; the inventory recorded `OracleGreenCheck` as an `object` |
| `ChainStateKernel` / `LedgerValidatorKernel` / `BannerEngineKernel` / `ConformanceModel` | Stainless objects | `probatio.verified` | reuse and extend for Ring 6 |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `ArmTree` | final case class (private ctor) | a materialised, seam-configured copy of the scanner tree; carries the implementation each seam resolved to, so "both arms are the same tree" is detectable rather than invisible |
| `ArmDivergence` | enum (`Diverged(perSeam)` / `Identical(seams)`) | the pre-run guard's verdict — a comparison whose arms are `Identical` is refused, not reported as PROCEED |
| `GraphNode` | sealed hierarchy (`Concept`, `Action`, `Sync`, `Type`, `Spec`, `Req`, `Oblig`, `Artifact`, `Code`) | the traceability graph's node algebra, ported from `openspec-graph.py`'s nine node kinds |
| `GraphEdge` | enum (`Declares`, `DefinesSync`, `ImplementedBy`, `Cites`, `Uses`, `Introduces`, `HasReq`, `EnforcedBy`, `VerifiedBy`) | the nine edge kinds |
| `TraceabilityGraph` | final case class (nodes, edges, unlinkable) | the graph itself; `unlinkable` is a first-class field so a row the parser could not bind is **reported**, never silently dropped |
| `GraphQuery` | enum (`Export`, `Stats`, `Impact`, `Obligations`, `ConceptCode`) | the five ported subcommands as a closed set |
| `ReachabilityResult` | final case class | the `obligations` audit's output: requirements that reach an enforcement artifact, and those that do not |
| `ConceptRegistryDoc` / `InventoryDoc` | case classes + parsers | the two markdown sources the port must parse (`SpecDocumentParser` already covers the third) |
| `EventDispatch` | enum (`Tier(GateEvent)` / `Injection`) | makes the predecessor's permissive fallback total and explicit, rather than a `case _` in a parser |
| `WitnessVerdict` | enum (`Witnessed` / `Unwitnessed(row)`) | the completion tier's refusal predicate, currently absent |
| `UnportedTool` | final case class (name, path, blocker, citedBy) | one row of the carry-forward register |
| `ToolSurfaceClassification` | enum (`Ported(Subcommand)` / `Registered(UnportedTool)`) | the total classification — every executable under `scanner/`/`hooks/` is one or the other, and the absence of a third case is what the check enforces |

## Risks and Mitigations

| Risk | Detection | Mitigation |
|---|---|---|
| The repaired harness is slow — two materialised trees × 17 bats files. The current (vacuous) run already takes 127 s. | Measure the repaired run; record it. | Materialise once per arm, not per file; keep the suite parallel-safe. If it exceeds a stated budget, it runs in CI and on demand rather than inside `probatio-core/test`, where it currently sits as a test class. |
| The repaired harness reveals **more** than 12 regressions once `ledger`/`checkpoint` become real seams — they have never been oracle-tested. | Spec 1's exit criterion is reproducing the hand-measured control (18); spec 8's is the six ledger/checkpoint bats files at parity. | Expected, not a surprise. Spec 8 is sequenced last among the swaps precisely so its regressions surface against an already-trusted harness. |
| Restoring the permissive event fallback (D2c) re-opens the hole the strict parse was meant to close — an unrecognised event silently doing nothing. | The bats oracle asserts the predecessor's behaviour; a property asserts the dispatch is total. | `EventDispatch` makes the fallback an explicit named case, not a `case _`. The injection tier is a real behaviour, not a no-op, so "unrecognised" still produces observable output. |
| The graph port's parsers misparse and silently shrink the reachability set — the tool's own stated failure mode. | `TraceabilityGraph.unlinkable` is a required field; a bidirectional property compares the port's `export` against `openspec-graph.py`'s on the repository's own corpus. | Ring 4 treats the export as a wire format; Ring 6 mirrors the closure. An unlinkable row is reported and counted, never dropped. |
| Porting `graph` looks like a feature addition under the R-X1 freeze. | Ring 8 reviews the freeze explicitly. | It is a port of an existing 626-line predecessor tool that `chain-state` already depended on; no verdict changes and no F-check is added. The freeze analysis is recorded in the spec. |
| Deferring the directory rename leaves the schema's declared name (`probatio`) disagreeing with its resolvable name (`verified-scala3`) for another cycle. | The session banner already reports the stamp drift every session. | The deferral's reason and the alias design are recorded in `schema-rename-completion`, so the next change inherits a design rather than a rediscovery. `unported-tool-register` gives the same treatment to the six unported tools. |
| `NonGoalsGuardSpec` is the only test in its package; repairing its corpus resolution could make it pass vacuously on an empty corpus — exactly how it broke. | Spec 6 requires the guard to fail when its corpus is empty or unresolvable. | "No fixtures found" must be UNDETERMINED or a failure, never a pass. This is the same boundary spec 2 enforces for chain-state. |
