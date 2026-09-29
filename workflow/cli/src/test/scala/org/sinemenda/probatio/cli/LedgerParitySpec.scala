package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.CheckpointEngine
import org.sinemenda.probatio.core.CheckpointReport
import org.sinemenda.probatio.core.LedgerRecord
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ReplayVerdict
import org.sinemenda.probatio.core.Ring
import org.sinemenda.probatio.core.RingStatus
import org.sinemenda.probatio.core.SessionId
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * Oracle for the record tool's parity operations (spec 7).
 *
 * Written from the spec and the approved Step-1 contract ONLY — before the
 * `???` implementations land. Derived from the spec's requirements and
 * scenarios, NOT from the implementation.
 *
 * Three surfaces are exercised:
 *   - `run` mode writes a self-observed record (sha256/digest/wallTime and
 *     a session when supplied) and rejects `--exit`/`--source`;
 *   - `verify` assigns exactly one replay verdict to every record —
 *     matching, diverging, or unreplayable — and exits clean only when
 *     no record diverges;
 *   - mutation operations are refused by name before parameters are
 *     examined (append-only).
 *
 * The verify seam is driven through `LedgerCmd.runVerify`'s injected
 * replay boundary so tests observe verdicts without a shell.
 *
 * spec: ledger-checkpoint-parity — Requirement: A record written by the observing path carries the fields that make it self-observed
 * spec: ledger-checkpoint-parity — Requirement: A recorded run can be replayed and its outcome compared
 * spec: ledger-checkpoint-parity — Property: self-observed-records-are-distinguishable
 * spec: ledger-checkpoint-parity — Property: replay-verdict-is-total-and-sound
 * spec: ledger-checkpoint-parity — Compile-Negative: A modification operation name on the record tool's operation type
 * spec: ledger-checkpoint-parity — Compile-Negative: ReplayVerdict pattern match omitting a case
 */
