package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Tests for LedgerRecord, the 12-clause validator, and ContractViolation.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: port-scanner-to-probatio/probatio-core — Property: Validator conforms to the jq contract in both directions
 * spec: port-scanner-to-probatio/probatio-core — Property: ContractViolation totality — every clause is reachable
 */
final class LedgerValidatorSpec extends ProbatioSuite:

  // A valid JSON record used as the base for clause-violation tests.
  private def validRecordJson: ujson.Value =
    ujson.Obj(
      "v" -> 1,
      "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "port-scanner-to-probatio",
      "spec" -> "probatio-core",
      "ring" -> "R3",
      "obligation" -> "F7 reachability",
      "artifact" -> "workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala",
      "command" -> "sbt probatio-core/test",
      "exit" -> 0,
      "baseline" -> "abc1234"
    )

  // ── Scenario: a record satisfying all 12 clauses is accepted
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record satisfying all 12 clauses is accepted
  test("a record satisfying all 12 clauses is accepted"):
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(validRecordJson)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(record) =>
        assertEquals(record.v, 1)
        assertEquals(record.ring, Ring.R3)
        assertEquals(record.baseline, "abc1234")
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: a record missing a required field is rejected with the field named
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record missing a required field is rejected with the field named
  test("a record missing the exit field is rejected with MissingRequiredFields naming exit"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "c", "spec" -> "s", "ring" -> "R3",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.MissingRequiredFields) =>
        assert(v.missingFields.contains("exit"), s"Expected missingFields to contain 'exit', got ${v.missingFields}")
        assertEquals(v.clauseIndex, 1)
      case other => fail(s"Expected MissingRequiredFields, got $other")

  // ── Scenario: a record with a ring outside the closed domain is rejected
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record with a ring outside the closed domain is rejected
  test("a record with ring R99 is rejected with RingOutsideDomain"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "c", "spec" -> "s", "ring" -> "R99",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.RingOutsideDomain) => assertEquals(v.clauseIndex, 6)
      case other => fail(s"Expected RingOutsideDomain, got $other")

  // ── Scenario: a record with a non-integer version is rejected
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record with a non-integer version is rejected
  test("a record with v=1.5 is rejected with VersionInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1.5, "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "c", "spec" -> "s", "ring" -> "R3",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.VersionInvalid) => assertEquals(v.clauseIndex, 2)
      case other => fail(s"Expected VersionInvalid, got $other")

  // ── Scenario: a record with a path separator in the change field is rejected
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record with a path separator in the change field is rejected
  test("a record with change='foo/bar' is rejected with ChangeInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "foo/bar", "spec" -> "s", "ring" -> "R3",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.ChangeInvalid) => assertEquals(v.clauseIndex, 4)
      case other => fail(s"Expected ChangeInvalid, got $other")

  // ── Scenario: a record with a malformed timestamp is rejected
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record with a malformed timestamp is rejected
  test("a record with ts='2026-08-08 12:34:56' is rejected with TimestampInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08 12:34:56",
      "change" -> "c", "spec" -> "s", "ring" -> "R3",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.TimestampInvalid) => assertEquals(v.clauseIndex, 3)
      case other => fail(s"Expected TimestampInvalid, got $other")

  // ── Scenario: a record with a non-hex baseline is rejected
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a record with a non-hex baseline is rejected
  test("a record with baseline='g1234567' is rejected with BaselineInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z",
      "change" -> "c", "spec" -> "s", "ring" -> "R3",
      "obligation" -> "o", "artifact" -> "a", "command" -> "cmd",
      "exit" -> 0, "baseline" -> "g1234567"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.BaselineInvalid) => assertEquals(v.clauseIndex, 11)
      case other => fail(s"Expected BaselineInvalid, got $other")

  // ── Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)
  test("a JSON null is rejected with NotAJsonObject, no exception"):
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(ujson.Null)
    assert(result.isLeft)
    result match
      case Left(v: ContractViolation.NotAJsonObject) => assertEquals(v.clauseIndex, 0)
      case other => fail(s"Expected NotAJsonObject, got $other")

  // ── Compile-Negative: Ring with a value outside the closed domain
  // spec: port-scanner-to-probatio/probatio-core — Compile-Negative: Ring with a value outside the closed domain
  test("Ring.R99 does not exist — compile-negative"):
    val err: String = compileErrors("val r: Ring = Ring.R99")
    assert(err.nonEmpty, "Ring.R99 should not compile — the enum is sealed")

  // ── Compile-Negative: ContractViolation with a 13th variant
  // spec: port-scanner-to-probatio/probatio-core — Compile-Negative: ContractViolation with a 13th variant
  test("ContractViolation is sealed — no 13th variant"):
    val err: String = compileErrors(
      "val v: ContractViolation = new ContractViolation { def clauseIndex = 99; def description = \"x\" }"
    )
    assert(err.nonEmpty, "ContractViolation should be sealed — no new variants")

  // ── Property: ContractViolation totality — every clause is reachable
  // spec: port-scanner-to-probatio/probatio-core — Property: ContractViolation totality — every clause is reachable
  property("contract violation totality — every clause is reachable"):
    for clauseIndex <- Gen.int(Range.linear(0, 11)).forAll
    yield
      val json: ujson.Value = clauseIndex match
        case 0  => ujson.Null
        case 1  => ujson.Obj("v" -> 1) // missing most fields
        case 2  => ujson.Obj(
          "v" -> 1.5, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 3  => ujson.Obj(
          "v" -> 1, "ts" -> "bad", "change" -> "c", "spec" -> "s",
          "ring" -> "R3", "obligation" -> "o", "artifact" -> "a",
          "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 4  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "a/b",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 5  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "x\\y", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 6  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R99", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 7  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 8  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 9  => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "", "exit" -> 0, "baseline" -> "abc1234"
        )
        case 10 => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 1.5, "baseline" -> "abc1234"
        )
        case 11 => ujson.Obj(
          "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
          "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
          "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "ZZZZZZZ"
        )
        case _  => validRecordJson
      val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
      result match
        case Left(violation) =>
          Result.assert(violation.clauseIndex == clauseIndex)
            .log(s"clause $clauseIndex: expected $clauseIndex got ${violation.clauseIndex}")
        case Right(_) =>
          Result.failure.log(s"expected a violation for clause $clauseIndex, got Right")

  // ── Mutation-killing: a float exit value is rejected (not an integer)
  test("a float exit value is rejected as ExitNotInteger"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 1.5, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.ExitNotInteger) => assert(true)
      case other => fail(s"Expected ExitNotInteger, got $other")

  // ── Mutation-killing: a whole-number exit too large for Int is rejected (kills && → ||)
  test("a whole-number exit too large for Int is rejected as ExitNotInteger"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 1e15, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.ExitNotInteger) => assert(true)
      case other => fail(s"Expected ExitNotInteger for 1e15, got $other")

  // ── Mutation-killing: a very large exit value that doesn't fit Int is rejected
  test("a non-integer numeric v is rejected as VersionInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1.5, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.VersionInvalid) => assert(true)
      case other => fail(s"Expected VersionInvalid for v=1.5, got $other")

  // ── Mutation-killing: a string v is rejected as VersionInvalid
  test("a string v is rejected as VersionInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> "1", "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.VersionInvalid) => assert(true)
      case other => fail(s"Expected VersionInvalid for v=\"1\", got $other")

  // ── Mutation-killing: a string exit is rejected as ExitNotInteger
  test("a string exit is rejected as ExitNotInteger"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> "0", "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.ExitNotInteger) => assert(true)
      case other => fail(s"Expected ExitNotInteger for exit=\"0\", got $other")

  // ── Mutation-killing: a null exit is rejected as ExitNotInteger
  test("a null exit is rejected as ExitNotInteger"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> ujson.Null, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.ExitNotInteger) => assert(true)
      case other => fail(s"Expected ExitNotInteger for exit=null, got $other")

  // ── Mutation-killing: a negative v is rejected as VersionInvalid
  test("a negative v is rejected as VersionInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> -1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.VersionInvalid) => assert(true)
      case other => fail(s"Expected VersionInvalid for v=-1, got $other")

  // ── Mutation-killing: a zero v is rejected as VersionInvalid
  test("a zero v is rejected as VersionInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 0, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.VersionInvalid) => assert(true)
      case other => fail(s"Expected VersionInvalid for v=0, got $other")

  // ── Mutation-killing: a ts with lowercase timezone offset is rejected
  test("a ts with non-UTC timezone is rejected as TimestampInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56+02:00", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.TimestampInvalid) => assert(true)
      case other => fail(s"Expected TimestampInvalid for non-UTC ts, got $other")

  // ── Mutation-killing: a change with a path separator is rejected
  test("a change with a path separator is rejected as ChangeInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c/d",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.ChangeInvalid) => assert(true)
      case other => fail(s"Expected ChangeInvalid for change with /, got $other")

  // ── Mutation-killing: a spec with a backslash is rejected
  test("a spec with a backslash is rejected as SpecInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s\\t", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc1234"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.SpecInvalid) => assert(true)
      case other => fail(s"Expected SpecInvalid for spec with \\, got $other")

  // ── Mutation-killing: a baseline with uppercase hex is rejected
  test("a baseline with uppercase hex is rejected as BaselineInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "ABCDEF1"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.BaselineInvalid) => assert(true)
      case other => fail(s"Expected BaselineInvalid for uppercase baseline, got $other")

  // ── Mutation-killing: a baseline too short (< 7 chars) is rejected
  test("a baseline too short is rejected as BaselineInvalid"):
    val json: ujson.Value = ujson.Obj(
      "v" -> 1, "ts" -> "2026-08-08T12:34:56Z", "change" -> "c",
      "spec" -> "s", "ring" -> "R3", "obligation" -> "o",
      "artifact" -> "a", "command" -> "cmd", "exit" -> 0, "baseline" -> "abc123"
    )
    val result: Either[ContractViolation, LedgerRecord] = Validator.validate(json)
    result match
      case Left(_: ContractViolation.BaselineInvalid) => assert(true)
      case other => fail(s"Expected BaselineInvalid for short baseline, got $other")
