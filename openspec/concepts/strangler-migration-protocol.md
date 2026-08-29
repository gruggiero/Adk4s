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
  The swap proceeds only if the oracle is green (zero failures).
- **Abort**: if the oracle regresses, the swap is aborted and the tool
  remains on the predecessor. The abort is recorded in the migration state.
- **Skill-doc update**: update skill documents atomically with the shim
  swap — the skill doc and the shim swap land in the same commit.

## Operational Principle

A swap without oracle clearance is an unverified change to a blocking
hook. The oracle-green gate is the strangler migration's acceptance
criterion — the gate must return true before any swap proceeds. The
dependency order prevents a partial cutover where the gate is on the
binary but its subcommands are not yet verified.

## Synchronizations

- The `OracleGreenGate.apply(tool, seamConfig)` function gates each swap
- The `SwapOrder` enum encodes the binding dependency order
- The `ShimSwap` case class records each swap event as an immutable audit
  trail entry
- The `SkillDocLintCheck` detects stale skill-doc references after a swap
