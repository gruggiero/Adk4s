package org.adk4s.eval

import cats.effect.Sync
import upickle.default.*

import scala.util.Using

/**
 * Dataset reader — reads a JSONL file (one JSON object per line) into a
 * `Vector[Example[I, O]]` using caller-supplied upickle readers for `I` and
 * `O`.
 *
 * Malformed lines raise a descriptive error naming the line number AND the
 * actual cause (JSON syntax error vs. schema mismatch). The empty file
 * yields `Vector.empty` — no error.
 *
 * Each line is expected to be a JSON object with `input`, `gold`, and
 * optional `id` / `meta` fields.
 */
object Dataset:

  /**
   * Read a JSONL file into a `Vector[Example[I, O]]`.
   *
   * @param path the file path
   * @return     the examples as `F[Vector[Example[I, O]]]`; raises an error
   *             naming the malformed line number and the cause (JSON syntax
   *             error or schema mismatch) if a line cannot be parsed
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def fromJsonl[F[_], I, O](
    path: String
  )(using readerI: Reader[I], readerO: Reader[O], F: Sync[F]): F[Vector[Example[I, O]]] =
    F.blocking {
      Using.resource(scala.io.Source.fromFile(path)) { source =>
        val lines: Vector[String] = source.getLines().toVector
        val results: Vector[Either[Throwable, Option[Example[I, O]]]] =
          lines.zipWithIndex.map { case (line, idx) =>
            if line.isBlank then Right(None)
            else parseLine(line, idx)
          }
        val firstError: Option[Throwable] = results.collectFirst { case Left(e) => e }
        firstError match
          case Some(e) => throw e
          case None    => results.collect { case Right(Some(ex)) => ex }
      }
    }

  /** Parse a single line, distinguishing JSON syntax errors from schema mismatches. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def parseLine[I, O](line: String, idx: Int)(using
    readerI: Reader[I],
    readerO: Reader[O]
  ): Either[Throwable, Option[Example[I, O]]] =
    // Step 1: parse JSON syntax
    val parsed: Either[Throwable, ujson.Value] =
      try Right(ujson.read(line))
      catch case e: Exception => Left(new MalformedLineException(idx + 1, line, "JSON syntax error", e.getMessage))

    parsed.flatMap { jsonValue =>
      // Step 2: parse schema (field extraction + type decoding)
      try
        val parsedObj: ujson.Obj = jsonValue match
          case obj: ujson.Obj => obj
          case other =>
            throw new SchemaMismatchException(idx + 1, s"expected JSON object, got: ${other.getClass.getSimpleName}")

        val input: I = parsedObj.value.get("input") match
          case Some(v) => v.transform(readerI)
          case None    => throw new SchemaMismatchException(idx + 1, "missing required field: input")

        val gold: O = parsedObj.value.get("gold") match
          case Some(v) => v.transform(readerO)
          case None    => throw new SchemaMismatchException(idx + 1, "missing required field: gold")

        val id: Option[String] = parsedObj.value.get("id") match
          case Some(ujson.Null) => None
          case Some(v)          => Some(v.str)
          case None             => None

        val meta: Map[String, String] = parsedObj.value.get("meta") match
          case Some(obj: ujson.Obj) => obj.value.toMap.map { case (k, v) => k -> v.str }
          case Some(ujson.Null)     => Map.empty[String, String]
          case None                 => Map.empty[String, String]
          case Some(other) =>
            throw new SchemaMismatchException(
              idx + 1,
              s"meta field must be a JSON object, got: ${other.getClass.getSimpleName}"
            )

        Right(Some(Example(input, gold, id, meta)))
      catch case e: SchemaMismatchException => Left(e)
    }

/** Raised by `Dataset.fromJsonl` when a line is not valid JSON. */
final class MalformedLineException(val lineNumber: Int, val line: String, val causeType: String, val detail: String)
    extends RuntimeException(s"Malformed JSON at line $lineNumber ($causeType): $detail")

/** Raised by `Dataset.fromJsonl` when a line is valid JSON but does not match the expected schema. */
final class SchemaMismatchException(val lineNumber: Int, val detail: String)
    extends RuntimeException(s"Schema mismatch at line $lineNumber: $detail")
