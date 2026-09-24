package org.sinemenda.probatio.plugin

import java.io.File

import munit.FunSuite

/**
 * Typed contract for workflow-delivery-hygiene (spec 9, Step 1).
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * signatures via eta-expanded references and asserts the compile-negative
 * obligations. Zero runtime cost; any later signature drift breaks
 * `sbt-probatio/Test/compile`.
 *
 * Pinned decisions for human review:
 *
 *  - `ShimTargetScope` is a REQUIRED parameter of `generateShim` — a
 *    generation request that does not state its resolution scope does
 *    not compile. The scope is never inferred from the target's shape:
 *    inferring "this looks absolute, so it is an install" is the
 *    reasoning that produced a committed absolute path nobody noticed.
 *  - `ShimTargetScope` has exactly two variants:
 *      `RepositoryRelative(fromShimToBinary: RelPath)` — the in-repo
 *        scope; the generated script resolves the tool relative to its
 *        own location (`$SCRIPT_DIR/<fromShimToBinary>`).
 *      `AbsoluteInstall(path: String)` — the user-level install scope;
 *        the generated script execs that absolute path.
 *  - `ShimTargetScope.RelPath` is a value class with a PRIVATE
 *    constructor; `RelPath.from` is the only constructor and refuses
 *    empty, root-anchored, home-anchored, drive-anchored and UNC
 *    inputs. A `RelPath` value can never name an absolute location —
 *    "the relative variant takes a relative path type" is a type-level
 *    fact, and `RepositoryRelative("/usr/local/bin/x")` is
 *    unconstructible because a `String` is not a `RelPath`.
 *  - `ResolutionResult` is unchanged: it still gates generation (a
 *    blocked resolution yields `Left`, no shim written). The scope
 *    supplies the target; the resolution supplies the blocked/present
 *    verdict. Under `AbsoluteInstall` the scope's `path` MUST equal the
 *    resolved artifact's path — a scope and a resolution naming two
 *    different artifacts is refused rather than silently resolved.
 *  - `ProbatioPlugin.writeShim` forwards the scope; `probatioGateShim`
 *    passes `AbsoluteInstall(installed.getAbsolutePath)` — the
 *    user-level shim is install-scoped by construction.
 *
 * spec: repair-probatio-cutover/workflow-delivery-hygiene — Step 1: typed contract
 * spec: repair-probatio-cutover/workflow-delivery-hygiene — Requirement: The generated script states which scope it resolved
 * spec: repair-probatio-cutover/workflow-delivery-hygiene — Requirement: An in-repository forwarding script resolves its target relative to itself
 */
final class WorkflowDeliveryHygieneTypeContract extends FunSuite {

  // ── Signature pins (eta-expanded against the real implementation) ────────
  // These pins make "signatures stay as approved" compiler-checked.

  // ShimGenerator.generateShim: (ResolutionResult, ShimTargetScope, String) => Either[String, String]
  val generateShimSig: (ResolutionResult, ShimTargetScope, String) => Either[String, String] =
    ShimGenerator.generateShim _

  // ShimGenerator.generateShim: (ResolutionResult, ShimTargetScope) => Either[String, String]
  val generateShimGateSig: (ResolutionResult, ShimTargetScope) => Either[String, String] =
    ShimGenerator.generateShim _

  // ShimTargetScope.RepositoryRelative: RelPath => ShimTargetScope
  val repositoryRelativeSig: ShimTargetScope.RelPath => ShimTargetScope =
    ShimTargetScope.RepositoryRelative.apply

  // ShimTargetScope.AbsoluteInstall: String => ShimTargetScope
  val absoluteInstallSig: String => ShimTargetScope =
    ShimTargetScope.AbsoluteInstall.apply

  // ShimTargetScope.RelPath.from: String => Either[String, RelPath]
  val relPathFromSig: String => Either[String, ShimTargetScope.RelPath] =
    ShimTargetScope.RelPath.from

  // ProbatioPlugin.writeShim: (ResolutionResult, ShimTargetScope, String, File) => Either[String, File]
  val writeShimSig: (ResolutionResult, ShimTargetScope, String, File) => Either[String, File] =
    ProbatioPlugin.writeShim _

  // ── Compile-Negative: a generation request without a scope ──────────────
  // spec: repair-probatio-cutover/workflow-delivery-hygiene — Compile-Negative: A generation request without a scope
  test("generateShim without a scope does not compile") {
    val err: String = compileErrors(
      "ShimGenerator.generateShim(ResolutionResult(Some(\"/usr/local/bin/probatio\"), Nil), \"gate\")"
    )
    assert(
      err.nonEmpty,
      "generateShim(resolution, subcommand) should not compile — the scope is a required parameter"
    )
  }

  test("generateShim without a scope does not compile at the default-subcommand arity either") {
    val err: String = compileErrors(
      "ShimGenerator.generateShim(ResolutionResult(Some(\"/usr/local/bin/probatio\"), Nil))"
    )
    assert(
      err.nonEmpty,
      "generateShim(resolution) should not compile — the scope is a required parameter"
    )
  }

  // ── Compile-Negative: a repository-relative scope carrying an absolute
  //    path ────────────────────────────────────────────────────────────────
  // spec: repair-probatio-cutover/workflow-delivery-hygiene — Compile-Negative: A repository-relative scope carrying an absolute path
  test("ShimTargetScope.RepositoryRelative with an absolute path does not compile") {
    val err: String = compileErrors(
      "ShimTargetScope.RepositoryRelative(\"/usr/local/bin/x\")"
    )
    assert(
      err.nonEmpty,
      "RepositoryRelative(\"/usr/local/bin/x\") should not compile — the variant takes a relative path type"
    )
  }

  // ── RelPath refuses absolute inputs at its only constructor ─────────────
  // spec: repair-probatio-cutover/workflow-delivery-hygiene — the relative variant cannot carry an absolute path
  test("RelPath.from refuses every absolute anchoring") {
    List("/usr/local/bin/x", "~/bin/x", "C:\\tools\\x", "C:/tools/x", "C:", "\\\\host\\share\\x", "").foreach { input =>
      ShimTargetScope.RelPath.from(input) match {
        case Left(_)  => ()
        case Right(p) => fail(s"RelPath.from($input) must refuse an absolute/empty target, got $p")
      }
    }
  }

  test("RelPath.from accepts a relative path and carries it verbatim") {
    ShimTargetScope.RelPath.from("../bin/probatio") match {
      case Right(rel)   => assertEquals(rel.value, "../bin/probatio")
      case Left(reason) => fail(s"a relative path must construct, got Left($reason)")
    }
  }
}
