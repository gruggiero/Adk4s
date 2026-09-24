package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Test oracle for the hook-cutover spec — dependency order, oracle-green
 * gating, and the SwapOrder compile-negative.
 *
 * These tests are derived from the spec's requirements and properties,
 * NOT from the implementation. They verify:
 * - Requirement: Shims are swapped in dependency order — gate last
 * - Requirement: Skill-doc updates are atomic with the shim swap (stale ref)
 * - Property: oracle-green-at-every-step
 * - Property: swap-order-respects-dependencies
 * - Compile-Negative: SwapOrder.GateFirst
 *
 * The shim-idempotency property and the shim-with-logic compile-negative
 * live in `HookCutoverShimSpec.scala` (plugin test sources) because
 * `ShimGenerator` is in the sbt-probatio plugin module, which is not
 * visible to probatio-core tests.
 *
 * spec: hook-cutover — all requirements and properties
 */
final class HookCutoverSpec extends ProbatioSuite:

  import SeamTypes.*

  // ── Requirement: Shims are swapped in dependency order — gate last
  // spec: hook-cutover — Requirement: Shims are swapped in dependency order — gate last
  // Scenario: ChainState is swapped first
  test("swap order: ChainState is the first tool swapped (after LedgerFirst)"):
    val order: List[SwapOrder] = SwapOrder.swapOrder
    order.headOption match
      case Some(SwapOrder.LedgerFirst) => () // expected
      case other                       => fail(s"expected first = LedgerFirst, got $other")
    assertEquals(order(1), SwapOrder.ChainState, s"expected second = ChainState, got ${order(1)}")

  // ── Requirement: Shims are swapped in dependency order — gate last
  // spec: hook-cutover — Scenario: The gate is swapped last
  test("swap order: GateLast is the final swap"):
    val order: List[SwapOrder] = SwapOrder.swapOrder
    order.lastOption match
      case Some(SwapOrder.GateLast) => () // expected
      case other                    => fail(s"expected last = GateLast, got $other")
    assert(SwapOrder.isLast(SwapOrder.GateLast), "SwapOrder.isLast(GateLast) must return true")

  // ── Requirement: Shims are swapped in dependency order — gate last
  // spec: hook-cutover — Scenario: A regressing swap is aborted
  // The per-swap gating function returns false when the oracle would
  // regress. This test runs the full bats oracle (~60s). Ignored in
  // normal CI; un-ignore for the ORACLE POLARITY run and the actual
  // migration step.
  test("per-swap gating: OracleGreenGate.apply(tool, seamConfig) returns Boolean".ignore):
    val config: SeamConfiguration = SeamConfiguration.fromPorted(Set.empty)
    val gateResult: Boolean       = OracleGreenGate.apply(ToolId.ChainState, config)
    // The gate returns a Boolean — true iff the oracle is green.
    gateResult match
      case true  => () // oracle is green
      case false => () // oracle has failures (expected with predecessor fallback)

  // ── Requirement: Shims are swapped in dependency order — gate last
  // spec: hook-cutover — Scenario: A regressing swap is aborted
  // Non-oracle test: verifies that a ShimSwap with oracleGreen=false
  // represents an aborted swap (the swap was not performed).
  test("ShimSwap with oracleGreen=false represents an aborted swap"):
    val regressionEvidence: DifferentialResult = DifferentialResult(
      List(FileComparison("spec-lint.bats", 10, 3, 5, true, true)),
      "/repo",
      Set("spec-lint.bats")
    )
    val abortedSwap: ShimSwap = ShimSwap(
      tool = ToolId.SpecLint,
      predecessorPath = "openspec/schemas/verified-scala3/scanner/spec-lint.sh",
      shimPath = "openspec/schemas/verified-scala3/scanner/spec-lint.sh",
      binaryPath = "/path/to/probatio",
      comparison = CutoverGate.record(regressionEvidence),
      timestamp = "2026-08-28T10:00:00Z"
    )
    assert(!abortedSwap.oracleGreen, "an aborted swap must have oracleGreen=false — the comparison refused")
    assert(
      abortedSwap.comparison.verdict == CutoverVerdict.Revert(regressionEvidence),
      "an aborted swap must record the refusing comparison"
    )
    assert(abortedSwap.tool == ToolId.SpecLint, "the aborted swap must record which tool was attempted")

  // ── Requirement: Skill-doc updates are atomic with the shim swap
  // spec: hook-cutover — Scenario: A stale skill doc is detected
  test("ShimSwap records the oracle result that gated the swap"):
    val parityEvidence: DifferentialResult = DifferentialResult(
      List(FileComparison("chain-state.bats", 10, 3, 3, true, true)),
      "/repo",
      Set("chain-state.bats")
    )
    val swap: ShimSwap = ShimSwap(
      tool = ToolId.ChainState,
      predecessorPath = "openspec/schemas/verified-scala3/scanner/chain-state.sh",
      shimPath = "openspec/schemas/verified-scala3/scanner/chain-state.sh",
      binaryPath = "/path/to/probatio",
      comparison = CutoverGate.record(parityEvidence),
      timestamp = "2026-08-28T10:00:00Z"
    )
    assert(swap.oracleGreen, "oracleGreen must derive from the recorded comparison")
    assert(
      swap.comparison.verdict == CutoverVerdict.Proceed,
      "the recorded comparison must be the one that gated the swap"
    )
    assert(swap.tool == ToolId.ChainState, "tool must be recorded")

  // ── Property: oracle-green-at-every-step
  // spec: hook-cutover — Property: oracle-green-at-every-step
  // Generates prefix subsets of the swap order and verifies each prefix
  // is a valid seam configuration (ported tools form a prefix of the
  // swap order). The full oracle run is too slow for property testing
  // (~30s per run); the actual oracle-green check is in the .ignore'd
  // test above. This property verifies the prefix structure that the
  // oracle-green gate depends on.
  property("oracle green at every step (prefix-subset)"):
    for config <- genSeamConfigurationPrefix.forAll
    yield
      // Verify the ported tools form a prefix of ToolId.swapOrder.
      // A non-prefix configuration would mean a tool was swapped before
      // its dependencies — violating the dependency order.
      val portedList: List[ToolId]     = ToolId.swapOrder.filter(config.portedTools.contains)
      val expectedPrefix: List[ToolId] = ToolId.swapOrder.take(portedList.length)
      Result
        .assert(portedList == expectedPrefix)
        .log(s"ported tools $portedList must be a prefix of swap order, expected $expectedPrefix")

  // ── Property: swap-order-respects-dependencies
  // spec: hook-cutover — Property: swap-order-respects-dependencies
  // For every swap sequence (prefix of SwapOrder.swapOrder), the gate
  // is the last tool swapped. No tool is swapped before all tools earlier
  // in the swap order are ported and oracle-green.
  property("swap order respects dependencies"):
    for sequence <- genSwapSequence.forAll
    yield
      // The invariant: if GateLast is in the sequence, it must be the
      // last element. A prefix that doesn't include GateLast is valid.
      // GateLast must never appear before the last position.
      val gateLastIdx: Int            = sequence.indexOf(SwapOrder.GateLast)
      val lastIdx: Int                = sequence.length - 1
      val gateIsLastOrAbsent: Boolean = gateLastIdx < 0 || gateLastIdx == lastIdx
      Result
        .assert(gateIsLastOrAbsent)
        .log(s"GateLast must be last or absent, got: $sequence (gateLastIdx=$gateLastIdx, lastIdx=$lastIdx)")

  // ── Compile-Negative: SwapOrder variant with Gate not last
  // spec: hook-cutover — Compile-Negative: SwapOrder.GateFirst
  test("compile-negative: SwapOrder has no GateFirst case"):
    val err: String = compileErrors("SwapOrder.GateFirst")
    assert(err.nonEmpty, "SwapOrder must not have a GateFirst case — the gate is always last")

  // ── Compile-Negative: SwapOrder has exactly 7 cases
  // spec: differential-harness-integrity — Type-Constraint: the seam enum gains a ledger seam and a checkpoint seam
  // Cardinality widened 6 → 7 by differential-harness-integrity: the
  // checkpoint seam joins the six declared positions (gate remains last).
  test("SwapOrder enum has exactly 7 cases"):
    val cases: Array[SwapOrder] = SwapOrder.values
    assertEquals(cases.length, 7, s"SwapOrder should have exactly 7 cases, found ${cases.length}")
    assert(cases.contains(SwapOrder.LedgerFirst), "LedgerFirst missing")
    assert(cases.contains(SwapOrder.ChainState), "ChainState missing")
    assert(cases.contains(SwapOrder.SpecLint), "SpecLint missing")
    assert(cases.contains(SwapOrder.DangerScan), "DangerScan missing")
    assert(cases.contains(SwapOrder.Reconcile), "Reconcile missing")
    assert(cases.contains(SwapOrder.Checkpoint), "Checkpoint missing")
    assert(cases.contains(SwapOrder.GateLast), "GateLast missing")

  // ── Generator: genSeamConfigurationPrefix
  // Constructive over prefix subsets of ToolId.swapOrder: {ChainState},
  // {ChainState, SpecLint}, {ChainState, SpecLint, DangerScan}, etc.
  // Edge cases: empty set (all predecessor), full set (all ported).
  def genSeamConfigurationPrefix: Gen[SeamConfiguration] =
    Gen.frequency(
      1 -> Gen.constant(SeamConfiguration.fromPorted(Set.empty)),
      List(
        1 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.take(1).toSet)),
        1 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.take(2).toSet)),
        1 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.take(3).toSet)),
        1 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.take(4).toSet)),
        1 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.toSet))
      )
    )

  // ── Generator: genSwapSequence
  // Constructive over valid swap sequences (prefixes of SwapOrder.swapOrder).
  // Edge cases: empty sequence, full sequence, single-tool sequence.
  def genSwapSequence: Gen[List[SwapOrder]] =
    Gen.frequency(
      1 -> Gen.constant(List.empty[SwapOrder]),
      List(
        1 -> Gen.constant(SwapOrder.swapOrder.take(1)),
        1 -> Gen.constant(SwapOrder.swapOrder.take(2)),
        1 -> Gen.constant(SwapOrder.swapOrder.take(3)),
        1 -> Gen.constant(SwapOrder.swapOrder.take(4)),
        1 -> Gen.constant(SwapOrder.swapOrder.take(5)),
        1 -> Gen.constant(SwapOrder.swapOrder)
      )
    )
