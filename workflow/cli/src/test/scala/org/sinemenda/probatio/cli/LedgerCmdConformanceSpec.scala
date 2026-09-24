package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.*

import scala.jdk.CollectionConverters.*

/**
 * Test oracle for the `ledger` subcommand wiring (cli-wiring spec).
 *
 * Tests the 15-clause validator wiring, the append-before-write invariant,
 * the R8-session requirement, and the three-way exit protocol. Derived from
 * the spec's requirements and scenarios — NOT from the implementation.
 *
 * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
 * spec: cli-wiring — Property: ledger-append-contract-conformance
 * spec: cli-wiring — Property: three-way-exit-protocol-faithfulness
 */
final class LedgerCmdConformanceSpec extends ProbatioCliSuite:

  // ── Scenario: A conformant record is appended successfully
  // spec: cli-wiring — Scenario: A conformant record is appended successfully

  test("ledger append with all required fields validates against all 15 clauses"):
    val record: ujson.Value = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-26T12:00:00Z"),
      "change"     -> ujson.Str("test-change"),
      "spec"       -> ujson.Str("test-spec"),
      "ring"       -> ujson.Str("R0"),
      "obligation" -> ujson.Str("test obligation"),
      "artifact"   -> ujson.Str("Test.scala"),
      "command"    -> ujson.Str("sbt test"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234")
    )
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(record)
    assert(result.isRight, s"conformant record should validate: $result")

  // ── Scenario: A record missing a required field is rejected before writing
  // spec: cli-wiring — Scenario: A record missing a required field is rejected before writing

  test("ledger append missing --change is rejected with Finding naming the missing field"):
    val record: ujson.Value = ujson.Obj(
      "v"  -> ujson.Num(1),
      "ts" -> ujson.Str("2026-08-26T12:00:00Z"),
      // change deliberately omitted
      "spec"       -> ujson.Str("test-spec"),
      "ring"       -> ujson.Str("R0"),
      "obligation" -> ujson.Str("test obligation"),
      "artifact"   -> ujson.Str("Test.scala"),
      "command"    -> ujson.Str("sbt test"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234")
    )
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(record)
    result match
      case Left(v: ContractViolation.MissingRequiredFields) =>
        assert(v.missingFields.contains("change"), s"missing field should name 'change': ${v.missingFields}")
      case other => fail(s"expected MissingRequiredFields, got $other")

  // ── Scenario: An adversarial-review-ring record without a session is rejected
  // spec: cli-wiring — Scenario: An adversarial-review-ring record without a session is rejected

  test("ledger append with R8 ring and no session is rejected (clause 14)"):
    val record: ujson.Value = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-26T12:00:00Z"),
      "change"     -> ujson.Str("test-change"),
      "spec"       -> ujson.Str("test-spec"),
      "ring"       -> ujson.Str("R8"),
      "obligation" -> ujson.Str("adversarial review"),
      "artifact"   -> ujson.Str("review.md"),
      "command"    -> ujson.Str("adversarial review"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234")
      // session deliberately omitted
    )
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(record)
    result match
      case Left(v: ContractViolation.SessionProvenanceInvalid) =>
        assert(v.clauseIndex == 14, s"clause index should be 14, got ${v.clauseIndex}")
      case other => fail(s"expected SessionProvenanceInvalid, got $other")

  // ── Scenario: An unreadable ledger file produces undetermined, not clean
  // spec: cli-wiring — Scenario: An unreadable ledger file produces undetermined

  test("ledger read on nonexistent file produces Undetermined, not Ran"):
    // The wired entrypoint should return Undetermined for a nonexistent file.
    // The stub currently returns Ran(0) — this test is RED until wired.
    val outcome: Outcome[Int] = LedgerCmd.run(Array("read", "--file", "/nonexistent/path", "--change", "test"))
    outcome match
      case Outcome.Undetermined(reason) => assert(reason.contains("UNDETERMINED") || reason.nonEmpty)
      case Outcome.Ran(_)               => fail("ledger read on nonexistent file returned Ran — should be Undetermined")
      case Outcome.Finding(_) => fail("ledger read on nonexistent file returned Finding — should be Undetermined")

  // ── Scenario: The run action observes the command exit code
  // spec: cli-wiring — Scenario: The run action observes the command exit code

  test("ledger run records the observed exit code, not the caller's"):
    // The run action executes the command after --, observes its exit code,
    // and appends a self-observed record. The entrypoint exits 0 (the run
    // itself succeeded; the observed exit is recorded, not reflected).
    // This test is RED until the run action is wired.
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        "/tmp/test-ledger.jsonl",
        "--change",
        "test",
        "--spec",
        "test-spec",
        "--ring",
        "R0",
        "--obligation",
        "test",
        "--artifact",
        "Test.scala",
        "--baseline",
        "abc1234",
        "--",
        "true"
      )
    )
    // The run action should succeed (exit 0) regardless of the observed command's exit.
    outcome match
      case Outcome.Ran(0)               => () // expected — run succeeded
      case Outcome.Ran(n)               => fail(s"run should exit 0, got $n")
      case Outcome.Finding(msg)         => fail(s"run should not produce Finding: $msg")
      case Outcome.Undetermined(reason) => fail(s"run should not produce Undetermined: $reason")

  // ── Property: ledger-append-contract-conformance
  // spec: cli-wiring — Property: ledger-append-contract-conformance

  property("ledger-append-contract-conformance"):
    for
      change     <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
      spec       <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
      ring       <- Gen.element1("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "manual").forAll
      obligation <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      artifact   <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
      command    <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
      exitCode   <- Gen.int(Range.linear(-999, 999)).forAll
      baseline   <- Gen.string(Gen.char('a', 'f'), Range.linear(7, 40)).forAll
      session    <- Gen.string(Gen.alphaNum, Range.linear(0, 20)).forAll
    yield
      // Build a record JSON matching the ledger-record-contract.jq shape.
      // R8 rows require session; non-R8 rows may omit it.
      val baseRecord: ujson.Obj = ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-26T12:00:00Z"),
        "change"     -> ujson.Str(change),
        "spec"       -> ujson.Str(spec),
        "ring"       -> ujson.Str(ring),
        "obligation" -> ujson.Str(obligation),
        "artifact"   -> ujson.Str(artifact),
        "command"    -> ujson.Str(command),
        "exit"       -> ujson.Num(exitCode),
        "baseline"   -> ujson.Str(baseline)
      )
      // Add session for R8 rows
      if ring == "R8" && session.nonEmpty then baseRecord.value("session") = ujson.Str(session)
      else if ring == "R8" then baseRecord.value("session") = ujson.Str("default-session")

      val validationResult: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(baseRecord)
      // The validator and the contract should agree:
      // - if the validator accepts, the record is conformant
      // - if the validator rejects, the record is non-conformant
      // Bidirectional equivalence is the property.
      val validatorAccepts: Boolean = validationResult.isRight
      // The contract (ledger-record-contract.jq) accepts iff:
      //   - all required fields present and valid
      //   - ring is in the closed domain
      //   - baseline is lowercase hex 7-40
      //   - R8 rows have session
      val ringValid: Boolean     = Ring.fromString(ring).isDefined
      val baselineValid: Boolean = baseline.matches("^[0-9a-f]{7,40}$")
      val r8SessionOk: Boolean   = ring != "R8" || session.nonEmpty || baseRecord.value.contains("session")
      val contractAccepts: Boolean =
        ringValid && baselineValid && r8SessionOk && change.nonEmpty && spec.nonEmpty && obligation.nonEmpty && artifact.nonEmpty && command.nonEmpty

      Result.diff(validatorAccepts, contractAccepts)(_ == _)

  // ── Ring 4 contract-conformance: persisted records satisfy the record
  //    contract checker itself (spec 7) ─────────────────────────────────
  // spec: ledger-checkpoint-parity — Requirement: A record written by the observing path carries the fields that make it self-observed
  //
  // The wire contract is stated ONCE — in
  // `scanner/ledger-record-contract.jq` — and the tool and this oracle both
  // conform to it. `Validator.validateFull` agreeing is not enough: the
  // contract checker itself is executed (`jq -e -f`) over every row the
  // observing path persists, so an observation field the contract does not
  // admit (or one whose shape drifts from the contract's type checks)
  // cannot slip through a green run.

  private val ledgerContractPath: java.nio.file.Path =
    LazyList
      .unfold(java.nio.file.Paths.get("").toAbsolutePath.normalize)((p: java.nio.file.Path) =>
        Option(p.getParent).map((par: java.nio.file.Path) => p -> par)
      )
      .find(p =>
        java.nio.file.Files.isRegularFile(
          p.resolve("openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq")
        )
      )
      .getOrElse(java.nio.file.Paths.get("").toAbsolutePath)
      .resolve("openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq")

  /** `echo '<row>' | jq -e -f ledger-record-contract.jq` — exit 0 = conforms. */
  private def contractAccepts(row: String): Boolean =
    val pb: ProcessBuilder =
      new ProcessBuilder("jq", "-e", "-f", ledgerContractPath.toString)
    val p: Process = pb.start()
    p.getOutputStream.write(row.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    p.getOutputStream.close()
    p.getInputStream.readAllBytes()
    p.getErrorStream.readAllBytes()
    p.waitFor() == 0

  test("a record persisted by the run path satisfies ledger-record-contract.jq"):
    val dir: java.nio.file.Path      = java.nio.file.Files.createTempDirectory("contract-conformance")
    val ledger: java.nio.file.Path   = dir.resolve("ledger.jsonl")
    val artifact: java.nio.file.Path = dir.resolve("Artifact.scala")
    java.nio.file.Files.writeString(artifact, "object Artifact\n", java.nio.charset.StandardCharsets.UTF_8)
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array(
        "run",
        "--file",
        ledger.toString,
        "--change",
        "test-change",
        "--spec",
        "test-spec",
        "--ring",
        "R0",
        "--obligation",
        "test obligation",
        "--artifact",
        artifact.toString,
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
    val rows: List[String] =
      java.nio.file.Files
        .readAllLines(ledger, java.nio.charset.StandardCharsets.UTF_8)
        .asScala
        .toList
        .filter(_.nonEmpty)
    assertEquals(rows.length, 1, "the run path persists exactly one row")
    val persistedRow: String =
      rows.headOption.getOrElse(fail("no record line persisted"))
    // The persisted row — observation fields and all — is checked by the
    // contract checker itself, not by a second statement of the contract.
    assert(
      contractAccepts(persistedRow),
      s"persisted run-mode row does not satisfy ledger-record-contract.jq:\n$persistedRow"
    )
    // And the observation fields the contract admits are the ones present —
    // the contract rejects a `source` other than "ambient", a non-string
    // digest, a non-integer wallTime; a row passing the checker cannot
    // carry a silently-misshapen observation field.
    val parsed: ujson.Value = ujson.read(persistedRow)
    assert(parsed.obj.contains("digest"), "run-mode row must carry digest")
    assert(parsed.obj.contains("wallTime"), "run-mode row must carry wallTime")
    assert(parsed("wallTime").num == parsed("wallTime").num.floor, "wallTime must be an integer")

  test("every row of the shipped mixed-shape fixture satisfies ledger-record-contract.jq"):
    val fixture: java.nio.file.Path =
      ledgerContractPath.getParent.getParent.resolve("tests/fixtures/evidence-ledger-v1.jsonl")
    assert(
      java.nio.file.Files.isRegularFile(fixture),
      s"mixed-shape fixture missing at $fixture"
    )
    val rows: List[String] =
      java.nio.file.Files
        .readAllLines(fixture, java.nio.charset.StandardCharsets.UTF_8)
        .asScala
        .toList
        .filter(_.nonEmpty)
    assert(rows.nonEmpty, "the fixture holds at least one row")
    rows.foreach { row =>
      assert(
        contractAccepts(row),
        s"fixture row does not satisfy ledger-record-contract.jq:\n$row"
      )
    }

  // ══════════════════════════════════════════════════════════════════════
  // spec 8 — ledger-checkpoint-cutover: the ported evidence tool agrees
  // with the executable record contract in both directions.
  // Written from the spec's scenarios and the approved contract — NOT
  // from the implementation.
  // ══════════════════════════════════════════════════════════════════════

  /** A fully-conforming v1 row. */
  private def conformingRow: ujson.Obj = ujson.Obj(
    "v"          -> ujson.Num(1),
    "ts"         -> ujson.Str("2026-09-24T00:00:00Z"),
    "change"     -> ujson.Str("chg"),
    "spec"       -> ujson.Str("spc"),
    "ring"       -> ujson.Str("R1"),
    "obligation" -> ujson.Str("obligation"),
    "artifact"   -> ujson.Str("artifact"),
    "command"    -> ujson.Str("true"),
    "exit"       -> ujson.Num(0),
    "baseline"   -> ujson.Str("abc1234")
  )

  private def setField(k: String, v: ujson.Value): ujson.Obj => ujson.Obj =
    (r: ujson.Obj) =>
      r.value(k) = v
      r

  /**
   * One isolated violation per contract clause — each mutation breaks
   * exactly one clause of `ledger-record-contract.jq` while leaving the
   * rest of the row conforming. Expressed as ROW TRANSFORMERS so the
   * same clause can be applied to any conforming base — the fixed row
   * (the single-clause test) and every generated row (the property).
   */
  private def clauseMutations: List[(String, ujson.Obj => ujson.Obj)] = List(
    "missing required field" -> { (r: ujson.Obj) =>
      r.value.remove("obligation"); r
    },
    "v non-integer"             -> setField("v", ujson.Str("x")),
    "v below supported floor"   -> setField("v", ujson.Num(0)),
    "ts non-string"             -> setField("ts", ujson.Num(1)),
    "ts wrong shape"            -> setField("ts", ujson.Str("not-a-date")),
    "ts fractional seconds"     -> setField("ts", ujson.Str("2026-09-24T00:00:00.5Z")),
    "change empty"              -> setField("change", ujson.Str("")),
    "change path separator"     -> setField("change", ujson.Str("a/b")),
    "spec empty"                -> setField("spec", ujson.Str("")),
    "spec path separator"       -> setField("spec", ujson.Str("a/b")),
    "ring non-string"           -> setField("ring", ujson.Num(1)),
    "ring outside domain"       -> setField("ring", ujson.Str("R42")),
    "obligation empty"          -> setField("obligation", ujson.Str("")),
    "artifact empty"            -> setField("artifact", ujson.Str("")),
    "command empty"             -> setField("command", ujson.Str("")),
    "exit non-integer"          -> setField("exit", ujson.Num(1.5)),
    "baseline non-string"       -> setField("baseline", ujson.Num(1)),
    "baseline wrong shape"      -> setField("baseline", ujson.Str("XYZ")),
    "sha256 non-string"         -> setField("sha256", ujson.Num(1)),
    "digest non-string"         -> setField("digest", ujson.Num(1)),
    "wallTime non-integer"      -> setField("wallTime", ujson.Num(1.5)),
    "source outside closed set" -> setField("source", ujson.Str("typed")),
    "R8 missing session" -> { (r: ujson.Obj) =>
      r.value("ring") = ujson.Str("R8"); r.value.remove("session"); r
    },
    "R8 empty session" -> { (r: ujson.Obj) =>
      r.value("ring") = ujson.Str("R8"); r.value("session") = ujson.Str(""); r
    },
    "session non-string" -> setField("session", ujson.Num(1)),
    "session empty"      -> setField("session", ujson.Str(""))
  )

  /**
   * Each mutation applied to the fixed conforming base — every row
   * violates exactly one clause.
   */
  private def clauseViolations: List[(String, ujson.Obj)] =
    clauseMutations.map { case (name: String, f: (ujson.Obj => ujson.Obj)) => name -> f(conformingRow) }

  // ── Scenario: Happy path — a conforming record is accepted by both
  // spec: ledger-checkpoint-cutover — Scenario: Happy path — a conforming record is accepted by both
  test("a conforming record is accepted by the ported validator and the executable contract"):
    val row: ujson.Obj                                     = conformingRow
    val judged: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(row)
    assert(judged.isRight, s"the ported validator must accept a conforming record: $judged")
    assert(
      contractAccepts(ujson.write(row)),
      "the executable contract must accept the same conforming record"
    )

  // ── Scenario: Adversarial — a record violating one clause is rejected
  //    by both ─────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Scenario: Adversarial — a record violating one clause is rejected by both
  test("a record violating exactly one clause is rejected by both, for each clause in turn"):
    clauseViolations.foreach { case (clause: String, row: ujson.Obj) =>
      val judged: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(row)
      assert(judged.isLeft, s"clause [$clause]: the ported validator must reject, got $judged")
      assert(
        !contractAccepts(ujson.write(row)),
        s"clause [$clause]: the executable contract must reject:\n${ujson.write(row)}"
      )
    }

  // ── Scenario: Error path — a record at an unrecognised format version
  //    is could-not-determine ──────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Scenario: Error path — a record at an unrecognised format version is could-not-determine
  // The version guard is the TOOL's read path (the contract's v>=1 floor
  // is forward-compatible by design; the reader knows only v1). PO-table
  // amendment: the check lives in the cli read path, so the scenario
  // runs here rather than in core's LedgerReadSpec.
  test("a record at an unrecognised format version is could-not-determine naming the version"):
    val dir: java.nio.file.Path    = java.nio.file.Files.createTempDirectory("unrecognised-version")
    val ledger: java.nio.file.Path = dir.resolve("ledger.jsonl")
    val good: String               = ujson.write(conformingRow)
    val future: String =
      ujson.write {
        val r: ujson.Obj = conformingRow; r.value("v") = ujson.Num(99); r.value("obligation") = ujson.Str("future-row");
        r
      }
    java.nio.file.Files.writeString(ledger, good + "\n" + future + "\n", java.nio.charset.StandardCharsets.UTF_8)
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array("read", "--file", ledger.toString, "--change", "chg")
    )
    outcome match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("format version 99"),
          s"the refusal must name the unrecognised version: $reason"
        )
        assert(reason.contains("reader knows 1"), s"the refusal must state the known version: $reason")
      case Outcome.Ran(n) =>
        fail(s"an unrecognised version must not produce a clean read, got Ran($n)")
      case Outcome.Finding(msg) =>
        fail(s"an unrecognised version is could-not-determine, not a finding: $msg")

  // ── Deterministic boundary pins — the two divergence classes the
  //    fresh-context review found, exercised every run (not left to the
  //    property's draw). ───────────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Property: record-validator-agrees-with-the-contract

  test("a fractional-seconds timestamp is rejected by both — the contract's shape is strict"):
    val row: ujson.Obj = setField("ts", ujson.Str("2026-09-24T00:00:00.5Z"))(conformingRow)
    assert(
      Validator.validateFull(row).isLeft,
      "the ported validator must reject a fractional-seconds ts — the contract has no such form"
    )
    assert(
      !contractAccepts(ujson.write(row)),
      s"the executable contract must reject the same row:\n${ujson.write(row)}"
    )

  test("integral values beyond Int32 are accepted by both — the domain is the whole doubles, not Int32"):
    List("v", "exit", "wallTime").foreach { (field: String) =>
      val row: ujson.Obj = setField(field, ujson.Num(3000000000.0))(conformingRow)
      assert(
        Validator.validateFull(row).isRight,
        s"$field=3000000000: the ported validator must accept an integral JSON number beyond Int32"
      )
      assert(
        contractAccepts(ujson.write(row)),
        s"$field=3000000000: the executable contract must accept:\n${ujson.write(row)}"
      )
    }

  // ── Property: record-validator-agrees-with-the-contract
  // spec: ledger-checkpoint-cutover — Property: record-validator-agrees-with-the-contract
  // Constructive per the spec's declared `genLedgerRecord` strategy:
  // conforming rows are BUILT (each required field drawn from a lawful
  // domain, each optional field independently present or absent) so both
  // acceptance directions are generated by construction; violating rows
  // are a generated conforming row with exactly one clause mutated —
  // covering each clause independently. The judge on the contract side
  // is the EXECUTABLE contract itself (`jq -e -f`), not a restatement.
  property("record-validator-agrees-with-the-contract"):
    for pair <- genLedgerRecord.forAll
        .cover(30, "conforming", (p: (String, ujson.Obj)) => p._1 == "conforming")
        .cover(30, "violating", (p: (String, ujson.Obj)) => p._1.startsWith("violating"))
        .cover(10, "edge", (p: (String, ujson.Obj)) => p._1.startsWith("edge"))
        .cover(5, "corpus", (p: (String, ujson.Obj)) => p._1.startsWith("corpus"))
    yield
      val (clause: String, row: ujson.Obj) = pair
      val ported: Boolean                  = Validator.validateFull(row).isRight
      val contract: Boolean                = contractAccepts(ujson.write(row))
      Result
        .assert(ported == contract)
        .log(s"clause=$clause ported=$ported contract=$contract row=${ujson.write(row)}")

  /**
   * Lawful value domains per clause — three or more values each so the
   * conforming pool exercises every clause independently, including the
   * contract's real boundaries: `v`/`exit`/`wallTime` admit the whole
   * integral-double domain (jq numbers are doubles — Int32 is NOT the
   * domain), `ts` is the strict form only, and `manual` is a lawful ring.
   */
  private def versionValues: List[Double] =
    List(1.0, 2.0, 3.0, 3000000000.0, 9007199254740992.0)
  private def timestampValues: List[String] =
    List("2026-09-24T00:00:00Z", "2025-01-01T23:59:59Z", "2027-12-31T12:30:45Z", "1999-06-30T06:06:06Z")
  private def scopeValues: List[String] = List("chg", "spec-8", "a.b.c", "change_42", "X", "spéc-8", "変更-42")
  private def ringValues: List[String] =
    List("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "manual")
  private def textValues: List[String] = List("obligation", "test obligation", "a", "obl-42", "obligation-π", "日本語の義務")
  private def commandValues: List[String] =
    List("true", "sbt test", "echo 'a'", "exit 3", "echo 'héllo π'", "日本語のコマンド")
  private def exitValues: List[Double]     = List(0.0, 1.0, 2.0, 127.0, 255.0, -1.0, 3000000000.0)
  private def baselineValues: List[String] = List("abc1234", "1234567", "deadbeef0", "f" * 40)

  /**
   * A conforming record, BUILT field-by-field from lawful domains —
   * including the contract's edge values: `sha256`/`digest` are
   * contract-conforming when empty (type-only clauses) and free text
   * fields carry non-ASCII values; `session` is always non-empty
   * (clause 15) and `source` is only ever `"ambient"` (closed set).
   */
  private def genConforming: Gen[ujson.Obj] =
    for
      v          <- Gen.elementUnsafe(versionValues)
      ts         <- Gen.elementUnsafe(timestampValues)
      change     <- Gen.elementUnsafe(scopeValues)
      spec       <- Gen.elementUnsafe(scopeValues)
      ring       <- Gen.elementUnsafe(ringValues)
      obligation <- Gen.elementUnsafe(textValues)
      artifact   <- Gen.elementUnsafe(textValues)
      command    <- Gen.elementUnsafe(commandValues)
      exit       <- Gen.elementUnsafe(exitValues)
      baseline   <- Gen.elementUnsafe(baselineValues)
      sha256     <- Gen.elementUnsafe(List(None, Some("a" * 64), Some(""), Some("café-π-日本語")))
      digest     <- Gen.elementUnsafe(List(None, Some("d1gest"), Some(""), Some("日本語ダイジェスト")))
      wall       <- Gen.elementUnsafe(List(None, Some(0.0), Some(42.0), Some(3000000000.0)))
      source     <- Gen.elementUnsafe(List(None, Some("ambient")))
      session    <- Gen.elementUnsafe(List(None, Some("sess-1"), Some("devin-cli-abc"), Some("セッション-π")))
    yield
      val row: ujson.Obj = ujson.Obj(
        "v"          -> ujson.Num(v),
        "ts"         -> ujson.Str(ts),
        "change"     -> ujson.Str(change),
        "spec"       -> ujson.Str(spec),
        "ring"       -> ujson.Str(ring),
        "obligation" -> ujson.Str(obligation),
        "artifact"   -> ujson.Str(artifact),
        "command"    -> ujson.Str(command),
        "exit"       -> ujson.Num(exit),
        "baseline"   -> ujson.Str(baseline)
      )
      sha256.foreach((s: String) => row.value("sha256") = ujson.Str(s))
      digest.foreach((s: String) => row.value("digest") = ujson.Str(s))
      wall.foreach((n: Double) => row.value("wallTime") = ujson.Num(n))
      source.foreach((s: String) => row.value("source") = ujson.Str(s))
      // R8 REQUIRES a session — an R8 row without one is violating, so a
      // conforming draw supplies one unconditionally.
      val sess: Option[String] = if ring == "R8" then Some(session.getOrElse("sess-r8")) else session
      sess.foreach((s: String) => row.value("session") = ujson.Str(s))
      row

  /**
   * Boundary rows both sides must judge identically — the contract's own
   * edges: the strict timestamp has no fractional or offset form, and
   * the integral-JSON-number domain extends past Int32 (and to negative
   * exits — `floor(-1) == -1` conforms).
   */
  private def edgeRows: List[(String, ujson.Obj)] =
    List(
      "edge: ts fractional .5"      -> setField("ts", ujson.Str("2026-09-24T00:00:00.5Z"))(conformingRow),
      "edge: ts fractional millis"  -> setField("ts", ujson.Str("2026-09-24T00:00:00.123Z"))(conformingRow),
      "edge: ts numeric offset"     -> setField("ts", ujson.Str("2026-09-24T00:00:00+00:00"))(conformingRow),
      "edge: v beyond Int32"        -> setField("v", ujson.Num(3000000000.0))(conformingRow),
      "edge: v at 2^53"             -> setField("v", ujson.Num(9007199254740992.0))(conformingRow),
      "edge: v fractional"          -> setField("v", ujson.Num(1.5))(conformingRow),
      "edge: exit beyond Int32"     -> setField("exit", ujson.Num(3000000000.0))(conformingRow),
      "edge: exit negative"         -> setField("exit", ujson.Num(-1.0))(conformingRow),
      "edge: wallTime beyond Int32" -> setField("wallTime", ujson.Num(3000000000.0))(conformingRow),
      "edge: wallTime negative"     -> setField("wallTime", ujson.Num(-7.0))(conformingRow),
      // The spec's declared edge cases, constructively: the minimal
      // record (no optional fields — conformingRow IS minimal), the
      // all-optional record, empty optional strings (contract-conforming
      // for sha256/digest — type-only clauses), and non-ASCII values.
      "edge: minimal record" -> conformingRow,
      "edge: all optional fields" -> {
        val r: ujson.Obj = conformingRow
        r.value("sha256") = ujson.Str("a" * 64)
        r.value("digest") = ujson.Str("b" * 64)
        r.value("wallTime") = ujson.Num(42)
        r.value("source") = ujson.Str("ambient")
        r.value("session") = ujson.Str("sess-1")
        r
      },
      "edge: empty sha256"      -> setField("sha256", ujson.Str(""))(conformingRow),
      "edge: empty digest"      -> setField("digest", ujson.Str(""))(conformingRow),
      "edge: non-ASCII command" -> setField("command", ujson.Str("echo 'héllo π'"))(conformingRow),
      "edge: non-ASCII obligation" -> setField("obligation", ujson.Str("日本語の義務"))(conformingRow),
      "edge: non-ASCII session" -> setField("session", ujson.Str("セッション-π"))(conformingRow)
    )

  /**
   * The committed fixture corpus, as a generated-domain class — the
   * spec's declared augmentation: fixture rows are not merely
   * jq-checked in a separate test, they are drawn INSIDE the property
   * so the ported-vs-contract agreement is asserted on them too.
   */
  private def corpusRows: List[(String, ujson.Obj)] =
    val fixture: java.nio.file.Path =
      ledgerContractPath.getParent.getParent.resolve("tests/fixtures/evidence-ledger-v1.jsonl")
    java.nio.file.Files
      .readAllLines(fixture, java.nio.charset.StandardCharsets.UTF_8)
      .asScala
      .toList
      .filter((line: String) => line.trim.nonEmpty)
      .zipWithIndex
      .map { case (line: String, i: Int) =>
        ujson.read(line) match
          case row: ujson.Obj => s"corpus: fixture row $i" -> row
          case other          => sys.error(s"fixture row $i is not a JSON object: $other") // danger-scan:allow unreachable — the committed fixture is all objects
      }

  /**
   * `genLedgerRecord` — the spec's declared generator: conforming rows
   * built per-clause, violating rows built per-clause-mutation, plus the
   * contract-boundary edge pool and the committed fixture corpus.
   */
  def genLedgerRecord: Gen[(String, ujson.Obj)] =
    Gen.frequency1(
      35 -> genConforming.map((r: ujson.Obj) => "conforming" -> r),
      40 -> genViolating,
      15 -> Gen.elementUnsafe(edgeRows),
      10 -> Gen.elementUnsafe(corpusRows)
    )

  /** A generated conforming row with exactly one clause mutated. */
  private def genViolating: Gen[(String, ujson.Obj)] =
    for
      row      <- genConforming
      mutation <- Gen.elementUnsafe(clauseMutations)
    yield
      val (name: String, f: (ujson.Obj => ujson.Obj)) = mutation
      s"violating: $name" -> f(row)
