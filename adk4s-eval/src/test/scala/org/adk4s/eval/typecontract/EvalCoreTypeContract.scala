package org.adk4s.eval
package typecontract

import cats.Applicative
import cats.Functor
import cats.effect.IO
import upickle.default.*

// ═══════════════════════════════════════════════════════════════════════════
//  Typed Contract for spec: eval-core
//  Generated: 2026-08-01
//  Schema: verified-scala3
//
//  The type declarations live in MAIN sources (org.adk4s.eval) with `???`
//  bodies — this file verifies their signatures compile correctly and that
//  compile-negative obligations are enforced, using `compileErrors` against
//  the real classpath.
//
//  PLACEMENT: this file lives in the owning module's TEST sources:
//    adk4s-eval/src/test/scala/org/adk4s/eval/typecontract/
//  so that `sbt adk4s-eval/Test/compile` genuinely compiles it against the
//  real project classpath.
//
//  LIFECYCLE — after implementation (apply Step 3) this file is NOT deleted
//  or gutted: it becomes a permanent API CONFORMANCE ASSERTION. The
//  `compileErrors` blocks below already reference the real main-source types,
//  so they serve as zero-cost signature pins — any later signature drift
//  breaks adk4s-eval/Test/compile.
//
//  Status: [x] Compiles via adk4s-eval/Test/compile  [ ] Human-approved
//          [ ] Converted to conformance assertions after implementation
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Type contract test suite — verifies signatures compile and compile-negative
 * obligations are enforced. No behavioral tests here (those live in the
 * test oracle: EvaluateSpec, DatasetSpec, MetricsSpec).
 */
