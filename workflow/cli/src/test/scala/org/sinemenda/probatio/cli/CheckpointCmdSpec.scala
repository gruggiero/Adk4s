package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path


/**
 * Oracle for the `checkpoint` subcommand's boundary behavior (spec 7).
 *
 * Written from the spec and the approved Step-1 contract ONLY — before the
 * `???` implementations land. Derived from the spec's requirements and
 * scenarios, NOT from the implementation.
 *
 * The report operation consumes the supplied correctness verdict verbatim
 * and delegates row filtering to the record tool's own read path; the
 * task-regeneration operation rewrites checkbox state from the progress
 * tracker, preserving every other byte.
 *
 * spec: ledger-checkpoint-parity — Requirement: The record and checkpoint tools accept the predecessor's operation and parameter set
 * spec: ledger-checkpoint-parity — Scenario: Error path — a requested ring outside the known set is rejected naming it
 * spec: ledger-checkpoint-parity — Scenario: Error path — a verdict that does not parse is could-not-determine
 */
final class CheckpointCmdSpec extends ProbatioCliSuite:

  // ── Fixture helpers ─────────────────────────────────────────────────

  private def ledgerRow(ring: String, baseline: String, exit: Int = 0, session: Option[String] = None): ujson.Obj =
    val row: ujson.Obj = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
      "change"     -> ujson.Str("chg"),
      "spec"       -> ujson.Str("spc"),
      "ring"       -> ujson.Str(ring),
      "obligation" -> ujson.Str("obligation"),
      "artifact"   -> ujson.Str("artifact"),
      "command"    -> ujson.Str("true"),
      "exit"       -> ujson.Num(exit),
      "baseline"   -> ujson.Str(baseline)
    )
    session.foreach((s: String) => row.value("session") = ujson.Str(s))
    row

  private def writeLedger(dir: Path, rows: List[ujson.Value]): Path =
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(file, rows.map(ujson.write(_)).mkString("\n").getBytes(StandardCharsets.UTF_8))
    file

  private def writeChainState(dir: Path, content: String): Path =
    val file: Path = dir.resolve("chain-state.json")
    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
    file

  private val dischargedVerdict: String =
    """{"change":"chg","baseline":"abc1234","total":2,"bound":2,"resolved":2,"discharged":2,"unresolved":[],"unmapped_obligations":[]}"""

  private def reportArgs(ledger: Path, chainState: Path, extra: List[String]): Array[String] =
    (List(
      "report",
      "--ledger",
      ledger.toString,
      "--change",
      "chg",
      "--spec",
      "spc",
      "--baseline",
      "abc1234",
      "--rings",
      "R0",
      "--chain-state-json",
      chainState.toString
    ) ++ extra).toArray

  // Declared before the tests: the class-initialisation checker requires
  // every field a test closure could touch to be already initialised.
  private val progressCommitted: String =
    """### 1. alpha
      |
      || Field | Value |
      ||-------|-------|
      || Commit | `abc1234` |
      |
      |### 2. beta
      |
      || Field | Value |
      ||-------|-------|
      || Commit | _(pending)_ |
      |""".stripMargin

  private val tasksInitial: String =
    """## 1. alpha
      |
      |- [ ] Step 0 — do a thing
      |- [ ] Step 1 — do another thing
      |
      |## 2. beta
      |
      |- [ ] Step 0 — do a thing
      |""".stripMargin

  // ── Scenario: Error path — a requested ring outside the known set is
  //    rejected naming it ───────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Error path — a requested ring outside the known set is rejected naming it

  test("a ring name outside the known set is rejected naming it — never reported unevidenced"):
    val dir: Path        = Files.createTempDirectory("ckpt-unknown-ring")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (_, err, outcome) = runCheckpoint(
      reportArgs(ledger, chainState, Nil).updated(
        reportArgs(ledger, chainState, Nil).indexOf("R0"),
        "R42"
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        assert(msg.contains("R42"), s"the rejection must name the unrecognised ring: $msg")
        assertEquals(
          err,
          "checkpoint: unrecognised ring in --rings: R42 (known: R0 R1 R2 R3 R4 R5 R6 R7 R8 R9 manual)\n"
        )
      case Outcome.Ran(n)               => fail(s"an unknown ring must not produce a report, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unknown ring is a finding, not undetermined: $reason")

  // ── Ring-8 finding: a delimiter-only --rings must not produce a
  //    vacuous all-green marker ────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Error path — a requested ring outside the known set is rejected naming it

  test("a --rings value of only delimiters is rejected, never a vacuous marker"):
    val dir: Path        = Files.createTempDirectory("ckpt-empty-rings")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    List(",", "R0,", ",R0", ",,").foreach { (ringsArg: String) =>
      val outcome: Outcome[Int] = CheckpointCmd.run(
        reportArgs(ledger, chainState, Nil).updated(
          reportArgs(ledger, chainState, Nil).indexOf("R0"),
          ringsArg
        )
      )
      outcome match
        case Outcome.Finding(msg) =>
          assert(
            msg.contains("unrecognised ring"),
            s"'--rings $ringsArg' must be rejected by name, got: $msg"
          )
        case Outcome.Ran(n) =>
          fail(s"'--rings $ringsArg' produced a checkpoint (Ran($n)) — a vacuous marker over zero rings")
        case Outcome.Undetermined(reason) =>
          fail(s"'--rings $ringsArg' is a finding, not undetermined: $reason")
    }

  // ── Scenario: Error path — a verdict that does not parse is
  //    could-not-determine ──────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Error path — a verdict that does not parse is could-not-determine

  test("a chain-state input that does not parse is undetermined, exit 2"):
    val dir: Path             = Files.createTempDirectory("ckpt-bad-cs")
    val ledger: Path          = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path      = writeChainState(dir, "not json")
    val outcome: Outcome[Int] = CheckpointCmd.run(reportArgs(ledger, chainState, Nil))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("does not parse or is undetermined"),
          s"the outcome carries the verdict reason: $reason"
        )
      case Outcome.Ran(n)       => fail(s"an unparseable verdict must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unparseable verdict must be undetermined, not a finding: $msg")

  test("a chain-state verdict that is itself undetermined is undetermined, not a clean zero"):
    val dir: Path    = Files.createTempDirectory("ckpt-undet-cs")
    val ledger: Path = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    // chain-state.sh's genuine undetermined shape: total:null + undetermined:true
    // alongside an EMPTY unresolved array — reading it as "unresolved 0" is
    // the defect the predecessor's two-signal check exists to kill.
    val chainState: Path = writeChainState(
      dir,
      """{"change":"chg","baseline":"abc1234","undetermined":true,"reason":"stub","total":null,"bound":null,"resolved":null,"discharged":null,"unresolved":[],"unmapped_obligations":[]}"""
    )
    val outcome: Outcome[Int] = CheckpointCmd.run(reportArgs(ledger, chainState, Nil))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("does not parse or is undetermined"),
          s"the outcome carries the verdict reason: $reason"
        )
      case Outcome.Ran(n)       => fail(s"an undetermined verdict must not report ready, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an undetermined verdict is undetermined, not a finding: $msg")

  test("an absent chain-state file is undetermined"):
    val dir: Path    = Files.createTempDirectory("ckpt-no-cs")
    val ledger: Path = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val outcome: Outcome[Int] = CheckpointCmd.run(
      reportArgs(ledger, dir.resolve("does-not-exist.json"), Nil)
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("not found or not readable"), s"the outcome carries the reason: $reason")
      case Outcome.Ran(n)       => fail(s"an absent verdict file must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent verdict file is undetermined, not a finding: $msg")

  test("an absent ledger is undetermined"):
    val dir: Path        = Files.createTempDirectory("ckpt-no-ledger")
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val outcome: Outcome[Int] = CheckpointCmd.run(
      reportArgs(dir.resolve("does-not-exist.jsonl"), chainState, Nil)
    )
    outcome match
      case Outcome.Undetermined(_) => ()
      case Outcome.Ran(n)          => fail(s"an absent ledger must be undetermined, got Ran($n)")
      case Outcome.Finding(msg)    => fail(s"an absent ledger is undetermined, not a finding: $msg")

  // ── Scenario: Happy path — the report operation is accepted ─────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — the report operation is accepted

  test("report with predecessor parameters produces the generated report"):
    val dir: Path        = Files.createTempDirectory("ckpt-report")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (out, _, outcome) = StdoutCapture.captureBoth(
      CheckpointCmd.run(reportArgs(ledger, chainState, Nil))
    )
    outcome match
      case Outcome.Ran(0) =>
        val report: ujson.Value = ujson.read(out)
        assertEquals(report("change").str, "chg")
        assertEquals(report("spec").str, "spc")
        assertEquals(report("baseline").str, "abc1234")
        assertEquals(report("rings").arr.length, 1, "one requested ring reported once")
        assertEquals(report("rings")(0)("status").str, "green")
        // The supplied verdict is carried verbatim — never recomputed.
        assertEquals(report("chain_state")("total").num.toInt, 2)
      case Outcome.Ran(n)               => fail(s"report should exit 0, got Ran($n); stdout: $out")
      case Outcome.Finding(msg)         => fail(s"report must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"report must not be undetermined: $reason")

  // ── Scenario: Happy path — the task-regeneration operation is
  //    accepted ─────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — the task-regeneration operation is accepted

  test("regenerate-tasks --write rewrites checkbox state from the tracker, preserving all other text"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    val outcome: Outcome[Int] = CheckpointCmd.run(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString, "--write")
    )
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"regenerate-tasks must exit 0, got $n")
      case Outcome.Finding(msg)         => fail(s"regenerate-tasks must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"regenerate-tasks must not be undetermined: $reason")
    val result: String = Files.readString(tasks, StandardCharsets.UTF_8)
    assert(result.contains("- [x] Step 0 — do a thing"), s"alpha is committed — its boxes are checked:\n$result")
    assert(result.contains("- [x] Step 1 — do another thing"), s"all of alpha's boxes are checked:\n$result")
    val betaSection: String = result.substring(result.indexOf("## 2. beta"))
    assert(betaSection.contains("- [ ] Step 0 — do a thing"), s"beta is pending — its box stays unchecked:\n$result")
    // Every non-checkbox line is preserved verbatim.
    assert(result.contains("## 1. alpha"), "section headers preserved")
    assert(result.contains("Step 0 — do a thing"), "task text preserved")

  test("regenerate-tasks without --write reports whether the tasks already match"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-dry")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    // alpha committed but its boxes unchecked — a stale tasks file must
    // be reported (exit 1) WITHOUT being rewritten.
    val outcome: Outcome[Int] = CheckpointCmd.run(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString)
    )
    outcome match
      case Outcome.Ran(0)               => fail("a stale tasks file must not report already-matching")
      case Outcome.Ran(_)               => ()
      case Outcome.Finding(_)           => ()
      case Outcome.Undetermined(reason) => fail(s"a stale tasks file is a finding, not undetermined: $reason")
    assertEquals(
      Files.readString(tasks, StandardCharsets.UTF_8),
      tasksInitial,
      "without --write the tasks file is untouched"
    )

  test("regenerate-tasks is idempotent — a second --write changes nothing"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-idem")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    val args: Array[String] =
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString, "--write")
    CheckpointCmd.run(args)
    val once: String = Files.readString(tasks, StandardCharsets.UTF_8)
    CheckpointCmd.run(args)
    val twice: String = Files.readString(tasks, StandardCharsets.UTF_8)
    assertEquals(twice, once, "regeneration is a fixed point")

  test("spec numbers sharing a digit suffix do not collide (1 vs 21)"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-suffix")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(
      progress,
      """### 21. twentyone
        |
        || Field | Value |
        ||-------|-------|
        || Commit | `deadbee` |
        |
        |### 1. alpha
        |
        || Field | Value |
        ||-------|-------|
        || Commit | _(pending)_ |
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    Files.write(
      tasks,
      """## 21. twentyone
        |
        |- [ ] Step 0 — do a thing
        |
        |## 1. alpha
        |
        |- [ ] Step 0 — do a thing
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    CheckpointCmd.run(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString, "--write")
    )
    val result: String = Files.readString(tasks, StandardCharsets.UTF_8)
    val s21: String    = result.substring(0, result.indexOf("## 1. alpha"))
    val s1: String     = result.substring(result.indexOf("## 1. alpha"))
    assert(s21.contains("- [x]"), s"spec 21 committed — checked:\n$s21")
    assert(s1.contains("- [ ]"), s"spec 1 pending — unchecked; its number is a substring of 21's:\n$s1")

  // ── Ring 5 surface: the checkpoint subcommand's exact diagnostics,
  //    emitted report, and marker side effect are asserted ──────────────
  // spec: ledger-checkpoint-parity — Requirement: The record and checkpoint tools accept the predecessor's operation and parameter set
  // spec: ledger-checkpoint-parity — Requirement: The presentation marker is written only when every requested ring is evidenced and the verdict is fully discharged

  private def runCheckpoint(args: Array[String]): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(CheckpointCmd.run(args))

  private def gitInit(dir: Path): Unit =
    assertEquals(
      HermeticEnv.run(List("git", "-C", dir.toString, "init"), HermeticEnv.empty),
      0,
      "git init must succeed"
    )

  test("checkpoint with no subcommand is rejected naming '<none>'"):
    val (_, err, outcome) = runCheckpoint(Array.empty[String])
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "checkpoint: unknown subcommand: '<none>'. Expected: report, regenerate-tasks.\n")
        assertEquals(msg, "unknown subcommand")
      case Outcome.Ran(n)               => fail(s"a missing subcommand must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing subcommand is a finding, not undetermined: $reason")

  test("report names the first missing required flag, in contract order"):
    val dir: Path        = Files.createTempDirectory("ckpt-req-flags")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    // Each progressively fuller invocation names the next missing flag.
    val flagOrder: List[String] =
      List("--ledger", "--change", "--spec", "--baseline", "--rings", "--chain-state-json")
    val flagValues: Map[String, String] = Map(
      "--ledger"           -> ledger.toString,
      "--change"           -> "chg",
      "--spec"             -> "spc",
      "--baseline"         -> "abc1234",
      "--rings"            -> "R0",
      "--chain-state-json" -> chainState.toString
    )
    flagOrder.zipWithIndex.foreach { case (expected: String, supplied: Int) =>
      val args: Array[String] =
        ("report" +: flagOrder.take(supplied).flatMap((f: String) => List(f, flagValues(f)))).toArray
      val (_, err, outcome) = runCheckpoint(args)
      outcome match
        case Outcome.Finding(msg) =>
          assertEquals(err, s"checkpoint: $expected is required\n")
          assertEquals(msg, s"$expected is required")
        case Outcome.Ran(n) =>
          fail(s"with only $supplied flag(s) the report must not succeed, got Ran($n)")
        case Outcome.Undetermined(reason) =>
          fail(s"a missing flag is a finding, not undetermined: $reason")
    }

  test("report rejects an unrecognised argument naming it"):
    val dir: Path         = Files.createTempDirectory("ckpt-badarg")
    val ledger: Path      = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path  = writeChainState(dir, dischargedVerdict)
    val (_, err, outcome) = runCheckpoint(reportArgs(ledger, chainState, List("--bogus", "x")))
    outcome match
      case Outcome.Finding(_) =>
        assertEquals(err, "checkpoint: unrecognised argument: --bogus\n")
      case Outcome.Ran(n)               => fail(s"an unrecognised argument must not produce a report, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unrecognised argument is a finding, not undetermined: $reason")

  test("report rejects a --format outside json/text"):
    val dir: Path        = Files.createTempDirectory("ckpt-badfmt")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    List("xml", "JSON", "").foreach { (fmt: String) =>
      val (_, err, outcome) = runCheckpoint(reportArgs(ledger, chainState, List("--format", fmt)))
      outcome match
        case Outcome.Finding(msg) =>
          assertEquals(err, "checkpoint: --format must be json or text\n")
          assertEquals(msg, "--format must be json or text")
        case Outcome.Ran(n) =>
          fail(s"--format '$fmt' must not produce a report, got Ran($n)")
        case Outcome.Undetermined(reason) =>
          fail(s"--format '$fmt' is a finding, not undetermined: $reason")
    }

  test("report --format text emits the predecessor's text report"):
    val dir: Path         = Files.createTempDirectory("ckpt-txtfmt")
    val ledger: Path      = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path  = writeChainState(dir, dischargedVerdict)
    val (out, _, outcome) = runCheckpoint(reportArgs(ledger, chainState, List("--format", "text")))
    outcome match
      case Outcome.Ran(0) =>
        assertEquals(
          out,
          "checkpoint: chg/spc @ abc1234\n" +
            "  R0: green (true)\n" +
            "  chain state: total 2  bound 2  resolved 2  discharged 2  unresolved 0\n"
        )
      case Outcome.Ran(n)               => fail(s"the text report must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the text report must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the text report must not be undetermined: $reason")

  test("report on an absent chain-state names the file on stderr"):
    val dir: Path         = Files.createTempDirectory("ckpt-cs-absent-msg")
    val ledger: Path      = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val missing: Path     = dir.resolve("absent.json")
    val (_, err, outcome) = runCheckpoint(reportArgs(ledger, missing, Nil))
    outcome match
      case Outcome.Undetermined(_) =>
        assertEquals(
          err,
          s"checkpoint: UNDETERMINED — chain-state JSON not found or not readable at $missing\n"
        )
      case Outcome.Ran(n)       => fail(s"an absent verdict must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent verdict is undetermined, not a finding: $msg")

  test("report on an unparseable chain-state names the file and the reason"):
    val dir: Path         = Files.createTempDirectory("ckpt-cs-badjson-msg")
    val ledger: Path      = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path  = writeChainState(dir, "not json")
    val (_, err, outcome) = runCheckpoint(reportArgs(ledger, chainState, Nil))
    outcome match
      case Outcome.Undetermined(_) =>
        assertEquals(
          err,
          s"checkpoint: UNDETERMINED — chain-state JSON at $chainState does not parse, has no numeric total, or is itself undetermined\n"
        )
      case Outcome.Ran(n)       => fail(s"an unparseable verdict must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unparseable verdict is undetermined, not a finding: $msg")

  test("report on a chain-state with a non-numeric total is undetermined, not a clean zero"):
    val dir: Path    = Files.createTempDirectory("ckpt-cs-strtotal")
    val ledger: Path = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(
      dir,
      """{"total":"two","bound":2,"resolved":2,"discharged":2,"unresolved":[]}"""
    )
    val (_, err, outcome) = runCheckpoint(reportArgs(ledger, chainState, Nil))
    outcome match
      case Outcome.Undetermined(_) =>
        assert(err.contains("no numeric total"), s"the refusal names the malformed total: $err")
      case Outcome.Ran(n)       => fail(s"a string total must not report ready, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a string total is undetermined, not a finding: $msg")

  test("a numeric-total verdict carrying undetermined:true is undetermined"):
    val dir: Path    = Files.createTempDirectory("ckpt-cs-undet-only")
    val ledger: Path = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(
      dir,
      """{"total":2,"bound":2,"resolved":2,"discharged":2,"unresolved":[],"undetermined":true}"""
    )
    val outcome: Outcome[Int] = runCheckpoint(reportArgs(ledger, chainState, Nil))._3
    outcome match
      case Outcome.Undetermined(_) => ()
      case Outcome.Ran(n)          => fail(s"an undetermined verdict must not report ready, got Ran($n)")
      case Outcome.Finding(msg)    => fail(s"an undetermined verdict is undetermined, not a finding: $msg")

  test("a verdict whose undetermined member is null or false is NOT treated as undetermined"):
    val dir: Path    = Files.createTempDirectory("ckpt-cs-undet-falsy")
    val ledger: Path = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    List("null", "false").foreach { (flag: String) =>
      val chainState: Path = writeChainState(
        dir,
        s"""{"total":2,"bound":2,"resolved":2,"discharged":2,"unresolved":[],"undetermined":$flag}"""
      )
      val outcome: Outcome[Int] = runCheckpoint(reportArgs(ledger, chainState, Nil))._3
      outcome match
        case Outcome.Ran(0) => ()
        case Outcome.Ran(n) =>
          fail(s"undetermined:$flag is falsy — the report must run, got Ran($n)")
        case Outcome.Finding(msg) =>
          fail(s"undetermined:$flag is falsy — the report must run, got Finding: $msg")
        case Outcome.Undetermined(reason) =>
          fail(s"undetermined:$flag is falsy — must not be undetermined: $reason")
    }

  test("report on a ledger path that is a directory is undetermined naming the read failure"):
    val dir: Path         = Files.createTempDirectory("ckpt-ledger-dir")
    val chainState: Path  = writeChainState(dir, dischargedVerdict)
    val (_, err, outcome) = runCheckpoint(reportArgs(dir, chainState, Nil))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("ledger read failed"), s"the failure is attributed to the read: $err")
        assert(reason.contains("ledger read failed"), s"the outcome carries the reason: $reason")
      case Outcome.Ran(n)       => fail(s"an unreadable ledger must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unreadable ledger is undetermined, not a finding: $msg")

  test("report on a ledger holding an invalid row is undetermined naming the clause"):
    val dir: Path      = Files.createTempDirectory("ckpt-ledger-invalid")
    val bad: ujson.Obj = ledgerRow("R0", "abc1234")
    bad.value("ring") = ujson.Str("R42")
    val ledger: Path      = writeLedger(dir, List(bad))
    val chainState: Path  = writeChainState(dir, dischargedVerdict)
    val (_, err, outcome) = runCheckpoint(reportArgs(ledger, chainState, Nil))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("ledger read failed"), s"the failure is attributed to the read: $err")
        assert(err.contains("violates the record contract"), s"the violation is named: $err")
        assert(err.contains("clause 6"), s"the violated clause is named: $err")
        assert(reason.contains("ledger read failed"), s"the outcome carries the reason: $reason")
      case Outcome.Ran(n)       => fail(s"an invalid row must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an invalid row is undetermined, not a finding: $msg")

  test("--change-dir supplies the per-spec baseline, traced on stderr and in the report"):
    val dir: Path       = Files.createTempDirectory("ckpt-perspec")
    val changeDir: Path = Files.createTempDirectory("ckpt-perspec-change")
    Files.write(
      changeDir.resolve("implementation-progress.md"),
      """## Spec 7/13: ledger-checkpoint-parity — spc
        |
        |**BASELINE SHA**: `deadbee`
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "deadbee")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (out, err, outcome) = runCheckpoint(
      reportArgs(ledger, chainState, List("--change-dir", changeDir.toString))
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(
          err.contains("using per-spec baseline deadbee for spec spc"),
          s"the per-spec baseline is traced: $err"
        )
        assertEquals(ujson.read(out)("baseline").str, "deadbee")
      case Outcome.Ran(n)               => fail(s"the report must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the report must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the report must not be undetermined: $reason")

  test("--change-dir without a per-spec baseline falls back to the gate baseline, traced"):
    val dir: Path       = Files.createTempDirectory("ckpt-noperspec")
    val changeDir: Path = Files.createTempDirectory("ckpt-noperspec-change")
    Files.write(
      changeDir.resolve("implementation-progress.md"),
      "## Spec 7/13: other-change\n\n**BASELINE SHA**: `deadbee`\n".getBytes(StandardCharsets.UTF_8)
    )
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (out, err, outcome) = runCheckpoint(
      reportArgs(ledger, chainState, List("--change-dir", changeDir.toString))
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(
          err.contains("no per-spec baseline for spec spc"),
          s"the fallback is traced: $err"
        )
        assertEquals(ujson.read(out)("baseline").str, "abc1234")
      case Outcome.Ran(n)               => fail(s"the report must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the report must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the report must not be undetermined: $reason")

  test("a fully evidenced, discharged report writes the session-keyed marker"):
    val dir: Path       = Files.createTempDirectory("ckpt-marker")
    val changeDir: Path = Files.createTempDirectory("ckpt-marker-change")
    gitInit(changeDir)
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (out, _, outcome) = runCheckpoint(
      reportArgs(
        ledger,
        chainState,
        List("--change-dir", changeDir.toString, "--session", "sess-7")
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        val marker: Path =
          changeDir.resolve(".git/verified-scala3-gate/checkpoint-output-chg-spc-sess-7")
        assert(Files.isRegularFile(marker), s"the marker must be written at $marker")
        assertEquals(
          Files.readString(marker, StandardCharsets.UTF_8),
          out,
          "the marker tees the emitted report verbatim"
        )
      case Outcome.Ran(n)               => fail(s"a clean checkpoint must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a clean checkpoint must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"a clean checkpoint must not be undetermined: $reason")

  test("a not-clean report is a finding — and writes no marker"):
    val dir: Path       = Files.createTempDirectory("ckpt-notclean")
    val changeDir: Path = Files.createTempDirectory("ckpt-notclean-change")
    gitInit(changeDir)
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R1", "abc1234"))) // R0 unevidenced
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val (out, _, outcome) = runCheckpoint(
      reportArgs(
        ledger,
        chainState,
        List("--change-dir", changeDir.toString, "--session", "sess-7")
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        assert(
          msg.contains("checkpoint not clean"),
          s"the refusal names the not-clean verdict: $msg"
        )
        val marker: Path =
          changeDir.resolve(".git/verified-scala3-gate/checkpoint-output-chg-spc-sess-7")
        assert(!Files.exists(marker), s"no marker for a not-clean report: $marker")
      case Outcome.Ran(n) =>
        fail(s"an unevidenced ring must not report clean, got Ran($n); stdout: $out")
      case Outcome.Undetermined(reason) =>
        fail(s"an unevidenced ring is a finding, not undetermined: $reason")

  test("a clean report without --session writes no marker"):
    val dir: Path       = Files.createTempDirectory("ckpt-nosession")
    val changeDir: Path = Files.createTempDirectory("ckpt-nosession-change")
    gitInit(changeDir)
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val outcome: Outcome[Int] = runCheckpoint(
      reportArgs(ledger, chainState, List("--change-dir", changeDir.toString))
    )._3
    outcome match
      case Outcome.Ran(0) =>
        val stateDir: Path = changeDir.resolve(".git/verified-scala3-gate")
        assert(
          !Files.isDirectory(stateDir) ||
            !Files.list(stateDir).iterator().hasNext,
          "no session keys the marker — nothing is written"
        )
      case Outcome.Ran(n)               => fail(s"a clean checkpoint must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a clean checkpoint must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"a clean checkpoint must not be undetermined: $reason")

  test("a clean report without --change-dir writes no marker and still exits 0"):
    val dir: Path        = Files.createTempDirectory("ckpt-nochangedir")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val outcome: Outcome[Int] = runCheckpoint(
      reportArgs(ledger, chainState, List("--session", "sess-7"))
    )._3
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"a clean checkpoint must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a clean checkpoint must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"a clean checkpoint must not be undetermined: $reason")

  test("regenerate-tasks names the missing --progress and --tasks flags"):
    val (_, err1, out1) = runCheckpoint(Array("regenerate-tasks"))
    out1 match
      case Outcome.Finding(msg) =>
        assertEquals(err1, "checkpoint: --progress is required\n")
        assertEquals(msg, "--progress is required")
      case other => fail(s"missing --progress must be a finding, got $other")
    val dir: Path      = Files.createTempDirectory("ckpt-regen-req")
    val progress: Path = dir.resolve("implementation-progress.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    val (_, err2, out2) = runCheckpoint(
      Array("regenerate-tasks", "--progress", progress.toString)
    )
    out2 match
      case Outcome.Finding(msg) =>
        assertEquals(err2, "checkpoint: --tasks is required\n")
        assertEquals(msg, "--tasks is required")
      case other => fail(s"missing --tasks must be a finding, got $other")

  test("regenerate-tasks rejects an unrecognised argument"):
    val (_, err, outcome) = runCheckpoint(Array("regenerate-tasks", "--bogus"))
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "checkpoint: unrecognised argument: --bogus\n")
        assert(msg.contains("arg parse error"), s"the finding names the parse error: $msg")
      case Outcome.Ran(n)               => fail(s"an unrecognised argument must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unrecognised argument is a finding, not undetermined: $reason")

  test("regenerate-tasks on an absent progress tracker is undetermined naming it"):
    val dir: Path   = Files.createTempDirectory("ckpt-regen-noprogress")
    val tasks: Path = dir.resolve("tasks.md")
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    val (_, err, outcome) = runCheckpoint(
      Array(
        "regenerate-tasks",
        "--progress",
        dir.resolve("absent.md").toString,
        "--tasks",
        tasks.toString
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("progress tracker"), s"the unreadable tracker is named: $err")
        assert(reason.contains("progress tracker"), s"the outcome names the tracker: $reason")
        assert(reason.contains("not found at"), s"the outcome carries the read failure: $reason")
      case Outcome.Ran(n)       => fail(s"an absent tracker must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent tracker is undetermined, not a finding: $msg")

  test("regenerate-tasks on an absent tasks file is undetermined naming it"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-notasks")
    val progress: Path = dir.resolve("implementation-progress.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    val (_, err, outcome) = runCheckpoint(
      Array(
        "regenerate-tasks",
        "--progress",
        progress.toString,
        "--tasks",
        dir.resolve("absent.md").toString
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("tasks file"), s"the unreadable tasks file is named: $err")
        assert(reason.contains("tasks file"), s"the outcome names the tasks file: $reason")
        assert(reason.contains("not found at"), s"the outcome carries the read failure: $reason")
      case Outcome.Ran(n)       => fail(s"an absent tasks file must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent tasks file is undetermined, not a finding: $msg")

  test("regenerate-tasks dry-run on already-matching tasks exits 0 printing the content"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-fresh")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    // Regenerate once to obtain the canonical content, then dry-run against it.
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    CheckpointCmd.run(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString, "--write")
    )
    val canonical: String = Files.readString(tasks, StandardCharsets.UTF_8)
    val (out, _, outcome) = runCheckpoint(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString)
    )
    outcome match
      case Outcome.Ran(0) =>
        assertEquals(out, canonical, "the dry run prints the regenerated content")
      case Outcome.Ran(n)               => fail(s"a matching tasks file must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a matching tasks file must not be stale: $msg")
      case Outcome.Undetermined(reason) => fail(s"a matching tasks file must not be undetermined: $reason")

  test("regenerate-tasks dry-run on stale tasks reports staleness and prints the regeneration"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-stale")
    val progress: Path = dir.resolve("implementation-progress.md")
    val tasks: Path    = dir.resolve("tasks.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    val (out, _, outcome) = runCheckpoint(
      Array("regenerate-tasks", "--progress", progress.toString, "--tasks", tasks.toString)
    )
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(msg, "tasks.md is stale — regenerate with --write")
        assert(out.contains("- [x] Step 0 — do a thing"), s"the dry run prints the would-be content:\n$out")
      case Outcome.Ran(0)               => fail("a stale tasks file must not report matching")
      case Outcome.Ran(n)               => fail(s"a stale tasks file is a finding, not Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a stale tasks file is a finding, not undetermined: $reason")

  test("regenerate-tasks --write to an unwritable tasks file is undetermined"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-nowrite")
    val progress: Path = dir.resolve("implementation-progress.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    val missingTasksParent: Path = dir.resolve("no-such-dir").resolve("tasks.md")
    val (_, err, outcome) = runCheckpoint(
      Array(
        "regenerate-tasks",
        "--progress",
        progress.toString,
        "--tasks",
        missingTasksParent.toString,
        "--write"
      )
    )
    outcome match
      case Outcome.Undetermined(_) =>
        // The tasks file itself cannot be read — the read failure is named
        // before any write is attempted.
        assert(err.contains("tasks file"), s"the failure is named: $err")
      case Outcome.Ran(n)       => fail(s"an unwritable tasks file must not succeed, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unwritable tasks file is undetermined, not a finding: $msg")

  // ── Ring 5 second pass — the file-kind/readability guard's own
  //    reasons are asserted, not only the undetermined outcome ─────────

  test("regenerate-tasks on a progress path that is a directory reports 'not found', not a crash"):
    val dir: Path   = Files.createTempDirectory("ckpt-regen-progressdir")
    val tasks: Path = dir.resolve("tasks.md")
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    val progressDir: Path = dir.resolve("a-directory")
    Files.createDirectories(progressDir)
    val (_, _, outcome) = runCheckpoint(
      Array(
        "regenerate-tasks",
        "--progress",
        progressDir.toString,
        "--tasks",
        tasks.toString
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        // A directory is not a regular file — the reason names 'not found',
        // never the exception text a skipped guard would surface.
        assert(reason.contains("not found at"), s"the file-kind failure is named: $reason")
      case Outcome.Ran(n)       => fail(s"a directory tracker must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a directory tracker is undetermined, not a finding: $msg")

  test("regenerate-tasks on an unreadable tasks file reports 'not readable'"):
    val dir: Path      = Files.createTempDirectory("ckpt-regen-tasksunreadable")
    val progress: Path = dir.resolve("implementation-progress.md")
    Files.write(progress, progressCommitted.getBytes(StandardCharsets.UTF_8))
    val tasks: Path = dir.resolve("tasks.md")
    Files.write(tasks, tasksInitial.getBytes(StandardCharsets.UTF_8))
    assume(tasks.toFile.setReadable(false), "the test platform must let us revoke read permission")
    assume(!Files.isReadable(tasks), "the file must actually be unreadable (skipped when running as root)")
    val (_, _, outcome) = runCheckpoint(
      Array(
        "regenerate-tasks",
        "--progress",
        progress.toString,
        "--tasks",
        tasks.toString
      )
    )
    tasks.toFile.setReadable(true)
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("not readable at"), s"the readability failure is named: $reason")
      case Outcome.Ran(n)       => fail(s"an unreadable tasks file must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unreadable tasks file is undetermined, not a finding: $msg")

  test("--change-dir whose implementation-progress.md is a directory falls back silently"):
    val dir: Path        = Files.createTempDirectory("ckpt-progressdir")
    val ledger: Path     = writeLedger(dir, List(ledgerRow("R0", "abc1234")))
    val chainState: Path = writeChainState(dir, dischargedVerdict)
    val changeDir: Path  = Files.createTempDirectory("ckpt-progressdir-change")
    Files.createDirectories(changeDir.resolve("implementation-progress.md"))
    val (_, err, outcome) = runCheckpoint(
      reportArgs(ledger, chainState, List("--change-dir", changeDir.toString))
    )
    outcome match
      case Outcome.Ran(0) =>
        // A non-regular progress file means NO per-spec baseline — the
        // fallback is silent, never a spurious 'no per-spec baseline' trace.
        assert(!err.contains("per-spec baseline"), s"no baseline fallback is traced: $err")
      case Outcome.Ran(n)               => fail(s"the report must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the report must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the report must not be undetermined: $reason")

  // ── Scenario: Error path — an unknown operation is rejected naming it ─
  // spec: ledger-checkpoint-parity — Scenario: Error path — an unknown operation is rejected naming it

  test("an unknown checkpoint operation is rejected naming it"):
    val (_, err, outcome) = runCheckpoint(Array("frobnicate", "--x", "y"))
    outcome match
      case Outcome.Finding(msg) =>
        assert(msg.contains("frobnicate"), s"the rejection must name the operation: $msg")
        assertEquals(
          err,
          "checkpoint: unknown subcommand: 'frobnicate'. Expected: report, regenerate-tasks.\n"
        )
      case Outcome.Ran(n)               => fail(s"an unknown op must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unknown op is a finding, not undetermined: $reason")
