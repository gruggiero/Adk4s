# Spec: Migration Protocol

<!-- Delta spec for the complete-probatio-porting change. Restates R-M1–R-M5
     as the active acceptance protocol for the strangler migration from bash
     tooling to the probatio binary. These requirements were originally
     introduced by the archived port-scanner-to-probatio change; this delta
     re-establishes them as the active protocol for the remaining work
     (Stage 2 wiring + Stage 3 cutover) and adds the oracle-green gate as
     a mandatory checkpoint between stages. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol (NEW — created by this spec) | This spec IS the strangler migration protocol — it restates the acceptance criteria (R-M1–R-M5) that govern the incremental port from bash to binary | `openspec/concepts/strangler-migration-protocol.md` |
| Conformance property-test contract (NEW — created by this spec) | R-M2 (conformance property tests) is the bidirectional equivalence between a ported validator and an executable contract — this spec restates it as an active requirement | `openspec/concepts/conformance-property-test-contract.md` |

This spec does not alter any concept's actions, state, or synchronizations.
The strangler migration protocol and conformance property-test contract are
behavioral contracts reused as-is. This delta re-establishes their
acceptance criteria as active requirements for the remaining work.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `SeamConfiguration` | final case class (test-only) | `org.sinemenda.probatio.migration` |
| `MigrationState` | final case class (test-only) | `org.sinemenda.probatio.migration` |
| `ToolId` | enum (SpecLint, ChainState, DangerScan, Reconcile, Gate) | `org.sinemenda.probatio.migration` |
| `OracleGreenCheck` | object (runOracle) | `org.sinemenda.probatio.migration` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `OracleGreenGate` | object | The gating function that blocks a stage transition until the bats oracle is green with the ported tools substituted at their seams. `apply(stage, seamConfig): Boolean` — returns true only when every bats test passes under the given seam configuration. This is the mandatory checkpoint between Stage 2 (wiring) and Stage 3 (cutover), and between each swap within Stage 3. |

## ADDED Requirements

### Requirement: The bats oracle is the porting acceptance suite

The bats oracle (17 `.bats` files under `openspec/schemas/verified-scala3/tests/`) SHALL be the acceptance suite for the strangler migration. A wiring step or shim swap is complete only when the oracle is green — every bats test passes — with the ported tool substituted at its `*_OVERRIDE` seam. The oracle MUST pass unmodified: no test may be added, removed, or altered to accommodate a porting defect. A test that fails after substitution is a regression, not a test bug.

**Given** a ported subcommand wired in the CLI entrypoint module and the corresponding `*_OVERRIDE` env var set to invoke the probatio binary
**When** the bats oracle runs
**Then** every test in every `.bats` file passes, and no `.bats` file has been modified from its predecessor-tracked version

**Rationale**: The oracle was written from the specs before the bash implementations existed — it is an independent witness, not a mirror of the implementation. Modifying it to accommodate a porting defect would destroy its independence and make the migration self-certifying. This is R-M1 from the archived `port-scanner-to-probatio` change, re-established as active.

#### Scenario: The oracle passes with all tools on the predecessor

**Given** no `*_OVERRIDE` env vars set (all tools on the predecessor bash scripts)
**When** the bats oracle runs
**Then** every test passes (baseline — the oracle is green before the migration begins)

#### Scenario: The oracle passes with one tool ported

**Given** `CHAIN_STATE_OVERRIDE` set to invoke the probatio binary's `chain-state` subcommand and all other tools on the predecessor
**When** the bats oracle runs
**Then** every test passes (the ported chain-state produces the same outcomes as the predecessor)

#### Scenario: A regression is a porting defect, not a test bug

**Given** `SPEC_LINT_OVERRIDE` set to invoke the probatio binary's `spec-lint` subcommand and one bats test fails
**When** the regression is investigated
**Then** the failure is attributed to the ported spec-lint, not to the bats test, and the swap is aborted

