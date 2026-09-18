package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Test oracle for the 15-clause provenance validator.
 *
 * spec: port-scanner-to-probatio/provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
 * spec: port-scanner-to-probatio/provenance-validation — Property: validator-conforms-to-jq-contract-in-both-directions-15-clauses
 * spec: port-scanner-to-probatio/provenance-validation — Property: ContractViolation-totality-15-clauses
 * spec: port-scanner-to-probatio/provenance-validation — Property: adversarial-review-ring-session-presence-is-enforced
 */
final class ProvenanceValidatorSpec extends ProbatioSuite:

  // A valid JSON record with all 10 required fields (clauses 0–11 pass).
  // Optional provenance fields can be added via the `withOpt` helper.
  private def validRecordJson(
    ring: String = "R3",
    sha256: Option[ujson.Value] = None,
    digest: Option[ujson.Value] = None,
    wallTime: Option[ujson.Value] = None,
    source: Option[ujson.Value] = None,
    session: Option[ujson.Value] = None
  ): ujson.Value =
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
    val withSha256: Map[String, ujson.Value] = sha256 match
      case Some(v) => base.updated("sha256", v)
      case None    => base
    val withDigest: Map[String, ujson.Value] = digest match
      case Some(v) => withSha256.updated("digest", v)
      case None    => withSha256
    val withWallTime: Map[String, ujson.Value] = wallTime match
      case Some(v) => withDigest.updated("wallTime", v)
      case None    => withDigest
    val withSource: Map[String, ujson.Value] = source match
      case Some(v) => withWallTime.updated("source", v)
      case None    => withWallTime
    val withSession: Map[String, ujson.Value] = session match
      case Some(v) => withSource.updated("session", v)
      case None    => withSource
    ujson.Obj.from(withSession.toList)

  // ── Scenario: an adversarial-review ring row with a valid session is accepted
  // spec: provenance-validation — Scenario: an adversarial-review ring row with a valid session is accepted
  test("R8 row with valid session is accepted"):
    val json: ujson.Value = validRecordJson(ring = "R8", session = Some(ujson.Str("devin-cli-session-r8")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(vr) =>
        assertEquals(vr.provenance.session, Some("devin-cli-session-r8"))
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: an adversarial-review ring row missing session is rejected (adversarial)
  // spec: provenance-validation — Scenario: an adversarial-review ring row missing session is rejected (adversarial)
  test("R8 row missing session is rejected with SessionProvenanceInvalid"):
    val json: ujson.Value                                  = validRecordJson(ring = "R8")
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.SessionProvenanceInvalid) =>
        assert(
          v.description.contains("session is required for R8"),
          s"Expected description to contain 'session is required for R8', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 14)
      case other => fail(s"Expected SessionProvenanceInvalid, got $other")

  // ── Scenario: an adversarial-review ring row with an empty session is rejected (adversarial)
  // spec: provenance-validation — Scenario: an adversarial-review ring row with an empty session is rejected (adversarial)
  test("R8 row with empty session is rejected with SessionProvenanceInvalid"):
    val json: ujson.Value                                  = validRecordJson(ring = "R8", session = Some(ujson.Str("")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.SessionProvenanceInvalid) =>
        assert(
          v.description.contains("session must be a non-empty string"),
          s"Expected description to contain 'session must be a non-empty string', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 14)
      case other => fail(s"Expected SessionProvenanceInvalid, got $other")

  // ── Scenario: a non-adversarial-review ring row with an empty session is rejected (adversarial)
  test("R3 row with empty session is rejected with SessionProvenanceInvalid"):
    val json: ujson.Value                                  = validRecordJson(ring = "R3", session = Some(ujson.Str("")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.SessionProvenanceInvalid) =>
        assert(
          v.description.contains("session must be a non-empty string when present"),
          s"Expected description to contain 'session must be a non-empty string when present', got: ${v.description}"
        )
      case other => fail(s"Expected SessionProvenanceInvalid, got $other")

  // ── Scenario: a non-adversarial-review ring row without session is accepted
  // spec: provenance-validation — Scenario: a non-adversarial-review ring row without session is accepted
  test("R3 row without session is accepted with session=None"):
    val json: ujson.Value                                  = validRecordJson(ring = "R3")
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(vr) =>
        assertEquals(vr.provenance.session, None)
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: a non-adversarial-review ring row with a valid session is accepted
  // spec: provenance-validation — Scenario: a non-adversarial-review ring row with a valid session is accepted
  test("R3 row with valid session is accepted with session=Some"):
    val json: ujson.Value = validRecordJson(ring = "R3", session = Some(ujson.Str("devin-cli-session-abc")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(vr) =>
        assertEquals(vr.provenance.session, Some("devin-cli-session-abc"))
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: a row with source set to a non-ambient value is rejected (adversarial)
  // spec: provenance-validation — Scenario: a row with source set to a non-ambient value is rejected (adversarial)
  test("source='manual' is rejected with ObserverProvenanceInvalid"):
    val json: ujson.Value = validRecordJson(ring = "R3", source = Some(ujson.Str("manual")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.ObserverProvenanceInvalid) =>
        assert(
          v.description.contains("source must be \"ambient\""),
          s"Expected description to contain 'source must be \"ambient\"', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 13)
      case other => fail(s"Expected ObserverProvenanceInvalid, got $other")

  // ── Scenario: a row with source set to ambient is accepted
  // spec: provenance-validation — Scenario: a row with source set to ambient is accepted
  test("source='ambient' is accepted with source=Some"):
    val json: ujson.Value = validRecordJson(ring = "R3", source = Some(ujson.Str("ambient")))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(vr) =>
        assertEquals(vr.provenance.source, Some("ambient"))
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: a row with a non-string sha256 is rejected (adversarial)
  // spec: provenance-validation — Scenario: a row with a non-string sha256 is rejected (adversarial)
  test("sha256=42 (number) is rejected with OptionalFieldTypeInvalid"):
    val json: ujson.Value                                  = validRecordJson(ring = "R3", sha256 = Some(ujson.Num(42)))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.OptionalFieldTypeInvalid) =>
        assert(
          v.description.contains("sha256 must be a string"),
          s"Expected description to contain 'sha256 must be a string', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 12)
      case other => fail(s"Expected OptionalFieldTypeInvalid, got $other")

  // ── Scenario: a row with a non-string digest is rejected (adversarial)
  // spec: provenance-validation — Scenario: a row with a non-string digest is rejected (adversarial)
  test("digest=42 (number) is rejected with OptionalFieldTypeInvalid"):
    val json: ujson.Value                                  = validRecordJson(ring = "R3", digest = Some(ujson.Num(42)))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.OptionalFieldTypeInvalid) =>
        assert(
          v.description.contains("digest must be a string"),
          s"Expected description to contain 'digest must be a string', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 12)
      case other => fail(s"Expected OptionalFieldTypeInvalid, got $other")

  // ── Scenario: a row with a non-integer wallTime is rejected (adversarial)
  // spec: provenance-validation — Scenario: a row with a non-integer wallTime is rejected (adversarial)
  test("wallTime=1.5 (non-integer) is rejected with OptionalFieldTypeInvalid"):
    val json: ujson.Value = validRecordJson(ring = "R3", wallTime = Some(ujson.Num(1.5)))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.OptionalFieldTypeInvalid) =>
        assert(
          v.description.contains("wallTime must be an integer"),
          s"Expected description to contain 'wallTime must be an integer', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 12)
      case other => fail(s"Expected OptionalFieldTypeInvalid, got $other")

  test("wallTime=1e20 (integer-valued but not Int-representable) is rejected with OptionalFieldTypeInvalid"):
    val json: ujson.Value = validRecordJson(ring = "R3", wallTime = Some(ujson.Num(1e20)))
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.OptionalFieldTypeInvalid) =>
        assert(
          v.description.contains("wallTime must be an integer"),
          s"Expected description to contain 'wallTime must be an integer', got: ${v.description}"
        )
        assertEquals(v.clauseIndex, 12)
      case other => fail(s"Expected OptionalFieldTypeInvalid, got $other")

  // ── Scenario: a row with all optional fields valid is accepted
  // spec: provenance-validation — Scenario: a row with all optional fields valid is accepted
  test("all optional fields valid is accepted with all Some"):
    val json: ujson.Value = validRecordJson(
      ring = "R3",
      sha256 = Some(ujson.Str("a1b2c3d4e5f6")),
      digest = Some(ujson.Str("e1f2a3b4c5d6")),
      wallTime = Some(ujson.Num(15000)),
      source = Some(ujson.Str("ambient")),
      session = Some(ujson.Str("devin-cli-session-xyz"))
    )
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(vr) =>
        assertEquals(vr.provenance.sha256, Some("a1b2c3d4e5f6"))
        assertEquals(vr.provenance.digest, Some("e1f2a3b4c5d6"))
        assertEquals(vr.provenance.wallTime, Some(15000))
        assertEquals(vr.provenance.source, Some("ambient"))
        assertEquals(vr.provenance.session, Some("devin-cli-session-xyz"))
      case Left(v) => fail(s"Expected Right, got Left($v)")

  // ── Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)
  // spec: provenance-validation — Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)
  test("validateFull is total — null input is rejected with NotAJsonObject"):
    val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(ujson.Null)
    assert(result.isLeft, s"Expected Left, got $result")
    result match
      case Left(v: ContractViolation.NotAJsonObject) => assertEquals(v.clauseIndex, 0)
      case other                                     => fail(s"Expected NotAJsonObject, got $other")

  // Helper: build a valid record with one field overridden.
  private def recordWith(field: String, value: ujson.Value): ujson.Value =
    val base: Map[String, ujson.Value] = Map(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-08-08T12:34:56Z"),
      "change"     -> ujson.Str("port-scanner-to-probatio"),
      "spec"       -> ujson.Str("probatio-core"),
      "ring"       -> ujson.Str("R3"),
      "obligation" -> ujson.Str("F7 reachability"),
      "artifact"   -> ujson.Str("workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala"),
      "command"    -> ujson.Str("sbt probatio-core/test"),
      "exit"       -> ujson.Num(0),
      "baseline"   -> ujson.Str("abc1234")
    )
    ujson.Obj.from(base.updated(field, value).toList)

  // ── Property: ContractViolation totality — every clause 0-14 is reachable
  // spec: provenance-validation — Property: ContractViolation-totality-15-clauses
  property("ContractViolation totality — every clause 0-14 is reachable"):
    for clauseIdx <- Gen.int(Range.linear(0, 14)).forAll
    yield
      val json: ujson.Value = clauseIdx match
        case 0  => ujson.Null
        case 1  => ujson.Obj("v" -> 1)
        case 2  => recordWith("v", ujson.Num(1.5))
        case 3  => recordWith("ts", ujson.Str("bad"))
        case 4  => recordWith("change", ujson.Str("a/b"))
        case 5  => recordWith("spec", ujson.Str("x\\y"))
        case 6  => recordWith("ring", ujson.Str("R99"))
        case 7  => recordWith("obligation", ujson.Str(""))
        case 8  => recordWith("artifact", ujson.Str(""))
        case 9  => recordWith("command", ujson.Str(""))
        case 10 => recordWith("exit", ujson.Num(1.5))
        case 11 => recordWith("baseline", ujson.Str("ZZZZZZZ"))
        case 12 => validRecordJson(sha256 = Some(ujson.Num(42)))
        case 13 => validRecordJson(source = Some(ujson.Str("manual")))
        case 14 => validRecordJson(ring = "R8") // R8 missing session
      val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
      result match
        case Left(v) =>
          Result
            .assert(v.clauseIndex == clauseIdx)
            .log(s"clause $clauseIdx: expected $clauseIdx got ${v.clauseIndex}")
        case Right(_) =>
          Result.failure.log(s"expected a violation for clause $clauseIdx, got Right")

  // ── Property: adversarial-review ring rows require a non-empty session
  // spec: provenance-validation — Property: adversarial-review-ring-session-presence-is-enforced
  property("adversarial-review ring rows require a non-empty session"):
    for session <- Gen
        .choice1(
          Gen.constant(None),
          Gen.string(Gen.alphaNum, Range.linear(0, 20)).map(Some(_))
        )
        .forAll
    yield
      val json: ujson.Value = session match
        case Some(s) => validRecordJson(ring = "R8", session = Some(ujson.Str(s)))
        case None    => validRecordJson(ring = "R8")
      val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
      val isNonEmpty: Boolean                                = session.exists(s => s.nonEmpty)
      (isNonEmpty, result) match
        case (true, Right(_)) =>
          Result.success
        case (true, Left(v)) =>
          Result.failure.log(s"R8 with non-empty session should be accepted, got Left(${v.clauseIndex})")
        case (false, Right(_)) =>
          Result.failure.log("R8 with absent/empty session should be rejected, got Right")
        case (false, Left(v)) =>
          Result
            .assert(v.clauseIndex == 14)
            .log(s"R8 with absent/empty session should be rejected at clause 14, got ${v.clauseIndex}")

  // ── Property: validator conforms to jq contract in both directions (15 clauses)
  // A valid record (all 15 clauses pass) is always accepted; a record with
  // any single clause violated is always rejected with that clause index.
  // spec: provenance-validation — Property: validator-conforms-to-jq-contract-in-both-directions-15-clauses
  property("validator conforms to jq contract — valid records accepted, invalid rejected"):
    for
      ring <- Gen.element1("R3", "R8", "R0", "R1", "R2", "R4", "R5", "R6", "R7").forAll
      session <- Gen
        .choice1(
          Gen.constant(None),
          Gen.string(Gen.alphaNum, Range.linear(1, 20)).map(Some(_))
        )
        .forAll
    yield
      val needsSession: Boolean = ring == "R8"
      val hasSession: Boolean   = session.exists(s => s.nonEmpty)
      val json: ujson.Value = validRecordJson(
        ring = ring,
        session = session.map(s => ujson.Str(s))
      )
      val result: Either[ContractViolation, ValidatedRecord] = Validator.validateFull(json)
      val isValid: Boolean                                   = !needsSession || hasSession
      (isValid, result) match
        case (true, Right(_)) =>
          Result.success
        case (true, Left(v)) =>
          Result.failure.log(s"valid record rejected at clause ${v.clauseIndex}: ${v.description}")
        case (false, Right(_)) =>
          Result.failure.log(s"invalid record (R8 without session) accepted, should be rejected")
        case (false, Left(v)) =>
          Result
            .assert(v.clauseIndex == 14)
            .log(s"invalid record rejected at wrong clause ${v.clauseIndex}, expected 14")
