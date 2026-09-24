package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome

import scala.util.Using

import SeamTypes.*

/**
 * The measured per-seam swap driver (spec 8).
 *
 * For one seam, `attempt`:
 *   1. materialises the predecessor arm and the arm with this seam
 *      ported (all other seams on predecessor) at `baseline`,
 *   2. runs the oracle differential and asserts the arms diverged at
 *      this seam,
 *   3. scopes the comparison to the oracle files exercising the seam
 *      (`DifferentialHarness.exercisedToolPaths`) and asks
 *      `CutoverGate.authoriseSwap` for the recorded decision,
 *   4. on an authorising record only: preserves the live predecessor
 *      to `<seam>.predecessor.bak`, writes the exec shim at the seam
 *      path in `schemaDir`, and returns the `ShimSwap` record carrying
 *      the comparison it rested on.
 *
 * Outcome algebra (spec §4.1):
 *   - `Ran(swap)` — the comparison authorised; the swap was performed
 *     and recorded with its comparison.
 *   - `Finding(description)` — the swap was refused: the comparison
 *     regressed or is incomplete (the description names the justifying
 *     files), or the predecessor implementation is absent at its
 *     revert-target path (the description names it).
 *   - `Undetermined(reason)` — the swap could not be determined: an arm
 *     could not be materialised, or the suite could not run.
 *
 * spec: ledger-checkpoint-cutover — Requirement: Each seam is swapped only after its oracle files reach control parity
 * spec: ledger-checkpoint-cutover — Requirement: The predecessor remains a revert target
 * spec: ledger-checkpoint-cutover — Scenario: The swap records the comparison it rested on
 * spec: ledger-checkpoint-cutover — Scenario: A swap whose predecessor is absent is refused
 */
