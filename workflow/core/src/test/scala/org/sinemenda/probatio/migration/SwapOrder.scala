package org.sinemenda.probatio.migration

/** The R-M3 dependency order for shim swaps.
  *
  * The cutover replaces each predecessor bash hook with a 3-line exec shim
  * pointing to the probatio binary. Swaps proceed in dependency order —
  * the gate is always last — and each swap is gated by the oracle-green
  * check.
  *
  * `LedgerFirst` is the first swap: the ledger is the purest, best-covered
  * tool. `GateLast` is the final swap: the gate depends on every other
  * subcommand being verified. The gate is the only blocking hook — swapping
  * it before its dependencies are verified would leave the gate running on
  * unverified subcommands, creating a window where the gate could silently
  * allow blocked edits.
  *
  * The enum has no `GateFirst` case — a swap order with the gate first is
  * a defect, and the compile-negative test proves it is unconstructible.
  *
  * spec: hook-cutover — Requirement: Shims are swapped in dependency order — gate last
  * spec: hook-cutover — Property: swap-order-respects-dependencies
  * spec: hook-cutover — Compile-Negative: SwapOrder variant with Gate not last
  */
enum SwapOrder:
  case LedgerFirst
  case ChainState
  case SpecLint
  case DangerScan
  case Reconcile
  case GateLast

object SwapOrder:
  /** The full swap order as a list, from first to last. */
  val swapOrder: List[SwapOrder] = List(
    SwapOrder.LedgerFirst,
    SwapOrder.ChainState,
    SwapOrder.SpecLint,
    SwapOrder.DangerScan,
    SwapOrder.Reconcile,
    SwapOrder.GateLast
  )

  /** True iff the given swap order is the last in the sequence (GateLast). */
  def isLast(order: SwapOrder): Boolean = order == SwapOrder.GateLast

  /** The index of a swap order in the sequence (0-based). */
  def indexOf(order: SwapOrder): Int = swapOrder.indexOf(order)
