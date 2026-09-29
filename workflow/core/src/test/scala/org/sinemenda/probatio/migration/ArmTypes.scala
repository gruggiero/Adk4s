package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome

import scala.util.Try

import SeamTypes.*

/**
 * Types for the arm-materialisation comparison (differential-harness-integrity).
 *
 * The differential comparison decides the cutover by running the acceptance
 * suite against two materialised trees: one in which every seam resolves to
 * its predecessor implementation, one in which every seam resolves to the
 * ported implementation. `ArmTree` is the type of one such side; it is
 * constructible only through `ArmTree.materialise`, so a comparison side that
 * was never built cannot be passed to the harness.
 *
 * `SeamResolution` identifies what a seam resolved to by CONTENT DIGEST, not
 * by path — two different paths holding the same forwarding script are the
 * same implementation, and comparing paths is precisely the defect that let
 * the old harness report "no regression" while both arms ran the same bytes.
 *
 * `ArmDivergence` is the verdict of comparing the two arms' resolutions before
 * the suite runs: `Identical` is a refusal — never a passing verdict — and
 * `Diverged` names the seams whose resolved contents differ.
 *
 * spec: differential-harness-integrity — Concepts Introduced: ArmTree
 * spec: differential-harness-integrity — Concepts Introduced: ArmDivergence
 * spec: differential-harness-integrity — Concepts Introduced: SeamResolution
 */

/** A SHA-256 content digest (64 lowercase hex characters). */
opaque type ContentDigest = String

object ContentDigest:

  /** Digest a file's bytes. Always produces a well-formed digest. */
  def ofBytes(bytes: Array[Byte]): ContentDigest =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** Digest a file on disk. */
  def ofFile(path: os.Path): ContentDigest =
    ofBytes(os.read.bytes(path))

  /** Parse a hex string; `None` unless it is exactly 64 lowercase hex chars. */
  def parse(text: String): Option[ContentDigest] =
    if text.length == 64 && text.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
    then Some(text)
    else None

  /** The 64-char lowercase hex form. */
  def hex(d: ContentDigest): String = d

/**
 * What one seam resolved to in one arm: the seam, the content digest of the
 * implementation it holds, and the path that content was read from.
 *
 * Equality of implementations is by `implementationDigest` only — `sourcePath`
 * is provenance, not identity.
 *
 * spec: differential-harness-integrity — Concepts Introduced: SeamResolution
 */
final case class SeamResolution(
  seam: ToolId,
  implementationDigest: ContentDigest,
  sourcePath: os.Path
)

/**
 * A materialised copy of the tool tree for one side of the comparison.
 *
 * The constructor is private: the only way to obtain an `ArmTree` is
 * [[ArmTree.materialise]], which writes the tree, resolves every seam, and
 * records each resolution's content digest. A caller cannot assemble a
 * comparison side out of paths or digests alone — the resolved-seam record is
 * produced by the materialisation itself.
 *
 * spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
 * spec: differential-harness-integrity — Compile-Negative: A comparison side constructed without materialisation
 */
// A final class, not a case class: a private case-class constructor still
// emits a PUBLIC `copy`, which would let a caller clone an arm with altered
// resolutions and bypass materialise. Sealing copy is the codebase's own
// convention (see ChainStateAttributionSpec's "copy is sealed shut" checks).
final class ArmTree private (
  val root: os.Path,
  val origin: os.Path,
  val baseline: String,
  val config: SeamConfiguration,
  val resolutions: List[SeamResolution]
)

