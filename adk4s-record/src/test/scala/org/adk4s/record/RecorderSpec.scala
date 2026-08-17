package org.adk4s.record

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric
import io.github.iltotore.iron.refineEither
import org.adk4s.core.types.Positive
import org.adk4s.record.canonical.CallKind
import smithy4s.Document

/**
 * Test oracle for the recorder-sink spec — 5 properties + 9 scenarios +
 * 2 compile-negative stubs.
 *
 * These properties are derived from the SPEC (not from the implementation).
 * Before implementation, all properties are RED (NotImplementedError).
 * After implementation, all properties should be GREEN.
 *
 * spec: add-adk4s-record/recorder-sink — Property: record-lookup-coherence
 * spec: add-adk4s-record/recorder-sink — Property: append-only-monotonicity
 * spec: add-adk4s-record/recorder-sink — Property: codec-round-trip
 * spec: add-adk4s-record/recorder-sink — Property: failure-fidelity
 * spec: add-adk4s-record/recorder-sink — Property: bounded-eviction-preserves-recency
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class RecorderSpec extends HedgehogSuite:

  import RecorderSpec.*

  private val config: PropertyConfig => PropertyConfig = _.copy(testLimit = SuccessCount(500))

  // ── Scenarios (munit) ─────────────────────────────────────────────────

  test("Noop recorder returns None on lookup"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Noop recorder returns None on lookup
    val result: Option[CallRecord] =
      (for
        rec    <- IO.pure(Recorder.noop[IO])
        result <- rec.lookup(CallKey.fromDigest("anykey"))
      yield result).unsafeRunSync()
    assertEquals(result, None)

  test("Noop recorder's record is a no-op"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Noop recorder's record is a no-op
    val rec: Recorder[IO] = Recorder.noop[IO]
    val result: Option[CallRecord] =
      (for
        _ <- rec.record(CallKey.fromDigest("k"), genSampleSucceededRecord)
        r <- rec.lookup(CallKey.fromDigest("k"))
      yield r).unsafeRunSync()
    assertEquals(result, None)

  test("InMemory recorder is bounded"):
    // spec: add-adk4s-record/recorder-sink — Scenario: InMemory recorder is bounded
    val maxEntries: Positive = 3
    val result: Int =
      (for
        rec <- Recorder.inMemory[IO](maxEntries)
        _   <- rec.record(CallKey.fromDigest("k1"), genSampleSucceededRecord)
        _   <- rec.record(CallKey.fromDigest("k2"), genSampleSucceededRecord)
        _   <- rec.record(CallKey.fromDigest("k3"), genSampleSucceededRecord)
        _   <- rec.record(CallKey.fromDigest("k4"), genSampleSucceededRecord)
        // After 4 records with maxEntries=3, at most 3 are stored
        r1 <- rec.lookup(CallKey.fromDigest("k1"))
        r2 <- rec.lookup(CallKey.fromDigest("k2"))
        r3 <- rec.lookup(CallKey.fromDigest("k3"))
        r4 <- rec.lookup(CallKey.fromDigest("k4"))
        count = List(r1, r2, r3, r4).count(_.isDefined)
      yield count).unsafeRunSync()
    assert(result <= 3, s"Expected at most 3 entries, got $result")

  test("File recorder appends JSONL"):
    // spec: add-adk4s-record/recorder-sink — Scenario: File recorder appends JSONL
    import java.nio.file.{ Files, Path => JPath }
    val tmpPath: JPath = Files.createTempFile("recorder-test-", ".jsonl")
    tmpPath.toFile.deleteOnExit()
    val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
    val result: String =
      (for
        _ <- Recorder
          .file[IO](fs2Path)
          .use(rec => rec.record(CallKey.fromDigest("k1"), genSampleSucceededRecord))
        content <- IO(Files.readString(tmpPath))
      yield content).unsafeRunSync()
    assert(result.nonEmpty, "File should have content after record")
    assert(result.contains("\n"), "File should contain at least one JSONL line")

  test("Duplicate key in append-only recorder — first-written wins"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Duplicate key in append-only recorder
    import java.nio.file.{ Files, Path => JPath }
    val tmpPath: JPath = Files.createTempFile("recorder-dup-", ".jsonl")
    tmpPath.toFile.deleteOnExit()
    val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
    val key: CallKey              = CallKey.fromDigest("dup-key")
    val firstRecord: CallRecord   = genSampleSucceededRecord
    val secondRecord: CallRecord  = genSampleSucceededRecord
    val result: Option[CallRecord] =
      (for
        _ <- Recorder
          .file[IO](fs2Path)
          .use(rec =>
            for
              _ <- rec.record(key, firstRecord)
              _ <- rec.record(key, secondRecord)
            yield ()
          )
        // Re-open for read
        rec2 <- Recorder.file[IO](fs2Path).use(r => r.lookup(key))
      yield rec2).unsafeRunSync()
    // First-written record should win in append-only mode
    assert(result.isDefined, "Lookup should return a record")
    result match
      case Some(rec) => assert(rec == firstRecord, "First-written record should win")
      case None      => fail("Expected a record")

  test("Eviction at capacity"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Eviction at capacity
    val maxEntries: Positive = 2
    val result: (Boolean, Boolean, Boolean) =
      (for
        rec <- Recorder.inMemory[IO](maxEntries)
        _   <- rec.record(CallKey.fromDigest("A"), genSampleSucceededRecord)
        _   <- rec.record(CallKey.fromDigest("B"), genSampleSucceededRecord)
        _   <- rec.record(CallKey.fromDigest("C"), genSampleSucceededRecord)
        a   <- rec.lookup(CallKey.fromDigest("A"))
        b   <- rec.lookup(CallKey.fromDigest("B"))
        c   <- rec.lookup(CallKey.fromDigest("C"))
      yield (a.isDefined, b.isDefined, c.isDefined)).unsafeRunSync()
    // C must be stored; one of A or B is evicted
    assert(result._3, "C must be stored after eviction")
    assert(result._1 || result._2, "At least one of A/B must remain")

  test("Sequence increments on each call"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Sequence increments on each call
    val result: (Long, Long, Long) =
      (for
        rec <- Recorder.inMemory[IO](1: Positive)
        s1  <- rec.nextSeq
        s2  <- rec.nextSeq
        s3  <- rec.nextSeq
      yield (s1, s2, s3)).unsafeRunSync()
    assert(result._1 < result._2, s"seq1 < seq2: $result")
    assert(result._2 < result._3, s"seq2 < seq3: $result")

  test("Record contains key and seq"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Record contains key and seq
    val key: CallKey = CallKey.fromDigest("key-seq-5")
    val result: Option[CallRecord] =
      (for
        rec <- Recorder.inMemory[IO](10: Positive)
        _   <- rec.record(key, genSampleSucceededRecord)
        r   <- rec.lookup(key)
      yield r).unsafeRunSync()
    assert(result.isDefined, "Record should be found")
    result match
      case Some(rec) =>
        val (k, s): (String, Long) = extractKeyAndSeq(rec)
        assertEquals(k, key.value)
        assert(s >= 0L, "seq should be non-negative")
      case None => fail("Expected a record")

  test("Failed call is recorded as failure variant"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Failed call is recorded as failure variant
    val key: CallKey         = CallKey.fromDigest("fail-key")
    val error: RecordedError = RecordedError("LlmCallError", "timeout", None)
    val failedRecord: CallRecord = CallRecord.failed(
      FailedRecord(key.value, 1L, CallKind.MODEL, error, Classification.INTERNAL)
    )
    val result: Option[CallRecord] =
      (for
        rec <- Recorder.inMemory[IO](10: Positive)
        _   <- rec.record(key, failedRecord)
        r   <- rec.lookup(key)
      yield r).unsafeRunSync()
    assert(result.isDefined, "Failed record should be found")
    result match
      case Some(rec) =>
        rec match
          case _: CallRecord.FailedCase    => () // expected
          case _: CallRecord.SucceededCase => fail("expected FailedCase")
      case None => fail("Expected a record")

  test("Successful call is recorded as success variant"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Successful call is recorded as success variant
    val key: CallKey = CallKey.fromDigest("success-key")
    val result: Option[CallRecord] =
      (for
        rec <- Recorder.inMemory[IO](10: Positive)
        _   <- rec.record(key, genSampleSucceededRecord)
        r   <- rec.lookup(key)
      yield r).unsafeRunSync()
    assert(result.isDefined, "Success record should be found")
    result match
      case Some(rec) =>
        rec match
          case _: CallRecord.SucceededCase => () // expected
          case _: CallRecord.FailedCase    => fail("expected SucceededCase")
      case None => fail("Expected a record")

  test("Classification travels with record"):
    // spec: add-adk4s-record/recorder-sink — Scenario: Classification travels with record
    val key: CallKey = CallKey.fromDigest("class-key")
    val classifiedRecord: CallRecord = CallRecord.succeeded(
      SucceededRecord(
        key.value,
        1L,
        CallKind.MODEL,
        RecordPayload.model(ModelPayload("content", "", 0L, "", None, None, None, None, None)),
        Classification.CONFIDENTIAL
      )
    )
    val result: Option[CallRecord] =
      (for
        rec <- Recorder.inMemory[IO](10: Positive)
        _   <- rec.record(key, classifiedRecord)
        r   <- rec.lookup(key)
      yield r).unsafeRunSync()
    assert(result.isDefined, "Record should be found")
    result match
      case Some(rec) =>
        val cls: Classification = extractClassification(rec)
        assertEquals(cls, Classification.CONFIDENTIAL)
      case None => fail("Expected a record")

  // ── Ring 5 mutation-killing properties ────────────────────────────────
  // stryker4s 0.21.0 detects Hedgehog property failures but not munit test
  // failures, so these are written as properties with Gen.constant inputs.

  property("R5: File recorder re-open loads multiple JSONL lines", config):
    // spec: add-adk4s-record/recorder-sink — Requirement: Append-only recorders do not overwrite or delete
    // Ring 5: kills mutants in loadExistingIndex (line filtering, exists check)
    import java.nio.file.{ Files, Path => JPath }
    for
      keyDigestA <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
      keyDigestB <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
      payloadA   <- Gen.string(Gen.alphaNum, Range.linear(0, 50)).forAll
      payloadB   <- Gen.string(Gen.alphaNum, Range.linear(0, 50)).forAll
    yield
      val tmpPath: JPath = Files.createTempFile("recorder-r5-reopen-", ".jsonl")
      tmpPath.toFile.deleteOnExit()
      val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
      val keyA: CallKey             = CallKey.fromDigest(keyDigestA)
      val keyB: CallKey             = CallKey.fromDigest(keyDigestB)
      val recA: CallRecord =
        CallRecord.succeeded(
          SucceededRecord(
            keyA.value,
            1L,
            CallKind.MODEL,
            RecordPayload.model(ModelPayload(payloadA, "", 0L, "", None, None, None, None, None)),
            Classification.INTERNAL
          )
        )
      val recB: CallRecord =
        CallRecord.succeeded(
          SucceededRecord(
            keyB.value,
            2L,
            CallKind.MODEL,
            RecordPayload.model(ModelPayload(payloadB, "", 0L, "", None, None, None, None, None)),
            Classification.INTERNAL
          )
        )
      val result: (Option[CallRecord], Option[CallRecord]) =
        (for
          _ <- Recorder
            .file[IO](fs2Path)
            .use(rec =>
              for
                _ <- rec.record(keyA, recA)
                _ <- rec.record(keyB, recB)
              yield ()
            )
          rec2 <- Recorder
            .file[IO](fs2Path)
            .use(r =>
              for
                a <- r.lookup(keyA)
                b <- r.lookup(keyB)
              yield (a, b)
            )
        yield rec2).unsafeRunSync()
      Result
        .assert(result._1.isDefined)
        .log("first record loaded on re-open")
        .and(Result.assert(result._2.isDefined).log("second record loaded on re-open"))
        .and(Result.assert(result._1.contains(recA)).log("first record matches"))
        .and(Result.assert(result._2.contains(recB)).log("second record matches"))

  property("R5: File recorder skips blank lines on re-open", config):
    // spec: add-adk4s-record/recorder-sink — Requirement: Append-only recorders do not overwrite or delete
    // Ring 5: kills _.nonEmpty → _.isEmpty and filter → filterNot mutants
    import java.nio.file.{ Files, Path => JPath }
    for
      keyDigest <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
      payload   <- Gen.string(Gen.alphaNum, Range.linear(0, 50)).forAll
    yield
      val tmpPath: JPath = Files.createTempFile("recorder-r5-blank-", ".jsonl")
      tmpPath.toFile.deleteOnExit()
      val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
      val key: CallKey              = CallKey.fromDigest(keyDigest)
      val record: CallRecord =
        CallRecord.succeeded(
          SucceededRecord(
            key.value,
            1L,
            CallKind.MODEL,
            RecordPayload.model(ModelPayload(payload, "", 0L, "", None, None, None, None, None)),
            Classification.INTERNAL
          )
        )
      val json: String = smithy4s.json.Json.writeBlob(record)(using CallRecord.schema).toUTF8String
      // Write: valid line, blank line, valid line
      Files.writeString(tmpPath, json + "\n\n" + json + "\n")
      val result: Option[CallRecord] =
        Recorder.file[IO](fs2Path).use(r => r.lookup(key)).unsafeRunSync()
      // If filter is inverted (filterNot(_.nonEmpty)), only blank lines pass
      // and valid lines are skipped → lookup returns None
      Result.assert(result.isDefined).log("record found despite blank lines")

  property("R5: File recorder record first-written-wins without re-open", config):
    // spec: add-adk4s-record/recorder-sink — Requirement: Append-only recorders do not overwrite or delete
    // Ring 5: kills idx.contains → true/false mutants in FileRecorder.record
    // The existing duplicate-key test re-opens the file, which masks the record
    // path because loadExistingIndex also implements first-written-wins.
    import java.nio.file.{ Files, Path => JPath }
    for
      keyDigest <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
      payloadA  <- Gen.string(Gen.alphaNum, Range.linear(0, 50)).forAll
      payloadB  <- Gen.string(Gen.alphaNum, Range.linear(0, 50)).forAll
    yield
      val tmpPath: JPath = Files.createTempFile("recorder-r5-fw-", ".jsonl")
      tmpPath.toFile.deleteOnExit()
      val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
      val key: CallKey              = CallKey.fromDigest(keyDigest)
      val firstRecord: CallRecord =
        CallRecord.succeeded(
          SucceededRecord(
            key.value,
            1L,
            CallKind.MODEL,
            RecordPayload.model(ModelPayload(payloadA, "", 0L, "", None, None, None, None, None)),
            Classification.INTERNAL
          )
        )
      val secondRecord: CallRecord =
        CallRecord.succeeded(
          SucceededRecord(
            key.value,
            2L,
            CallKind.MODEL,
            RecordPayload.model(ModelPayload(payloadB, "", 0L, "", None, None, None, None, None)),
            Classification.INTERNAL
          )
        )
      val result: Option[CallRecord] =
        Recorder
          .file[IO](fs2Path)
          .use(r =>
            for
              _  <- r.record(key, firstRecord)
              _  <- r.record(key, secondRecord)
              lr <- r.lookup(key)
            yield lr
          )
          .unsafeRunSync()
      Result
        .assert(result.isDefined)
        .log("lookup returns a record")
        .and(Result.assert(result.contains(firstRecord)).log("first-written wins in index (no re-open)"))

  property("R5: File recorder writes newline-separated JSONL lines", config):
    // spec: add-adk4s-record/recorder-sink — Requirement: Append-only recorders do not overwrite or delete
    // Ring 5: kills the "\n" → "" StringLiteral mutant
    // Writes two records and verifies the file has exactly 2 newline characters.
    import java.nio.file.{ Files, Path => JPath }
    for
      keyDigestA <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
      keyDigestB <- Gen.string(Gen.alphaNum, Range.linear(8, 32)).forAll
    yield
      val tmpPath: JPath = Files.createTempFile("recorder-r5-nl-", ".jsonl")
      tmpPath.toFile.deleteOnExit()
      val fs2Path: fs2.io.file.Path = fs2.io.file.Path.fromNioPath(tmpPath)
      val keyA: CallKey             = CallKey.fromDigest(keyDigestA)
      val keyB: CallKey             = CallKey.fromDigest(keyDigestB)
      val content: String =
        (for
          _ <- Recorder
            .file[IO](fs2Path)
            .use(rec =>
              for
                _ <- rec.record(keyA, genSampleSucceededRecord)
                _ <- rec.record(keyB, genSampleSucceededRecord)
              yield ()
            )
          c <- IO(Files.readString(tmpPath))
        yield c).unsafeRunSync()
      val lineCount: Int = content.count(_ == '\n')
      Result.assert(lineCount == 2).log(s"expected 2 newlines, got $lineCount")

  property("R5: InMemory recorder evicts oldest at capacity boundary", config):
    // spec: add-adk4s-record/recorder-sink — Requirement: Bounded recorder evicts without failing the call
    // Ring 5: kills withNew.size > maxEntries → false (no eviction)
    for capacity <- Gen.int(Range.linear(1, 10)).forAll
    yield
      val capRefined: Positive = capacity
        .refineEither[numeric.Positive]
        .fold(err => throw RuntimeException(err), identity)
      val nRecords: Int       = capacity + 1
      val keys: List[CallKey] = (1 to nRecords).toList.map(i => CallKey.fromDigest(s"e$i"))
      val result: List[Boolean] =
        (for
          rec     <- Recorder.inMemory[IO](capRefined)
          _       <- keys.traverse_(k => rec.record(k, genSampleSucceededRecord))
          lookups <- keys.traverse(k => rec.lookup(k).map(_.isDefined))
        yield lookups).unsafeRunSync()
      // With eviction: first key (oldest) is evicted → result.headOption == Some(false)
      // Without eviction (mutant): all present → result.headOption == Some(true)
      Result
        .assert(result.headOption.contains(false))
        .log("oldest key must be evicted")
        .and(Result.assert(result.lastOption.contains(true)).log("newest key must be present"))

  // ── Compile-negative obligations ──────────────────────────────────────

  test("Recorder.inMemory(0) does not compile — Iron Positive rejects zero"):
    // spec: add-adk4s-record/recorder-sink — Compile-Negative: Recorder.inMemory(0)
    val errors: String = compileErrors("Recorder.inMemory[IO](0)")
    assert(errors.nonEmpty, "Recorder.inMemory(0) should not compile")

  test("Recorder.inMemory(-1) does not compile — Iron Positive rejects negative"):
    // spec: add-adk4s-record/recorder-sink — Compile-Negative: Recorder.inMemory(-1)
    val errors: String = compileErrors("Recorder.inMemory[IO](-1)")
    assert(errors.nonEmpty, "Recorder.inMemory(-1) should not compile")

  // ── Properties (Ring 3) ───────────────────────────────────────────────

  property("record-lookup-coherence: lookup(k) after record(k,v) returns Some(v)", config):
    // spec: add-adk4s-record/recorder-sink — Property: record-lookup-coherence
    for
      k <- genCallKey.forAll
      j <- genCallKey.forAll
      v <- genCallRecord.forAll
    yield
      val result: (Option[CallRecord], Option[CallRecord]) =
        (for
          rec <- Recorder.inMemory[IO](100: Positive)
          _   <- rec.record(k, v)
          r1  <- rec.lookup(k)
          _   <- rec.record(j, v)
          r2  <- rec.lookup(k)
        yield (r1, r2)).unsafeRunSync()
      Result
        .assert(result._1 == Some(v))
        .log("r1 == Some(v)")
        .and(Result.assert(result._2 == result._1).log("r2 == r1 (unaffected)"))

  property("append-only-monotonicity: nextSeq is strictly increasing", config):
    // spec: add-adk4s-record/recorder-sink — Property: append-only-monotonicity
    for ops <- genSeqOps.forAll
    yield
      val seqs: List[Long] =
        (for
          rec <- Recorder.inMemory[IO](100: Positive)
          seqs <- ops.foldLeftM(List.empty[Long])((acc, op) =>
            op match
              case RecorderOp.NextSeq   => rec.nextSeq.map(acc :+ _)
              case RecorderOp.Lookup(k) => rec.lookup(k).as(acc)
          )
        yield seqs).unsafeRunSync()
      val pairs: List[(Long, Long)] = seqs.zip(seqs.drop(1))
      Result.assert(pairs.forall { case (a, b) => a < b }).log("strictly increasing")

  property("codec-round-trip: read(write(record)) == record", config):
    // spec: add-adk4s-record/recorder-sink — Property: codec-round-trip
    for record <- genCallRecord.forAll
    yield
      val json: String = smithy4s.json.Json
        .writeBlob(record)(using CallRecord.schema)
        .toUTF8String
      val decoded: Either[Throwable, CallRecord] =
        smithy4s.json.Json.read[CallRecord](smithy4s.Blob(json))
      Result.assert(decoded == Right(record)).log("round-trip")

  property("failure-fidelity: recorded failure replays as equal failure", config):
    // spec: add-adk4s-record/recorder-sink — Property: failure-fidelity
    for
      err <- genRecordedError.forAll
      key <- genCallKey.forAll
    yield
      val failedRecord: CallRecord = CallRecord.failed(
        FailedRecord(key.value, 0L, CallKind.MODEL, err, Classification.INTERNAL)
      )
      val result: Option[CallRecord] =
        (for
          rec <- Recorder.inMemory[IO](100: Positive)
          _   <- rec.record(key, failedRecord)
          r   <- rec.lookup(key)
        yield r).unsafeRunSync()
      result match
        case Some(_: CallRecord.FailedCase) =>
          Result.assert(result.contains(failedRecord)).log("equal failure")
        case _ =>
          Result.failure.log("expected FailedCase")

  property("bounded-eviction-preserves-recency: most-recently-written is present", config):
    // spec: add-adk4s-record/recorder-sink — Property: bounded-eviction-preserves-recency
    for
      ops      <- genBoundedOps.forAll
      capacity <- Gen.int(Range.linear(1, 10)).forAll
    yield
      val capRefined: Positive = capacity
        .refineEither[numeric.Positive]
        .fold(err => throw RuntimeException(err), identity)
      val lastKey: Option[CallKey] = ops.lastOption.map(_._1)
      val result: Boolean =
        (for
          rec <- Recorder.inMemory[IO](capRefined)
          _   <- ops.traverse_(op => rec.record(op._1, op._2))
          r <- lastKey match
            case Some(k) => rec.lookup(k).map(_.isDefined)
            case None    => IO.pure(true)
        yield r).unsafeRunSync()
      Result.assert(result).log("most-recently-written is present")

object RecorderSpec:

  // ── Generators ────────────────────────────────────────────────────────

  val genCallKey: Gen[CallKey] =
    Gen.string(Gen.alphaNum, Range.linear(8, 64)).map(CallKey.fromDigest)

  val genClassification: Gen[Classification] =
    Gen.element1(
      Classification.PUBLIC,
      Classification.INTERNAL,
      Classification.CONFIDENTIAL,
      Classification.RESTRICTED
    )

  val genCallKind: Gen[CallKind] =
    Gen.element1(CallKind.MODEL, CallKind.TOOL, CallKind.EMBEDDING)

  val genRecordedError: Gen[RecordedError] =
    for
      errorType <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      message   <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
      hasCause  <- Gen.boolean
      cause     <- Gen.string(Gen.alphaNum, Range.linear(0, 30))
    yield RecordedError(errorType, message, if hasCause then Some(cause) else None)

  val genModelPayload: Gen[ModelPayload] =
    for
      content   <- Gen.string(Gen.alphaNum, Range.linear(0, 100))
      finish    <- Gen.string(Gen.alphaNum, Range.linear(0, 20))
      hasFinish <- Gen.boolean
      hasTokens <- Gen.boolean
      tokens    <- Gen.int(Range.linear(0, 10000))
    yield ModelPayload(
      content = content,
      id = "",
      created = 0L,
      model = "",
      finishReason = if hasFinish then Some(finish) else None,
      toolCalls = None,
      promptTokens = if hasTokens then Some(tokens) else None,
      completionTokens = if hasTokens then Some(tokens) else None,
      totalTokens = if hasTokens then Some(tokens * 2) else None
    )

  val genToolPayload: Gen[ToolPayload] =
    for
      name    <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      callId  <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      isError <- Gen.boolean
    yield ToolPayload(name, Document.DNull, callId, isError)

  val genEmbeddingPayload: Gen[EmbeddingPayload] =
    for
      model     <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      hasTokens <- Gen.boolean
      tokens    <- Gen.int(Range.linear(0, 1000))
    yield EmbeddingPayload(model, if hasTokens then Some(tokens) else None, None)

  val genRecordPayload: Gen[RecordPayload] =
    Gen.frequency(
      40 -> genModelPayload.map(RecordPayload.model(_)),
      List(
        40 -> genToolPayload.map(RecordPayload.tool(_)),
        20 -> genEmbeddingPayload.map(RecordPayload.embedding(_))
      )
    )

  val genSucceededRecord: Gen[CallRecord] =
    for
      key     <- Gen.string(Gen.alphaNum, Range.linear(8, 64))
      seq     <- Gen.long(Range.linear(0, 1000000))
      kind    <- genCallKind
      payload <- genRecordPayload
      cls     <- genClassification
    yield CallRecord.succeeded(
      SucceededRecord(key, seq, kind, payload, cls)
    )

  val genFailedRecord: Gen[CallRecord] =
    for
      key  <- Gen.string(Gen.alphaNum, Range.linear(8, 64))
      seq  <- Gen.long(Range.linear(0, 1000000))
      kind <- genCallKind
      err  <- genRecordedError
      cls  <- genClassification
    yield CallRecord.failed(
      FailedRecord(key, seq, kind, err, cls)
    )

  val genCallRecord: Gen[CallRecord] =
    Gen.frequency1(60 -> genSucceededRecord, 40 -> genFailedRecord)

  enum RecorderOp:
    case NextSeq
    case Lookup(key: CallKey)

  val genSeqOps: Gen[List[RecorderOp]] =
    Gen
      .choice1(
        Gen.constant(RecorderOp.NextSeq),
        genCallKey.map(RecorderOp.Lookup(_))
      )
      .list(Range.linear(1, 20))

  val genBoundedOps: Gen[List[(CallKey, CallRecord)]] =
    genCallRecord.list(Range.linear(1, 15)).map { records =>
      records.map(r => (CallKey.fromDigest(java.util.UUID.randomUUID.toString), r))
    }

  // ── Helpers ───────────────────────────────────────────────────────────

  val genSampleSucceededRecord: CallRecord =
    CallRecord.succeeded(
      SucceededRecord(
        "sample-key",
        1L,
        CallKind.MODEL,
        RecordPayload.model(ModelPayload("content", "", 0L, "", None, None, None, None, None)),
        Classification.INTERNAL
      )
    )

  def extractKeyAndSeq(rec: CallRecord): (String, Long) =
    rec match
      case s: CallRecord.SucceededCase => (s.succeeded.key, s.succeeded.seq)
      case f: CallRecord.FailedCase    => (f.failed.key, f.failed.seq)

  def extractClassification(rec: CallRecord): Classification =
    rec match
      case s: CallRecord.SucceededCase => s.succeeded.classification
      case f: CallRecord.FailedCase    => f.failed.classification
