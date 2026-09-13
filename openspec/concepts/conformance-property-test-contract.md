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
- **Check green**: the oracle is green when no file fails more tests
  under the ported implementation than under the predecessor, measured
  in the same repository under the same suite. The predicate is a
  per-file comparison against the predecessor control, not an absolute
  zero-failure threshold. The predecessor itself fails 20 of 282 tests
  (measured 2026-08-29), so an absolute zero-failure predicate is
  unsatisfiable by any implementation including the one it certifies.
  A comparison-based predicate is both satisfiable and strictly
  stronger than "no new failures overall", because it forbids trading
  a regression in one file for an improvement in another.

## Operational Principle

The oracle is the fixed reference. The ported tool's behavior at the seam
must not change any test's outcome. Conformance holds when no file fails
more tests under the ported implementation than under the predecessor —
the ported tool is no worse than a drop-in replacement for the predecessor
at that seam. The comparison is per-file, not total: a regression in one
file cannot be excused by an improvement in another.

## Synchronizations

- The `DifferentialHarness.runSuite(config, oracleDir, binaryPath)`
  function runs the bats oracle under a seam configuration and returns
  per-file results
- The `DifferentialHarness.diff(predecessor, ported, repository)`
  function computes the `DifferentialResult` from two suite runs
- The `CutoverGate.decide(differential)` function decides whether to
  proceed or revert based on the per-file comparison
- The `OracleGreenGate.apply(stage, seamConfig)` function gates stage
  transitions on the cutover gate's decision
- The `OracleGreenGate.apply(tool, seamConfig)` function gates each swap
  on the cutover gate's decision