object ArmTree:

  /**
   * Materialise an arm: materialise the repository tree containing
   * `schemaDir` at `baseline` into `destDir` (a `git worktree` at that
   * commit, so the suite's `git ls-files` enumerations and the arm's
   * `workflow/` ported sources resolve inside the arm), resolve every
   * seam in `ToolId.swapOrder` to the implementation `config` names, and
   * record each seam's content digest.
   *
   * `root` in the result is the materialised schema subtree
   * (`destDir` + `schemaDir`'s path relative to the repository root);
   * `origin` is the repository root `schemaDir` lives in.
   *
   * Resolutions write into the arm's live seam paths: a predecessor
   * resolution copies `predecessorSource` over the seam path (or keeps the
   * live file when it IS the predecessor source, for `ledger`/`checkpoint`);
   * a ported resolution always writes the canonical exec shim
   * (`exec <bin/probatio> <subcommand>`) — identical bytes to the committed
   * shims at a swapped baseline, and correct wiring at a pre-swap baseline
   * where the committed file is still the predecessor script.
   *
   * A seam whose predecessor implementation is absent from the tree yields
   * `Outcome.Undetermined` naming that seam — never a silently-empty arm.
   * A `schemaDir` that is not inside a git repository, or a `baseline`
   * that does not resolve to a commit, is likewise `Undetermined`.
   *
   * spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
   * spec: differential-harness-integrity — Scenario: Error path — a seam with no predecessor implementation is could-not-determine
   */
  def materialise(
    config: SeamConfiguration,
    baseline: String,
    schemaDir: os.Path,
    destDir: os.Path
  ): Outcome[ArmTree] =
    gitOut(schemaDir, List("rev-parse", "--show-toplevel")) match
      case None =>
        Outcome.Undetermined(s"materialise: $schemaDir is not inside a git repository")
      case Some(rootText) =>
        val origin: os.Path = os.Path(rootText)
        val schemaRel: Option[os.RelPath] =
          Try(schemaDir.relativeTo(origin)).toOption
        schemaRel match
          case None =>
            Outcome.Undetermined(s"materialise: $schemaDir is outside repository root $origin")
          case Some(rel) =>
            gitOut(origin, List("rev-parse", "--verify", s"$baseline^{commit}")) match
              case None =>
                Outcome.Undetermined(s"materialise: baseline '$baseline' does not resolve to a commit")
              case Some(sha) =>
                // The built tool the arm must carry for direct invocations:
                // the native executable and/or the assembly archive under
                // the ORIGIN's workflow/cli/target. Both paths are
                // gitignored, so the worktree itself carries neither and
                // materialise provisions them — an arm with nothing built
                // is could-not-determine, never silently tool-less.
                // spec: jar-launcher-dispatch — Requirement: A comparison arm carries the built tool for direct invocations
                // spec: jar-launcher-dispatch — Scenario: Adversarial — an arm with no built tool is could-not-determine
                val toolTarget: os.Path   = origin / "workflow" / "cli" / "target"
                val nativeTool: os.Path   = toolTarget / "native-image" / "probatio"
                val jarDir: os.Path       = toolTarget / "scala-3.8.4"
                val jars: List[os.Path] =
                  if os.isDir(jarDir) then
                    os
                      .list(jarDir)
                      .filter((p: os.Path) => p.last.matches("probatio-cli-assembly-.*\\.jar"))
                      .toList
                  else List.empty
                val provided: List[os.Path] =
                  (if os.exists(nativeTool) then List(nativeTool) else List.empty) ++ jars
                if provided.isEmpty then
                  Outcome.Undetermined(
                    s"materialise: no built tool available to provide — " +
                      s"searched $nativeTool and $jarDir/probatio-cli-assembly-*.jar"
                  )
                else
                  gitOut(origin, List("worktree", "add", "--detach", destDir.toString, sha)) match
                    case None =>
                      Outcome.Undetermined(s"materialise: could not create a worktree at $destDir for $sha")
                    case Some(_) =>
                      // Copy each built artifact into the arm at the same
                      // repo-relative path — the arm's own bin/probatio
                      // resolves $ARM_ROOT/workflow/cli/target/...
                      provided.foreach { (src: os.Path) =>
                        val dst: os.Path = destDir / src.relativeTo(origin)
                        os.makeDir.all(dst / os.up)
                        os.copy(src, dst, replaceExisting = true)
                        if src.toIO.canExecute then os.perms.set(dst, "rwxr-xr-x")
                      }
                      val armSchema: os.Path = destDir / rel
                      resolveSeams(config, schemaDir, armSchema) match
                        case Left(reason) =>
                          // The worktree was already added — remove it so a
                          // failed materialisation does not leak stale
                          // .git/worktrees entries.
                          Try(
                            os
                              .proc("git", "-C", origin.toString, "worktree", "remove", "--force", destDir.toString)
                              .call(check = false, stderr = os.Pipe)
                          )
                          Outcome.Undetermined(reason)
                        case Right(resolved) =>
                          Outcome.Ran(new ArmTree(armSchema, origin, sha, config, resolved))

  /**
   * Resolve every seam in swap order against the materialised arm's live
   * paths. `Left` names the first seam that could not be resolved.
   */
  private def resolveSeams(
    config: SeamConfiguration,
    originSchema: os.Path,
    armSchema: os.Path
  ): Either[String, List[SeamResolution]] =
    ToolId.swapOrder.foldLeft[Either[String, List[SeamResolution]]](Right(List.empty)) { (acc, seam) =>
      acc.flatMap(done => resolveSeam(config, seam, originSchema, armSchema).map(done :+ _))
    }

  private def resolveSeam(
    config: SeamConfiguration,
    seam: ToolId,
    originSchema: os.Path,
    armSchema: os.Path
  ): Either[String, SeamResolution] =
    val armSeam: os.Path = armSchema / os.RelPath(ToolId.seamPath(seam))
    // A seam whose predecessor source IS its live path was never swapped —
    // the live file is the predecessor, and a ported resolution must write
    // the exec shim the swap will eventually write.
    val liveIsPredecessor: Boolean =
      ToolId.predecessorSource(seam) == ToolId.seamPath(seam)
    config.resolve(seam) match
      case Some(Implementation.Predecessor) =>
        val src: os.Path = originSchema / os.RelPath(ToolId.predecessorSource(seam))
        if !os.exists(src) then
          Left(s"seam $seam: predecessor implementation absent at ${ToolId.predecessorSource(seam)}")
        else
          if !liveIsPredecessor then os.copy(src, armSeam, replaceExisting = true)
          Right(SeamResolution(seam, ContentDigest.ofFile(armSeam), armSeam))
      case Some(Implementation.Ported) =>
        // Always write the canonical ported shim — never keep the baseline's
        // file. The committed shim is the self-relative form (it resolves
        // $SCRIPT_DIR/../bin/probatio); the arm shim intentionally execs the
        // ORIGIN's launcher, so the ported arm and the predecessor control
        // run the same built artifact — the arm's own copy is provisioned
        // for tests that invoke it directly. At a pre-swap baseline the
        // committed file is the predecessor implementation, and keeping it
        // would silently put predecessor bytes in the ported arm.
        val subcommand: String = os.RelPath(ToolId.seamPath(seam)).last.stripSuffix(".sh")
        os.write.over(
          armSeam,
          s"#!/usr/bin/env bash\nexec \"$originSchema/bin/probatio\" $subcommand \"$$@\"\n"
        )
        Right(SeamResolution(seam, ContentDigest.ofFile(armSeam), armSeam))
      case None =>
        Left(s"seam $seam: no implementation configured")

  /** `git` stdout on success, `None` on any failure. */
  private def gitOut(cwd: os.Path, args: List[String]): Option[String] =
    // spec: hermetic-test-processes — via the shared helper.
    Try(
      HermeticEnv.capture("git" :: args, HermeticEnv.empty, cwd = Some(cwd.toIO))
    ).toOption
      .filter((r: HermeticResult) => r.exitCode == 0)
      .map((r: HermeticResult) => r.out.trim)

/**
 * The verdict of comparing two arms' per-seam resolutions before the suite
 * runs.
 *
 * `Identical` carries the seam resolutions that proved the arms identical; it
 * is a REFUSAL — the comparison does not proceed to the suite and the outcome
 * is distinguishable from any gate verdict. `Diverged` carries the seam pairs
 * whose content digests differ.
 *
 * spec: differential-harness-integrity — Concepts Introduced: ArmDivergence
 * spec: differential-harness-integrity — Requirement: A comparison whose arms resolve identically is refused
 */
enum ArmDivergence:
  case Identical(seams: List[SeamResolution])
  case Diverged(perSeam: List[(SeamResolution, SeamResolution)])

  /** True iff the arms are identical at every seam — the refusal case. */
  def isIdentical: Boolean = this match
    case ArmDivergence.Identical(_) => true
    case ArmDivergence.Diverged(_)  => false

  /** The seams whose resolved contents differ (empty when identical). */
  def divergingSeams: List[ToolId] = this match
    case ArmDivergence.Identical(_)      => List.empty[ToolId]
    case ArmDivergence.Diverged(perSeam) => perSeam.map((l: SeamResolution, _: SeamResolution) => l.seam)
