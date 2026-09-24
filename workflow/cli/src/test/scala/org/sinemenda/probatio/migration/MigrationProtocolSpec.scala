package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.cli.ProbatioCliSuite
import org.sinemenda.probatio.core.RenameDeferral

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.ListHasAsScala

/**
 * Test oracle for the migration-protocol spec — R-M4 (exactly one
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
      val isPorted: Boolean      = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(
        isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor"
      )
    assert(
      state.portedTools.intersect(state.predecessorTools).isEmpty,
      "ported and predecessor tools overlap — dual implementation"
    )
    assert(
      state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet,
      "some tools are neither ported nor predecessor — missing implementation"
    )

  // ── R-M4: A dual-implementation configuration is rejected
  // spec: migration-protocol — Scenario: A dual-implementation configuration is rejected
  test("R-M4: full ported config has exactly one implementation per seam"):
    val state: MigrationState = MigrationState(ToolId.swapOrder.toSet)
    for tool <- ToolId.swapOrder do
      val isPorted: Boolean      = state.portedTools.contains(tool)
      val isPredecessor: Boolean = state.predecessorTools.contains(tool)
      assert(
        isPorted ^ isPredecessor,
        s"tool $tool is not exactly one implementation: ported=$isPorted, predecessor=$isPredecessor"
      )

  // ── Compile-Negative: MigrationState with dual-implementation seam
  // spec: migration-protocol — Compile-Negative Obligations
  // A MigrationState with a seam having implementationCount > 1 is
  // unconstructible — the type has no `both` field.
  test("compile-negative: MigrationState has no 'both' field — dual-implementation unconstructible"):
    val err: String = compileErrors("MigrationState(portedTools = Set(ToolId.ChainState), both = true)")
    assert(
      err.nonEmpty,
      "MigrationState should not have a 'both' field — a dual-implementation seam is unconstructible at the type level"
    )

  // ── Property: exactly-one-implementation-per-seam (all-valid-subsets)
  // spec: migration-protocol — Property: exactly-one-implementation-per-seam
  // For every seam configuration (all valid subsets of ToolId), exactly one
  // implementation is active per seam — no seam has both the predecessor
  // and the ported tool active.
  property("exactly one implementation per seam (all-valid-subsets)"):
    for portedSet <- genAllValidSubsets.forAll
    yield
      val state: MigrationState = MigrationState(portedSet)
      val noOverlap: Boolean    = state.portedTools.intersect(state.predecessorTools).isEmpty
      val allCovered: Boolean   = state.portedTools.union(state.predecessorTools) == ToolId.swapOrder.toSet
      Result.assert(noOverlap && allCovered)

  // ── Generator: genAllValidSubsets
  // Constructive over all valid subsets of ToolId.swapOrder (each tool
  // is independently ported or predecessor). Edge cases: empty set
  // (all predecessor), full set (all ported), single-tool.
  def genAllValidSubsets: Gen[Set[ToolId]] =
    for
      portedLedger     <- Gen.boolean
      portedChainState <- Gen.boolean
      portedSpecLint   <- Gen.boolean
      portedDangerScan <- Gen.boolean
      portedReconcile  <- Gen.boolean
      portedCheckpoint <- Gen.boolean
      portedGate       <- Gen.boolean
    yield Set(
      if portedLedger then Some(ToolId.Ledger) else None,
      if portedChainState then Some(ToolId.ChainState) else None,
      if portedSpecLint then Some(ToolId.SpecLint) else None,
      if portedDangerScan then Some(ToolId.DangerScan) else None,
      if portedReconcile then Some(ToolId.Reconcile) else None,
      if portedCheckpoint then Some(ToolId.Checkpoint) else None,
      if portedGate then Some(ToolId.Gate) else None
    ).flatten

  // ════════════════════════════════════════════════════════════════════
  // spec 10 of repair-probatio-cutover: schema-rename-completion
  //
  // The directory-rename deferral is a recorded VALUE — these scenarios
  // read it and check what it names.
  // ════════════════════════════════════════════════════════════════════

  /** The repository root, walked up from the test working directory. */
  private def repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    Iterator
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find((p: Path) => Files.isDirectory(p.resolve("openspec/changes")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  /**
   * The on-disk count of recorded changes that pin the schema's current
   * directory name — every `.openspec.yaml` under `openspec/changes`
   * declaring `schema: verified-scala3`. The deferral's recorded count
   * must agree with this, not with prose.
   */
  private def changesPinningCurrentName: Int =
    val changesDir: Path = repoRoot.resolve("openspec/changes")
    val found: List[Path] =
      Files
        .walk(changesDir)
        .filter((p: Path) => p.getFileName.toString == ".openspec.yaml")
        .filter((p: Path) => Files.readString(p).contains("schema: verified-scala3"))
        .toList
        .asScala
        .toList
    found.length

  // spec: schema-rename-completion — Scenario: Happy path — the deferral names the coupling and the blocked items
  test("schema-rename: the recorded deferral names the directory rename, the coupling, and the pinning count"):
    val recorded: List[RenameDeferral] = RenameDeferral.recorded
    assert(recorded.nonEmpty, "the deferral record must not be empty")
    recorded.foreach { (d: RenameDeferral) =>
      assertEquals(
        RenameDeferral.missing(d),
        List.empty,
        s"a recorded deferral must be complete — missing: ${RenameDeferral.missing(d)}"
      )
    }
    val directoryRename: List[RenameDeferral] = recorded.filter { (d: RenameDeferral) =>
      d.item.toLowerCase.contains("director") &&
      d.item.contains("verified-scala3")
    }
    assertEquals(
      directoryRename.length,
      1,
      s"exactly one deferral must name the schema directory rename, got: ${recorded.map(_.item)}"
    )
    directoryRename.headOption match
      case Some(d) =>
        assert(
          d.blockedBy.resolutionMechanism.toLowerCase.contains("directory"),
          s"the coupling must name directory-name resolution: ${d.blockedBy.resolutionMechanism}"
        )
        assert(
          d.blockedBy.configurationPin.contains("openspec/config.yaml") ||
            d.blockedBy.configurationPin.contains("config.yaml"),
          s"the coupling must name the pinning configuration: ${d.blockedBy.configurationPin}"
        )
        assertEquals(
          d.blockedBy.recordedChangesPinning,
          changesPinningCurrentName,
          "the recorded count must equal the on-disk count of changes pinning the current name"
        )
      case None => fail("unreachable — length asserted above")

  // spec: schema-rename-completion — Scenario: Adversarial — a deferral without a recorded reason is not accepted
  test("schema-rename: a deferral entry carrying no reason is reported incomplete"):
    val entry: RenameDeferral = RenameDeferral(
      item = "schema directory rename",
      reason = "",
      blockedBy = RenameDeferral.Coupling(
        resolutionMechanism = "the workflow tool resolves a schema by its directory name",
        configurationPin = "openspec/config.yaml pins schema: verified-scala3",
        recordedChangesPinning = 19
      )
    )
    val missing: List[RenameDeferral.Missing] = RenameDeferral.missing(entry)
    assert(
      missing.contains(RenameDeferral.Missing.Reason),
      s"the check must report the missing reason, got: $missing"
    )
