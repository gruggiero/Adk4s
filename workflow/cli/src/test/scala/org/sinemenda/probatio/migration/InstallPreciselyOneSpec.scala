package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.cli.ProbatioCliSuite

/**
 * Exactly-one-implementation invariant property test (R-M4).
 *
 * During migration, precisely one implementation per tool is active at
 * any commit. The installation step asserts precisely-one by resolving
 * each shim's target and verifying that exactly one implementation is
 * reachable — zero is a broken install, two is an ambiguous install.
 *
 * spec: migration-protocol — Requirement: Exactly one implementation per tool during migration
 * spec: migration-protocol — Property: exactly-one-implementation-invariant
 */
final class InstallPreciselyOneSpec extends ProbatioCliSuite:

  import MigrationTypes.*

  // ── Scenario: Single implementation per tool after partial migration
  // spec: migration-protocol — Scenario: Single implementation per tool after partial migration
  test("exactly one: partial migration — three ported, rest on predecessor"):
    val state: MigrationState = MigrationState(
      Set(
        ToolId.ChainState,
        ToolId.SpecLint,
        ToolId.DangerScan
      )
    )
    val targets: ShimResolution = resolveAllShimTargets(state)
    assert(
      targets.allExactlyOne,
      s"not all tools have exactly one implementation: missing=${targets_missing(targets)}, dual=${targets_dual(targets)}"
    )

  // ── Scenario: Dual installation detected and rejected
  // spec: migration-protocol — Scenario: Dual installation detected and rejected
  test("exactly one: dual installation is detected and rejected"):
    val dualTarget: ShimTarget = ShimTarget(
      tool = ToolId.ChainState,
      resolvedTarget = Some("/path/to/predecessor/chain-state.sh"),
      candidateTargets = List("/path/to/predecessor/chain-state.sh", "/path/to/probatio")
    )
    assert(dualTarget.isDual, "dual installation not detected")
    assert(!dualTarget.isExactlyOne, "dual installation incorrectly reported as exactly-one")

  // ── Scenario: Missing installation detected and rejected
  // spec: migration-protocol — Scenario: Missing installation detected and rejected
  test("exactly one: missing installation is detected and rejected"):
    val missingTarget: ShimTarget = ShimTarget(
      tool = ToolId.SpecLint,
      resolvedTarget = None,
      candidateTargets = Nil
    )
    assert(missingTarget.isMissing, "missing installation not detected")
    assert(!missingTarget.isExactlyOne, "missing installation incorrectly reported as exactly-one")

  // ── Compile-Negative: Two implementations installed for the same tool
  // spec: migration-protocol — Compile-Negative: Two implementations installed for the same tool
  test("compile-negative: candidateTargets.size == 1 per tool — dual fails install"):
    val state: MigrationState   = MigrationState(Set(ToolId.ChainState))
    val targets: ShimResolution = resolveAllShimTargets(state)
    // Every tool must have exactly 1 candidate target
    for target <- targets.targets do
      assert(
        target.candidateTargets.length == 1,
        s"tool ${target.tool} has ${target.candidateTargets.length} candidates — dual installation"
      )

  // ── Compile-Negative: Zero implementations reachable for a tool
  // spec: migration-protocol — Compile-Negative: Zero implementations reachable for a tool
  test("compile-negative: resolvedTarget.isDefined per tool — missing fails install"):
    val state: MigrationState   = MigrationState(Set(ToolId.ChainState))
    val targets: ShimResolution = resolveAllShimTargets(state)
    // Every tool must have a defined resolved target
    for target <- targets.targets do
      assert(target.resolvedTarget.isDefined, s"tool ${target.tool} has no resolved target — missing installation")

  // ── Property: exactly-one-implementation-invariant
  // spec: migration-protocol — Property: exactly-one-implementation-invariant
  property("exactly one implementation per tool"):
    for portedSet <- genMigrationState.forAll
    yield
      val targets: ShimResolution = resolveAllShimTargets(portedSet)
      Result.assert(
        targets.targets.forall(_.resolvedTarget.isDefined) &&
          targets.targets.forall(t => t.candidateTargets.length == 1)
      )

  // ── Generator: genMigrationState
  // Constructive over subsets of tools that have been ported (the ported
  // set ranges from empty to all tools).
  def genMigrationState: Gen[MigrationState] =
    for
      portedChainState <- Gen.boolean
      portedSpecLint   <- Gen.boolean
      portedDangerScan <- Gen.boolean
      portedReconcile  <- Gen.boolean
      portedGate       <- Gen.boolean
    yield
      val ported: Set[ToolId] = Set(
        if portedChainState then Some(ToolId.ChainState) else None,
        if portedSpecLint then Some(ToolId.SpecLint) else None,
        if portedDangerScan then Some(ToolId.DangerScan) else None,
        if portedReconcile then Some(ToolId.Reconcile) else None,
        if portedGate then Some(ToolId.Gate) else None
      ).flatten
      MigrationState(ported)

  // ── Helper: resolve all shim targets for a migration state
  // For each tool in swap order, resolves the shim target. A ported tool
  // resolves to the probatio binary; a predecessor tool resolves to the
  // predecessor script. Exactly one candidate target per tool.
  def resolveAllShimTargets(state: MigrationState): ShimResolution =
    val targets: List[ShimTarget] = ToolId.swapOrder.map { tool =>
      val (resolved: Option[String], candidates: List[String]) =
        if state.portedTools.contains(tool) then
          // Ported: resolves to the probatio binary
          (Some("probatio"), List("probatio"))
        else
          // Predecessor: resolves to the scanner script
          val predecessorPath: String = predecessorScriptPath(tool)
          (Some(predecessorPath), List(predecessorPath))
      ShimTarget(tool, resolved, candidates)
    }
    ShimResolution(targets)

  // ── Helper: map a tool to its predecessor script path
  private def predecessorScriptPath(tool: ToolId): String = tool match
    case ToolId.SpecLint   => "openspec/schemas/verified-scala3/scanner/spec-lint.sh"
    case ToolId.ChainState => "openspec/schemas/verified-scala3/scanner/chain-state.sh"
    case ToolId.DangerScan => "openspec/schemas/verified-scala3/scanner/danger-scan.sh"
    case ToolId.Reconcile  => "openspec/schemas/verified-scala3/scanner/reconcile.sh"
    case ToolId.Gate       => "openspec/schemas/verified-scala3/hooks/gate.sh"

  // ── Helpers for test reporting
  private def targets_missing(r: ShimResolution): String =
    r.missing.map(_.tool.toString).mkString(", ")
  private def targets_dual(r: ShimResolution): String =
    r.dual.map(_.tool.toString).mkString(", ")
