package org.sinemenda.probatio.migration

/**
 * Records one shim swap event: the tool being swapped, the predecessor
 * path, the shim path, the binary path, the oracle result that gated it,
 * and the timestamp. Immutable audit trail entry.
 *
 * Each field is set at swap time and never mutated. The `oracleGreen`
 * field records whether the bats oracle was green (zero failures) when
 * this swap was gated — a swap with `oracleGreen = false` is a defect
 * (the swap should have been aborted).
 *
 * spec: hook-cutover — Requirement: Each bash hook is replaced by a 3-line exec shim pointing to the probatio binary
 * spec: hook-cutover — Requirement: Shims are swapped in dependency order — gate last
 */
final case class ShimSwap(
  tool: SeamTypes.ToolId,
  predecessorPath: String,
  shimPath: String,
  binaryPath: String,
  oracleGreen: Boolean,
  timestamp: String
)
