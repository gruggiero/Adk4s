package org.sinemenda.probatio.core

/**
 * Test oracle for Ledger.read with 15-clause validation.
 *
 * spec: port-scanner-to-probatio/provenance-validation — Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined
 */
final class LedgerReadSpec extends ProbatioSuite:

  // A valid JSON record with all 10 required fields + optional provenance.
  private def validRecordJson(ring: String, session: Option[String] = None): ujson.Value =
    val base: Map[String, ujson.Value] = Map(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-08T12:34:56Z"),
      "change"     -> ujson.Str("port-scanner-to-probatio"),
      "spec"       -> ujson.Str("probatio-core"),
      "ring"       -> ujson.Str(ring),
      "obligation" -> ujson.Str("F7 reachability"),
      "artifact"   -> ujson.Str("workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala"),
      "command"    -> ujson.Str("sbt probatio-core/test"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234")
    )
    val withSession: Map[String, ujson.Value] = session match
      case Some(s) => base.updated("session", ujson.Str(s))
      case None    => base
    ujson.Obj.from(withSession.toList)

  // ── Scenario: a ledger with all valid rows is read successfully
  // spec: provenance-validation — Scenario: a ledger with all valid rows is read successfully
  test("a ledger with 3 valid rows is read successfully"):
    val rows: List[ujson.Value] = List(
      validRecordJson("R3"),
      validRecordJson("R8", Some("devin-cli-session-1")),
      validRecordJson("R3", Some("devin-cli-session-2"))
    )
    val result: Either[LedgerReadError, List[ValidatedRecord]] = Ledger.readValidated(rows)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(records) =>
        assertEquals(records.length, 3)
        assertEquals(records(1).provenance.session, Some("devin-cli-session-1"))
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: a ledger with a malformed adversarial-review ring row is rejected as undetermined (adversarial)
  // spec: provenance-validation — Scenario: a ledger with a malformed adversarial-review ring row is rejected as undetermined (adversarial)
  test("a ledger with R8 row missing session is rejected as undetermined naming row 2"):
    val rows: List[ujson.Value] = List(
      validRecordJson("R3"),
      validRecordJson("R8"), // missing session
      validRecordJson("R3")
    )
    val result: Either[LedgerReadError, List[ValidatedRecord]] = Ledger.readValidated(rows)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(e: LedgerReadError.MalformedRow) =>
        assertEquals(e.rowIndex, 1) // 0-based
        assert(
          e.violation.clauseIndex == 14,
          s"Expected clause 14 (session provenance), got ${e.violation.clauseIndex}"
        )
        assert(e.description.contains("row 1"), s"Expected description to contain 'row 1', got: ${e.description}")
      case other => fail(s"Expected MalformedRow, got $other")

  // ── Scenario: a ledger with a malformed optional field is rejected as undetermined (adversarial)
  // spec: provenance-validation — Scenario: a ledger with a malformed optional field is rejected as undetermined (adversarial)
  test("a ledger with non-string sha256 in row 1 is rejected as undetermined"):
    val baseFields: Map[String, ujson.Value] = Map(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-08T12:34:56Z"),
      "change"     -> ujson.Str("port-scanner-to-probatio"),
      "spec"       -> ujson.Str("probatio-core"),
      "ring"       -> ujson.Str("R3"),
      "obligation" -> ujson.Str("F7 reachability"),
      "artifact"   -> ujson.Str("workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala"),
      "command"    -> ujson.Str("sbt probatio-core/test"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234"),
      "sha256"     -> ujson.Num(42)
    )
    val rows: List[ujson.Value] = List(
      ujson.Obj.from(baseFields.toList),
      validRecordJson("R3"),
      validRecordJson("R3")
    )
    val result: Either[LedgerReadError, List[ValidatedRecord]] = Ledger.readValidated(rows)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(e: LedgerReadError.MalformedRow) =>
        assertEquals(e.rowIndex, 0) // 0-based
        assert(
          e.violation.clauseIndex == 12,
          s"Expected clause 12 (optional field type), got ${e.violation.clauseIndex}"
        )
      case other => fail(s"Expected MalformedRow, got $other")

  // ── Scenario: an empty ledger is read as zero records, not undetermined
  // spec: provenance-validation — Scenario: an empty ledger is read as zero records, not undetermined
  test("an empty ledger is read as zero records, not undetermined"):
    val rows: List[ujson.Value]                                = List.empty
    val result: Either[LedgerReadError, List[ValidatedRecord]] = Ledger.readValidated(rows)
    assert(result.isRight, s"Expected Right for empty ledger, got $result")
    result match
      case Right(records) => assertEquals(records.length, 0)
      case Left(e)        => fail(s"Expected Right (empty), got Left($e)")