### Requirement: Conformance property tests verify bidirectional equivalence

Each ported validator (ledger record, chain-state report, gate hook-json payload) SHALL have a conformance property test that proves bidirectional equivalence with the executable `.jq` contract: every input the ported validator accepts, the contract also accepts; every input the ported validator rejects, the contract also rejects. The property test SHALL use a constructive generator covering each clause independently, augmented with the existing fixture corpus. The property test SHALL run as a Hedgehog property in the `probatio-cli` test suite.

**Given** a ported ledger validator and the `ledger-record-contract.jq` executable contract
**When** the conformance property test runs with a generated corpus of records satisfying and violating each of the 15 clauses
**Then** the ported validator and the contract agree on every record (both accept or both reject)

**Rationale**: A ported validator that accepts records the contract rejects (or vice versa) is a silent drift from the approved format. The bidirectional property catches both directions of drift. This is R-M2 from the archived `port-scanner-to-probatio` change, re-established as active.

#### Scenario: The ledger conformance property passes

**Given** the `LedgerCmdConformanceSpec` Hedgehog property and the `ledger-record-contract.jq` contract
**When** the property runs with `genLedgerRecord` (constructive, 15-clause coverage)
**Then** the property holds for all generated records

#### Scenario: The chain-state conformance property passes

**Given** the `ChainStateCmdConformanceSpec` Hedgehog property and the `chain-state-report-contract.jq` contract
**When** the property runs with `genChangeState` (constructive, varying discharge states)
**Then** the property holds for all generated change states

### Requirement: Exactly one implementation is active at each seam

At each `*_OVERRIDE` seam, exactly one implementation SHALL be active: either the predecessor bash script or the probatio binary. A configuration where both are active (e.g. the shim points to the binary but the predecessor script is still invoked directly) is a dual-implementation defect. The seam configuration SHALL be recorded in `MigrationState` and verified by the `OracleGreenCheck` before each swap.

**Given** a seam configuration where ChainState is ported and SpecLint is on the predecessor
**When** the migration state is inspected
**Then** the migration state record shows exactly one implementation per seam, and no seam has both implementations active

**Rationale**: A dual-implementation configuration is a race condition — two tools with different behaviors competing for the same seam. This is R-M4 from the archived `port-scanner-to-probatio` change, re-established as active.

#### Scenario: A single-tool ported configuration is well-formed

**Given** `SeamConfiguration(Set(ToolId.ChainState))`
**When** the migration state is inspected
**Then** ChainState is ported and all other tools are on the predecessor — exactly one implementation per seam

#### Scenario: A dual-implementation configuration is rejected

**Given** a seam where both the predecessor script and the binary are invoked (e.g. the shim points to the binary but the `*_OVERRIDE` env var also points to the predecessor)
**When** the migration state is inspected
**Then** the configuration is rejected as a dual-implementation defect

### Requirement: The oracle-green gate is a mandatory checkpoint between stages

The migration SHALL NOT proceed from Stage 2 (wiring) to Stage 3 (cutover) until the `OracleGreenGate` returns true — the bats oracle is green with every ported subcommand substituted at its seam. The migration SHALL NOT proceed from one swap to the next within Stage 3 until the `OracleGreenGate` returns true for the current swap. A stage transition without oracle clearance is an unverified change to a blocking hook.

**Given** Stage 2 is complete (all 16 subcommands wired) and the bats oracle is green with every `*_OVERRIDE` set
**When** the `OracleGreenGate` is evaluated
**Then** it returns true and Stage 3 (cutover) may begin

**Rationale**: The oracle-green gate is the strangler migration's acceptance criterion. A stage transition without it is an unverified change — the gate could silently allow blocked edits after cutover. This is R-M3 from the archived `port-scanner-to-probatio` change, re-established as active with the added requirement of a mandatory checkpoint between stages.

#### Scenario: Stage 2 to Stage 3 transition is gated

