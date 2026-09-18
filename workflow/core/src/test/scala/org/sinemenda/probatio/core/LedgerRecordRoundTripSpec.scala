package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/**
 * Ring 4 oracle for the joined ledger record (spec 7).
 *
 * Written from the spec and the approved Step-1 contract ONLY — before the
 * `???` implementations land. Derived from the spec's requirements and
 * scenarios, NOT from the implementation.
 *
 * The record is the ten required contract fields joined with the optional
 * observation group. The single total encoder emits every present field;
 * reading routes through the total validator — so a written row reads
 * back with every field intact, and a record that drops an optional
 * field on write is a failure of the requirement, not a variant.
 *
 * spec: ledger-checkpoint-parity — Requirement: A record written by the observing path carries the fields that make it self-observed
 * spec: ledger-checkpoint-parity — Property: record-round-trips-all-present-fields
 * spec: ledger-checkpoint-parity — Compile-Negative: A record encoder that serialises only the required fields while an optional-field value is present
 */
final class LedgerRecordRoundTripSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(120))

  // ── Generators ──────────────────────────────────────────────────────

  private val genRing: Gen[Ring] =
    Gen.frequency1(
      9 -> Gen.elementUnsafe(
        List(Ring.R0, Ring.R1, Ring.R2, Ring.R3, Ring.R4, Ring.R5, Ring.R6, Ring.R7, Ring.R9)
      ),
      4 -> Gen.constant(Ring.R8),
      2 -> Gen.constant(Ring.Manual)
    )

  private val genNonEmptyString: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 24))

  private val genBaseline: Gen[String] =
    Gen.string(Gen.char('a', 'f'), Range.linear(7, 40)).map((s: String) => s"abc${s.take(4)}")

  private val genTimestamp: Gen[String] =
    for
      mo <- Gen.int(Range.linear(1, 12)).map((n: Int) => f"$n%02d")
      d  <- Gen.int(Range.linear(1, 28)).map((n: Int) => f"$n%02d")
      h  <- Gen.int(Range.linear(0, 23)).map((n: Int) => f"$n%02d")
      mi <- Gen.int(Range.linear(0, 59)).map((n: Int) => f"$n%02d")
      se <- Gen.int(Range.linear(0, 59)).map((n: Int) => f"$n%02d")
    yield s"2026-$mo-${d}T$h:$mi:${se}Z"

  private val genHex64: Gen[String] =
    Gen.string(Gen.char('a', 'f'), Range.constant(64, 64))

  private def genOptString(shape: Int): Gen[Option[String]] =
    if shape == 0 then Gen.constant(Option.empty[String])
    else if shape == 5 then genHex64.map((s: String) => Option(s))
    else
      Gen.frequency1(
        50 -> genHex64.map((s: String) => Option(s)),
        50 -> Gen.constant(Option.empty[String])
      )

  private def genOptWallTime(shape: Int): Gen[Option[Int]] =
    if shape == 0 then Gen.constant(Option.empty[Int])
    else if shape == 5 then Gen.int(Range.linear(0, 60000)).map((n: Int) => Option(n))
    else
      Gen.frequency1(
        50 -> Gen.int(Range.linear(0, 60000)).map((n: Int) => Option(n)),
        50 -> Gen.constant(Option.empty[Int])
      )

  private def genOptSource(shape: Int): Gen[Option[String]] =
    if shape == 0 then Gen.constant(Option.empty[String])
    else if shape == 5 then Gen.constant(Option("ambient"))
    else
      Gen.frequency1(
        40 -> Gen.constant(Option("ambient")),
        60 -> Gen.constant(Option.empty[String])
      )

  private def genOptSession(shape: Int): Gen[Option[String]] =
    if shape == 0 then Gen.constant(Option.empty[String])
    else if shape == 5 then genNonEmptyString.map((s: String) => Option(s))
    else
      Gen.frequency1(
        40 -> genNonEmptyString.map((s: String) => Option(s)),
        60 -> Gen.constant(Option.empty[String])
      )

  /**
   * `genLedgerRecord` — constructive: all ten required fields inside
   * their contract domains, each of the five optional fields
   * independently present or absent, with a shape selector so the
   * all-absent / all-present / mixed cover classes each occur at spec
   * rates. `source` is generated only as `"ambient"` (the sole
   * contract-valid value); `session` is forced present for R8 rows
   * (clause 14 requires it).
   */
  val genLedgerRecord: Gen[LedgerRecord] =
    for
      // 0 = all five absent, 5 = all five present, -1 = mixed per-field
      shape      <- Gen.frequency1(30 -> Gen.constant(0), 25 -> Gen.constant(5), 45 -> Gen.constant(-1))
      change     <- genNonEmptyString
      spec       <- genNonEmptyString
      ring       <- genRing
      obligation <- genNonEmptyString
      artifact   <- genNonEmptyString
      command    <- genNonEmptyString
      exit       <- Gen.int(Range.linear(-9, 99))
      baseline   <- genBaseline
      ts         <- genTimestamp
      sha256     <- genOptString(shape)
      digest     <- genOptString(shape)
      wallTime   <- genOptWallTime(shape)
      source     <- genOptSource(shape)
      session    <- genOptSession(shape)
    yield
      val sessionForRing: Option[String] =
        if ring == Ring.R8 then session.orElse(Some("reviewer-session")) else session
      val json: ujson.Obj = ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str(ts),
        "change"     -> ujson.Str(change),
        "spec"       -> ujson.Str(spec),
        "ring"       -> ujson.Str(Ring.asString(ring)),
        "obligation" -> ujson.Str(obligation),
        "artifact"   -> ujson.Str(artifact),
        "command"    -> ujson.Str(command),
        "exit"       -> ujson.Num(exit),
        "baseline"   -> ujson.Str(baseline)
      )
      sha256.foreach((s: String) => json.value("sha256") = ujson.Str(s))
      digest.foreach((s: String) => json.value("digest") = ujson.Str(s))
      wallTime.foreach((n: Int) => json.value("wallTime") = ujson.Num(n))
      source.foreach((s: String) => json.value("source") = ujson.Str(s))
      sessionForRing.foreach((s: String) => json.value("session") = ujson.Str(s))
      LedgerRecord.from(json) match
        case Right(r) => r
        case Left(err) =>
          sys.error(
            s"genLedgerRecord produced an invalid record: $err"
          ) // danger-scan:allow unreachable — the generator stays inside the contract domain by construction

  private def optionalPresentCount(r: LedgerRecord): Int =
    List(
      r.optional.sha256,
      r.optional.digest,
      r.optional.wallTime,
      r.optional.source,
      r.optional.session
    ).count(_.isDefined)

  // ── Scenario: Happy path — a self-observed record round-trips its
  //    observation fields ──────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — a self-observed record round-trips its observation fields

  test("a record carrying all observation fields round-trips them unchanged"):
    val record: LedgerRecord =
      LedgerRecord
        .from(
          ujson.Obj(
            "v"          -> ujson.Num(1),
            "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
            "change"     -> ujson.Str("c"),
            "spec"       -> ujson.Str("s"),
            "ring"       -> ujson.Str("R1"),
            "obligation" -> ujson.Str("o"),
            "artifact"   -> ujson.Str("a"),
            "command"    -> ujson.Str("true"),
            "exit"       -> ujson.Num(0),
            "baseline"   -> ujson.Str("abc1234"),
            "sha256"     -> ujson.Str("a" * 64),
            "digest"     -> ujson.Str("b" * 64),
            "wallTime"   -> ujson.Num(42),
            "session"    -> ujson.Str("sess-1")
          )
        )
        .getOrElse(fail("a valid record must construct"))
    val written: String = upickle.default.write(record)
    val readBack: LedgerRecord =
      upickle.default.read[LedgerRecord](written)
    assertEquals(readBack.optional.sha256, Some("a" * 64))
    assertEquals(readBack.optional.digest, Some("b" * 64))
    assertEquals(readBack.optional.wallTime, Some(42))
    assertEquals(readBack.optional.session, Some("sess-1"))

  // ── Scenario: Adversarial — the observation fields are not silently
  //    dropped ────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — the observation fields are not silently dropped

  test("the persisted JSON line contains every present observation field"):
    val record: LedgerRecord =
      LedgerRecord
        .from(
          ujson.Obj(
            "v"          -> ujson.Num(1),
            "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
            "change"     -> ujson.Str("c"),
            "spec"       -> ujson.Str("s"),
            "ring"       -> ujson.Str("R1"),
            "obligation" -> ujson.Str("o"),
            "artifact"   -> ujson.Str("a"),
            "command"    -> ujson.Str("true"),
            "exit"       -> ujson.Num(0),
            "baseline"   -> ujson.Str("abc1234"),
            "sha256"     -> ujson.Str("a" * 64),
            "digest"     -> ujson.Str("b" * 64),
            "wallTime"   -> ujson.Num(7)
          )
        )
        .getOrElse(fail("a valid record must construct"))
    val line: String        = upickle.default.write(record)
    val parsed: ujson.Value = ujson.read(line)
    assert(parsed.obj.contains("sha256"), "sha256 must be present in the persisted line")
    assert(parsed.obj.contains("digest"), "digest must be present in the persisted line")
    assert(parsed.obj.contains("wallTime"), "wallTime must be present in the persisted line")
    assertEquals(parsed("sha256").str, "a" * 64)
    assertEquals(parsed("wallTime").num.toInt, 7)

  // ── Scenario: Happy path — a written assertion carries no observation
  //    fields ─────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — a written assertion carries no observation fields

  test("a record written without observation fields persists none"):
    val record: LedgerRecord =
      LedgerRecord
        .from(
          ujson.Obj(
            "v"          -> ujson.Num(1),
            "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
            "change"     -> ujson.Str("c"),
            "spec"       -> ujson.Str("s"),
            "ring"       -> ujson.Str("R1"),
            "obligation" -> ujson.Str("o"),
            "artifact"   -> ujson.Str("a"),
            "command"    -> ujson.Str("true"),
            "exit"       -> ujson.Num(0),
            "baseline"   -> ujson.Str("abc1234")
          )
        )
        .getOrElse(fail("a valid record must construct"))
    val parsed: ujson.Value = ujson.read(upickle.default.write(record))
    assert(!parsed.obj.contains("sha256"), "a written assertion must not carry sha256")
    assert(!parsed.obj.contains("digest"), "a written assertion must not carry digest")
    assert(!parsed.obj.contains("wallTime"), "a written assertion must not carry wallTime")
    assert(!parsed.obj.contains("source"), "a written assertion must not carry source")
    assert(!parsed.obj.contains("session"), "a written assertion must not carry session")

  // ── Scenario: Edge case — a record set mixing both shapes reads
  //    cleanly ────────────────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Edge case — a record set mixing both shapes reads cleanly

  test("the mixed-shape fixture reads cleanly — every row validated, none rejected"):
    val fixture: Path = LedgerRecordRoundTripSpec.fixturePath
    val rows: List[ujson.Value] =
      Files
        .readAllLines(fixture, StandardCharsets.UTF_8)
        .asScala
        .toList
        .filter((line: String) => line.trim.nonEmpty)
        .map((line: String) => ujson.read(line))
    assertEquals(rows.length, 5, "the fixture holds five rows")
    Ledger.readValidated(rows) match
      case Left(err) => fail(s"a mixed-shape record set must read cleanly: ${err.description}")
      case Right(records) =>
        assertEquals(records.length, 5, "every row is returned")
        val shapes: List[Int] =
          records.map((v: ValidatedRecord) =>
            List(
              v.provenance.sha256,
              v.provenance.digest,
              v.provenance.wallTime,
              v.provenance.source,
              v.provenance.session
            ).count(_.isDefined)
          )
        assert(shapes.exists(_ == 0), "the fixture must contain bare assertion rows")
        assert(shapes.exists(_ > 0), "the fixture must contain rows carrying observation fields")

  // ── Property: record-round-trips-all-present-fields ─────────────────
  // spec: ledger-checkpoint-parity — Property: record-round-trips-all-present-fields

  property("record-round-trips-all-present-fields", coverConfig):
    for r <- genLedgerRecord.forAll
        .cover(15, "all-optional-present", (x: LedgerRecord) => optionalPresentCount(x) == 5)
        .cover(15, "no-optional-present", (x: LedgerRecord) => optionalPresentCount(x) == 0)
        .cover(
          40,
          "some-optional-present",
          (x: LedgerRecord) =>
            val n: Int = optionalPresentCount(x)
            n > 0 && n < 5
        )
        .cover(10, "review-ring", (x: LedgerRecord) => x.ring == Ring.R8)
    yield
      val written: String        = upickle.default.write(r)
      val readBack: LedgerRecord = upickle.default.read[LedgerRecord](written)
      Result
        .assert(readBack == r)
        .log(s"round-trip mismatch\n  wrote: $written\n  read:  $readBack\n  want:  $r")

  // ── Adversarial — reading never maps an invalid row to a record ──────

  test("a row failing validation cannot be read as a LedgerRecord"):
    // upickle wraps the decoder's rejection in a trace exception — the
    // cause carries the validator's description.
    val thrown: upickle.core.TraceVisitor.TraceException =
      intercept[upickle.core.TraceVisitor.TraceException](upickle.default.read[LedgerRecord]("""{"v":1}"""))
    assert(
      thrown.getCause.getMessage.contains("invalid ledger record"),
      s"the rejection must name the record, got: ${thrown.getCause.getMessage}"
    )

  test("an invalid ring token cannot be read as a Ring"):
    import LedgerRecord.given_ReadWriter_Ring
    val badName: upickle.core.TraceVisitor.TraceException =
      intercept[upickle.core.TraceVisitor.TraceException](upickle.default.read[Ring]("\"bogus\""))
    assert(badName.getCause.getMessage.contains("invalid ring"), s"got: ${badName.getCause.getMessage}")
    val badType: upickle.core.TraceVisitor.TraceException =
      intercept[upickle.core.TraceVisitor.TraceException](upickle.default.read[Ring]("42"))
    assert(
      badType.getCause.getMessage.contains("expected ring string"),
      s"got: ${badType.getCause.getMessage}"
    )

  // ── Compile-Negative: a record encoder that serialises only the
  //    required fields while an optional-field value is present ────────
  // spec: ledger-checkpoint-parity — Compile-Negative: A record encoder that serialises only the required fields while an optional-field value is present

  test("a LedgerRecord cannot be constructed without its optional group"):
    val err: String = compileErrors(
      "LedgerRecord(v = 1, ts = \"2026-09-18T00:00:00Z\", change = \"c\", spec = \"s\", ring = Ring.R1, obligation = \"o\", artifact = \"a\", command = \"true\", exit = 0, baseline = \"abc1234\")"
    )
    assert(
      err.nonEmpty,
      "the ten-field constructor must not compile — the observation group is part of the record"
    )

/** The fixture path — resolved from the repository root. */
object LedgerRecordRoundTripSpec:

  private val repoRoot: Path =
    LazyList
      .unfold(Paths.get("").toAbsolutePath.normalize)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(Paths.get("").toAbsolutePath)

  val fixturePath: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3/tests/fixtures/evidence-ledger-v1.jsonl")