object SeamSwapRunner:

  /**
   * Attempt the measured swap of `seam` inside `schemaDir` at
   * `baseline`. `workRoot` is the parent directory for the two
   * materialised arm worktrees; `timestamp` is stamped onto the
   * emitted `ShimSwap` record.
   */
  def attempt(
    seam: ToolId,
    schemaDir: os.Path,
    baseline: String,
    workRoot: os.Path,
    timestamp: String
  ): Outcome[ShimSwap] =
    val predecessorPath: os.Path = schemaDir / os.RelPath(ToolId.predecessorSource(seam))
    // Refuse before measuring: a seam with no predecessor implementation
    // has no revert target and is never swapped.
    if !os.exists(predecessorPath) then
      Outcome.Finding(
        s"seam $seam swap refused: predecessor implementation absent at ${ToolId.predecessorSource(seam)}"
      )
    else
      ArmTree.materialise(
        SeamConfiguration.fromPorted(Set.empty),
        baseline,
        schemaDir,
        workRoot / "predecessor"
      ) match
        case Outcome.Undetermined(reason) => Outcome.Undetermined(s"predecessor arm: $reason")
        case Outcome.Finding(desc)        => Outcome.Finding(s"predecessor arm: $desc")
        case Outcome.Ran(predecessorArm) =>
          ArmTree.materialise(
            SeamConfiguration.fromPorted(Set(seam)),
            baseline,
            schemaDir,
            workRoot / "ported"
          ) match
            case Outcome.Undetermined(reason) =>
              removeWorktrees(schemaDir, List(workRoot / "predecessor"))
              Outcome.Undetermined(s"ported arm: $reason")
            case Outcome.Finding(desc) =>
              removeWorktrees(schemaDir, List(workRoot / "predecessor"))
              Outcome.Finding(s"ported arm: $desc")
            case Outcome.Ran(portedArm) =>
              // The arms are registered in the origin's .git/worktrees —
              // the resource removes them on close whatever the decision is.
              Using.resource(WorktreeCleanup(schemaDir, List(workRoot / "predecessor", workRoot / "ported"))) { _ =>
                overlaySuite(schemaDir, predecessorArm)
                overlaySuite(schemaDir, portedArm)
                compareAndSwap(seam, schemaDir, predecessorArm, portedArm, timestamp)
              }

  /**
   * Overlay the live suite onto a materialised arm. The arms are
   * worktrees of the committed baseline, but the comparison must gate
   * the swap under the suite being SHIPPED — including oracle repairs
   * that are part of this change and not yet committed. The overlay
   * replaces the arm's `tests/` wholesale, so both arms run byte-
   * identical suite files: "the same repository under the same suite"
   * still holds.
   */
  private def overlaySuite(schemaDir: os.Path, arm: ArmTree): Unit =
    val liveSuite: os.Path = schemaDir / "tests"
    if os.exists(liveSuite) then
      val armSuite: os.Path = arm.root / "tests"
      if os.exists(armSuite) then os.remove.all(armSuite)
      os.copy(liveSuite, armSuite)

  /**
   * The measured half of the attempt: refuse identical arms, scope the
   * comparison to the seam's exercising files, and swap only on an
   * authorising record.
   */
  private def compareAndSwap(
    seam: ToolId,
    schemaDir: os.Path,
    predecessorArm: ArmTree,
    portedArm: ArmTree,
    timestamp: String
  ): Outcome[ShimSwap] =
    DifferentialHarness.compare(predecessorArm, portedArm) match
      case Left(_: ArmDivergence.Identical) =>
        Outcome.Finding(
          s"seam $seam swap refused: the arms resolved to identical implementations — nothing was measured"
        )
      case Right(d) =>
        val exercising: Map[String, Set[String]] = DifferentialHarness.exercisedToolPaths(portedArm)
        val record: GateRecord                   = CutoverGate.authoriseSwap(seam, d, exercising)
        if record.authorisesSwap then performSwap(seam, schemaDir, portedArm.origin, record, timestamp)
        else
          val reason: String =
            if record.evidence.files.isEmpty then
              s"no oracle file exercises ${ToolId.seamPath(seam)} — nothing was measured"
            else s"comparison refused on: ${record.evidence.justifyingFileNames.mkString(", ")}"
          Outcome.Finding(s"seam $seam swap refused — $reason")

  /**
   * The swap itself — runs only on an authorising `GateRecord`:
   * preserves the predecessor implementation at its `.predecessor.bak`
   * revert-target path (only when the live file IS the predecessor
   * source — an already-recorded `.bak` is left untouched), writes the
   * canonical exec shim at the seam path, and returns the recorded swap.
   */
  private def performSwap(
    seam: ToolId,
    schemaDir: os.Path,
    repoRoot: os.Path,
    comparison: GateRecord,
    timestamp: String
  ): Outcome[ShimSwap] =
    val seamRel: os.RelPath        = os.RelPath(ToolId.seamPath(seam))
    val predecessorRel: os.RelPath = os.RelPath(ToolId.predecessorSource(seam))
    val liveSeam: os.Path          = schemaDir / seamRel
    if predecessorRel == seamRel then
      val backup: os.Path = schemaDir / os.RelPath(ToolId.seamPath(seam) + ".predecessor.bak")
      os.copy(liveSeam, backup, replaceExisting = true)
    // The exec shim is byte-identical to the canonical ported resolution
    // the ported arm was measured with.
    val subcommand: String = seamRel.last.stripSuffix(".sh")
    val binary: os.Path    = schemaDir / "bin" / "probatio"
    os.write.over(
      liveSeam,
      s"#!/usr/bin/env bash\nexec \"$binary\" $subcommand \"$$@\"\n"
    )
    os.perms.set(liveSeam, "rwxr-xr-x")
    val schemaRel: String = schemaDir.relativeTo(repoRoot).toString
    Outcome.Ran(
      ShimSwap(
        tool = seam,
        predecessorPath = s"$schemaRel/${ToolId.predecessorSource(seam)}",
        shimPath = s"$schemaRel/${ToolId.seamPath(seam)}",
        binaryPath = binary.toString,
        comparison = comparison,
        timestamp = timestamp
      )
    )

  /**
   * Deregister materialised-arm worktrees — they are registered in the
   * repository's `.git/worktrees`, so a run must remove them or the
   * repository accumulates stale entries.
   */
  private def removeWorktrees(schemaDir: os.Path, worktrees: List[os.Path]): Unit =
    worktrees.foreach { (wt: os.Path) =>
      os.proc("git", "-C", schemaDir.toString, "worktree", "remove", "--force", wt.toString)
        .call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
