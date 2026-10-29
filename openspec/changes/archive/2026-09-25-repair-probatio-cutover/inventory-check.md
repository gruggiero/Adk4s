# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-09-20 (previously
refreshed 2026-09-20 by `complete-probatio-cutover`'s `native-gate-delivery` spec)
**Consistency check**: **2 stale rows fixed, 7 missing rows added** — all in the
`org.sinemenda.probatio.migration` test-only region.

Verification method: the inventory was **not** re-scanned from scratch (that would
destroy the provenance column). Every concept this change's proposal cites as reusable
was spot-verified against the source by declaration grep across
`workflow/{core,cli,plugin}/src/{main,test}/scala` — 33 `probatio-core` concepts, 14
`probatio-cli`/`packaging` concepts, 2 `probatio-plugin` concepts, and 13 `migration`
concepts. The semantic scanner (`scanner/scan.sh`) was **not** used: it is
creation-only per the artifact instruction, and it is one of the unported predecessor
tools this change registers rather than runs.

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `OracleGreenCheck` | `object (runOracle: SeamConfiguration → OracleOutcome)` | `final class OracleGreenCheck extends ProbatioSuite` — a munit suite with **instance** methods `runOracle`, `runDifferential`, `genSeamConfiguration`; callers instantiate it | yes — `port-scanner-to-probatio / migration-protocol` section retained |
| `OracleGreenGate` | "delegates to `OracleGreenCheck.runOracle`" (reads as a static path) | "instantiates `new OracleGreenCheck()` and calls its instance `runOracle`, since `OracleGreenCheck` is a suite class not an object" | yes — `complete-probatio-porting / migration-protocol` section retained |

Evidence: `OracleGreenCheck.scala:29` (`final class … extends ProbatioSuite`),
`:116` (`def runOracle`), `:151` (`def runDifferential`); `OracleGreenGate.scala:49,81`
(`val check: OracleGreenCheck = new OracleGreenCheck()`).

## Missing rows added

Seven concepts were **shipped but never recorded**. Six of them landed with the archived
`cutover-gate` spec, whose concept-delta check did not catch them; the seventh landed
with `hook-cutover`. They are recorded with **their originating spec's provenance**, not
this change's — this change discovered the gap, it did not introduce the concepts.

| Concept | Kind | Provenance recorded |
|---------|------|---------------------|
| `DifferentialHarness` | object (`runSuite`, `diff`, `verifySuiteDigests`; nested `SuiteRun`, `BatsFileResult`) | `spec:complete-probatio-cutover/cutover-gate` |
| `FileComparison` | final case class (fileName, total, predecessorFailures, portedFailures, predecessorPresent, portedPresent — `isWorse`) | same |
| `DifferentialResult` | final case class (files, repository — `isComplete`, `worseFiles`, `hasRegression`, `worseFileNames`) | same |
| `CutoverGate` | object (`decide`, `record`) | same |
| `CutoverVerdict` | enum (Proceed, Revert(evidence)) | same |
| `GateRecord` | final case class (verdict, evidence — `authorisesSwap`, `hasEvidence`) | same |
| `SkillDocLintCheck` | `final class … extends ProbatioCliSuite` (a suite, not an object) | `spec:complete-probatio-porting/hook-cutover` |

The `DifferentialHarness` row carries an explicit **KNOWN DEFECT** note recording that
`runSuite` does not materialise seam-configured trees as its own scaladoc and the
`strangler-migration-protocol` concept both declare, and naming the spec that repairs it.
An inventory row that described only the intended behaviour would reproduce the very
gap this change exists to close.

## Behavioral Concepts (registry pass)

**registry-check.sh**: **OK** —
`registry-check: OK (803 implementation-map tokens verified, 0 spec concept references checked, 5 weak binding(s) to tighten)`

**Stale implementation-map rows**: none. Five **weak bindings** reported, all
pre-existing and all in adk4s concepts untouched by this change
(`graph.md`: `GraphCompilationError`; `tools-node.md`: `executeToolCalls`,
`executeFromToolCalls`, `toolCalls`, `toolsNode` — each cites `ReactAgent.scala` while
the identifier lives in its own file). Out of scope here: the rule is that a failing row
is fixed by the change that moved the code, and this change moves none of it.

**Note on the "0 spec concept references checked" figure**: that clause checks active
change specs' `Concepts Used (behavioral)` tables against the registry. This change has
no `specs/` directory yet, so the clause had nothing to check — it is **not** a pass for
this change's specs. It must be re-run once the eleven spec files exist; that re-run is
a task in the specs artifact, not a result inheritable from here.

**Unregistered actions / syncs / state components**: one, and it is load-bearing.

`openspec/concepts/strangler-migration-protocol.md` declares under **Synchronizations**:

> The `DifferentialHarness` object materialises two seam-configured scanner trees, runs
> the suite against each, and emits the `DifferentialResult` the gate decides on

`registry-check.sh` passes this row because it verifies that the **identifier**
`DifferentialHarness` resolves to a tracked file — it cannot verify that the object's
behaviour matches the prose. The prose is correct and the code is not: `runSuite` sets
five `*_OVERRIDE` environment variables and runs `bats` against whatever is on disk.
This is a limitation of the registry mechanism (name-binding, not behaviour-binding),
recorded here so it is not mistaken for a clean verdict on that synchronization.