final class LedgerParitySpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    // 300 draws: every gated class lands ≥3σ above its gate, so the
    // coverage check fails only on a real distribution defect.
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  // ── Fixture helpers ─────────────────────────────────────────────────

  private def ledgerRow(
    ring: String,
    exit: Int,
    command: String,
    session: Option[String] = None
  ): ujson.Obj =
    val row: ujson.Obj = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
      "change"     -> ujson.Str("chg"),
      "spec"       -> ujson.Str("spc"),
      "ring"       -> ujson.Str(ring),
      "obligation" -> ujson.Str("obligation"),
      "artifact"   -> ujson.Str("artifact"),
      "command"    -> ujson.Str(command),
      "exit"       -> ujson.Num(exit),
      "baseline"   -> ujson.Str("abc1234")
    )
    session.foreach((s: String) => row.value("session") = ujson.Str(s))
    row

  private def writeLedger(dir: Path, rows: List[ujson.Value]): Path =
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(file, rows.map(ujson.write(_)).mkString("\n").getBytes(StandardCharsets.UTF_8))
    file

  /** Drive `runVerify` with pure seams — every effect is a stub. */
  private def verifyWith(
    parsed: Map[String, String],
    replayTable: Map[String, Int],
    artifactHashes: Map[String, String] = Map.empty
  ): Outcome[Int] =
    val stubRepo: Path = Files.createTempDirectory("ledger-parity-repo")
    LedgerCmd.runVerify(
      parsed,
      (_: Path) => stubRepo,
      (p: Path) => artifactHashes.get(p.toString),
      (cmd: String) => replayTable.contains(cmd), // a command parses iff the table knows it
      (_: Path, cmd: String) => replayTable.getOrElse(cmd, 127)
    )

  // ── Generators for the two properties ────────────────────────────────
  // Declared before the tests: the class-initialisation checker requires
  // every field a test closure could touch to be already initialised.

  /** Deterministic shell commands paired with their true exit codes. */
  private val commandPool: List[(String, Int)] = List(
    "true"           -> 0,
    "false"          -> 1,
    "exit 2"         -> 2,
    "exit 7"         -> 7,
    "echo hi"        -> 0,
    "sh -c 'exit 3'" -> 3
  )

  /** A generated command execution: a pool command plus a record key. */
  final case class CommandExecution(command: String, observedExit: Int)

  private val genCommandExecution: Gen[CommandExecution] =
    // Class-balanced: the coverage gates (30% success / 30% failure /
    // 15% nonzero-nonone) sit at the uniform pool's edge (2 of 6
    // commands exit 0 → ~33% success, flaky against the 30% gate over
    // 80 draws). Pick the exit class first, then the command —
    // assertions, labels, and thresholds unchanged.
    Gen
      .frequency1(
        40 -> Gen.elementUnsafe(List(("true", 0), ("echo hi", 0))),
        30 -> Gen.constant(("false", 1)),
        30 -> Gen.elementUnsafe(List(("exit 2", 2), ("exit 7", 7), ("sh -c 'exit 3'", 3)))
      )
      .map { case (cmd: String, code: Int) => CommandExecution(cmd, code) }

  /** A replay fixture: a record plus the replay table the seam consults. */
  final case class ReplayFixture(
    record: ujson.Value,
    ring: String,
    recordedExit: Int,
    observedExit: Option[Int] // None = the command does not parse
  )

  private val genReplayFixture: Gen[ReplayFixture] =
    for
      ring <- Gen.frequency1(
        70 -> Gen.elementUnsafe(List("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R9")),
        30 -> Gen.elementUnsafe(List("manual", "R8"))
      )
      agreement <- Gen.frequency1(
        // Class-balanced: the coverage gates sit at 30%/30% — the
        // disagree class must land well above its gate, not on it.
        40 -> Gen.constant("agree"),
        45 -> Gen.constant("disagree"),
        15 -> Gen.constant("noparse")
      )
      picked <- Gen.elementUnsafe(commandPool)
      recorded <-
        agreement match
          case "agree"    => Gen.constant(picked._2)
          case "disagree" => Gen.constant(picked._2 + 1)
          case _ =>
            Gen.constant(
              picked._2
            ) // danger-scan:allow type-rejection — agreement is one of three generated tokens; "noparse" records the true exit but supplies no replay
    yield ReplayFixture(
      ledgerRow(ring, exit = recorded, command = picked._1, session = if ring == "R8" then Some("s") else None),
      ring,
      recorded,
      if agreement == "noparse" then None else Some(picked._2)
    )

  // ── Scenario: Happy path — a matching record reports matching and
  //    the run exits clean ─────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — a matching record reports matching and the run exits clean

  test("a record whose command replays with the recorded outcome reports matching; exit clean"):
    val dir: Path  = Files.createTempDirectory("ledger-parity")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "true")))
    val outcome: Outcome[Int] = verifyWith(
      Map("--file" -> file.toString),
      replayTable = Map("true" -> 0)
    )
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"a matching replay must exit 0, got $n")
      case Outcome.Finding(msg)         => fail(s"a matching replay must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"a matching replay must not be undetermined: $reason")

  // ── Scenario: Adversarial — a record whose command now yields a
  //    different outcome reports diverging ─────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — a record whose command now yields a different outcome reports diverging

  test("a record whose command replays with a different outcome reports diverging; exit not clean"):
    val dir: Path  = Files.createTempDirectory("ledger-parity")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "false")))
    val outcome: Outcome[Int] = verifyWith(
      Map("--file" -> file.toString),
      replayTable = Map("false" -> 1) // recorded 0, replays 1 → diverges
    )
    outcome match
      case Outcome.Ran(0)               => fail("a diverging replay must not exit 0")
      case Outcome.Ran(n)               => assertEquals(n, 1, "a diverging replay exits 1")
      case Outcome.Finding(_)           => ()
      case Outcome.Undetermined(reason) => fail(s"a diverging replay is a finding, not undetermined: $reason")

  // ── Scenario: Edge case — a record whose discharge is a human
  //    judgment is unreplayable ─────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Edge case — a record whose discharge is a human judgment is unreplayable

  test("a manual-ring record is unreplayable and does not fail the run"):
    val dir: Path = Files.createTempDirectory("ledger-parity")
    // The manual row names an artifact that resolves and hashes.
    val artifact: Path = dir.resolve("report.md")
    Files.write(artifact, "review".getBytes(StandardCharsets.UTF_8))
    val file: Path = writeLedger(
      dir,
      List(ledgerRow("manual", exit = 0, command = "human review"))
    )
    val outcome: Outcome[Int] = LedgerCmd.runVerify(
      Map("--file" -> file.toString),
      (_: Path) => dir,
      (_: Path) => Some("a" * 64),
      (_: String) => false,
      (_: Path, _: String) => 127
    )
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"an unreplayable record must not fail the run, got $n")
      case Outcome.Finding(msg)         => fail(s"an unreplayable record must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"an unreplayable record must not be undetermined: $reason")

  test("a manual-ring record whose artifact does not resolve fails the run"):
    val dir: Path = Files.createTempDirectory("ledger-parity")
    val file: Path = writeLedger(
      dir,
      List(ledgerRow("manual", exit = 0, command = "human review"))
    )
    // sha256Of returns None for every path — nothing resolves.
    val outcome: Outcome[Int] = LedgerCmd.runVerify(
      Map("--file" -> file.toString),
      (_: Path) => dir,
      (_: Path) => None,
      (_: String) => false,
      (_: Path, _: String) => 127
    )
    outcome match
      case Outcome.Ran(0)               => fail("an unresolvable manual artifact must not pass")
      case Outcome.Ran(_)               => ()
      case Outcome.Finding(_)           => ()
      case Outcome.Undetermined(reason) => fail(s"a missing artifact is a failure, not undetermined: $reason")

  // ── Scenario: Error path — an unreadable record set is
  //    could-not-determine ─────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Error path — an unreadable record set is could-not-determine

  test("verify on an absent ledger is undetermined"):
    val outcome: Outcome[Int] = verifyWith(
      Map("--file" -> "/nonexistent/ledger.jsonl"),
      replayTable = Map.empty
    )
    outcome match
      case Outcome.Undetermined(_) => ()
      case Outcome.Ran(n)          => fail(s"an absent ledger must be undetermined, got Ran($n)")
      case Outcome.Finding(msg)    => fail(s"an absent ledger must be undetermined, not a finding: $msg")

  test("verify on a ledger with a malformed row is undetermined"):
    val dir: Path  = Files.createTempDirectory("ledger-parity")
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(file, "not json\n".getBytes(StandardCharsets.UTF_8))
    val (_, err, outcome) = StdoutCapture.captureBoth(
      verifyWith(
        Map("--file" -> file.toString),
        replayTable = Map.empty
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("UNDETERMINED —"), s"the UNDETERMINED marker is emitted on stderr: $err")
        assert(reason.nonEmpty, s"the undetermined outcome carries a reason: $reason")
      case Outcome.Ran(n)       => fail(s"a malformed row must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a malformed row must be undetermined, not a finding: $msg")

  // ── Scenario: run mode rejects the caller-supplied observation fields ─
  // spec: ledger-checkpoint-parity — Requirement: A record written by the observing path carries the fields that make it self-observed

  test("run mode rejects --exit — the tool observes the exit code"):
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        "/tmp/l.jsonl",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--exit",
        "0",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Finding(msg)         => assert(msg.contains("exit"), s"the rejection must name --exit: $msg")
      case Outcome.Ran(n)               => fail(s"run mode must reject --exit, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"run mode must reject --exit as a finding, not undetermined: $reason")

  test("run mode rejects --source — a run row is self-observed"):
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        "/tmp/l.jsonl",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--source",
        "ambient",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Finding(msg) => assert(msg.contains("source"), s"the rejection must name --source: $msg")
      case Outcome.Ran(n)       => fail(s"run mode must reject --source, got Ran($n)")
      case Outcome.Undetermined(reason) =>
        fail(s"run mode must reject --source as a finding, not undetermined: $reason")

  test("run mode requires a command after --"):
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        "/tmp/l.jsonl",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234"
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        assert(msg.contains("command") || msg.contains("--"), s"the rejection must name the missing command: $msg")
      case Outcome.Ran(n)               => fail(s"run mode without a command must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"missing command is a finding, not undetermined: $reason")

  // ── Scenario: run mode writes the observation fields ────────────────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — the observation fields are not silently dropped

  test("a run-mode record carries sha256, digest, and wallTime; the observed exit is recorded"):
    val dir: Path = Files.createTempDirectory("ledger-parity-run")
    // The ledger lives in the temp dir, which is the repo root fallback —
    // the artifact resolves relative to it.
    val artifact: Path = dir.resolve("artifact.txt")
    Files.write(artifact, "produced".getBytes(StandardCharsets.UTF_8))
    val ledger: Path = dir.resolve("ledger.jsonl")
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "artifact.txt",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"run must exit 0, got $n")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    assertEquals(lines.length, 1, "exactly one record must be appended")
    val row: ujson.Value =
      ujson.read(lines.headOption.getOrElse(fail("no record line persisted")))
    assert(row.obj.contains("digest"), "a run row must carry the output digest")
    assert(row.obj.contains("wallTime"), "a run row must carry the wall-clock duration")
    assertEquals(row("exit").num.toInt, 0, "the observed exit must be recorded")
    assertEquals(row("command").str, "true", "the executed command must be recorded")

  // ── Scenario: a modifying operation is refused for what it is ───────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — a modifying operation on the record set is refused for what it is

  List("update", "delete", "rewrite", "edit").foreach { (op: String) =>
    test(s"the '$op' operation is refused for what it is — before any parameter parsing"):
      val outcome: Outcome[Int] = LedgerCmd.run(Array(op, "--nonsense", "x"))
      outcome match
        case Outcome.Finding(msg) =>
          assert(
            msg.contains("append-only") || msg.contains(op),
            s"'$op' must be refused naming the append-only invariant: $msg"
          )
        case Outcome.Ran(n)               => fail(s"'$op' must not succeed, got Ran($n)")
        case Outcome.Undetermined(reason) => fail(s"'$op' is refused as a finding, not undetermined: $reason")
  }

  // ── Property: replay-verdict-is-total-and-sound ─────────────────────
  // spec: ledger-checkpoint-parity — Property: replay-verdict-is-total-and-sound

  property("replay-verdict-is-total-and-sound", coverConfig):
    for fx <- genReplayFixture.forAll
        .cover(30, "agrees", (x: ReplayFixture) => x.observedExit.contains(x.recordedExit))
        .cover(30, "disagrees", (x: ReplayFixture) => x.observedExit.exists(_ != x.recordedExit))
        .cover(20, "judgment-ring", (x: ReplayFixture) => x.ring == "manual" || x.ring == "R8")
    yield
      val verdict: ReplayVerdict = ReplayVerdict.classify(
        Ring.fromString(fx.ring).getOrElse(fail("generated ring must be in-domain")),
        fx.observedExit,
        fx.recordedExit
      )
      Result
        .assert(
          (verdict == ReplayVerdict.Matches) == (fx.observedExit
            .contains(fx.recordedExit) && fx.ring != "manual" && fx.ring != "R8")
        )
        .log(s"verdict $verdict for ring=${fx.ring} recorded=${fx.recordedExit} observed=${fx.observedExit}")

  // ── Property: self-observed-records-are-distinguishable ─────────────
  // spec: ledger-checkpoint-parity — Property: self-observed-records-are-distinguishable

  property("self-observed-records-are-distinguishable", coverConfig):
    for ex <- genCommandExecution.forAll
        .cover(30, "success", (x: CommandExecution) => x.observedExit == 0)
        .cover(30, "failure", (x: CommandExecution) => x.observedExit != 0)
        .cover(15, "nonzero-nonone-code", (x: CommandExecution) => x.observedExit > 1)
    yield
      val dir: Path = Files.createTempDirectory("ledger-parity-dist")
      // run path: the tool executes the command and records what it observed
      val runLedger: Path = dir.resolve("run.jsonl")
      LedgerCmd.run(
        Array(
          "run",
          "--file",
          runLedger.toString,
          "--change",
          "c",
          "--spec",
          "s",
          "--ring",
          "R0",
          "--obligation",
          "o",
          "--artifact",
          "a.txt",
          "--baseline",
          "abc1234",
          "--",
          ex.command
        )
      )
      // append path: the caller asserts the outcome
      val appendLedger: Path = dir.resolve("append.jsonl")
      LedgerCmd.run(
        Array(
          "append",
          "--file",
          appendLedger.toString,
          "--change",
          "c",
          "--spec",
          "s",
          "--ring",
          "R0",
          "--obligation",
          "o",
          "--artifact",
          "a.txt",
          "--command",
          ex.command,
          "--exit",
          ex.observedExit.toString,
          "--baseline",
          "abc1234"
        )
      )
      val runRow: Option[ujson.Value] =
        if Files.isRegularFile(runLedger) then
          Files
            .readAllLines(runLedger, StandardCharsets.UTF_8)
            .asScala
            .toList
            .filter(_.trim.nonEmpty)
            .map(ujson.read(_))
            .headOption
        else None
      val appendRow: Option[ujson.Value] =
        if Files.isRegularFile(appendLedger) then
          Files
            .readAllLines(appendLedger, StandardCharsets.UTF_8)
            .asScala
            .toList
            .filter(_.trim.nonEmpty)
            .map(ujson.read(_))
            .headOption
        else None
      val runObserved: Boolean =
        runRow.exists((r: ujson.Value) => r.obj.contains("digest") && r.obj.contains("wallTime"))
      val appendObserved: Boolean =
        appendRow.exists((r: ujson.Value) =>
          r.obj.contains("digest") || r.obj.contains("wallTime") || r.obj.contains("sha256")
        )
      Result
        .assert(runObserved && !appendObserved)
        .log(s"run row: $runRow\nappend row: $appendRow")

  // ── Scenario: Edge case — a record at a superseded baseline does not
  //    evidence the ring; a forgiven one does ───────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Edge case — a record at a superseded baseline does not evidence the ring
  //
  // Ring-8 remediation: the spec's scenario is exercised at the layer
  // that owns it — `readRowsFiltered` (the record tool's own read path),
  // not the checkpoint engine. Baseline exclusion lives here; the engine
  // selects by ring only over the rows this path emits.

  private def staleRow(ring: String, exit: Int): ujson.Obj =
    val row: ujson.Obj = ledgerRow(ring, exit = exit, command = "true")
    row.value("baseline") = ujson.Str("deadbee")
    row

  private def reportOverRows(
    rows: List[ujson.Value],
    rings: List[Ring]
  ): CheckpointReport =
    val records: List[LedgerRecord] =
      rows.map((row: ujson.Value) => LedgerRecord.from(row).getOrElse(fail("fixture row must validate")))
    CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = "abc1234",
      requested = rings,
      records = records,
      chainState = ujson.Obj("unresolved" -> ujson.Arr()),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )

  test("a stale-baseline row the read path does not forgive is excluded — the ring is unevidenced"):
    val dir: Path  = Files.createTempDirectory("ledger-parity-stale")
    val file: Path = writeLedger(dir, List(staleRow("R0", exit = 0)))
    LedgerCmd.readRowsFiltered(
      file.toString,
      change = "chg",
      spec = "spc",
      baseline = "abc1234",
      artifactUnchanged = (_: String, _: String) => false // nothing forgives
    ) match
      case Outcome.Ran(rows) =>
        assertEquals(rows.length, 0, "the unforgiven stale row is filtered out by the read path")
        val report: CheckpointReport = reportOverRows(rows, List(Ring.R0))
        assertEquals(report.rings.headOption.map(_.status), Some(RingStatus.Unevidenced))
      case Outcome.Finding(msg)         => fail(s"the read must succeed, got Finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the read must succeed, got Undetermined: $reason")

  test("a stale-baseline row forgiven by artifactUnchanged still evidences its ring"):
    // Ring-8 FAIL: the engine re-checked the stored baseline and dropped
    // forgiven rows — the predecessor selects by ring over the filtered
    // read output, so a forgiven row counts as evidence.
    val dir: Path  = Files.createTempDirectory("ledger-parity-forgiven")
    val file: Path = writeLedger(dir, List(staleRow("R0", exit = 0)))
    LedgerCmd.readRowsFiltered(
      file.toString,
      change = "chg",
      spec = "spc",
      baseline = "abc1234",
      artifactUnchanged = (_: String, _: String) => true // git diff --quiet exit 0
    ) match
      case Outcome.Ran(rows) =>
        assertEquals(rows.length, 1, "the forgiven stale row is kept by the read path")
        val report: CheckpointReport = reportOverRows(rows, List(Ring.R0))
        assertEquals(
          report.rings.headOption.map(_.status),
          Some(RingStatus.Green),
          "a forgiven row evidences by its exit — its stored baseline is not re-checked"
        )
      case Outcome.Finding(msg)         => fail(s"the read must succeed, got Finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the read must succeed, got Undetermined: $reason")

  // ── Ring 5 surface: the ledger subcommand's exact diagnostics and
  //    emitted rows are asserted, not only its exit status ─────────────
  // spec: ledger-checkpoint-parity — Requirement: The record and checkpoint tools accept the predecessor's operation and parameter set

  private def runLedger(args: Array[String]): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(LedgerCmd.run(args))

  private def appendArgs(file: Path, extra: List[String]): Array[String] =
    (List(
      "append",
      "--file",
      file.toString,
      "--change",
      "chg",
      "--spec",
      "spc",
      "--ring",
      "R0",
      "--obligation",
      "obligation",
      "--artifact",
      "artifact",
      "--command",
      "true",
      "--exit",
      "7",
      "--baseline",
      "abc1234"
    ) ++ extra).toArray

  test("ledger with no subcommand is rejected naming '<none>'"):
    val (_, err, outcome) = runLedger(Array.empty[String])
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "ledger: unknown subcommand: '<none>'. Expected: append, run, read, verify.\n")
        assert(msg.contains("<none>") || msg.contains("unknown"), s"must name the absent subcommand: $msg")
      case Outcome.Ran(n)               => fail(s"a missing subcommand must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing subcommand is a finding, not undetermined: $reason")

  test("an unknown ledger subcommand is rejected naming it"):
    val (_, err, outcome) = runLedger(Array("frobnicate", "--x"))
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "ledger: unknown subcommand: 'frobnicate'. Expected: append, run, read, verify.\n")
        assertEquals(msg, "unknown subcommand: frobnicate")
      case Outcome.Ran(n)               => fail(s"an unknown subcommand must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unknown subcommand is a finding, not undetermined: $reason")

  test("append names every missing required field, in contract order"):
    val dir: Path         = Files.createTempDirectory("ledger-missing")
    val file: Path        = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(Array("append", "--file", file.toString))
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(
          err,
          "ledger: missing required field(s): change spec ring obligation artifact command exit baseline\n"
        )
        assertEquals(msg, "missing required field(s): change spec ring obligation artifact command exit baseline")
      case Outcome.Ran(n)               => fail(s"a fieldless append must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"missing fields are a finding, not undetermined: $reason")

  test("append rejects --exit outside the written -999..999 grammar"):
    val dir: Path  = Files.createTempDirectory("ledger-exit-grammar")
    val file: Path = dir.resolve("ledger.jsonl")
    List("007", "abc", "1000", "-1000", "00", "+1", "1.5").foreach { (exitArg: String) =>
      val args: Array[String] = appendArgs(file, Nil)
      args(args.indexOf("--exit") + 1) = exitArg
      val (_, err, outcome) = runLedger(args)
      outcome match
        case Outcome.Finding(msg) =>
          assertEquals(
            err,
            s"ledger: exit must be an integer in -999..999 as written, got: $exitArg\n"
          )
          assertEquals(msg, s"exit must be an integer in -999..999 as written, got: $exitArg")
        case Outcome.Ran(n) =>
          fail(s"--exit $exitArg must be rejected as-written, got Ran($n)")
        case Outcome.Undetermined(reason) =>
          fail(s"--exit $exitArg is a finding, not undetermined: $reason")
    }

  test("append accepts the canonical exit grammar including negative exits"):
    val dir: Path  = Files.createTempDirectory("ledger-exit-ok")
    val file: Path = dir.resolve("ledger.jsonl")
    List("0", "7", "-7", "999", "-999").foreach { (exitArg: String) =>
      val args: Array[String] = appendArgs(file, Nil)
      args(args.indexOf("--exit") + 1) = exitArg
      val (_, _, outcome) = runLedger(args)
      outcome match
        case Outcome.Ran(0)               => ()
        case Outcome.Ran(n)               => fail(s"--exit $exitArg is canonical, got Ran($n)")
        case Outcome.Finding(msg)         => fail(s"--exit $exitArg is canonical, got Finding: $msg")
        case Outcome.Undetermined(reason) => fail(s"--exit $exitArg is canonical, got Undetermined: $reason")
    }
    val rows: List[String] =
      Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    assertEquals(rows.length, 5, "each canonical append writes one row")
    assertEquals(ujson.read(rows(1))("exit").num.toInt, 7)
    assertEquals(ujson.read(rows(2))("exit").num.toInt, -7)

  test("append rejects an R8 row without --session"):
    val dir: Path           = Files.createTempDirectory("ledger-r8-session")
    val file: Path          = dir.resolve("ledger.jsonl")
    val args: Array[String] = appendArgs(file, Nil)
    args(args.indexOf("--ring") + 1) = "R8"
    val (_, err, outcome) = runLedger(args)
    outcome match
      case Outcome.Finding(msg) =>
        // Exact text — the early check's diagnostic differs from the
        // validator's clause-14 fallback ("clause 14 — session is
        // required for R8 …"), which shares the same substring.
        assertEquals(
          err,
          "ledger: session is required for R8 (adversarial-review) rows — pass --session with the producing session's identity\n"
        )
        assertEquals(msg, "session is required for R8 rows")
      case Outcome.Ran(n)               => fail(s"an R8 row without session must not append, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing session is a finding, not undetermined: $reason")

  test("a conformant append writes a row carrying every supplied field, including session and source"):
    val dir: Path       = Files.createTempDirectory("ledger-append-row")
    val file: Path      = dir.resolve("ledger.jsonl")
    val (_, _, outcome) = runLedger(appendArgs(file, List("--session", "sess-1", "--source", "ambient")))
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"append must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"append must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"append must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    assertEquals(lines.length, 1, "exactly one row is appended")
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no row written")))
    assertEquals(row("v").num.toInt, 1)
    assertEquals(row("change").str, "chg")
    assertEquals(row("spec").str, "spc")
    assertEquals(row("ring").str, "R0")
    assertEquals(row("obligation").str, "obligation")
    assertEquals(row("artifact").str, "artifact")
    assertEquals(row("command").str, "true")
    assertEquals(row("exit").num.toInt, 7)
    assertEquals(row("baseline").str, "abc1234")
    assertEquals(row("session").str, "sess-1", "the supplied session is recorded")
    assertEquals(row("source").str, "ambient", "the supplied source is recorded")

  test("append rejects a record violating the contract, naming the clause"):
    val dir: Path           = Files.createTempDirectory("ledger-append-clause")
    val file: Path          = dir.resolve("ledger.jsonl")
    val args: Array[String] = appendArgs(file, Nil)
    args(args.indexOf("--baseline") + 1) = "notahex"
    val (_, err, outcome) = runLedger(args)
    outcome match
      case Outcome.Finding(msg) =>
        assert(err.contains("clause 11"), s"the rejection names clause 11: $err")
        assert(msg.contains("clause 11"), s"the finding names clause 11: $msg")
      case Outcome.Ran(n)               => fail(s"a contract violation must not append, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a contract violation is a finding, not undetermined: $reason")

  test("append to an unwritable path is undetermined"):
    val (_, err, outcome) =
      runLedger(appendArgs(Path.of("/nonexistent-dir-xyz/ledger.jsonl"), Nil))
    outcome match
      case Outcome.Undetermined(_) =>
        assert(err.contains("UNDETERMINED"), s"the failure is reported undetermined: $err")
      case Outcome.Ran(n)       => fail(s"an unwritable path must not append, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unwritable path is undetermined, not a finding: $msg")

  test("read requires --file and --change"):
    val (_, err1, out1) = runLedger(Array("read"))
    out1 match
      case Outcome.Finding(_) => assertEquals(err1, "ledger: --file is required\n")
      case other              => fail(s"read without --file must be a finding, got $other")
    val dir: Path       = Files.createTempDirectory("ledger-read-req")
    val (_, err2, out2) = runLedger(Array("read", "--file", dir.resolve("l.jsonl").toString))
    out2 match
      case Outcome.Finding(_) => assertEquals(err2, "ledger: --change is required for read\n")
      case other              => fail(s"read without --change must be a finding, got $other")

  test("read on an absent ledger reports 'no ledger' on stdout, exit 2"):
    val dir: Path         = Files.createTempDirectory("ledger-read-absent")
    val file: Path        = dir.resolve("absent.jsonl")
    val (out, _, outcome) = runLedger(Array("read", "--file", file.toString, "--change", "chg"))
    outcome match
      case Outcome.Undetermined(reason) =>
        assertEquals(out, s"ledger: no ledger at $file\n")
        assertEquals(reason, s"no ledger at $file")
      case Outcome.Ran(n)       => fail(s"an absent ledger must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent ledger is undetermined, not a finding: $msg")

  test("read on a directory is undetermined, not a clean empty"):
    val dir: Path         = Files.createTempDirectory("ledger-read-dir")
    val (_, err, outcome) = runLedger(Array("read", "--file", dir.toString, "--change", "chg"))
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("not a regular file"), s"the reason names the file kind: $err")
        assert(reason.contains("not a regular file"), s"the outcome carries the reason: $reason")
      case Outcome.Ran(n)       => fail(s"a directory must not read clean, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a directory is undetermined, not a finding: $msg")

  test("read on an unreadable ledger is undetermined"):
    val dir: Path  = Files.createTempDirectory("ledger-read-unreadable")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "true")))
    assume(file.toFile.setReadable(false), "the test platform must let us revoke read permission")
    assume(!Files.isReadable(file), "the file must actually be unreadable (skipped when running as root)")
    val (_, err, outcome) = runLedger(Array("read", "--file", file.toString, "--change", "chg"))
    file.toFile.setReadable(true)
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(err.contains("not readable"), s"the reason names readability: $err")
        assert(reason.contains("not readable"), s"the outcome carries the reason: $reason")
      case Outcome.Ran(n)       => fail(s"an unreadable ledger must not read clean, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an unreadable ledger is undetermined, not a finding: $msg")

  test("read emits exactly the rows matching change/spec/baseline — filtered rows are absent"):
    val dir: Path              = Files.createTempDirectory("ledger-read-filter")
    val wrongChange: ujson.Obj = ledgerRow("R1", exit = 0, command = "true")
    wrongChange.value("change") = ujson.Str("other")
    val wrongSpec: ujson.Obj = ledgerRow("R2", exit = 0, command = "true")
    wrongSpec.value("spec") = ujson.Str("other")
    val stale: ujson.Obj = staleRow("R3", exit = 0)
    val kept: ujson.Obj  = ledgerRow("R0", exit = 0, command = "true")
    val file: Path       = writeLedger(dir, List(wrongChange, wrongSpec, stale, kept))
    val (out, _, outcome) = runLedger(
      Array(
        "read",
        "--file",
        file.toString,
        "--change",
        "chg",
        "--spec",
        "spc",
        "--baseline",
        "abc1234"
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assertEquals(out, ujson.write(kept) + "\n", "only the matching row is emitted")
      case Outcome.Ran(n)               => fail(s"the read must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the read must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the read must not be undetermined: $reason")

  test("read without --baseline emits every change/spec-matching row, newline-separated"):
    val dir: Path         = Files.createTempDirectory("ledger-read-nobaseline")
    val first: ujson.Obj  = ledgerRow("R0", exit = 0, command = "true")
    val second: ujson.Obj = staleRow("R1", exit = 1)
    val file: Path        = writeLedger(dir, List(first, second))
    val (out, _, outcome) = runLedger(
      Array("read", "--file", file.toString, "--change", "chg", "--spec", "spc")
    )
    outcome match
      case Outcome.Ran(0) =>
        // Both baselines pass — an absent --baseline never filters.
        assertEquals(out, ujson.write(first) + "\n" + ujson.write(second) + "\n")
      case Outcome.Ran(n)               => fail(s"the read must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the read must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the read must not be undetermined: $reason")

  test("read with no matching rows emits nothing on stdout"):
    val dir: Path  = Files.createTempDirectory("ledger-read-empty")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "true")))
    val (out, _, outcome) = runLedger(
      Array("read", "--file", file.toString, "--change", "absent-change")
    )
    outcome match
      case Outcome.Ran(0) =>
        assertEquals(out, "", "no rows match — stdout stays empty, no stray newline")
      case Outcome.Ran(n)               => fail(s"the read must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the read must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the read must not be undetermined: $reason")

  test("read on a ledger holding a malformed line is undetermined naming the line"):
    val dir: Path = Files.createTempDirectory("ledger-read-malformed")
    List(
      ("bad json\n", "does not parse as JSON"),
      ("{}\n", "violates the record contract"),
      ("\n", "is empty")
    ).foreach { case (line: String, fragment: String) =>
      val file: Path = dir.resolve(s"ledger-${line.hashCode.abs}.jsonl")
      Files.write(
        file,
        (ujson.write(ledgerRow("R0", exit = 0, command = "true")) + "\n" + line).getBytes(StandardCharsets.UTF_8)
      )
      val (_, err, outcome) = runLedger(Array("read", "--file", file.toString, "--change", "chg"))
      outcome match
        case Outcome.Undetermined(_) =>
          assert(err.contains(fragment), s"'$fragment' expected in: $err")
        case Outcome.Ran(n)       => fail(s"a malformed line must be undetermined, got Ran($n)")
        case Outcome.Finding(msg) => fail(s"a malformed line is undetermined, not a finding: $msg")
    }

  test("read on a ledger holding a foreign format version is undetermined"):
    val dir: Path        = Files.createTempDirectory("ledger-read-v2")
    val v2row: ujson.Obj = ledgerRow("R0", exit = 0, command = "true")
    v2row.value("v") = ujson.Num(2)
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(file, (ujson.write(v2row) + "\n").getBytes(StandardCharsets.UTF_8))
    val (_, err, outcome) = runLedger(Array("read", "--file", file.toString, "--change", "chg"))
    outcome match
      case Outcome.Undetermined(_) =>
        assert(err.contains("format version 2"), s"the refusal names the version: $err")
      case Outcome.Ran(n)       => fail(s"a v:2 row must be refused, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a v:2 row is undetermined, not a finding: $msg")

  test("read --forgive-unchanged keeps a stale row whose artifact is unchanged since its baseline"):
    val dir: Path = Files.createTempDirectory("ledger-forgive")
    // A real repository: the forgive oracle is `git diff --quiet <baseline> HEAD -- <artifact>`.
    val artifact: Path = dir.resolve("artifact.txt")
    Files.write(artifact, "payload".getBytes(StandardCharsets.UTF_8))
    assertEquals(
      HermeticEnv.run(List("git", "-C", dir.toString, "init"), HermeticEnv.empty),
      0,
      "git init must succeed"
    )
    assertEquals(
      HermeticEnv.run(List("git", "-C", dir.toString, "add", "artifact.txt"), HermeticEnv.empty),
      0
    )
    assertEquals(
      HermeticEnv.run(
        List(
          "git",
          "-C",
          dir.toString,
          "-c",
          "user.email=t@t",
          "-c",
          "user.name=t",
          "commit",
          "-m",
          "init"
        ),
        HermeticEnv.empty
      ),
      0
    )
    val sha: String =
      HermeticEnv
        .capture(List("git", "-C", dir.toString, "rev-parse", "HEAD"), HermeticEnv.empty)
        .out
        .trim
    val row: ujson.Obj = ledgerRow("R0", exit = 0, command = "true")
    row.value("baseline") = ujson.Str(sha)
    row.value("artifact") = ujson.Str("artifact.txt")
    val file: Path = writeLedger(dir, List(row))
    // The row is stale under the queried baseline — without the flag it is dropped.
    val (outNoFlag, _, noFlagOutcome) = runLedger(
      Array("read", "--file", file.toString, "--change", "chg", "--baseline", "abc1234")
    )
    noFlagOutcome match
      case Outcome.Ran(0) => assertEquals(outNoFlag, "", "unforgiven stale rows stay absent")
      case other          => fail(s"the unforgiven read must exit 0, got $other")
    // Forgiven: the artifact has not changed since the row's own baseline.
    val (outForgiven, _, forgivenOutcome) = runLedger(
      Array(
        "read",
        "--file",
        file.toString,
        "--change",
        "chg",
        "--baseline",
        "abc1234",
        "--forgive-unchanged"
      )
    )
    forgivenOutcome match
      case Outcome.Ran(0) =>
        assertEquals(
          outForgiven,
          ujson.write(row) + "\n",
          "the forgiven stale row is emitted — its stored baseline is not re-checked"
        )
      case Outcome.Ran(n)               => fail(s"the forgiven read must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"the forgiven read must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"the forgiven read must not be undetermined: $reason")

  test("verify names the per-row verdicts and the summary counts on stderr"):
    val dir: Path          = Files.createTempDirectory("ledger-verify-msgs")
    val diverge: ujson.Obj = ledgerRow("R0", exit = 0, command = "exit 9")
    val manual: ujson.Obj  = ledgerRow("manual", exit = 0, command = "human")
    val file: Path         = writeLedger(dir, List(diverge, manual))
    val (_, err, outcome) = StdoutCapture.captureBoth(
      LedgerCmd.runVerify(
        Map("--file" -> file.toString),
        (_: Path) => dir,
        (_: Path) => None, // the manual artifact never resolves
        (_: String) => true,
        (_: Path, _: String) => 9
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        assert(
          err.contains("command/exit disagreement (recorded 0, observed 9)"),
          s"the divergence is named: $err"
        )
        assert(
          err.contains("manual row artifact does not resolve: artifact"),
          s"the unresolvable artifact is named: $err"
        )
        assert(
          err.contains("verify found 2 failure(s) in 2 row(s)"),
          s"the counts are reported: $err"
        )
        assert(msg.contains("2 failure(s)"), s"the finding carries the counts: $msg")
      case Outcome.Ran(n)               => fail(s"a diverging verify must not exit clean, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a diverging verify is a finding, not undetermined: $reason")

  test("verify reports a resolvable manual artifact's hash and the unreplayable verdict"):
    val dir: Path      = Files.createTempDirectory("ledger-verify-manual")
    val artifact: Path = dir.resolve("report.md")
    Files.write(artifact, "review".getBytes(StandardCharsets.UTF_8))
    val file: Path = writeLedger(dir, List(ledgerRow("manual", exit = 0, command = "human")))
    val (_, err, outcome) = StdoutCapture.captureBoth(
      LedgerCmd.runVerify(
        Map("--file" -> file.toString),
        (_: Path) => dir,
        // Resolve only the artifact's own path — a verify that hashes the
        // wrong path (e.g. an empty repo/artifact concat) gets None.
        (p: Path) =>
          Option(p.getFileName)
            .map(_.toString)
            .filter((n: String) => n == "artifact")
            .map((_: String) => "f" * 64),
        (_: String) => false,
        (_: Path, _: String) => 127
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(err.contains("artifact artifact sha256="), s"the hash is traced: $err")
        assert(
          err.contains("manual/unreplayable (ring=manual, artifact=artifact)"),
          s"the unreplayable verdict is named: $err"
        )
        assert(err.contains("verify passed (1 rows)"), s"the pass is reported: $err")
      case Outcome.Ran(n)               => fail(s"a resolvable manual row must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a resolvable manual row must not fail: $msg")
      case Outcome.Undetermined(reason) => fail(s"a resolvable manual row must not be undetermined: $reason")

  test("verify skips replay for a command that does not parse as shell — named, never silently passed"):
    val dir: Path  = Files.createTempDirectory("ledger-verify-noparse")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "if (")))
    val (_, err, outcome) = StdoutCapture.captureBoth(
      LedgerCmd.runVerify(
        Map("--file" -> file.toString),
        (_: Path) => dir,
        (_: Path) => None,
        (_: String) => false, // nothing parses
        (_: Path, _: String) => 127
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(
          err.contains("does not parse as shell (skipped replay)"),
          s"the skipped replay is named: $err"
        )
      case Outcome.Ran(n)               => fail(s"a skipped replay is not a divergence, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a skipped replay must not fail the run: $msg")
      case Outcome.Undetermined(reason) => fail(s"a skipped replay must not be undetermined: $reason")

  test("verify on an absent ledger reports 'no ledger' on stdout, exit 2"):
    val dir: Path  = Files.createTempDirectory("ledger-verify-absent")
    val file: Path = dir.resolve("absent.jsonl")
    val (out, _, outcome) = StdoutCapture.captureBoth(
      LedgerCmd.runVerify(
        Map("--file" -> file.toString),
        (_: Path) => dir,
        (_: Path) => None,
        (_: String) => true,
        (_: Path, _: String) => 0
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assertEquals(out, s"ledger: no ledger at $file\n")
        assertEquals(reason, s"no ledger at $file")
      case Outcome.Ran(n)       => fail(s"an absent ledger must be undetermined, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"an absent ledger is undetermined, not a finding: $msg")

  test("an end-to-end verify through the real shell boundary replays matching rows clean"):
    val dir: Path  = Files.createTempDirectory("ledger-verify-e2e")
    val file: Path = writeLedger(dir, List(ledgerRow("R0", exit = 0, command = "true")))
    val (_, err, outcome) = StdoutCapture.captureBoth(
      LedgerCmd.run(Array("verify", "--file", file.toString))
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(err.contains("verify passed (1 rows)"), s"the pass is reported: $err")
        // `true` parses under `bash -n` — a broken shell-check boundary
        // would silently skip the replay instead.
        assert(!err.contains("does not parse"), s"the replay is not skipped: $err")
      case Outcome.Ran(n)               => fail(s"a matching replay must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"a matching replay must not fail: $msg")
      case Outcome.Undetermined(reason) => fail(s"a matching replay must not be undetermined: $reason")

  test("run mode rejects --exit and --source at parse position, naming the flag"):
    List("--exit", "--source").foreach { (flag: String) =>
      val (_, err, outcome) = runLedger(
        Array(
          "run",
          "--file",
          "/tmp/l.jsonl",
          "--change",
          "c",
          "--spec",
          "s",
          "--ring",
          "R0",
          "--obligation",
          "o",
          "--artifact",
          "a",
          "--baseline",
          "abc1234",
          flag,
          "0",
          "--",
          "true"
        )
      )
      outcome match
        case Outcome.Finding(msg) =>
          val expected: String =
            if flag == "--exit" then "ledger: run mode does not accept --exit; the script observes the exit code\n"
            else "ledger: run mode does not accept --source; a run row is self-observed\n"
          assertEquals(err, expected)
          assert(msg.contains(flag), s"the finding names $flag: $msg")
        case Outcome.Ran(n)               => fail(s"run mode must reject $flag, got Ran($n)")
        case Outcome.Undetermined(reason) => fail(s"run mode rejects $flag as a finding, not undetermined: $reason")
    }

  test("run mode requires a command after -- and names the missing fields"):
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        "/tmp/l.jsonl",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234"
      )
    )
    outcome match
      case Outcome.Finding(_) =>
        assertEquals(err, "ledger: run mode requires a command after --\n")
      case Outcome.Ran(n)               => fail(s"run without a command must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing command is a finding, not undetermined: $reason")
    val (_, err2, outcome2) = runLedger(
      Array("run", "--file", "/tmp/l.jsonl", "--", "true")
    )
    outcome2 match
      case Outcome.Finding(msg) =>
        assertEquals(
          err2,
          "ledger: missing required field(s): change spec ring obligation artifact baseline\n"
        )
        assertEquals(msg, "missing required field(s): change spec ring obligation artifact baseline")
      case Outcome.Ran(n)               => fail(s"run without fields must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"missing fields are a finding, not undetermined: $reason")

  test("run mode writes the R8 session row and traces the artifact hash"):
    val dir: Path      = Files.createTempDirectory("ledger-run-r8")
    val artifact: Path = dir.resolve("review.md")
    Files.write(artifact, "evidence".getBytes(StandardCharsets.UTF_8))
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R8",
        "--obligation",
        "o",
        "--artifact",
        "review.md",
        "--baseline",
        "abc1234",
        "--session",
        "review-session",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(err.contains("run recorded exit=0"), s"the observation is reported: $err")
        assert(!err.contains("WARNING"), s"a resolvable artifact warns nothing: $err")
      case Outcome.Ran(n)               => fail(s"run must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no run row written")))
    assertEquals(row("ring").str, "R8")
    assertEquals(row("session").str, "review-session", "the producing session is recorded")
    assert(row("sha256").str.matches("[0-9a-f]{64}"), s"the artifact hash is traced: ${row("sha256")}")
    assert(row("digest").str.nonEmpty, "the output digest is recorded")
    assert(row.obj.contains("wallTime"), "the wall-clock duration is recorded")

  test("run mode warns and records an empty sha256 when the artifact does not resolve"):
    val dir: Path    = Files.createTempDirectory("ledger-run-noartifact")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "missing.bin",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(
          err.contains("WARNING — artifact missing.bin does not resolve at run time"),
          s"the unresolvable artifact is warned, never faked: $err"
        )
      case Outcome.Ran(n)               => fail(s"run must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no run row written")))
    assertEquals(row("sha256").str, "", "an unresolvable artifact records an empty hash — never a fake one")

  test("run mode records the observed exit and a positive wall time for a failing command"):
    val dir: Path    = Files.createTempDirectory("ledger-run-exit")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "sleep 0.05; exit 3"
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(err.contains("run recorded exit=3"), s"the observed exit is reported: $err")
      case Outcome.Ran(n)               => fail(s"run must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no run row written")))
    assertEquals(row("exit").num.toInt, 3, "the observed exit is recorded, not the command's success")
    assert(row("wallTime").num >= 10, s"a slept command measures a positive wall time: ${row("wallTime")}")

  test("run mode joins a multi-token command after -- with spaces"):
    val dir: Path    = Files.createTempDirectory("ledger-run-join")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "echo",
        "hello"
      )
    )
    outcome match
      case Outcome.Ran(0) =>
        assert(err.contains("run recorded exit=0"), s"the joined command runs: $err")
      case Outcome.Ran(n)               => fail(s"run must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no run row written")))
    assertEquals(row("command").str, "echo hello", "the command tokens join with spaces")

  test("run mode resolves the repo from the ledger's directory, not an ambient path"):
    // `--file /` has no parent — the fallback must still land on a real
    // directory so the command executes instead of crashing the run.
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        "/",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.startsWith("could not append to /"),
          s"the run observes, then the append to / is named: $reason"
        )
      case Outcome.Ran(n)       => fail(s"appending to / must not succeed, got Ran($n)")
      case Outcome.Finding(msg) => fail(s"a root ledger path is undetermined, not a finding: $msg")
    assert(err.contains("could not append"), s"the append failure is named: $err")

  test("parseArgs records a boolean flag's presence as 1"):
    val result: Either[CliError, Map[String, String]] =
      SubcommandWiring.parseArgs(
        Array("--forgive-unchanged"),
        Set("--file"),
        Set("--forgive-unchanged")
      )
    assertEquals(result, Right(Map("--forgive-unchanged" -> "1")))

  test("appendLedgerLine names the failed append in its reason"):
    val dir: Path = Files.createTempDirectory("append-dir")
    SubcommandWiring.appendLedgerLine(dir.toString, "{}") match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.startsWith(s"could not append to $dir"),
          s"the reason names the failed append: $reason"
        )
      case Outcome.Ran(_)       => fail("appending to a directory must not succeed")
      case Outcome.Finding(msg) => fail(s"appending to a directory is undetermined, not a finding: $msg")

  test("argErrorMessage names each parse-error class as the predecessor does"):
    assertEquals(
      SubcommandWiring.argErrorMessage(CliError.MissingValue("--file")),
      "--file requires a value"
    )
    assertEquals(
      SubcommandWiring.argErrorMessage(CliError.UnknownFlag("--bogus")),
      "unrecognised argument: --bogus"
    )
    assertEquals(
      SubcommandWiring.argErrorMessage(CliError.UnknownSubcommand("frobnicate")),
      "unrecognised argument: frobnicate"
    )
    assertEquals(
      SubcommandWiring.argErrorMessage(CliError.InvalidEnum("--format", "yaml")),
      "unrecognised argument: yaml"
    )
    assertEquals(
      SubcommandWiring.argErrorMessage(CliError.ForbiddenFlag("--exit")),
      "flag not accepted here: --exit"
    )

  test("absoluteGitDirOf resolves the git dir under a work tree"):
    val cwd: Path = Path.of("").toAbsolutePath
    SubcommandWiring.absoluteGitDirOf(cwd) match
      case Some(gitDir) =>
        assert(gitDir.toString.endsWith(".git"), s"the git dir is traced: $gitDir")
      case None => fail("the test run's working tree must resolve a git dir")

  test("writeTextFile names the failed write in its Left reason"):
    val dir: Path = Files.createTempDirectory("write-dir")
    SubcommandWiring.writeTextFile(dir, "x") match
      case Left(reason) =>
        assert(
          reason.startsWith(s"could not write $dir"),
          s"the reason names the failed write: $reason"
        )
      case Right(_) => fail("writing to a directory must not succeed")

  // ── Ring 5 second pass — exact outcome payloads and stderr text, so a
  //    skipped check cannot hide behind a later validation layer ───────

  test("run mode without --file names it before any other check"):
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "ledger: --file is required\n")
        assertEquals(msg, "--file is required")
      case Outcome.Ran(n)               => fail(s"run without --file must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing --file is a finding, not undetermined: $reason")

  test("an unrecognised ledger flag is diagnosed by name on stderr"):
    val (_, err, outcome) = runLedger(Array("append", "--bogus"))
    outcome match
      case Outcome.Finding(msg) =>
        assertEquals(err, "ledger: unrecognised argument: --bogus\n")
        assertEquals(msg, "arg parse error: --bogus")
      case Outcome.Ran(n)               => fail(s"an unknown flag must not succeed, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"an unknown flag is a finding, not undetermined: $reason")

  test("run mode refuses an R8 row without --session — the early check's exact diagnostic"):
    // The early check's message differs from the validator's clause-14
    // fallback ("clause 14 — session is required for R8 …"), which a
    // skipped check would emit under the same substring.
    val dir: Path    = Files.createTempDirectory("ledger-run-r8-nosession")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, err, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R8",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    outcome match
      case Outcome.Finding(msg) =>
        // The artifact warning precedes — the session diagnostic is the
        // final line, and it is the early check's own text (the clause-14
        // fallback reads "clause 14 — session is required …").
        assert(
          err.endsWith(
            "ledger: session is required for R8 (adversarial-review) rows — pass --session with the producing session's identity\n"
          ),
          s"the early check's exact diagnostic is emitted: $err"
        )
        assertEquals(msg, "session is required for R8 rows")
      case Outcome.Ran(n)               => fail(s"an R8 run without session must not record, got Ran($n)")
      case Outcome.Undetermined(reason) => fail(s"a missing session is a finding, not undetermined: $reason")

  test("run mode's digest covers stderr — the captured output merges both streams"):
    val dir: Path    = Files.createTempDirectory("ledger-run-stderr")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val (_, _, outcome) = runLedger(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "c",
        "--spec",
        "s",
        "--ring",
        "R0",
        "--obligation",
        "o",
        "--artifact",
        "a",
        "--baseline",
        "abc1234",
        "--",
        "echo oops >&2"
      )
    )
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"run must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"run must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(ledger, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    val row: ujson.Value = ujson.read(lines.headOption.getOrElse(fail("no run row written")))
    assertEquals(
      row("digest").str,
      SubcommandWiring.sha256Hex("oops\n".getBytes(StandardCharsets.UTF_8)),
      "stderr output is part of the merged capture's digest"
    )

  test("append separates a row from a ledger that does not end in a newline"):
    val dir: Path  = Files.createTempDirectory("ledger-append-nonl")
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(file, ujson.write(ledgerRow("R0", exit = 0, command = "true")).getBytes(StandardCharsets.UTF_8))
    val (_, _, outcome) = runLedger(appendArgs(file, Nil))
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"append must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"append must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"append must not be undetermined: $reason")
    val lines: List[String] =
      Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList
    assertEquals(lines.length, 2, "the separator makes the appended row its own line")

  test("append to an already-terminated ledger adds no blank row"):
    val dir: Path  = Files.createTempDirectory("ledger-append-terminated")
    val file: Path = dir.resolve("ledger.jsonl")
    Files.write(
      file,
      (ujson.write(ledgerRow("R0", exit = 0, command = "true")) + "\n").getBytes(StandardCharsets.UTF_8)
    )
    val (_, _, outcome) = runLedger(appendArgs(file, Nil))
    outcome match
      case Outcome.Ran(0)               => ()
      case Outcome.Ran(n)               => fail(s"append must exit 0, got Ran($n)")
      case Outcome.Finding(msg)         => fail(s"append must not be a finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"append must not be undetermined: $reason")
    val content: String = Files.readString(file, StandardCharsets.UTF_8)
    assertEquals(
      content.count(_ == '\n'),
      2,
      "a terminated ledger gets exactly one new line — no blank row between records"
    )

  // ── Compile-Negative: a modification operation name on the record
  //    tool's operation type ────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Compile-Negative: A modification operation name on the record tool's operation type

  test("modification operation names do not compile — append-only invariant"):
    // compileErrors requires a literal snippet — the four forbidden
    // names are spelled out, one assertion each.
    assert(compileErrors("LedgerCmd.Action.Update").nonEmpty, "Update must not exist")
    assert(compileErrors("LedgerCmd.Action.Delete").nonEmpty, "Delete must not exist")
    assert(compileErrors("LedgerCmd.Action.Rewrite").nonEmpty, "Rewrite must not exist")
    assert(compileErrors("LedgerCmd.Action.Edit").nonEmpty, "Edit must not exist")

  // ── Compile-Negative: ReplayVerdict pattern match omitting a case ───
  // spec: ledger-checkpoint-parity — Compile-Negative: ReplayVerdict pattern match omitting a case
  //
  // The escalation mechanism is -Werror on the production sources: an
  // inexhaustive match over the sealed ADT is a warning the build turns
  // into an error — `compileErrors` compiles snippets without that flag,
  // so the snippet level check is the ADT's own closure: a fourth
  // verdict name (the "skipped" escape hatch the totality requirement
  // forbids) must not be nameable.

  test("a fourth replay-verdict name — the skipped escape hatch — does not compile"):
    val err: String = compileErrors("ReplayVerdict.Skipped")
    assert(
      err.nonEmpty,
      "no skipped verdict may exist — every record receives exactly one of Matches/Diverges/Unreplayable"
    )

  test("ReplayVerdict is exactly the three-case sealed ADT"):
    assertEquals(
      ReplayVerdict.values.toList.map(_.toString).sorted,
      List("Diverges", "Matches", "Unreplayable"),
      "the verdict domain is Matches/Diverges/Unreplayable — nothing else"
    )
