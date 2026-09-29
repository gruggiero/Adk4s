# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-09-25 (404 typed
rows before this check, 405 after)
**Consistency check**: **1 missing row added, 0 stale rows.** Every concept the proposal
reuses was located in the inventory and, for the rows this change will modify, matched
against the source.

Verification method: spot-verification by exact row match, not a fresh scan. A fresh scan
would destroy the provenance column, and the semantic scanner is one of the unported tools
this change registers rather than runs. Forty-five concept names cited by the proposal
were checked. For the five rows this change will modify, the recorded shape was compared
against the declaring source file.

## Missing row added

| Concept | Kind | Provenance recorded |
|---------|------|---------------------|
| `CutoverKernel` | Stainless object — `decideWithWitness` / `Decision`, `findWorse`, and the arm-divergence extension `divergenceDecision` / `ArmVerdict`, `worseIndices`, `countWorse`, with ground lemmas | `spec:complete-probatio-cutover/cutover-gate` (`9e1ebc0`, 2026-09-13); extended by `spec:repair-probatio-cutover/differential-harness-integrity` (`c59a2aa`, 2026-09-21) |

This is the second consecutive change in which a shipped verification kernel or its
companion types reached the tree without an inventory row. The previous check found six
`cutover-gate` concepts in the same state. The kernel's two contract extensions are
recorded elsewhere under their method names (`DispatchKernel.classifyEvent`,
`GateKernel.decideCompletion`, `LedgerValidatorKernel.authoriseSwap`, …). `CutoverKernel`
had neither an object row nor method rows. The concept-delta check at apply Step 12 should
treat "a new object under `verified/probatio`" as an inventory obligation. It is recorded
here as a note for the apply phase, not changed in the schema.

## Rows this change will modify — verified against source

| Concept | Recorded | Source | Match |
|---------|----------|--------|-------|
| `Subcommand` | enum, 11 cases, including `Metals` and `Graph` | `Subcommand.scala`: `Gate, SpecLint, ChainState, Ledger, Checkpoint, Reconcile, DangerScan, Metals, InstallSkills, InstallHooks, Graph` | yes |
| `ToolId` (migration) | enum, 7 seams: `Ledger, ChainState, SpecLint, DangerScan, Reconcile, Checkpoint, Gate`; `overrideEnvVar` is optional | `SeamTypes.scala` | yes |
| `ToolSurfaceClassification` | enum `Ported(subcommand: String)`, `Registered(tool: UnportedTool)` | `UnportedTool.scala:72` | yes — note that `Ported` carries a free **string**, not a `Subcommand`. That is one reason the register can call a tool ported by name alone, and `surface-honesty` changes it |
| `InvocationName` | opaque type with `fromRuntime` | `InvocationName.scala` | yes |
| `GateStateDir` | case class; directory `<git-dir>/verified-scala3-gate` | `GateStateDir.scala` | yes |

Two cited names are **not concept rows, correctly**: `OracleDiffRunner` and
`GateBannerCompatSpec` are test suites — artifacts, not concepts — and the proposal does
not list them as reused concepts.

