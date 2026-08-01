package org.adk4s.eval

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * Scenario tests for the built-in string metrics (eval-core spec).
 *
 * Covers the Proof Obligations:
 *  - Exact match → Score 1.0 when prediction equals gold
 *  - Exact match miss → Score 0.0
 *  - Contains-all with partial match → Score 0.0
 *
 * All IOs run under `TestControl.executeEmbed` for deterministic execution.
 */
class MetricsSpec extends FunSuite:

  /** Run an IO deterministically under TestControl. */
  private def runIO[A](io: IO[A]): A =
    TestControl.executeEmbed(io).unsafeRunSync()

  test("exact match: prediction equals gold → Score 1.0"):
    val metric: Metric[IO, String, String] = Metrics.exactMatch[IO]
    val example: Example[String, String]   = Example("input", "hello world")
    val score: Score                       = runIO(metric(example, "hello world", trace = None))
    assertEquals(score.value, 1.0)

  test("exact match miss: prediction differs from gold → Score 0.0"):
    val metric: Metric[IO, String, String] = Metrics.exactMatch[IO]
    val example: Example[String, String]   = Example("input", "hello world")
    val score: Score                       = runIO(metric(example, "hello", trace = None))
    assertEquals(score.value, 0.0)

  test("contains-all: all gold tokens present → Score 1.0"):
    val metric: Metric[IO, String, String] = Metrics.containsAll[IO]
    val example: Example[String, String]   = Example("input", "Scala is great")
    val score: Score                       = runIO(metric(example, "Scala is great and fast", trace = None))
    assertEquals(score.value, 1.0)

  test("contains-all with partial match: missing token → Score 0.0"):
    val metric: Metric[IO, String, String] = Metrics.containsAll[IO]
    val example: Example[String, String]   = Example("input", "Scala is great")
    val score: Score                       = runIO(metric(example, "Scala is good", trace = None))
    assertEquals(score.value, 0.0)

  test("exact match: trace is None in eval mode → same result"):
    val metric: Metric[IO, String, String] = Metrics.exactMatch[IO]
    val example: Example[String, String]   = Example("input", "hello")
    val scoreNone: Score                   = runIO(metric(example, "hello", trace = None))
    val scoreSome: Score                   = runIO(metric(example, "hello", trace = Some(Trace.empty)))
    assertEquals(scoreNone.value, scoreSome.value)
