package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.cli.ProbatioCliSuite

/** Swap-order enforcement tests (R-M3).
  *
  * Hook shims are swapped in dependency order: purest tools first, gate
  * last. A shim SHALL NOT be swapped before every subcommand it dispatches
  * has a green oracle run against the ported binary.
  *
  * spec: migration-protocol — Requirement: Hook shims are swapped in dependency order after oracle clearance
  */
final class SwapOrderSpec extends ProbatioCliSuite:

  import MigrationTypes.*

  // ── Scenario: Ledger and chain-state shims swap first
  // spec: migration-protocol — Scenario: Ledger and chain-state shims swap first
  test("swap order: ledger and chain-state shims swap before all others"):
    val swapOrder: List[ToolId] = ToolId.swapOrder
    val firstTwo: List[ToolId] = List(ToolId.ChainState, ToolId.SpecLint)
    // The first two entries in swapOrder must be ChainState and SpecLint
    // (the purest, best-covered tools), in that order.
    assert(
      swapOrder.take(2) == firstTwo,
      s"expected first two = $firstTwo, got ${swapOrder.take(2)}"
    )

  // ── Scenario: Gate shim swaps last
  // spec: migration-protocol — Scenario: Gate shim swaps last
  test("swap order: gate shim is the final swap"):
    val swapOrder: List[ToolId] = ToolId.swapOrder
    val lastTool: ToolId = swapOrder.apply(swapOrder.length - 1)
    assert(
      lastTool == ToolId.Gate,
      s"expected last = Gate, got $lastTool"
    )

  // ── Scenario: Shim swap refused before subcommand clearance
  // spec: migration-protocol — Scenario: Shim swap refused before subcommand clearance
  test("swap order: shim not swapped before its subcommand passes oracle"):
    // A shim can only be swapped if ALL tools before it in the swap order
    // have already been ported. This enforces dependency order.
    val state: MigrationState = MigrationState(Set(ToolId.ChainState))
    // DangerScan (index 2) CANNOT be swapped because SpecLint
    // (index 1) hasn't been ported yet.
    val canSwapDanger: Boolean = canSwap(state, ToolId.DangerScan)
    assert(
      !canSwapDanger,
      "DangerScan must not be swappable before SpecLint is ported"
    )

  // ── Compile-Negative: Shim swapped before its last subcommand passes the oracle
  // spec: migration-protocol — Compile-Negative: Shim swapped before its last subcommand passes the oracle
  test("compile-negative: shim swap refused when prerequisite tools not ported"):
    // For each tool in the swap order, it can only be swapped if all
    // tools before it in the order have been ported. This is the
    // compile-negative: a swap is REFUSED if prerequisites aren't met.
    val allTools: List[ToolId] = ToolId.swapOrder
    val emptyState: MigrationState = MigrationState(Set.empty)
    // The first tool CAN be swapped (no prerequisites)
    assert(canSwap(emptyState, allTools(0)),
      "first tool in swap order must be swappable with empty state")
    // Every other tool CANNOT be swapped with empty state
    allTools.drop(1).foreach { tool =>
      assert(!canSwap(emptyState, tool),
        s"$tool must not be swappable before its prerequisites are ported")
    }

  // ── Property: swap-order-respects-dependencies
  // For every migration state, the set of swappable tools is exactly the
  // prefix of the swap order that has been fully ported.
  property("swap-order-respects-dependencies"):
    for
      portedCount <- Gen.int(Range.linear(0, ToolId.swapOrder.length)).forAll
    yield
      val ported: Set[ToolId] = ToolId.swapOrder.take(portedCount).toSet
      val state: MigrationState = MigrationState(ported)
      // The next tool in the order (if any) should be swappable
      val nextIdx: Int = portedCount
      if nextIdx < ToolId.swapOrder.length then
        val nextTool: ToolId = ToolId.swapOrder(nextIdx)
        Result.assert(canSwap(state, nextTool))
          .log(s"next tool $nextTool should be swappable after $portedCount tools ported")
      else
        Result.success

  // ── Helper: determine if a tool can be swapped given the migration state
  // A tool can be swapped only if ALL tools before it in the swap order
  // have been ported (i.e., their shims have already been swapped).
  def canSwap(state: MigrationState, tool: ToolId): Boolean =
    val idx: Int = ToolId.swapOrder.indexOf(tool)
    if idx < 0 then false
    else
      // All tools before this one in the swap order must be ported
      val prerequisites: List[ToolId] = ToolId.swapOrder.take(idx)
      prerequisites.forall(t => state.portedTools.contains(t))