`GraphNode` has two rows. They are **different concepts** in different packages (adk4s's
`org.adk4s.orchestration.graph.GraphNode` and probatio's nine-kind `GraphNode`), not a
duplicate. Specs citing it must give the package.

`MetalsClient` is recorded only through its nested types (`MetalsSession`, `MetalsError`,
`LspMessage`). The object itself has no row. That is acceptable for this change, which
removes the subcommand and leaves the model in place.

## Behavioral Concepts (registry pass)

**registry-check.sh**: **OK** —
`registry-check: OK (817 implementation-map tokens verified, 0 spec concept references checked, 5 weak binding(s) to tighten)`

**Stale implementation-map rows**: none.

**Weak bindings**: the same five as on 2026-09-20 — `graph.md` (`GraphCompilationError`)
and `tools-node.md` (`executeToolCalls`, `executeFromToolCalls`, `toolCalls`,
`toolsNode`). Each cites `ReactAgent.scala` for an identifier declared in its own file.
They have now persisted through a full change. They stay out of scope: they sit in adk4s
concepts this change does not touch, and the rule is that a stale binding is fixed by the
change that moves the code. **Recorded as re-established on 2026-09-25**, not carried
forward as "pre-existing".

**"0 spec concept references checked"**: this change has no `specs/` directory yet, so
that clause had nothing to check. It is not a pass for this change's specs. It must be
re-run once they exist.

**Registry growth since the last check**: 37 → 38 concepts. The new one is
`openspec/concepts/traceability-graph.md`, added by `graph-tool-port` (`c3e3d54`) — the
behavioural concept the previous inventory check flagged for a human decision.

**Concepts this change will modify:**

- **Strangler migration protocol** — its **State** gains three seams (the two installers
  and the registry verifier). Its **Gate** action is currently expressed as "the oracle
  passes unmodified", and it gains a precise meaning: every modification is sanctioned by
  a named requirement. The register's classification changes from *named on the surface*
  to *reached by the live invocation path*. Updating the concept file is part of
  `installer-swap`, `oracle-independence` and `surface-honesty` respectively.
- **Traceability graph** — no behavioural change. `surface-honesty` documents
  `probatio graph` as the tool's user-facing entry point.

**Unregistered actions / syncs / state components**: none found. Every new concept the
proposal previews is mechanism for an existing concept. The proposal's `OracleSanction`
record is new persisted state; it is owned by the migration protocol's Gate action and
is named there when `oracle-independence` updates the concept file.

## Concepts relevant to THIS change

Working excerpt; the specs' own tables remain the commitments.

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| Strangler migration protocol | behavioural concept | `openspec/concepts/` | **modify** (specs 4, 8, 9) |
| Traceability graph | behavioural concept | `openspec/concepts/` | reuse; documented (spec 9) |
| Conformance property-test contract | behavioural concept | `openspec/concepts/` | reuse — registry-verifier agreement (spec 10) |
| `InvocationName` / `MulticallDispatch` / `ProgramArgs` | opaque / object / opaque | `probatio.cli` | **modify** (spec 1) |
| `Subcommand` / `HelpRegistry` / `CliError` | enum / object / class | `probatio.cli` | **modify** — loses `Metals` (spec 9), gains the registry verifier (spec 10) |
| `ShimGenerator` / `InstallResolver` / `ShimTargetScope` / `BinaryResolution` | objects / enum | `probatio.plugin`, `probatio.packaging` | **modify** — installed launcher (spec 1) |
| `ReleaseCheck` | object | `probatio.packaging` | reuse (spec 6) |
| `ArmTree` / `ArmDivergence` / `SeamResolution` / `DifferentialHarness` / `CutoverGate` | case class / enum / case class / objects | `probatio.migration` | **modify** — artifact provisioning (spec 1); new seams (specs 8, 10) |
| `ToolId` / `SwapOrder` / `SeamConfiguration` | enums / case class | `probatio.migration` | **modify** (specs 8, 10) |
| `OracleImmutabilityResult` / `FeatureFreezeViolation` / `KnownCheckId` / `FixtureCorpus` / `CorpusResolution` | enums / case classes | `probatio.guard` | **modify** (specs 3, 4) |
| `UnportedTool` / `PortBlocker` / `ToolSurfaceClassification` / `UnportedToolRegister` | case class / enums / object | `probatio.core` | **modify** (specs 9, 10) |
| `TraceabilityGraph` / `GraphNode` (probatio) | case class / sealed trait | `probatio.core` | reuse (spec 9) |
| `EventDispatch` / `WitnessVerdict` / `PrePassOutcome` | enums | `probatio.core` | reuse |
| `InstallTarget` / `InstallMode` / `PrerequisiteProbe` | enums / case class | `probatio.core` | reuse (spec 8) |
| `SchemaPolicy` / `CacheMigration` / `RenameDeferral` | objects / case class | `probatio.core`, `probatio.cli` | **modify** (specs 11, 12) |
| `GateStateDir` | case class | `probatio.cli` | **modify** (spec 11) |
| `MetalsClient.MetalsSession` / `.MetalsError` | case class / sealed trait | `probatio.core` | retire from the surface (spec 9) |
| `CutoverKernel` / `DispatchKernel` (via `DispatchKernel.classifyEvent`) | Stainless objects | `probatio.verified` | **extend** (specs 1, 4) |
| `InvocationSource`, `HermeticEnv`, `ChangeLocation`, `OracleTestKind`, `OracleSanction`, `SanctionVerdict`, `LiveRoute`, `RegistryRow`, `BindingVerdict`, `LegacyAlias`, `SchemaAlias` | new | various | **introduce** — see the proposal |

### Candidate concepts flagged for human review

None that need a new behavioural concept file. The proposal's new types are mechanism for
existing concepts: `HermeticEnv` and `ChangeLocation` are test infrastructure;
`OracleSanction` and `LiveRoute` refine the migration protocol; `RegistryRow` and
`BindingVerdict` port the registry verifier's existing behaviour.
