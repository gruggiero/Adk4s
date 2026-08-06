package org.adk4s.eval

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import upickle.default.*

import java.io.File
import java.nio.file.Files

/**
 * Scenario tests for the JSONL dataset reader (eval-core spec).
 *
 * Covers the Proof Obligations:
 *  - Valid JSONL → Vector of Examples
 *  - Malformed line at position 15 → error naming line 15
 *  - Empty JSONL → Vector.empty
 *
 * All IOs run under `TestControl.executeEmbed` for deterministic execution.
 */
class DatasetSpec extends FunSuite:

  /** Write lines to a temporary file and return its path. */
  private def writeTempFile(lines: Vector[String]): String =
    val file: File = File.createTempFile("dataset-test", ".jsonl")
    file.deleteOnExit()
    Files.write(file.toPath, lines.mkString("\n").getBytes)
    file.getAbsolutePath

  /** Run an IO deterministically under TestControl and extract the result. */
  private def runIO[A](io: IO[A]): A =
    TestControl.executeEmbed(io).unsafeRunSync()

  /** Run an IO that is expected to raise, returning the thrown Throwable. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def runIOFailed[A](io: IO[A]): Throwable =
    io.attempt.unsafeRunSync() match
      case Right(_)  => throw new RuntimeException("expected failure, got success")
      case Left(err) => err

  test("valid JSONL → Vector of Examples"):
    val lines: Vector[String] = (0 until 10).toVector.map(i => s"""{"input":"input-$i","gold":"gold-$i","id":"$i"}""")
    val path: String          = writeTempFile(lines)
    val examples: Vector[Example[String, String]] =
      runIO(Dataset.fromJsonl[IO, String, String](path))
    assertEquals(examples.size, 10)
    assertEquals(examples(0).input, "input-0")
    assertEquals(examples(0).gold, "gold-0")
    assertEquals(examples(0).id, Some("0"))

  test("empty JSONL → Vector.empty"):
    val path: String = writeTempFile(Vector.empty)
    val examples: Vector[Example[String, String]] =
      runIO(Dataset.fromJsonl[IO, String, String](path))
    assert(examples.isEmpty)

  test("malformed line at position 15 → error naming line 15 and JSON syntax error"):
    val lines: Vector[String] = (0 until 20).toVector.map(i =>
      if i == 14 then "this is not valid json"
      else s"""{"input":"input-$i","gold":"gold-$i","id":"$i"}"""
    )
    val path: String = writeTempFile(lines)
    val error: Throwable =
      runIOFailed(Dataset.fromJsonl[IO, String, String](path))
    // The error message should mention line 15 (1-indexed) and identify as JSON syntax error
    val msg: String = error.getMessage
    assert(msg.contains("15"), s"Error message should mention line 15, got: $msg")
    assert(msg.contains("JSON syntax error"), s"Error message should identify JSON syntax error, got: $msg")

  test("schema mismatch at position 15 → error naming line 15 and schema mismatch"):
    val lines: Vector[String] = (0 until 20).toVector.map(i =>
      if i == 14 then s"""{"input":"input-$i"}"""
      else s"""{"input":"input-$i","gold":"gold-$i","id":"$i"}"""
    )
    val path: String = writeTempFile(lines)
    val error: Throwable =
      runIOFailed(Dataset.fromJsonl[IO, String, String](path))
    // The error message should mention line 15 and identify as schema mismatch (not malformed JSON)
    val msg: String = error.getMessage
    assert(msg.contains("15"), s"Error message should mention line 15, got: $msg")
    assert(msg.contains("Schema mismatch"), s"Error message should identify schema mismatch, got: $msg")
    assert(!msg.contains("Malformed JSON"), s"Error message should NOT say 'Malformed JSON' for schema mismatch, got: $msg")
