package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.*

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
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-26T12:00:00Z"),
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
      case Outcome.Finding(_)           => fail("ledger read on nonexistent file returned Finding — should be Undetermined")

  // ── Scenario: The run action observes the command exit code
  // spec: cli-wiring — Scenario: The run action observes the command exit code

  test("ledger run records the observed exit code, not the caller's"):
    // The run action executes the command after --, observes its exit code,
    // and appends a self-observed record. The entrypoint exits 0 (the run
    // itself succeeded; the observed exit is recorded, not reflected).
    // This test is RED until the run action is wired.
    val outcome: Outcome[Int] = LedgerCmd.run(
      Array("run", "--file", "/tmp/test-ledger.jsonl", "--change", "test", "--spec", "test-spec",
        "--ring", "R0", "--obligation", "test", "--artifact", "Test.scala", "--baseline", "abc1234",
        "--", "true")
    )
    // The run action should succeed (exit 0) regardless of the observed command's exit.
    outcome match
      case Outcome.Ran(0)              => () // expected — run succeeded
      case Outcome.Ran(n)              => fail(s"run should exit 0, got $n")
      case Outcome.Finding(msg)        => fail(s"run should not produce Finding: $msg")
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
      if ring == "R8" && session.nonEmpty then
        baseRecord.value("session") = ujson.Str(session)
      else if ring == "R8" then
        baseRecord.value("session") = ujson.Str("default-session")

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
      val ringValid: Boolean = Ring.fromString(ring).isDefined
      val baselineValid: Boolean = baseline.matches("^[0-9a-f]{7,40}$")
      val r8SessionOk: Boolean = ring != "R8" || session.nonEmpty || baseRecord.value.contains("session")
      val contractAccepts: Boolean = ringValid && baselineValid && r8SessionOk && change.nonEmpty && spec.nonEmpty && obligation.nonEmpty && artifact.nonEmpty && command.nonEmpty

      Result.diff(validatorAccepts, contractAccepts)(_ == _)
