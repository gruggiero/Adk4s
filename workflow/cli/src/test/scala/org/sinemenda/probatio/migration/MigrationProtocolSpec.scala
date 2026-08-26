package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.cli.ProbatioCliSuite

/** Test oracle for the migration-protocol spec — R-M4 (exactly one
  * implementation per seam) and the compile-negative for dual-implementation.
  *
  * These tests live in probatio-cli test sources because `MigrationState`
  * is defined in `MigrationTypes.scala` here (cli test sources cannot see
  * core test sources — `ToolId` is duplicated by design).
  *
  * spec: migration-protocol — Requirement: Exactly one implementation is active at each seam
  * spec: migration-protocol — Property: exactly-one-implementation-per-seam
  * spec: migration-protocol — Compile-Negative: MigrationState with dual-implementation seam
  */
final class MigrationProtocolSpec extends ProbatioCliSuite:

  import MigrationTypes.*

  // ── R-M4: Exactly one implementation is active at each seam
  // spec: migration-protocol — Requirement: Exactly one implementation is active at each seam
  // Scenario: A single-tool ported configuration is well-formed
  test("R-M4: single-tool ported config has exactly one implementation per seam"):
    val state: MigrationState = MigrationState(Set(ToolId.ChainState))
    // ChainState is ported; all others are on the predecessor.
    // Each tool is either ported or predecessor — never both.
    for tool <- ToolId.swapOrder do
      val isPorted: Boolean = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor")
    assert(state.portedTools.intersect(state.predecessorTools).isEmpty,
      "ported and predecessor tools overlap — dual implementation")
    assert(state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet,
      "some tools are neither ported nor predecessor — missing implementation")

  // ── R-M4: A dual-implementation configuration is rejected
  // spec: migration-protocol — Scenario: A dual-implementation configuration is rejected
  test("R-M4: full ported config has exactly one implementation per seam"):
    val state: MigrationState = MigrationState(ToolId.swapOrder.toSet)
    for tool <- ToolId.swapOrder do
      val isPorted: Boolean = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor")

  // ── Compile-Negative: MigrationState with dual-implementation seam
  // spec: migration-protocol — Compile-Negative Obligations
  // A MigrationState with a seam having implementationCount > 1 is
  // unconstructible — the type has no `both` field.
  test("compile-negative: MigrationState has no 'both' field — dual-implementation unconstructible"):
    val err: String = compileErrors("MigrationState(portedTools = Set(ToolId.ChainState), both = true)")
    assert(err.nonEmpty,
      "MigrationState should not have a 'both' field — a dual-implementation seam is unconstructible at the type level")

  // ── Property: exactly-one-implementation-per-seam (all-valid-subsets)
  // spec: migration-protocol — Property: exactly-one-implementation-per-seam
  // For every seam configuration (all valid subsets of ToolId), exactly one
  // implementation is active per seam — no seam has both the predecessor
  // and the ported tool active.
  property("exactly one implementation per seam (all-valid-subsets)"):
    for
      portedSet <- genAllValidSubsets.forAll
    yield
      val state: MigrationState = MigrationState(portedSet)
      val noOverlap: Boolean = state.portedTools.intersect(state.predecessorTools).isEmpty
      val allCovered: Boolean = state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet
      Result.assert(noOverlap && allCovered)

  // ── Generator: genAllValidSubsets
  // Constructive over all valid subsets of ToolId.swapOrder (each tool
  // is independently ported or predecessor). Edge cases: empty set
  // (all predecessor), full set (all ported), single-tool.
  def genAllValidSubsets: Gen[Set[ToolId]] =
    for
      portedChainState <- Gen.boolean
      portedSpecLint   <- Gen.boolean
      portedDangerScan <- Gen.boolean
      portedReconcile  <- Gen.boolean
      portedGate       <- Gen.boolean
    yield
      Set(
        if portedChainState then Some(ToolId.ChainState) else None,
        if portedSpecLint then Some(ToolId.SpecLint) else None,
        if portedDangerScan then Some(ToolId.DangerScan) else None,
        if portedReconcile then Some(ToolId.Reconcile) else None,
        if portedGate then Some(ToolId.Gate) else None
      ).flatten
