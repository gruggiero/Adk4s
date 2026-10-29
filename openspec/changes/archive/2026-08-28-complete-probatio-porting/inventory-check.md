# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-25
**Consistency check**: 1 stale row fixed (listed below)

The project inventory has 297 typed rows across 720 lines. Spot-verification against the current source confirmed all probatio entries are accurate except one: `ContractViolation` was listed as "12 clause variants" but the source has 15 (the `provenance-validation` spec added clauses 13–15 and recorded the 3 new variants as separate rows, but did not update the parent row's variant count).

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `ContractViolation` | sealed trait (12 clause variants) | sealed trait (15 clause variants — 12 original + 3 provenance clauses added by spec:port-scanner-to-probatio/provenance-validation) | spec:port-scanner-to-probatio/probatio-core (original); provenance clauses attributed to spec:port-scanner-to-probatio/provenance-validation |

**Spot-verification evidence**:
- `grep -cE "extends ContractViolation" workflow/core/src/main/scala/org/sinemenda/probatio/core/ContractViolation.scala` → 15
- `Subcommand` enum: 16 cases confirmed (`grep "case " Subcommand.scala` → Gate, SpecLint, ChainState, Ledger, Checkpoint, RegistryCheck, Reconcile, Scan, RemovalAudit, DangerScan, ImpactScan, Metals, ConceptScanner, Graph, InstallSkills, InstallHooks) — matches inventory row at line 591
- `Outcome[+A]`: enum with Ran/Finding/Undetermined confirmed — matches inventory row at line 563
- `GateEvent`: enum with 5 cases (SessionStart, PromptSubmit, PostEdit, ToolCall, Completion) confirmed — matches inventory row at line 691
- `ConformanceModel`: object in `org.sinemenda.probatio.verified` confirmed — matches inventory row at line 642
- `Validator`: object in `org.sinemenda.probatio.core` confirmed (not a separate inventory row — implicitly part of probatio-core spec concepts)

No rows were removed. No new rows added (this change introduces no new types until the apply phase).

## Behavioral Concepts (registry pass)

**registry-check.sh**: `registry-check: OK (803 implementation-map tokens verified, 0 spec concept references checked, 5 weak binding(s) to tighten)`

**Stale implementation-map rows**: none (the 5 weak bindings are all pre-existing adk4s entries — `GraphCompilationError`, `executeToolCalls`, `executeFromToolCalls`, `toolCalls`, `toolsNode` — where the identifier exists in a different file than the one cited. These are not related to this change and are not fixed here.)

**Unregistered actions / syncs / state components**: none. This change does not introduce new command/event enum variants, new message consumers/producers, or new persisted state components. The 16 subcommand entrypoints are existing stubs being populated; the 3-line shims are replacements for existing bash scripts. No new behavioral concepts are created — the migration-protocol concepts (Strangler migration, Conformance property-test, Exactly-one-implementation, Atomic skill-doc update) were registered by the archived `port-scanner-to-probatio` change.

## Concepts relevant to THIS change

The inventory entries this change will reuse (they feed the specs' "Concepts Used" tables). This change introduces NO new concepts — it wires existing stubs and swaps existing shims. The new concepts previewed in the proposal (`CliContext`, `StdoutRenderer`, `SubcommandWiring`, `ShimSwap`, `SwapOrder`, `OracleGreenGate`) will be added to the inventory during the apply phase (Step 12) when the specs that introduce them are implemented.

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` | reuse |
| `Subcommand` | enum (16 cases) | `org.sinemenda.probatio.cli` | reuse |
| `ProbatioMain` | object (dispatch) | `org.sinemenda.probatio.cli` | reuse |
| `MulticallDispatch` | object (resolve) | `org.sinemenda.probatio.cli` | reuse |
| `ExitCode` | enum (Clean, Finding, Undetermined) | `org.sinemenda.probatio.cli` | reuse |
| `HelpRegistry` / `HelpOutput` | object / case class | `org.sinemenda.probatio.cli` | reuse |
| `Validator` | object (validateFull — 15 clauses) | `org.sinemenda.probatio.core` | reuse |
| `Ledger` | object (read/append/validate) | `org.sinemenda.probatio.core` | reuse |
| `ContractViolation` | sealed trait (15 variants) | `org.sinemenda.probatio.core` | reuse |
| `LedgerRecord` / `LedgerRecordOptional` | case classes | `org.sinemenda.probatio.core` | reuse |
| `ChainState` / `ChainStateReport` | object / case class | `org.sinemenda.probatio.core` | reuse |
| `LintReport` / `CheckId` | case class / enum (F1–F10) | `org.sinemenda.probatio.core` | reuse |
| `BannerEngine` / `BannerInputs` / `BannerOutput` | object / case classes | `org.sinemenda.probatio.core` | reuse |
| `DriftScan` / `DriftScanResult` | object / case class | `org.sinemenda.probatio.core` | reuse |
| `GatePayload` / `HookSpecificOutput` | case classes | `org.sinemenda.probatio.core` | reuse |
| `PredecessorCheck` / `GrantWaiver` | objects (pure functions) | `org.sinemenda.probatio.core` | reuse |
| `GateDecision` / `BlockReason` / `SpecPhase` / `GateEvent` / `PresentationMarker` | sealed traits / enums | `org.sinemenda.probatio.core` | reuse |
| `SchemaPolicy` / `ResolvedValue` / `EnvResolution` / `StampClassification` / `DriftLine` | object / enums / case classes | `org.sinemenda.probatio.core` | reuse |
| `MetalsClient` / `LspMessage` / `MetalsError` / `MetalsSession` | object / case classes / sealed trait | `org.sinemenda.probatio.core` | reuse |
| `ShimGenerator` | object (generateShim) | `org.sinemenda.probatio.plugin` | reuse |
| `ConformanceModel` / `RecordModel` | object / case class | `org.sinemenda.probatio.verified` | reuse |
| `SeamConfiguration` / `MigrationState` / `ShimTarget` / `ShimResolution` / `SkillDocLintResult` / `ToolId` | case classes / enum (test-only) | `org.sinemenda.probatio.migration` | reuse |
| `ChecksumVerifier` / `ChecksumResult` | object / enum | `org.sinemenda.probatio.packaging` | reuse |
| `CliContext` | final case class | `org.sinemenda.probatio.cli` | introduce (apply phase) |
| `StdoutRenderer[A]` | typeclass | `org.sinemenda.probatio.cli` | introduce (apply phase) |
| `SubcommandWiring` | object | `org.sinemenda.probatio.cli` | introduce (apply phase) |
| `ShimSwap` | final case class | `org.sinemenda.probatio.migration` | introduce (apply phase) |
| `SwapOrder` | enum | `org.sinemenda.probatio.migration` | introduce (apply phase) |
| `OracleGreenGate` | object | `org.sinemenda.probatio.migration` | introduce (apply phase) |
