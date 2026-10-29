package org.sinemenda.probatio.core

/**
 * The gate's decision: `Allow` or `Block(reason)` (spec 9).
 *
 * A pure function of (event, changed files, prior spec states, grant
 * tokens, oracle state). Maps to `Outcome[Int]` at the CLI boundary —
 * `Allow` → exit 0, `Block` → exit 1 with the reason in the payload.
 *
 * `Block` requires a `BlockReason` — a `Block` without a reason is
 * unrepresentable (the case class constructor requires the field).
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: GateDecision
 * spec: gate-checkpoint-lock — Compile-Negative: Block requires a BlockReason
 */
enum GateDecision:
  case Allow
  case Block(reason: BlockReason)