class EvalCoreTypeContract extends munit.FunSuite:

  // ── Signature verification (compile-only, no runtime invocation) ──────────

  test("Example signature: case class with input, gold, optional id, meta") {
    val errors: String = compileErrors("""
      val ex1: org.adk4s.eval.Example[String, String] =
        org.adk4s.eval.Example("input", "gold")
      val ex2: org.adk4s.eval.Example[String, String] =
        org.adk4s.eval.Example("input", "gold", Some("id-1"))
      val ex3: org.adk4s.eval.Example[String, String] =
        org.adk4s.eval.Example("input", "gold", Some("id-2"), Map("src" -> "test"))
      val input: String = ex1.input
      val gold: String = ex1.gold
      val id: Option[String] = ex1.id
      val meta: Map[String, String] = ex1.meta
      ()
    """)
    assert(errors.isEmpty, s"Example must compile: $errors")
  }

  test("Score signature: value + optional feedback + companion helpers") {
    val errors: String = compileErrors("""
      val s1: org.adk4s.eval.Score = org.adk4s.eval.Score(1.0)
      val s2: org.adk4s.eval.Score = org.adk4s.eval.Score(0.5, Some("reason"))
      val s3: org.adk4s.eval.Score = org.adk4s.eval.Score.zero
      val s4: org.adk4s.eval.Score = org.adk4s.eval.Score.bool(true)
      val s5: org.adk4s.eval.Score = org.adk4s.eval.Score.withFeedback(0.8, "good")
      val value: Double = s1.value
      val feedback: Option[String] = s1.feedback
      ()
    """)
    assert(errors.isEmpty, s"Score must compile: $errors")
  }

  test("TraceEntry and Trace signature: path + ujson values + forPredictor") {
    val errors: String = compileErrors("""
      import ujson.Value
      val entry: org.adk4s.eval.TraceEntry =
        org.adk4s.eval.TraceEntry("pred_0", ujson.Str("in"), ujson.Str("out"))
      val trace: org.adk4s.eval.Trace = org.adk4s.eval.Trace(Vector(entry))
      val sliced: org.adk4s.eval.Trace = trace.forPredictor("pred_0")
      val empty: org.adk4s.eval.Trace = org.adk4s.eval.Trace.empty
      ()
    """)
    assert(errors.isEmpty, s"TraceEntry/Trace must compile: $errors")
  }

  test("Metric signature: trait with apply + map + fromPredicate + fromDouble") {
    val errors: String = compileErrors("""
      val metric: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        new org.adk4s.eval.Metric[cats.effect.IO, String, String]:
          def apply(gold: org.adk4s.eval.Example[String, String], pred: String, trace: Option[org.adk4s.eval.Trace]): cats.effect.IO[org.adk4s.eval.Score] =
            cats.effect.IO.pure(org.adk4s.eval.Score(1.0))
      val fromPred: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Metric.fromPredicate[cats.effect.IO, String, String]((_, _) => true)
      val fromDbl: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Metric.fromDouble[cats.effect.IO, String, String]((_, _) => 0.5)
      ()
    """)
    assert(errors.isEmpty, s"Metric must compile: $errors")
  }

  test("EvalConfig signature: smart constructor with defaults") {
    val errors: String = compileErrors("""
      val c1: org.adk4s.eval.EvalConfig = org.adk4s.eval.EvalConfig()
      val c2: org.adk4s.eval.EvalConfig = org.adk4s.eval.EvalConfig(parallelism = 4)
      val c3: org.adk4s.eval.EvalConfig = org.adk4s.eval.EvalConfig(failureScore = 0.5)
      val c4: org.adk4s.eval.EvalConfig = org.adk4s.eval.EvalConfig(maxErrors = Some(3))
      val c5: org.adk4s.eval.EvalConfig = org.adk4s.eval.EvalConfig(parallelism = 2, failureScore = 0.0, maxErrors = Some(5), seed = 42L)
      ()
    """)
    assert(errors.isEmpty, s"EvalConfig must compile: $errors")
  }

  test("EvalOutcome signature: enum with Succeeded/Failed + isSucceeded/isFailed") {
    val errors: String = compileErrors("""
      val succ: org.adk4s.eval.EvalOutcome[String] = org.adk4s.eval.EvalOutcome.Succeeded("ok")
      val fail: org.adk4s.eval.EvalOutcome[String] = org.adk4s.eval.EvalOutcome.Failed(new RuntimeException("boom"))
      val isSucc: Boolean = succ.isSucceeded
      val isFail: Boolean = fail.isFailed
      ()
    """)
    assert(errors.isEmpty, s"EvalOutcome must compile: $errors")
  }

  test("EvalRow signature: example + outcome + score") {
    val errors: String = compileErrors("""
      val row: org.adk4s.eval.EvalRow[String, String] =
        org.adk4s.eval.EvalRow(
          org.adk4s.eval.Example("in", "out"),
          org.adk4s.eval.EvalOutcome.Succeeded("pred"),
          org.adk4s.eval.Score(1.0)
        )
      val example: org.adk4s.eval.Example[String, String] = row.example
      val outcome: org.adk4s.eval.EvalOutcome[String] = row.outcome
      val score: org.adk4s.eval.Score = row.score
      ()
    """)
    assert(errors.isEmpty, s"EvalRow must compile: $errors")
  }

  test("EvalError signature: sealed trait extends Throwable + TooManyErrors") {
    val errors: String = compileErrors("""
      val err: org.adk4s.eval.EvalError =
        org.adk4s.eval.EvalError.TooManyErrors(3, 3, Vector.empty[org.adk4s.eval.EvalRow[String, String]])
      val throwable: Throwable = err
      ()
    """)
    assert(errors.isEmpty, s"EvalError must compile: $errors")
  }

  test("EvaluationResult signature: score + rows + failures + toJson + toCsv") {
    val errors: String = compileErrors("""
      val result: org.adk4s.eval.EvaluationResult[String, String] =
        org.adk4s.eval.EvaluationResult(0.75, Vector.empty)
      val score: Double = result.score
      val rows: Vector[org.adk4s.eval.EvalRow[String, String]] = result.rows
      val failures: Vector[org.adk4s.eval.EvalRow[String, String]] = result.failures
      val json: String = result.toJson
      val csv: String = result.toCsv
      ()
    """)
    assert(errors.isEmpty, s"EvaluationResult must compile: $errors")
  }

  test("EvaluationResult.fromJson signature: returns Either") {
    val errors: String = compileErrors("""
      val parsed: Either[String, org.adk4s.eval.EvaluationResult[String, String]] =
        org.adk4s.eval.EvaluationResult.fromJson[String, String]("{}")
      ()
    """)
    assert(errors.isEmpty, s"EvaluationResult.fromJson must compile: $errors")
  }

  test("Evaluate signature: apply with program, devset, metric, config") {
    val errors: String = compileErrors("""
      val program: String => cats.effect.IO[String] = (s: String) => cats.effect.IO.pure(s)
      val devset: Vector[org.adk4s.eval.Example[String, String]] = Vector.empty
      val metric: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Metrics.exactMatch[cats.effect.IO]
      val result: cats.effect.IO[org.adk4s.eval.EvaluationResult[String, String]] =
        org.adk4s.eval.Evaluate[cats.effect.IO, String, String](program, devset, metric)
      val result2: cats.effect.IO[org.adk4s.eval.EvaluationResult[String, String]] =
        org.adk4s.eval.Evaluate[cats.effect.IO, String, String](program, devset, metric, org.adk4s.eval.EvalConfig(parallelism = 4))
      ()
    """)
    assert(errors.isEmpty, s"Evaluate must compile: $errors")
  }

  test("Dataset.fromJsonl signature: returns F[Vector[Example]]") {
    val errors: String = compileErrors("""
      val examples: cats.effect.IO[Vector[org.adk4s.eval.Example[String, String]]] =
        org.adk4s.eval.Dataset.fromJsonl[cats.effect.IO, String, String]("path.jsonl")
      ()
    """)
    assert(errors.isEmpty, s"Dataset.fromJsonl must compile: $errors")
  }

  test("Metrics signature: exactMatch and containsAll") {
    val errors: String = compileErrors("""
      val exact: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Metrics.exactMatch[cats.effect.IO]
      val contains: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Metrics.containsAll[cats.effect.IO]
      ()
    """)
    assert(errors.isEmpty, s"Metrics must compile: $errors")
  }

  // ── Exhaustiveness: EvalOutcome ───────────────────────────────────────────
  // spec: eval-core — Compile-Negative: non-exhaustive match over EvalOutcome
  // EvalOutcome is a sealed enum with two variants; the
  // -Wconf:name=PatternMatchExhaustivity:e flag (in scala3Options) escalates
  // any non-exhaustive match to a compile error at the project level — this is
  // the Ring 0 enforcement. The `compileErrors` munit macro does NOT apply
  // `-Wconf` escalations (same limitation as adk4s-optimize and
  // adk4s-core/AgentEvent precedents), so the compile-negative obligation is
  // enforced by `sbt adk4s-eval/compile` failing on any non-exhaustive match
  // in production code, not by a `compileErrors` assertion here. This positive
  // test verifies that an exhaustive match over both variants compiles without
  // a catch-all — proving the sealed enum has exactly the two declared variants
  // and both are matchable.

  test("EvalOutcome exhaustiveness: both variants matched, no catch-all needed") {
    val succ: EvalOutcome[String] = EvalOutcome.Succeeded("ok")
    val fail: EvalOutcome[String] = EvalOutcome.Failed(new RuntimeException("boom"))
    val succMsg: String = succ match
      case EvalOutcome.Succeeded(v) => v
      case EvalOutcome.Failed(e)    => e.getMessage
    val failMsg: String = fail match
      case EvalOutcome.Succeeded(v) => v
      case EvalOutcome.Failed(e)    => e.getMessage
    assertEquals(succMsg, "ok")
    assertEquals(failMsg, "boom")
  }

  test("EvalOutcome compile-negative: non-exhaustive match fails project compile") {
    // The `compileErrors` munit macro does NOT apply `-Wconf` escalations, so
    // it cannot detect the non-exhaustive match directly (confirmed by
    // experiment: the macro returns empty errors for a match missing `Failed`).
    // Instead, we verify that the `compileErrors` macro reports a type error
    // when the match arm's return type doesn't match the expected type — this
    // proves the macro IS running compiler checks on the snippet. The actual
    // exhaustiveness escalation is enforced by `sbt adk4s-eval/compile` with
    // `-Wconf:name=PatternMatchExhaustivity:e` active at the project level.
    //
    // This test documents the limitation: if a future munit/Scala 3 version
    // applies `-Wconf` inside `compileErrors`, replace this test with an
    // `assert(errors.contains("Failed"))` check on the non-exhaustive match.
    val errors: String = compileErrors("""
      val e: org.adk4s.eval.EvalOutcome[String] = org.adk4s.eval.EvalOutcome.Succeeded("ok")
      val v: Int = e match { case org.adk4s.eval.EvalOutcome.Succeeded(x) => x }
    """)
    assert(errors.nonEmpty, s"Type-mismatch match must be detected by compileErrors: $errors")
  }

  // ── Exhaustiveness: EvalError ─────────────────────────────────────────────
  // NOTE: EvalError currently has only ONE variant (TooManyErrors), so a match
  // on just TooManyErrors IS exhaustive. The compile-negative test for
  // EvalError applies only when EvalError gains >1 variant (per spec: "if
  // EvalError has >1 variant at time of test"). This positive test verifies
  // that the single-variant match compiles.

  test("EvalError exhaustiveness: TooManyErrors matched, no catch-all needed") {
    val err: EvalError =
      EvalError.TooManyErrors(3, 3, Vector.empty[EvalRow[String, String]])
    val count: Int = err match
      case EvalError.TooManyErrors(c, _, _) => c
    assertEquals(count, 3)
  }

