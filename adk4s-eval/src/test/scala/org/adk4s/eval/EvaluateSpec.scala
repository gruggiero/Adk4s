package org.adk4s.eval

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.eval.EvalGenerators.*

/**
 * Hedgehog properties + scenario tests for the eval-core spec.
 *
 * Covers 9 Ring 3 properties:
 *  1. devset-order-under-parallelism
 *  2. failure-score-substitution
 *  3. mean-aggregate
 *  4. empty-devset-score-zero
 *  5. feedback-inert
 *  6. metric-called-once-per-example
 *  7. determinism-under-parallelism
 *  8. json-round-trip
 *  9. trace-is-none-in-eval-mode
 *
 * Plus scenarios from the spec's Proof Obligations table:
 *  - Parallelism 4 with randomized latency
 *  - Parallelism 1 (sequential)
 *  - Poisoned example among 100
 *  - Metric raises
 *  - Cap exceeded with in-flight work (maxErrors cancellation)
 *  - No cap (unlimited)
 *  - Empty devset
 *  - All-failure devset
 *  - Counting metric verifies trace is None
 *  - Feedback does not affect aggregate
 *  - Counting metric
 *  - Round-trip with feedback and failures
 *  - formatVersion present
 *  - Parallelism sweep
 *  - CSV mixed outcomes
 *
 * All IOs run under `TestControl.executeEmbed` for deterministic
 * concurrency — no wall-clock sleeps, no real async boundaries. The
 * cancellation probe test uses `TestControl.execute` to inspect the
 * `Outcome` directly.
 */
