# Concept: Conformance Property-Test Contract

## Purpose

The conformance property-test contract is the oracle-green check at each
`*_OVERRIDE` seam — the conformance property test that gates each shim
swap. A swap proceeds only when the oracle is green with the ported tool
substituted at its seam.

## State

- `SeamConfiguration(portedTools: Set[ToolId])` — which tools are on the
  ported implementation
- `OracleOutcome(passed, failed, skipped)` — the result of running the
  bats oracle under a seam configuration

## Actions

- **Run oracle**: execute all 17 bats files under a seam configuration,
  with override env vars pointing ported tools to the probatio binary.
  Parse TAP output to count passed/failed/skipped.
- **Check green**: the oracle is green when `failed == 0`. A non-green
  oracle means the ported tool's behavior at the seam differs from the
  predecessor's — a regression.

## Operational Principle

The oracle is the fixed reference. The ported tool's behavior at the seam
must not change any test's outcome. Conformance holds when the oracle is
green with the ported tool substituted — the ported tool is a drop-in
replacement for the predecessor at that seam.

## Synchronizations

- The `OracleGreenCheck.runOracle(config)` function runs the bats oracle
  under a seam configuration
- The `OracleGreenGate.apply(tool, seamConfig)` function gates each swap
  on the oracle-green check
- The `OracleGreenGate.apply(stage, seamConfig)` function gates stage
  transitions (Wiring → Cutover) on the oracle-green check
