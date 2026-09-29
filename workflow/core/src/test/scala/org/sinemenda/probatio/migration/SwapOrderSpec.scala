package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Seam-set / swap-order bijection oracle for spec:
 * differential-harness-integrity.
 *
 * The migration concept declares a swap order; the comparison's seam set
 * must cover every position of it — a seam absent from the set is a tool
 * the comparison silently never measures, which is precisely the defect
 * that let ledger and checkpoint report "at parity" while unmeasured.
 *
 * spec: differential-harness-integrity — Requirement: Every swapped seam is represented in the comparison
 * spec: differential-harness-integrity — Property: seam-set-covers-the-swap-order
 */
final class SwapOrderSpec extends ProbatioSuite:

  import SeamTypes.*

  /**
   * Absolute path of the repository root. `os.pwd` is unreliable under
   * forked test runners (sbt `Test / fork` and the Stryker4s runner set the
   * working directory to the module base), so resolve via git.
   */
  private def repoRoot: os.Path =
    // spec: hermetic-test-processes — via the shared helper.
    scala.util
      .Try(
        HermeticEnv.capture(
          List("git", "rev-parse", "--show-toplevel"),
          HermeticEnv.empty,
          cwd = Some(os.pwd.toIO)
        )
      )
      .toOption
      .filter((r: HermeticResult) => r.exitCode == 0)
      .map((r: HermeticResult) => os.Path(r.out.trim))
      .getOrElse(os.pwd)

  /**
   * The declared swap-order position → seam correspondence. This mapping
   * is the spec-declared intent: each position names the tool swapped at
   * that step.
   */
  private def seamOf(position: SwapOrder): ToolId = position match
    case SwapOrder.LedgerFirst => ToolId.Ledger
    case SwapOrder.ChainState  => ToolId.ChainState
    case SwapOrder.SpecLint    => ToolId.SpecLint
    case SwapOrder.DangerScan  => ToolId.DangerScan
    case SwapOrder.Reconcile   => ToolId.Reconcile
    case SwapOrder.Checkpoint  => ToolId.Checkpoint
    case SwapOrder.GateLast    => ToolId.Gate

  // ── Scenario: Happy path — the seam set matches the declared swap order
  // spec: differential-harness-integrity — Scenario: Happy path — the seam set matches the declared swap order
  test("every position in the swap order has a corresponding seam"):
    val positionSeams: Set[ToolId] = SwapOrder.swapOrder.map(seamOf).toSet
    val seamSet: Set[ToolId]       = ToolId.swapOrder.toSet
    assertEquals(
      positionSeams,
      seamSet,
      s"swap-order positions and seam set must coincide; positions=${SwapOrder.swapOrder} seams=${ToolId.swapOrder}"
    )

  // ── Property: seam-set-covers-the-swap-order
  // spec: differential-harness-integrity — Property: seam-set-covers-the-swap-order
  // Enumerated, not sampled: both domains are finite closed sets (seven
  // positions, seven seams) — the property loops the full enumeration.
  property("seam set and swap order are in bijection"):
    for _ <- Gen.constant(()).forAll
    yield Result
      .assert(
        SwapOrder.swapOrder.map(seamOf).toSet == ToolId.swapOrder.toSet &&
          SwapOrder.swapOrder.length == ToolId.swapOrder.length &&
          ToolId.swapOrder.toSet.size == ToolId.swapOrder.length
      )
      .log("the seven swap-order positions and the seven seams must be in bijection")

  // ── Scenario: every seam names a distinct live invocation path
  // spec: differential-harness-integrity — Requirement: Every swapped seam is represented in the comparison
  test("every seam resolves a distinct live invocation path that exists in the tree"):
    val schemaDir: os.Path  = repoRoot / "openspec" / "schemas" / "verified-scala3"
    val paths: List[String] = ToolId.swapOrder.map(ToolId.seamPath)
    assertEquals(paths.toSet.size, ToolId.swapOrder.length, "each seam must name a distinct live path")
    paths.foreach { (rel: String) =>
      assert(os.exists(schemaDir / os.RelPath(rel)), s"seam path missing from the tree: $rel")
    }

  // ── Scenario: every seam names a predecessor implementation that exists
  // spec: differential-harness-integrity — Scenario: Error path — a seam with no predecessor implementation is could-not-determine
  test("every seam's predecessor source exists in the tree"):
    val schemaDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3"
    ToolId.swapOrder.foreach { (tool: ToolId) =>
      val rel: String = ToolId.predecessorSource(tool)
      assert(os.exists(schemaDir / os.RelPath(rel)), s"predecessor source missing for $tool: $rel")
    }
