package org.adk4s.examples.eval

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.eval.EvalConfig
import org.adk4s.eval.Evaluate
import org.adk4s.eval.EvaluationResult
import org.adk4s.eval.Example
import org.adk4s.eval.Metrics

/**
 * Eval harness acceptance example (DSPy port — Phase 1).
 *
 * Demonstrates the `Evaluate` harness: a mock extraction program is scored
 * against a 20-example devset using a rule metric (`Metrics.exactMatch`),
 * then a substring metric (`Metrics.containsAll`). Results are exported to
 * CSV and JSON.
 *
 * The program is a mock "keyword extractor" that returns the input unchanged
 * (simulating a perfect extractor). One example is poisoned (program raises)
 * to show failure-score substitution. The `maxErrors` cap is set to `Some(5)`
 * so the single failure does not abort the run.
 *
 * LLM judges (`Judges.semanticF1`, `Judges.completeAndGrounded`) will be added
 * when spec 2 (llm-judges) is implemented.
 *
 * Run: `./adk4s-examples/run-example.sh evalharness`
 *   or `sbt "adk4s-examples/runMain org.adk4s.examples.eval.EvalHarnessExample"`
 */
object EvalHarnessExample extends IOApp.Simple:

  /** A mock extraction program: returns the input unchanged (perfect extractor). */
  private val mockProgram: String => IO[String] =
    (input: String) =>
      // Simulate a poisoned example: example index 10 causes a failure.
      if input == "input-10" then IO.raiseError(new RuntimeException("poisoned example"))
      else IO.pure(input)

  /** Build a 20-example devset with sequential ids. */
  private val devset: Vector[Example[String, String]] =
    (0 until 20).toVector.map(i =>
      Example(
        input = s"input-$i",
        gold = s"input-$i",
        id = Some(i.toString),
        meta = Map("source" -> "mock", "difficulty" -> (if i < 10 then "easy" else "hard"))
      )
    )

  def run: IO[Unit] =
    val config: EvalConfig = EvalConfig(
      parallelism = 4,
      failureScore = 0.0,
      maxErrors = Some(5),
      seed = 42L
    )

    for
      // ── Run 1: exact-match metric ──
      _       <- IO.println("=== Run 1: Metrics.exactMatch ===")
      result1 <- Evaluate[IO, String, String](mockProgram, devset, Metrics.exactMatch[IO], config)
      _       <- IO.println(s"Aggregate score: ${result1.score}")
      _       <- IO.println(s"Succeeded: ${result1.rows.count(_.outcome.isSucceeded)}")
      _       <- IO.println(s"Failed: ${result1.rows.count(_.outcome.isFailed)}")
      _       <- IO.println(s"CSV export (first 5 lines):")
      _       <- IO.println(result1.toCsv.linesIterator.take(5).mkString("\n"))
      _       <- IO.println(s"JSON export (first 200 chars):")
      _       <- IO.println(result1.toJson.take(200) + "...")
      _       <- IO.println("")

      // ── Run 2: contains-all metric ──
      _       <- IO.println("=== Run 2: Metrics.containsAll ===")
      result2 <- Evaluate[IO, String, String](mockProgram, devset, Metrics.containsAll[IO], config)
      _       <- IO.println(s"Aggregate score: ${result2.score}")
      _       <- IO.println(s"Succeeded: ${result2.rows.count(_.outcome.isSucceeded)}")
      _       <- IO.println(s"Failed: ${result2.rows.count(_.outcome.isFailed)}")
      _       <- IO.println("")

      // ── Run 3: no cap (maxErrors = None) — all failures recorded ──
      _ <- IO.println("=== Run 3: maxErrors = None (unlimited) ===")
      result3 <- Evaluate[IO, String, String](
        mockProgram,
        devset,
        Metrics.exactMatch[IO],
        EvalConfig(parallelism = 4, failureScore = 0.0, maxErrors = None, seed = 42L)
      )
      _ <- IO.println(s"Aggregate score: ${result3.score}")
      _ <- IO.println(s"Succeeded: ${result3.rows.count(_.outcome.isSucceeded)}")
      _ <- IO.println(s"Failed: ${result3.rows.count(_.outcome.isFailed)}")
      _ <- IO.println("")

      // ── JSON round-trip verification ──
      _ <- IO.println("=== JSON Round-Trip ===")
      json   = result1.toJson
      parsed = EvaluationResult.fromJson[String, String](json)
      _ <- parsed match
        case Right(rt) => IO.println(s"Round-trip OK: score=${rt.score}, rows=${rt.rows.size}")
        case Left(err) => IO.println(s"Round-trip FAILED: $err")
    yield ()
