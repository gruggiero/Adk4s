package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.git
import LiveFactFixtures.withTempDir

/**
 * Test oracle for the `danger-scan` subcommand surface (spec 6, Step 2):
 * the predecessor's invocation forms — a positional baseline defaulting
 * to HEAD, the `--also` tail switch — and the three-way outcome:
 * clean scope reports Ran(0) with a stated scope, an unresolvable
 * baseline is Undetermined, an unjustified occurrence is Finding.
 *
 * spec: danger-reconcile-engines — Requirement: The scan's baseline is optional and defaults to the working tree
 * spec: danger-reconcile-engines — Scenario: Error path — an unknown named parameter is rejected naming the token
 */
final class DangerScanCmdSpec extends ProbatioCliSuite:

  /** Run the subcommand in `cwd` with both streams captured. */
  private def captureRun(args: Array[String], cwd: Path): (Outcome[Int], String, String) =
    val (out: String, err: String, outcome: Outcome[Int]) =
      StdoutCapture.captureBoth(DangerScanCmd.run(args, cwd))
    (outcome, out, err)

  /** `git init` + an empty baseline commit; returns the repo dir. */
  private def initRepo(prefix: String)(f: Path => Unit): Unit =
    withTempDir(prefix) { (dir: Path) =>
      assertEquals(git(dir, "init", "-q")._1, 0)
      assertEquals(
        git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "base")._1,
        0
      )
      f(dir)
    }

  /** Write `lines` at `dir/rel`, staging nothing. */
  private def writeFile(dir: Path, rel: String, lines: List[String]): Unit =
    val p: Path = dir.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.writeString(p, lines.mkString("", "\n", "\n"), StandardCharsets.UTF_8)

  /** Stage every file in `dir` so `git diff HEAD` reports them. */
  private def stageAll(dir: Path): Unit =
    assertEquals(git(dir, "add", "-A")._1, 0)

  // ── Argument shape ──────────────────────────────────────────────────
  // spec: danger-reconcile-engines — Scenario: Happy path — the positional baseline is accepted
  test("the baseline is a positional argument, defaulting to HEAD"):
    assertEquals(
      DangerScanCmd.parseArgs(Nil),
      Right(DangerScanCmd.DangerScanArgs("HEAD", Nil))
    )
    assertEquals(
      DangerScanCmd.parseArgs(List("v1.2.3")),
      Right(DangerScanCmd.DangerScanArgs("v1.2.3", Nil))
    )
    assertEquals(
      DangerScanCmd.parseArgs(List("main", "--also", "a.scala", "b.scala")),
      Right(DangerScanCmd.DangerScanArgs("main", List("a.scala", "b.scala")))
    )

  // spec: danger-reconcile-engines — Scenario: Edge case — a second --also is still the tail switch
  test("--also is a tail switch; a second --also is consumed as the flag"):
    assertEquals(
      DangerScanCmd.parseArgs(List("--also", "a.scala", "--also", "b.scala")),
      Right(DangerScanCmd.DangerScanArgs("HEAD", List("a.scala", "b.scala")))
    )

  // ── Scenario: an unknown named parameter is rejected ────────────────
  // spec: danger-reconcile-engines — Scenario: Error path — an unknown named parameter is rejected naming the token
  test("a dash-led token before --also is rejected naming the token"):
    assertEquals(DangerScanCmd.parseArgs(List("--frobnicate")), Left("--frobnicate"))
    withTempDir("danger-scan-unknown") { (dir: Path) =>
      val (outcome, _, err) = captureRun(Array("--frobnicate"), dir)
      outcome match
        case Outcome.Finding(msg) =>
          assert(msg.contains("--frobnicate"), s"the token is named: $msg")
        case other => fail(s"an unknown parameter is Finding, got $other")
      assert(err.contains("--frobnicate"), s"stderr names the token: $err")
    }

  // ── Scenario: no production scope reports clean ─────────────────────
  // spec: danger-reconcile-engines — Scenario: Edge case — no changed production files reports clean with a stated scope
  test("no changed production files reports clean with a stated scope"):
    initRepo("danger-scan-empty") { (dir: Path) =>
      writeFile(dir, "pkg/src/test/scala/OnlySpec.scala", List("val x = opt.get"))
      stageAll(dir)
      val (outcome, out, _) = captureRun(Array(), dir)
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"empty production scope must be Ran(0), got $other")
      assert(
        out.contains("no production .scala files"),
        s"the clean run states its scope: $out"
      )
    }

  // ── Scenario: an unresolvable baseline is could-not-determine ───────
  // spec: danger-reconcile-engines — Scenario: Error path — an unresolvable baseline is could-not-determine
  test("an unresolvable baseline is undetermined"):
    initRepo("danger-scan-badbase") { (dir: Path) =>
      val (outcome, _, err) =
        captureRun(Array("nonexistent-ref-xyz"), dir)
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"an unresolvable baseline must be Undetermined, got $other")
      assert(err.contains("UNDETERMINED"), s"the undetermined marker is emitted: $err")
    }

  // ── Scenario: an unjustified occurrence is reported ─────────────────
  // spec: danger-reconcile-engines — Scenario: Happy path — an unjustified occurrence is reported
  test("a changed production file with an unjustified occurrence is a finding"):
    initRepo("danger-scan-hit") { (dir: Path) =>
      writeFile(dir, "pkg/src/main/scala/A.scala", List("val ok = 1", "  case _ => boom"))
      stageAll(dir)
      val (outcome, out, _) = captureRun(Array(), dir)
      outcome match
        case Outcome.Finding(_) => ()
        case other              => fail(s"an unjustified occurrence must be Finding, got $other")
      assert(
        out.contains("danger-scan: pkg/src/main/scala/A.scala") &&
          out.contains("[catch-all] 2:"),
        s"the predecessor's file/line/label shape is emitted: $out"
      )
    }

  test("a justified occurrence reports clean; --also names an in-scope extra"):
    initRepo("danger-scan-justified") { (dir: Path) =>
      writeFile(
        dir,
        "pkg/src/main/scala/A.scala",
        List("  case _ => boom // danger-scan:allow total-on-sealed")
      )
      stageAll(dir)
      val (clean, outClean, _) = captureRun(Array(), dir)
      clean match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a justified occurrence must be Ran(0), got $other")
      assert(outClean.contains("OK"), s"the clean verdict line is emitted: $outClean")

      // --also names a test-path file — in scope because the caller named it.
      writeFile(dir, "pkg/src/test/scala/B.scala", List("val y = xs.head"))
      val (hit, outHit, _) = captureRun(Array("--also", "pkg/src/test/scala/B.scala"), dir)
      hit match
        case Outcome.Finding(_) => ()
        case other              => fail(s"an --also occurrence must be Finding, got $other")
      assert(outHit.contains("[unsafe-head]"), s"the --also file is scanned: $outHit")
    }

  test("a nonexistent --also file is skipped, not an error"):
    initRepo("danger-scan-missing-also") { (dir: Path) =>
      val (outcome, out, _) =
        captureRun(Array("--also", "does/not/exist.scala"), dir)
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a skipped --also file keeps clean scope, got $other")
      assert(out.contains("no production .scala files"), s"the scope line is stated: $out")
    }

end DangerScanCmdSpec
