package org.sinemenda.probatio.migration

/**
 * Records one shim swap event: the tool being swapped, the predecessor
 * path, the shim path, the binary path, the comparison the swap was
 * gated on, and the timestamp. Immutable audit trail entry.
 *
 * Each field is set at swap time and never mutated. The `comparison`
 * field is REQUIRED (spec 8): a `ShimSwap` cannot be constructed
 * without the gate record — verdict plus the full seam-scoped
 * comparison evidence — it rested on. A swap whose justification is
 * detached from its comparison is unconstructible.
 *
 * `oracleGreen` is derived from the comparison: a swap is
 * oracle-green iff the gate's verdict is Proceed AND the comparison
 * has evidence (`GateRecord.authorisesSwap`). A record carrying a
 * refusal records an aborted swap — the audit trail the spec
 * requires.
 *
 * spec: hook-cutover — Requirement: Each bash hook is replaced by a 3-line exec shim pointing to the probatio binary
 * spec: hook-cutover — Requirement: Shims are swapped in dependency order — gate last
 * spec: ledger-checkpoint-cutover — Scenario: The swap records the comparison it rested on
 */
final case class ShimSwap(
  tool: SeamTypes.ToolId,
  predecessorPath: String,
  shimPath: String,
  binaryPath: String,
  comparison: GateRecord,
  timestamp: String
):
  /** Oracle-green iff the gating comparison authorises the swap. */
  def oracleGreen: Boolean = comparison.authorisesSwap