**Given** Stage 2 is complete but one bats test fails with `SPEC_LINT_OVERRIDE` set
**When** the `OracleGreenGate` is evaluated
**Then** it returns false and Stage 3 does not begin

#### Scenario: Stage 3 swap-to-swap transition is gated

**Given** the ChainState shim has been swapped and the bats oracle is green
**When** the `OracleGreenGate` is evaluated for the SpecLint swap
**Then** it returns true and the SpecLint swap may proceed

### Requirement: A recorded limitation is re-established before it is relied upon

The R-M1–R-M5 requirements were originally established by the archived `port-scanner-to-probatio` change (2026-08-25). This delta re-establishes them as active requirements for the `complete-probatio-porting` change. A later change that relies on the migration protocol MUST re-test these requirements rather than inheriting them — the oracle must be re-run, the conformance properties must be re-verified, and the seam configuration must be re-inspected.

**Given** the `complete-probatio-porting` change relies on the migration protocol established by the archived `port-scanner-to-probatio` change
**When** the change is implemented
**Then** R-M1–R-M5 are re-tested in this session: the oracle is re-run, the conformance properties are re-verified, and the seam configuration is re-inspected

**Rationale**: The schema invariant states "a recorded limitation is re-established before it is relied upon." The migration protocol is a recorded limitation — it carries the date (2026-08-25) and the mechanism (bats oracle + conformance properties) by which it was established. This change re-tests it rather than inheriting it. This is R-M5 from the archived `port-scanner-to-probatio` change, re-established as active.

#### Scenario: The oracle is re-run in this session

**Given** the `complete-probatio-porting` change is in the apply phase
**When** the migration protocol is relied upon
**Then** the bats oracle is re-run with the ported tools substituted and the result is recorded in the implementation-progress artifact

#### Scenario: The conformance properties are re-verified in this session

**Given** the `complete-probatio-porting` change is in the apply phase
**When** the migration protocol is relied upon
**Then** the Hedgehog conformance property tests are re-run and the result is recorded in the implementation-progress artifact

## Properties (Ring 3)

### Property: oracle-green-at-every-step

**Invariant**: For every prefix of the swap order, the bats oracle is green when the ported tools are substituted at their seams and the remaining tools are on the predecessor. A swap that would cause the oracle to regress is aborted.

**Generator strategy**: `genSeamConfiguration` — constructive over subsets of `ToolId.swapOrder` that are always prefixes (e.g. {ChainState}, {ChainState, SpecLint}, etc.). Edge cases: empty set (all predecessor), full set (all ported), single-tool prefix.

```
property("oracle green at every step") {
  for {
    config <- genSeamConfiguration.forAll
    oracleResult = OracleGreenCheck.runOracle(config)
  } yield {
    Result.assert(oracleResult.isGreen || oracleResult.isAborted)
  }
}
```

### Property: exactly-one-implementation-per-seam

**Invariant**: For every seam configuration in `MigrationState`, exactly one implementation is active per seam — no seam has both the predecessor and the ported tool active.

**Generator strategy**: `genSeamConfiguration` — constructive over all valid seam configurations (subsets of `ToolId.swapOrder`). Edge cases: empty set, full set, single-tool.