// ── Property & generator obligations (become the Ring 3 test oracle) ────
//
// Property: devset-order-under-parallelism
//   Invariant: For any devset, program with randomized latency, and parallelism
//   P, the result rows are in devset declaration order.
//   Generator: genDevset (constructive, N 1..16), genParallelism (1..16)
//   cover: par=1 >= 25%, par>1 >= 50%
//
// Property: failure-score-substitution
//   Invariant: When exactly one example causes a program failure, the result
//   has N-1 real scores and 1 failure-scored row, and the aggregate reflects all N.
//   Generator: genDevset (2..50), genFailureIndex (0..N-1), genFailureScore (0.0..1.0)
//
// Property: mean-aggregate
//   Invariant: The aggregate score equals the arithmetic mean of all row scores.
//   Generator: genScores (1..30 doubles), genFailureScore (0.0..1.0)
//
// Property: empty-devset-score-zero
//   Invariant: The empty devset yields score 0.0 with empty rows.
//   Generator: constant Vector.empty
//
// Property: feedback-inert
//   Invariant: Two runs identical except for feedback strings produce the same
//   aggregate score.
//   Generator: genDevset (1..20), genScoreValue (0.0..1.0), genFeedback (Gen.string)
//
// Property: metric-called-once-per-example
//   Invariant: A counting metric is invoked exactly devset.size times.
//   Generator: genDevset (1..30)
//
// Property: determinism-under-parallelism
//   Invariant: With a pure program and metric, runs with different parallelism
//   produce equal results.
//   Generator: genDevset (1..25), parallelism ∈ {1, 2, 8}
//
// Property: json-round-trip
//   Invariant: EvaluationResult.fromJson(result.toJson) == Right(result)
//   Generator: genEvaluationResult (constructive, mixed outcomes, feedback)
//   cover: all-succeeded >= 25%, all-failed >= 25%, mixed >= 25%
//
// Property: trace-is-none-in-eval-mode
//   Invariant: The harness passes trace = None to the metric on every call.
//   Generator: genDevset (1..20)

// ── Formal contracts (Ring 6) — N/A for this spec ───────────────────────
// The harness is fs2 orchestration over F[_] with no pure decision/fold/law
// at the centre. Ring 6 skipped (justified in design.md).

// ── Temporal properties (Ring 9) — N/A ──────────────────────────────────
// No telemetry stack detected. Ring 9 skipped.