No concept prose changes are needed: `specs/differential-harness-integrity/spec.md`
brings the code to the already-correct concept. A related divergence in the same concept
— **State** declares `SwapOrder` with six positions including `LedgerFirst`, while
`ToolId` enumerates only five seams and has no `Ledger` — is likewise resolved by
bringing the code to the concept.

## Concepts relevant to THIS change

Working excerpt; the specs' own `Concepts Used` tables remain the commitments.

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| Strangler migration protocol | behavioural concept | `openspec/concepts/` | reuse — code brought to concept (specs 1, 8) |
| Conformance property-test contract | behavioural concept | `openspec/concepts/` | reuse — graph export bidirectional equivalence (spec 5) |
| `DifferentialHarness` / `DifferentialResult` / `FileComparison` / `CutoverGate` / `CutoverVerdict` / `GateRecord` | object / case classes / enum | `probatio.migration` | reuse — `diff` and `decide` are correct; `runSuite` is repaired (spec 1) |
| `ToolId` / `SeamConfiguration` / `MigrationState` / `SwapOrder` | enums / case classes | `probatio.migration` | reuse + **modify**: `ToolId` gains `Ledger`, `Checkpoint` (specs 1, 8) |
| `OracleGreenCheck` / `SkillDocLintCheck` | suite classes | `probatio.migration` | reuse by instantiation |
| `Outcome[+A]` | enum | `probatio.core` | reuse — spec 2 restores its three-way discipline, does not change it |
| `ChainState` / `ChainStateReport` / `ChainStateUndetermined` / `ChainState.Requirement` | object / case classes | `probatio.core` | reuse + **modify**: the boundary between report and undetermined (spec 2) |
| `SpecDocument` / `SpecDocumentParser` | case class / object | `probatio.core` | reuse — the graph port's spec-side parser already exists (spec 5) |
| `LintReport` / `CheckOutcome` / `CheckId` / `RequirementVerdict` | class / enums | `probatio.core` | reuse — requirement source for the graph (spec 5) |
| `GateEvent` / `GateDecision` / `GateDecisions` / `BlockReason` / `SpecPhase` | enums / object | `probatio.core` | reuse — spec 4 changes the parse, spec 3 the completion predicate |
| `PredecessorCheck` / `GrantWaiver` / `RefusalBudget` | objects | `probatio.core` | reuse as-is |
| `Ledger` / `LedgerRecord` / `Validator` / `ContractViolation` | object / class / object / hierarchy | `probatio.core` | reuse as-is (spec 8) |
| `CheckpointEngine` / `CheckpointReport` / `RingEvidence` / `ReplayVerdict` | object / case classes / enum | `probatio.core` | reuse as-is (spec 8) |
| `SchemaPolicy` (`migrateCache`, `CacheState`) | object / case class | `probatio.core` | reuse — **shipped, verified, no production caller**; spec 10 wires it |
| `DriftScan` / `InstallRootScan` / `DriftWarning` / `InstallRoots` | object / types | `probatio.core` | reuse as-is |
| `BannerEngine` / `BannerInputs` / `RepositoryFacts` | object / classes | `probatio.core` | reuse as-is |
| `Subcommand` / `MulticallDispatch` / `CliError` / `HelpRegistry` / `ExitCode` | enum / objects | `probatio.cli` | reuse + **modify**: `Subcommand` regains `Graph` (spec 5) |
| `GateCmd` … `InstallHooksCmd` (10 objects in `SubcommandEntrypoints.scala`) | objects | `probatio.cli` | reuse + **introduce** `GraphCmd` (spec 5) |
| `GateStateDir` / `GateStateDirReader` / `HarnessPayloadReader` | case class / objects | `probatio.cli` | reuse as-is |
| `ShimGenerator` / `InstallResolver` / `BinaryResolution` / `Platform` | objects | `probatio.plugin`, `probatio.packaging` | reuse — spec 9 changes the resolution supplied, not the signatures |
| `FeatureFreezeViolation` / `FeatureFreezeVerdict` / `KnownCheckId` / `FixtureVerdict` | enums / case class | `probatio.guard` | reuse — spec 6 repairs the corpus resolution around them |
| `ChainStateKernel` … `GateKernel` (9 objects) | Stainless objects | `probatio.verified` | reuse + **extend**: `ChainStateKernel` (spec 2), new reachability kernel (spec 5) |
| `ArmTree` / `ArmDivergence` | case class / enum | `probatio.migration` | **introduce** (spec 1) |
| `TraceabilityGraph` / `GraphNode` / `GraphEdge` / `GraphQuery` / `ReachabilityResult` / `ConceptRegistryDoc` / `InventoryDoc` | case classes / sealed hierarchies / enums | `probatio.core` | **introduce** (spec 5) |
| `EventDispatch` | enum | `probatio.core` | **introduce** (spec 4) |
| `WitnessVerdict` | enum | `probatio.core` | **introduce** (spec 3) |
| `UnportedTool` / `ToolSurfaceClassification` | case class / enum | `probatio.core` | **introduce** (spec 11) |

### Candidate concepts flagged for human review

None requiring a new behavioural concept file. The eleven specs' new types are all
*mechanism* for existing concepts: `ArmTree`/`ArmDivergence` refine the strangler
protocol's **Gate** action; `EventDispatch` and `WitnessVerdict` refine gate tiers
already described in the registry; `UnportedTool` records migration state the protocol
already reasons about. The graph port is the one open question — `TraceabilityGraph` may
warrant its own behavioural concept file rather than living under the migration
protocol. Flagged for the human gate at the specs artifact rather than decided here.
