package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import LiveFactFixtures.withTempDir

/**
 * Test oracle for the `reconcile` subcommand surface (spec 6, Step 2):
 * the predecessor's strict flag parser, the unreadable/malformed-ledger
 * undetermined paths, the testimony/contradicted report shapes, and the
 * boundary that keeps discharge verdicts out of this tool's output.
 *
 * spec: danger-reconcile-engines — Scenario: Error path — an unreadable record set is could-not-determine
 * spec: danger-reconcile-engines — Property: no-discharge-verdict-in-output
 */
final class ReconcileCmdSpec extends ProbatioCliSuite:

  /** Run the subcommand with both streams captured. */
  private def captureRun(args: Array[String]): (Outcome[Int], String, String) =
    val (out: String, err: String, outcome: Outcome[Int]) =
      StdoutCapture.captureBoth(ReconcileCmd.run(args))
    (outcome, out, err)

  /** One JSONL ledger row; `extra` carries optional provenance fields. */
  private def ledgerJson(
    spec: String,
    ring: String,
    command: String,
    exit: Int,
    baseline: String,
    change: String = "chg",
    extra: String = ""
  ): String =
    s"{\"v\":1,\"ts\":\"2026-09-17T00:00:00Z\",\"change\":\"$change\"," +
      s"\"spec\":\"$spec\",\"ring\":\"$ring\",\"obligation\":\"obl\"," +
      s"\"artifact\":\"a.scala\",\"command\":\"$command\",\"exit\":$exit," +
      s"\"baseline\":\"$baseline\"$extra}"

  private def writeLedger(dir: Path, lines: List[String]): String =
    val p: Path = dir.resolve("ledger.jsonl")
    Files.writeString(
      p,
      lines.mkString("", "\n", if lines.isEmpty then "" else "\n"),
      StandardCharsets.UTF_8
    )
    p.toString

  // ── Strict parsing ──────────────────────────────────────────────────
  // spec: danger-reconcile-engines — Scenario: Error path — an unknown parameter is rejected
  test("an unknown flag is a finding naming the token"):
    val (outcome, _, err) = captureRun(Array("--bogus"))
    outcome match
      case Outcome.Finding(msg) =>
        assert(msg.contains("--bogus"), s"the token is named: $msg")
      case other => fail(s"an unknown flag must be Finding, got $other")
    assert(err.contains("--bogus"), s"stderr names the token: $err")

  test("--file and --change are required"):
    val (noFile, _, _) = captureRun(Array("--change", "c"))
    noFile match
      case Outcome.Finding(msg) => assert(msg.contains("--file"))
      case other                => fail(s"missing --file must be Finding, got $other")
    val (noChange, _, _) = captureRun(Array("--file", "x.jsonl"))
    noChange match
      case Outcome.Finding(msg) => assert(msg.contains("--change"))
      case other                => fail(s"missing --change must be Finding, got $other")

  test("--format rejects anything but json or text"):
    withTempDir("reconcile-format") { (dir: Path) =>
      val ledger: String =
        writeLedger(dir, List(ledgerJson("s", "R3", "c", 0, "b0b0b0b")))
      val (outcome, _, _) =
        captureRun(Array("--file", ledger, "--change", "chg", "--format", "xml"))
      outcome match
        case Outcome.Finding(msg) =>
          assert(msg.contains("json") && msg.contains("text"), s"the valid values are named: $msg")
        case other => fail(s"an invalid --format must be Finding, got $other")
    }

  // ── Scenario: an unreadable record set is could-not-determine ───────
  // spec: danger-reconcile-engines — Scenario: Error path — an unreadable record set is could-not-determine
  test("a nonexistent ledger is undetermined"):
    val (outcome, _, err) =
      captureRun(Array("--file", "/nonexistent-ledger-xyz.jsonl", "--change", "chg"))
    outcome match
      case Outcome.Undetermined(_) => ()
      case other                   => fail(s"a missing ledger must be Undetermined, got $other")
    assert(err.contains("UNDETERMINED"), s"the undetermined marker is emitted: $err")

  test("an empty ledger is undetermined, not clean"):
    withTempDir("reconcile-empty") { (dir: Path) =>
      val ledger: String  = writeLedger(dir, Nil)
      val (outcome, _, _) = captureRun(Array("--file", ledger, "--change", "chg"))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"an empty ledger must be Undetermined, got $other")
    }

  test("a malformed ledger line is undetermined — never silently dropped"):
    withTempDir("reconcile-corrupt") { (dir: Path) =>
      val ledger: String  = writeLedger(dir, List("{not json"))
      val (outcome, _, _) = captureRun(Array("--file", ledger, "--change", "chg"))
      outcome match
        case Outcome.Undetermined(_) => ()
        case other                   => fail(s"a malformed row must be Undetermined, got $other")
    }

  // ── Scenario: testimony is a finding; witnessed is clean ────────────
  // spec: danger-reconcile-engines — Scenario: Adversarial — a written green record with no observer is testimony
  test("a written green claim with no observer reports testimony and exits 1"):
    withTempDir("reconcile-testimony") { (dir: Path) =>
      val ledger: String =
        writeLedger(dir, List(ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b")))
      val (outcome, out, _) =
        captureRun(Array("--file", ledger, "--change", "chg"))
      outcome match
        case Outcome.Finding(_) => ()
        case other              => fail(s"testimony must be Finding, got $other")
      assert(
        out.contains("testimony (green claim, no witness at this baseline):") &&
          out.contains("s1/R3"),
        s"the testimony block names the claim: $out"
      )
      assert(
        out.contains("1 claim(s) needing corroboration") && out.contains("0 witnessed"),
        s"the counts header is emitted: $out"
      )
    }

  // spec: danger-reconcile-engines — Scenario: Happy path — a written record with a matching independent observation is witnessed
  test("a claim corroborated by a matching ambient record exits 0"):
    withTempDir("reconcile-witnessed") { (dir: Path) =>
      val ledger: String = writeLedger(
        dir,
        List(
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b"),
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b", extra = ",\"source\":\"ambient\"")
        )
      )
      val (outcome, out, _) =
        captureRun(Array("--file", ledger, "--change", "chg"))
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a witnessed claim must be Ran(0), got $other")
      assert(out.contains("1 witnessed"), s"the witness count is stated: $out")
      assert(!out.contains("testimony ("), s"no testimony block: $out")
    }

  // spec: danger-reconcile-engines — Scenario: Adversarial — an observer disagreeing with the written outcome is contradicted, reported separately
  test("a claim whose observers disagree reports contradicted and exits 1"):
    withTempDir("reconcile-contradicted") { (dir: Path) =>
      val ledger: String = writeLedger(
        dir,
        List(
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b"),
          ledgerJson("s1", "R3", "sbt test", 1, "b1b1b1b", extra = ",\"source\":\"ambient\"")
        )
      )
      val (outcome, out, _) =
        captureRun(Array("--file", ledger, "--change", "chg"))
      outcome match
        case Outcome.Finding(_) => ()
        case other              => fail(s"a contradiction must be Finding, got $other")
      assert(
        out.contains("contradicted (a witness recorded a different exit):") &&
          out.contains("claimed 0, observed 1"),
        s"the contradicted block reports the observed exits: $out"
      )
    }

  // ── --spec / --baseline narrow the scope ────────────────────────────
  test("--spec narrows the record set before classification"):
    withTempDir("reconcile-spec") { (dir: Path) =>
      val ledger: String = writeLedger(
        dir,
        List(
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b"),
          ledgerJson("s2", "R3", "sbt test", 0, "b1b1b1b")
        )
      )
      val (outcome, out, _) =
        captureRun(Array("--file", ledger, "--change", "chg", "--spec", "s2"))
      outcome match
        case Outcome.Finding(_) => ()
        case other              => fail(s"the narrowed testimony must be Finding, got $other")
      assert(
        out.contains("1 row(s)") && out.contains("s2/R3") && !out.contains("s1/R3"),
        s"--spec narrows before classification: $out"
      )
    }

  // ── Property: no discharge verdict in rendered output ───────────────
  // spec: danger-reconcile-engines — Property: no-discharge-verdict-in-output
  test("neither text nor JSON output contains a discharge verdict"):
    withTempDir("reconcile-nodischarge") { (dir: Path) =>
      val ledger: String = writeLedger(
        dir,
        List(
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b"),
          ledgerJson("s2", "R3", "sbt test", 0, "b1b1b1b", extra = ",\"source\":\"ambient\"")
        )
      )
      val (_, textOut, _) =
        captureRun(Array("--file", ledger, "--change", "chg"))
      val (_, jsonOut, _) =
        captureRun(Array("--file", ledger, "--change", "chg", "--format", "json"))
      assert(
        !textOut.contains("discharg") && !jsonOut.contains("discharg"),
        s"reconcile never reports discharge — chain-state owns that\ntext: $textOut\njson: $jsonOut"
      )
    }

  // ── JSON output shape ───────────────────────────────────────────────
  test("--format json emits the predecessor's compact object with witnessed as a count"):
    withTempDir("reconcile-json") { (dir: Path) =>
      val ledger: String = writeLedger(
        dir,
        List(
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b"),
          ledgerJson("s1", "R3", "sbt test", 0, "b1b1b1b", extra = ",\"source\":\"ambient\"")
        )
      )
      val (outcome, out, _) =
        captureRun(Array("--file", ledger, "--change", "chg", "--format", "json"))
      outcome match
        case Outcome.Ran(code) => assertEquals(code, 0)
        case other             => fail(s"a witnessed claim must be Ran(0), got $other")
      val json: ujson.Value = ujson.read(out.trim)
      assertEquals(json("change").str, "chg")
      assertEquals(json("rows").num.toInt, 2)
      assertEquals(json("witnesses").num.toInt, 1)
      assertEquals(json("claims").num.toInt, 1)
      assertEquals(json("witnessed").num.toInt, 1)
      assertEquals(json("testimony").arr.toList, Nil)
      assertEquals(json("contradicted").arr.toList, Nil)
    }

end ReconcileCmdSpec