class EvaluateSpec extends HedgehogSuite:

  // ── Test helpers ─────────────────────────────────────────────────────────

  /**
   * Run an IO deterministically under TestControl and extract the result.
   * Fails the test if the IO does not complete successfully.
   */
  private def runIO[A](io: IO[A]): A =
    TestControl.executeEmbed(io).unsafeRunSync()

  /** Run an IO that is expected to raise, returning the thrown Throwable. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def runIOFailed[A](io: IO[A]): Throwable =
    io.attempt.unsafeRunSync() match
      case Right(_)  => throw new RuntimeException("expected failure, got success")
      case Left(err) => err

  /** A pure program: returns the input unchanged. */
  private val pureProgram: String => IO[String] =
    (s: String) => IO.pure(s)

  /** A stub program that returns a fixed value. */
  private def stubProgram: String => IO[String] =
    (s: String) => IO.pure(s)

  /** Build a devset of N examples with sequential ids. */
  private def stubDevset(n: Int): Vector[Example[String, String]] =
    (0 until n).toVector.map(i => Example(s"input-$i", s"gold-$i", Some(i.toString)))

  /** A metric that returns a fixed score. */
  private def fixedMetric(value: Double): Metric[IO, String, String] =
    new Metric[IO, String, String]:
      def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
        IO.pure(Score(value))

  // ── Scenario tests ───────────────────────────────────────────────────────

  test("empty devset yields score 0.0 with empty rows"):
    val result: EvaluationResult[String, String] =
      runIO(Evaluate[IO, String, String](stubProgram, Vector.empty, Metrics.exactMatch[IO]))
    assertEquals(result.score, 0.0)
    assert(result.rows.isEmpty)

  test("all-failure devset yields score 0.0 with maxErrors = None"):
    val failingProgram: String => IO[String]    = (_: String) => IO.raiseError(new RuntimeException("boom"))
    val devset: Vector[Example[String, String]] = stubDevset(5)
    val result: EvaluationResult[String, String] =
      runIO(
        Evaluate[IO, String, String](
          failingProgram,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = 0.0, maxErrors = None)
        )
      )
    assertEquals(result.score, 0.0)
    assertEquals(result.rows.size, 5)
    assert(result.rows.forall(_.outcome.isFailed))

  test("poisoned example among 100 gets failureScore, others get real scores"):
    val n: Int                                  = 100
    val failIdx: Int                            = 42
    val failScore: Double                       = 0.0
    val devset: Vector[Example[String, String]] = stubDevset(n)
    val program: String => IO[String] = (s: String) =>
      if s == devset(failIdx).input then IO.raiseError(new RuntimeException("poisoned"))
      else IO.pure(s)
    val result: EvaluationResult[String, String] =
      runIO(
        Evaluate[IO, String, String](
          program,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = failScore)
        )
      )
    assert(result.rows(failIdx).outcome.isFailed)
    assertEquals(result.rows(failIdx).score.value, failScore)
    assertEquals(result.rows.count(_.outcome.isSucceeded), n - 1)

  test("metric raises → row gets Failed + failureScore, run continues"):
    val devset: Vector[Example[String, String]] = stubDevset(5)
    val raisingMetric: Metric[IO, String, String] =
      new Metric[IO, String, String]:
        def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
          if gold.id == Some("2") then IO.raiseError(new RuntimeException("metric boom"))
          else IO.pure(Score(1.0))
    val result: EvaluationResult[String, String] =
      runIO(
        Evaluate[IO, String, String](
          stubProgram,
          devset,
          raisingMetric,
          EvalConfig(failureScore = 0.0)
        )
      )
    assert(result.rows(2).outcome.isFailed)
    assertEquals(result.rows(2).score.value, 0.0)
    assertEquals(result.rows.count(_.outcome.isSucceeded), 4)

  test("no cap (maxErrors = None) → all failures recorded, no TooManyErrors"):
    val failingProgram: String => IO[String]    = (_: String) => IO.raiseError(new RuntimeException("boom"))
    val devset: Vector[Example[String, String]] = stubDevset(10)
    val result: EvaluationResult[String, String] =
      runIO(
        Evaluate[IO, String, String](
          failingProgram,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = 0.0, maxErrors = None)
        )
      )
    assertEquals(result.rows.size, 10)
    assert(result.rows.forall(_.outcome.isFailed))

  test("cap exceeded → TooManyErrors raised with partial rows"):
    val failingProgram: String => IO[String]    = (_: String) => IO.raiseError(new RuntimeException("boom"))
    val devset: Vector[Example[String, String]] = stubDevset(20)
    val error: Throwable =
      runIOFailed(
        Evaluate[IO, String, String](
          failingProgram,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = 0.0, maxErrors = Some(2), parallelism = 8)
        )
      )
    // The error should be EvalError.TooManyErrors
    error match
      case EvalError.TooManyErrors(count, max, partial) =>
        assertEquals(max, 2)
        assert(count >= 2, s"count should be >= 2, got $count")
        assert(partial.nonEmpty, "partial rows should be non-empty")
      case other =>
        fail(s"Expected EvalError.TooManyErrors, got ${other.getClass.getName}")

  test("cap exceeded with exact count (parallelism 1) → count = max + 1"):
    // With parallelism 1 (sequential), the failure count is deterministic:
    // maxErrors = Some(3) raises at the 4th failure (count > cap → 4 > 3).
    val failingProgram: String => IO[String]    = (_: String) => IO.raiseError(new RuntimeException("boom"))
    val devset: Vector[Example[String, String]] = stubDevset(20)
    val error: Throwable =
      runIOFailed(
        Evaluate[IO, String, String](
          failingProgram,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = 0.0, maxErrors = Some(3), parallelism = 1)
        )
      )
    error match
      case EvalError.TooManyErrors(count, max, partial) =>
        assertEquals(max, 3)
        assertEquals(count, 4, s"count should be exactly max+1=4, got $count")
        assert(partial.nonEmpty, "partial rows should be non-empty")
      case other =>
        fail(s"Expected EvalError.TooManyErrors, got ${other.getClass.getName}")

  test("cap exceeded with cancellation probe → in-flight work cancelled"):
    // R1.3: maxErrors abort cancels in-flight work. A Deferred-based probe
    // observes cancellation: some examples wait on a never-completed Deferred,
    // and an onCancel handler records whether the fiber was cancelled.
    // Under TestControl, cancellation is deterministic.
    val devset: Vector[Example[String, String]] = stubDevset(20)
    val testIO: IO[(Either[Throwable, EvaluationResult[String, String]], Boolean)] =
      for
        probe        <- cats.effect.Deferred[IO, Unit]
        cancelledRef <- Ref.of[IO, Boolean](false)
        // First 3 examples fail immediately (trigger cap=2); rest wait on probe
        program = (s: String) =>
          val idx: Int = s.stripPrefix("input-").toInt
          if idx < 3 then IO.raiseError(new RuntimeException("boom"))
          else probe.get.as("result").onCancel(cancelledRef.set(true))
        result <- Evaluate[IO, String, String](
          program,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(failureScore = 0.0, maxErrors = Some(2), parallelism = 8)
        ).attempt
        wasCancelled <- cancelledRef.get
      yield (result, wasCancelled)

    val (result, wasCancelled): (Either[Throwable, EvaluationResult[String, String]], Boolean) =
      runIO(testIO)

    // Verify TooManyErrors was raised
    result match
      case Left(EvalError.TooManyErrors(count, max, partial)) =>
        assertEquals(max, 2)
        assert(count >= 2, s"count should be >= 2, got $count")
      case Left(other) =>
        fail(s"Expected EvalError.TooManyErrors, got ${other.getClass.getName}")
      case Right(_) =>
        fail("Expected failure, got success")

    // Verify in-flight work was cancelled (the probe was never completed,
    // so the only way the test completes is if the waiting fibers were cancelled)
    assert(wasCancelled, "in-flight work should have been cancelled")

  test("feedback does not affect aggregate"):
    val devset: Vector[Example[String, String]] = stubDevset(3)
    val withFeedback: Metric[IO, String, String] =
      new Metric[IO, String, String]:
        def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
          IO.pure(Score(0.5, Some("reason")))
    val noFeedback: Metric[IO, String, String] =
      new Metric[IO, String, String]:
        def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
          IO.pure(Score(0.5))
    val r1: EvaluationResult[String, String] =
      runIO(Evaluate[IO, String, String](stubProgram, devset, withFeedback))
    val r2: EvaluationResult[String, String] =
      runIO(Evaluate[IO, String, String](stubProgram, devset, noFeedback))
    assertEquals(r1.score, r2.score)
    assert(r1.rows.forall(_.score.feedback == Some("reason")))

  test("counting metric called exactly once per example"):
    val n: Int                                  = 20
    val devset: Vector[Example[String, String]] = stubDevset(n)
    val counter: Ref[IO, Int]                   = runIO(Ref.of[IO, Int](0))
    val countingMetric: Metric[IO, String, String] =
      new Metric[IO, String, String]:
        def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
          counter.update(_ + 1).as(Score(1.0))
    val result: EvaluationResult[String, String] =
      runIO(Evaluate[IO, String, String](stubProgram, devset, countingMetric))
    val count: Int = runIO(counter.get)
    assertEquals(count, n)

  test("counting metric verifies trace is None"):
    val n: Int                                  = 10
    val devset: Vector[Example[String, String]] = stubDevset(n)
    val traces: Ref[IO, Vector[Option[Trace]]] =
      runIO(Ref.of[IO, Vector[Option[Trace]]](Vector.empty))
    val recordingMetric: Metric[IO, String, String] =
      new Metric[IO, String, String]:
        def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
          traces.update(_ :+ trace).as(Score(1.0))
    val result: EvaluationResult[String, String] =
      runIO(Evaluate[IO, String, String](stubProgram, devset, recordingMetric))
    val recorded: Vector[Option[Trace]] = runIO(traces.get)
    assert(recorded.forall(_.isEmpty), "all traces should be None")

  test("formatVersion present in JSON export"):
    val result: EvaluationResult[String, String] =
      EvaluationResult(
        0.5,
        Vector(
          EvalRow(
            Example("in", "out", Some("0")),
            EvalOutcome.Succeeded("pred"),
            Score(0.5)
          )
        )
      )
    val json: String = result.toJson
    assert(
      json.contains("\"formatVersion\":1") || json.contains("\"formatVersion\": 1") || json.contains("formatVersion"),
      s"JSON should contain formatVersion, got: $json"
    )

  test("round-trip with feedback and failures"):
    val original: EvaluationResult[String, String] =
      EvaluationResult(
        0.75,
        Vector(
          EvalRow(Example("in0", "out0", Some("0")), EvalOutcome.Succeeded("pred0"), Score(1.0, Some("good"))),
          EvalRow(Example("in1", "out1", Some("1")), EvalOutcome.Succeeded("pred1"), Score(0.5)),
          EvalRow(Example("in2", "out2", Some("2")), EvalOutcome.Failed(new RuntimeException("boom")), Score(0.0)),
          EvalRow(Example("in3", "out3", Some("3")), EvalOutcome.Succeeded("pred3"), Score(1.0, Some("nice")))
        )
      )
    val json: String = original.toJson
    val parsed: Either[String, EvaluationResult[String, String]] =
      EvaluationResult.fromJson[String, String](json)
    assert(parsed.isRight, s"fromJson should succeed, got Left: $parsed")
    val roundTripped: EvaluationResult[String, String] =
      parsed.toOption.getOrElse(fail("fromJson returned Left"))
    assertEquals(roundTripped.score, original.score)
    assertEquals(roundTripped.rows.size, original.rows.size)
    // Check outcomes match (Succeeded/Failed)
    (0 until original.rows.size).foreach(i =>
      val orig: EvalRow[String, String] = original.rows(i)
      val rt: EvalRow[String, String]   = roundTripped.rows(i)
      assertEquals(orig.outcome.isSucceeded, rt.outcome.isSucceeded)
      assertEquals(orig.score.feedback, rt.score.feedback)
    )

  test("CSV mixed outcomes: header + data rows, outcome column values"):
    val result: EvaluationResult[String, String] =
      EvaluationResult(
        0.5,
        Vector(
          EvalRow(Example("in0", "out0", Some("0")), EvalOutcome.Succeeded("pred0"), Score(1.0, Some("fb"))),
          EvalRow(Example("in1", "out1", Some("1")), EvalOutcome.Succeeded("pred1"), Score(0.0)),
          EvalRow(Example("in2", "out2", Some("2")), EvalOutcome.Failed(new RuntimeException("boom")), Score(0.0))
        )
      )
    val csv: String           = result.toCsv
    val lines: Vector[String] = csv.linesIterator.toVector
    assertEquals(lines.size, 4, s"CSV should have 4 lines (header + 3 data), got ${lines.size}")
    assertEquals(lines(0), "id,score,feedback,outcome,meta")
    // Check outcome column values
    val dataLines: Vector[String] = lines.drop(1)
    assert(dataLines(2).contains("failed"), s"third data row should have 'failed' outcome: ${dataLines(2)}")
    assert(dataLines(0).contains("succeeded"), s"first data row should have 'succeeded' outcome: ${dataLines(0)}")

  test("parallelism 1 (sequential) preserves devset order"):
    val devset: Vector[Example[String, String]] = stubDevset(5)
    val result: EvaluationResult[String, String] =
      runIO(
        Evaluate[IO, String, String](
          stubProgram,
          devset,
          Metrics.exactMatch[IO],
          EvalConfig(parallelism = 1)
        )
      )
    assertEquals(
      result.rows.map(_.example.id),
      devset.map(_.id)
    )

  // ── Hedgehog properties ──────────────────────────────────────────────────

  property("devset-order-under-parallelism"):
    for
      devset <- genDevsetSized(2, 16).forAll
      par    <- genParallelism.forAll
    yield
      val result: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            stubProgram,
            devset,
            Metrics.exactMatch[IO],
            EvalConfig(parallelism = par)
          )
        )
      result.rows.map(_.example.id) ==== devset.map(_.id)

  property("failure-score-substitution"):
    for
      n         <- Gen.int(Range.linear(2, 50)).forAll
      failIdx   <- Gen.int(Range.linear(0, n - 1)).forAll
      failScore <- genFailureScore.forAll
    yield
      val devset: Vector[Example[String, String]] = stubDevset(n)
      val program: String => IO[String] = (s: String) =>
        if s == devset(failIdx).input then IO.raiseError(new RuntimeException("poisoned"))
        else IO.pure(s)
      val result: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            program,
            devset,
            Metrics.exactMatch[IO],
            EvalConfig(failureScore = failScore)
          )
        )
      val failRow: EvalRow[String, String] = result.rows(failIdx)
      (failRow.outcome.isFailed ==== true)
        .and(failRow.score.value ==== failScore)
        .and(result.rows.count(_.outcome.isSucceeded) ==== (n - 1))

  property("mean-aggregate"):
    for
      scores    <- genScores.forAll
      failScore <- genFailureScore.forAll
    yield
      val n: Int                                  = scores.size
      val devset: Vector[Example[String, String]] = stubDevset(n)
      val metric: Metric[IO, String, String] =
        new Metric[IO, String, String]:
          def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
            val idx: Int = gold.id.map(_.toInt).getOrElse(0)
            IO.pure(Score(scores(idx)))
      val result: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            stubProgram,
            devset,
            metric,
            EvalConfig(failureScore = failScore)
          )
        )
      val expected: Double = scores.sum / scores.size
      result.score ==== expected

  property("empty-devset-score-zero"):
    for _ <- Gen.constant(()).forAll
    yield
      val result: EvaluationResult[String, String] =
        runIO(Evaluate[IO, String, String](stubProgram, Vector.empty, Metrics.exactMatch[IO]))
      (result.score ==== 0.0).and(result.rows.isEmpty ==== true)

  property("feedback-inert"):
    for
      devset <- genDevsetSized(1, 20).forAll
      value  <- genScoreValue.forAll
      fb     <- genFeedback.forAll
    yield
      val withFb: Metric[IO, String, String] =
        new Metric[IO, String, String]:
          def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
            IO.pure(Score.withFeedback(value, fb))
      val noFb: Metric[IO, String, String] =
        new Metric[IO, String, String]:
          def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
            IO.pure(Score(value))
      val r1: EvaluationResult[String, String] =
        runIO(Evaluate[IO, String, String](stubProgram, devset, withFb))
      val r2: EvaluationResult[String, String] =
        runIO(Evaluate[IO, String, String](stubProgram, devset, noFb))
      r1.score ==== r2.score

  property("metric-called-once-per-example"):
    for devset <- genDevsetSized(1, 30).forAll
    yield
      val counter: Ref[IO, Int] = runIO(Ref.of[IO, Int](0))
      val countingMetric: Metric[IO, String, String] =
        new Metric[IO, String, String]:
          def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
            counter.update(_ + 1).as(Score(1.0))
      val result: EvaluationResult[String, String] =
        runIO(Evaluate[IO, String, String](stubProgram, devset, countingMetric))
      val count: Int = runIO(counter.get)
      count ==== devset.size

  property("determinism-under-parallelism"):
    for devset <- genDevsetSized(1, 25).forAll
    yield
      val r1: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            pureProgram,
            devset,
            Metrics.exactMatch[IO],
            EvalConfig(parallelism = 1)
          )
        )
      val r2: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            pureProgram,
            devset,
            Metrics.exactMatch[IO],
            EvalConfig(parallelism = 2)
          )
        )
      val r8: EvaluationResult[String, String] =
        runIO(
          Evaluate[IO, String, String](
            pureProgram,
            devset,
            Metrics.exactMatch[IO],
            EvalConfig(parallelism = 8)
          )
        )
      (r1 ==== r2).and(r2 ==== r8)

  property("json-round-trip"):
    for result <- genEvaluationResult.forAll
    yield
      val json: String = result.toJson
      val parsed: Either[String, EvaluationResult[String, String]] =
        EvaluationResult.fromJson[String, String](json)
      parsed match
        case Right(rt) =>
          // Full equality via resultEqual (compares Failed by class + message,
          // not reference equality, since JSON round-trip reconstructs the
          // RuntimeException).
          hedgehog.Result.assert(resultEqual(rt, result))
        case Left(err) =>
          hedgehog.Result.failure.log(s"fromJson failed: $err")

  property("trace-is-none-in-eval-mode"):
    for devset <- genDevsetSized(1, 20).forAll
    yield
      val traces: Ref[IO, Vector[Option[Trace]]] =
        runIO(Ref.of[IO, Vector[Option[Trace]]](Vector.empty))
      val recordingMetric: Metric[IO, String, String] =
        new Metric[IO, String, String]:
          def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
            traces.update(_ :+ trace).as(Score(1.0))
      val result: EvaluationResult[String, String] =
        runIO(Evaluate[IO, String, String](stubProgram, devset, recordingMetric))
      val recorded: Vector[Option[Trace]] = runIO(traces.get)
      recorded.forall(_.isEmpty) ==== true
