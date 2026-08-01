package org.adk4s.eval

import cats.effect.Sync
import upickle.default.*

/**
 * Dataset reader — reads a JSONL file (one JSON object per line) into a
 * `Vector[Example[I, O]]` using caller-supplied upickle readers for `I` and
 * `O`.
 *
 * Malformed lines raise a descriptive error naming the line number. The
 * empty file yields `Vector.empty` — no error.
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
   *             naming the malformed line number if a line is not valid JSON
   *             or does not match the expected schema
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def fromJsonl[F[_], I, O](
    path: String
  )(using readerI: Reader[I], readerO: Reader[O], F: Sync[F]): F[Vector[Example[I, O]]] =
    F.blocking {
      val lines: Vector[String] =
        scala.io.Source.fromFile(path).getLines().toVector
      val results: Vector[Either[Throwable, Option[Example[I, O]]]] =
        lines.zipWithIndex.map { case (line, idx) =>
          if line.isBlank then Right(None)
          else
            try
              val parsed: ujson.Value = ujson.read(line)
              val input: I            = upickle.default.read[I](parsed("input").render())
              val gold: O             = upickle.default.read[O](parsed("gold").render())
              val id: Option[String] = parsed.obj.get("id") match
                case Some(ujson.Null) => None
                case Some(v)          => Some(v.str)
                case None             => None
              val meta: Map[String, String] = parsed.obj.get("meta") match
                case Some(obj: ujson.Obj) => obj.value.toMap.map { case (k, v) => k -> v.str }
                case Some(ujson.Null)     => Map.empty[String, String]
                case None                 => Map.empty[String, String]
                case Some(other) =>
                  throw new MalformedLineException(idx + 1, s"meta field must be a JSON object, got: $other")
              Right(Some(Example(input, gold, id, meta)))
            catch
              case _: Exception =>
                Left(new MalformedLineException(idx + 1, line))
        }
      val firstError: Option[Throwable] = results.collectFirst { case Left(e) => e }
      firstError match
        case Some(e) => throw e
        case None    => results.collect { case Right(Some(ex)) => ex }
    }

/** Raised by `Dataset.fromJsonl` when a line is not valid JSON. */
final class MalformedLineException(val lineNumber: Int, val line: String)
    extends RuntimeException(s"Malformed JSON at line $lineNumber: $line")
