package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.DangerScanEngine
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ReconcileReport

import java.nio.file.Path

/**
 * Typed contract for the danger-scan and reconcile CLI adapters
 * (spec 6, Step 1).
 *
 * Pins the public shapes the Step-2 oracle exercises against the built
 * artifact — compiled under the real probatio-cli classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `DangerScanCmd` accepts the predecessor's invocation shape: an
 *    optional positional baseline (default `HEAD`) and `--also` taking
 *    every remaining token. A `-`-led token before `--also` is a named
 *    parameter the scan does not accept — rejected, naming the token.
 *  - `DangerScanCmd.run(args, cwd)` runs the git/diff/scan pipeline with
 *    `cwd` as the repository directory — injectable so tests drive the
 *    boundary without a process env.
 *  - `ReconcileCmd` takes the predecessor's five value-flags
 *    (`--file --change --spec --baseline --format`); any other token is
 *    an unrecognised argument naming the offending token.
 *  - `ChangedFilesReader` is the scan's only file-reading component:
 *    `resolveBaseline` (`git rev-parse --verify`), `changedProductionFiles`
 *    (`git diff --name-only -- '*.scala'` restricted to `/src/main/`),
 *    and `readFiles` (path → `ScannedFile`, skipping unreadable paths
 *    like the predecessor's `[ -f ]` guard).
 *  - `StdoutRenderer[ReconcileReport]` renders the predecessor's text
 *    summary; `StdoutRenderer.reconcileJson` renders the compact `jq -c`
 *    report object.
 *
 * spec: danger-reconcile-engines — Requirement: The scan's baseline is optional and defaults to the working tree
 * spec: danger-reconcile-engines — Implementation Anchor: ChangedFilesReader
 */
final class DangerReconcileCliTypeContract extends ProbatioCliSuite:

  // ── DangerScanCmd — the invocation surface ──────────────────────────
  val dangerParseSig: List[String] => Either[String, DangerScanCmd.DangerScanArgs] =
    DangerScanCmd.parseArgs

  val dangerDefaultBaselineSig: String =
    DangerScanCmd.defaultBaseline

  val dangerRunSig: Array[String] => Outcome[Int] =
    DangerScanCmd.run

  val dangerRunCwdSig: (Array[String], Path) => Outcome[Int] =
    DangerScanCmd.run

  // ── ReconcileCmd — the invocation surface ───────────────────────────
  val reconcileRunSig: Array[String] => Outcome[Int] =
    ReconcileCmd.run

  // ── ChangedFilesReader — the git/file boundary ──────────────────────
  val resolveBaselineSig: (Path, String) => Either[String, String] =
    ChangedFilesReader.resolveBaseline

  val changedProductionFilesSig: (Path, String) => Either[String, List[String]] =
    ChangedFilesReader.changedProductionFiles

  val readFilesSig: (Path, List[String]) => List[DangerScanEngine.ScannedFile] =
    ChangedFilesReader.readFiles

  // ── StdoutRenderer — the report emitters ────────────────────────────
  val reconcileTextSig: ReconcileReport => String =
    (r: ReconcileReport) => StdoutRenderer[ReconcileReport].render(r)

  val reconcileJsonSig: ReconcileReport => String =
    StdoutRenderer.reconcileJson

  // ── The pinned surface evaluates ────────────────────────────────────
  test("danger-scan with no args defaults the baseline to HEAD"):
    assertEquals(
      DangerScanCmd.parseArgs(Nil),
      Right(DangerScanCmd.DangerScanArgs("HEAD", Nil))
    )

  test("danger-scan accepts a positional baseline; last one wins"):
    assertEquals(
      DangerScanCmd.parseArgs(List("abc123", "def456")),
      Right(DangerScanCmd.DangerScanArgs("def456", Nil))
    )

  test("--also consumes every remaining token as a file"):
    assertEquals(
      DangerScanCmd.parseArgs(List("abc", "--also", "a.scala", "b.scala")),
      Right(DangerScanCmd.DangerScanArgs("abc", List("a.scala", "b.scala")))
    )

  test("a named parameter before --also is rejected naming the token"):
    assertEquals(
      DangerScanCmd.parseArgs(List("--baseline", "abc")),
      Left("--baseline")
    )
    assertEquals(
      DangerScanCmd.parseArgs(List("--frobnicate")),
      Left("--frobnicate")
    )

  test("--also after --also is consumed as the flag, not a file"):
    assertEquals(
      DangerScanCmd.parseArgs(List("--also", "a.scala", "--also", "b.scala")),
      Right(DangerScanCmd.DangerScanArgs("HEAD", List("a.scala", "b.scala")))
    )