```
property("exactly one implementation per seam") {
  for {
    config <- genSeamConfiguration.forAll
    state = MigrationState.fromConfig(config)
  } yield {
    Result.assert(state.seams.forall(_.implementationCount == 1))
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `MigrationState` with a seam having `implementationCount > 1` | A dual-implementation seam is a race condition | `assertDoesNotCompile("MigrationState(seams = Seam(both = true))")` — the type has no `both` field |

## Formal Contracts (Ring 6)

No formal contracts. The migration protocol is an operational procedure (oracle-gated stage transitions), not an algorithm. The `OracleGreenCheck.runOracle` function is a test harness, not a pure kernel.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The oracle passes with all tools on the predecessor | Requirement: The bats oracle is the porting acceptance suite + Scenario: The oracle passes with all tools on the predecessor | bats oracle (baseline run, all `*_OVERRIDE` unset) | `correctness-invariant.bats` |
| The oracle passes with one tool ported | Requirement: The bats oracle is the porting acceptance suite + Scenario: The oracle passes with one tool ported | bats oracle with `*_OVERRIDE` set per tool | `chain-state.bats` |
| A regression is a porting defect | Requirement: The bats oracle is the porting acceptance suite + Scenario: A regression is a porting defect, not a test bug | manual review (regression attribution) | `correctness-invariant.bats` |
| Ledger conformance property passes | Requirement: Conformance property tests verify bidirectional equivalence + Scenario: The ledger conformance property passes | Hedgehog property test (LedgerCmdConformanceSpec, to be written in apply phase) | `evidence-ledger.bats` |
| Chain-state conformance property passes | Requirement: Conformance property tests verify bidirectional equivalence + Scenario: The chain-state conformance property passes | Hedgehog property test (ChainStateCmdConformanceSpec, to be written in apply phase) | `chain-state.bats` |
| A single-tool ported configuration is well-formed | Requirement: Exactly one implementation is active at each seam + Scenario: A single-tool ported configuration is well-formed | Hedgehog exactly-one-implementation-per-seam property (MigrationStateSpec, to be written in apply phase) | `chain-state.bats` |
| A dual-implementation configuration is rejected | Requirement: Exactly one implementation is active at each seam + Scenario: A dual-implementation configuration is rejected | Hedgehog exactly-one-implementation-per-seam property + compile-negative | `chain-state.bats` |
| Stage 2 to Stage 3 transition is gated | Requirement: The oracle-green gate is a mandatory checkpoint between stages + Scenario: Stage 2 to Stage 3 transition is gated | Hedgehog oracle-green-at-every-step property (OracleGreenSpec, to be written in apply phase) | `correctness-invariant.bats` |
| Stage 3 swap-to-swap transition is gated | Requirement: The oracle-green gate is a mandatory checkpoint between stages + Scenario: Stage 3 swap-to-swap transition is gated | Hedgehog oracle-green-at-every-step property | `correctness-invariant.bats` |
| The oracle is re-run in this session | Requirement: A recorded limitation is re-established before it is relied upon + Scenario: The oracle is re-run in this session | manual review (implementation-progress artifact records the re-run) | `correctness-invariant.bats` |
| The conformance properties are re-verified in this session | Requirement: A recorded limitation is re-established before it is relied upon + Scenario: The conformance properties are re-verified in this session | manual review (implementation-progress artifact records the re-verification) | `evidence-ledger.bats` |
| oracle-green-at-every-step | Property: oracle-green-at-every-step | Hedgehog property test (OracleGreenSpec, to be written in apply phase) | `correctness-invariant.bats` |
| exactly-one-implementation-per-seam | Property: exactly-one-implementation-per-seam | Hedgehog property test (MigrationStateSpec, to be written in apply phase) | `chain-state.bats` |
| No MigrationState with dual-implementation seam | Compile-Negative: MigrationState with a seam having implementationCount > 1 | compile-negative test (assertDoesNotCompile) | `chain-state.bats` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `OracleGreenCheck.scala` | object | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/` | Already shipped — runs the bats oracle under a seam configuration |
| `SeamTypes.scala` | object (ToolId, SeamConfiguration, MigrationState) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/` | Already shipped — defines the swap order and seam state |
| `OracleGreenGate` | object (new) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/` | New — the stage-transition gate function |
| bats oracle | test suite (17 files) | `openspec/schemas/verified-scala3/tests/` | The acceptance suite — MUST pass unmodified |
| `.jq` contracts | executable contracts | `openspec/schemas/verified-scala3/scanner/` | `ledger-record-contract.jq`, `chain-state-report-contract.jq`, `gate-hookjson-contract.jq` — the bidirectional equivalence oracles |
