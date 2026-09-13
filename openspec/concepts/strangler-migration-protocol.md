# Concept: Strangler Migration Protocol

## Purpose

The strangler migration protocol governs the incremental replacement of
predecessor bash hook scripts with ported Scala subcommands. Each tool is
swapped independently, gated by the bats oracle: a swap proceeds only when
the oracle is green with the ported tool substituted at its `*_OVERRIDE`
seam. The gate is always swapped last because it is the only blocking hook.

## State

- `MigrationState(portedTools: Set[ToolId])` — which tools have been ported
- `SeamConfiguration(portedTools: Set[ToolId])` — which tools are on the
  ported implementation (the rest are on the predecessor)
- `SwapOrder` — the binding dependency order (LedgerFirst → ChainState →
  SpecLint → DangerScan → Reconcile → GateLast)

## Actions

- **Swap**: replace a predecessor bash hook with a 3-line exec shim
  pointing to the probatio binary. The predecessor is backed up to
  `.predecessor.bak` for one swap cycle.
- **Gate**: run the bats oracle with the tool substituted at its seam.
  The gate proceeds only when no file fails more tests under the ported
  implementation than under the predecessor, measured in the same
  repository under the same suite. The decision is a per-file
  comparison, not an absolute zero-failure threshold.
- **Abort**: if the gate decides to revert, the seams it had swapped
  are restored to their predecessor implementations. The restoration
  is verified by re-running the comparison. The abort is an executable
  decision: `CutoverGate.decide` returns `CutoverVerdict.Revert(evidence)`
  carrying the `DifferentialResult` that produced the refusal, and the
  revert restores every swapped seam to its predecessor implementation.
- **Skill-doc update**: update skill documents atomically with the shim
  swap — the skill doc and the shim swap land in the same commit.

## Operational Principle

A swap without oracle clearance is an unverified change to a blocking
hook. The cutover gate is the strangler migration's acceptance criterion
— the gate must decide `Proceed` before any swap proceeds. The gate
decides by comparing the ported implementation's per-file failure counts
against the predecessor's under the same repository and the same suite.
A per-file regression blocks even when the overall total improves. The
dependency order prevents a partial cutover where the gate is on the
binary but its subcommands are not yet verified.

## Synchronizations

- The `CutoverGate.decide(differential)` function decides whether to
  proceed or revert based on the per-file comparison
- The `DifferentialHarness` object materialises two seam-configured
  scanner trees, runs the suite against each, and emits the
  `DifferentialResult` the gate decides on
- The `OracleGreenGate.apply(tool, seamConfig)` function gates each swap
  on the cutover gate's decision
- The `SwapOrder` enum encodes the binding dependency order
- The `ShimSwap` case class records each swap event as an immutable audit
  trail entry
- The `SkillDocLintCheck` detects stale skill-doc references after a swap
