package org.adk4s.eval

import upickle.default.*

/**
 * The result of an evaluation run: the aggregate mean score and per-example
 * rows.
 *
 * The aggregate `score` is the arithmetic mean of all row scores (including
 * substituted failure scores). The empty devset yields `score = 0.0` with
 * empty rows.
 *
 * JSON export includes a `formatVersion: 1` field; [[fromJson]] checks the
 * version and round-trips the value. The harness itself is codec-free —
 * `Writer[I]`/`Writer[O]` are required on export only, not on the `Evaluate`
 * call.
 *
 * @param score the arithmetic mean of all row scores
 * @param rows  the per-example result rows in devset order
 */
final case class EvaluationResult[I, O](
  score: Double,
  rows: Vector[EvalRow[I, O]]
):

  /** Rows where the program failed (outcome is `EvalOutcome.Failed`). */
  def failures: Vector[EvalRow[I, O]] =
    rows.filter(_.outcome.isFailed)

  /** JSON export with `formatVersion: 1`. Requires `Writer[I]` and `Writer[O]` in scope. */
  def toJson(using writerI: Writer[I], writerO: Writer[O]): String =
    val rowsJson: ujson.Value = ujson.Arr.from(rows.map(row => rowToJson(row)))
    val obj: ujson.Obj = ujson.Obj(
      "formatVersion" -> ujson.Num(1),
      "score"         -> ujson.Num(score),
      "rows"          -> rowsJson
    )
    obj.render()

  /** CSV export: header row + one row per example. Columns: id, score, feedback, outcome, meta. */
  def toCsv: String =
    val header: String            = "id,score,feedback,outcome,meta"
    val dataLines: Vector[String] = rows.map(rowToCsvLine)
    (header +: dataLines).mkString("\n")

  private def rowToJson(row: EvalRow[I, O])(using writerI: Writer[I], writerO: Writer[O]): ujson.Value =
    val outcomeJson: ujson.Value = row.outcome match
      case EvalOutcome.Succeeded(v) => ujson.Obj("type" -> "succeeded", "value" -> upickle.default.writeJs(v))
      case EvalOutcome.Failed(err)  => ujson.Obj("type" -> "failed", "error" -> ujson.Str(err.getMessage))
    ujson.Obj(
      "example" -> ujson.Obj(
        "input" -> upickle.default.writeJs(row.example.input),
        "gold"  -> upickle.default.writeJs(row.example.gold),
        "id"    -> row.example.id.map(s => ujson.Str(s)).getOrElse(ujson.Null),
        "meta"  -> ujson.Obj.from(row.example.meta.map { case (k, v) => k -> ujson.Str(v) })
      ),
      "outcome" -> outcomeJson,
      "score" -> ujson.Obj(
        "value"    -> ujson.Num(row.score.value),
        "feedback" -> row.score.feedback.map(s => ujson.Str(s)).getOrElse(ujson.Null)
      )
    )

  private def rowToCsvLine(row: EvalRow[I, O]): String =
    val id: String       = row.example.id.getOrElse("")
    val scoreStr: String = row.score.value.toString
    val feedback: String = row.score.feedback.getOrElse("")
    val outcome: String  = if row.outcome.isSucceeded then "succeeded" else "failed"
    val meta: String     = row.example.meta.map { case (k, v) => s"$k=$v" }.mkString(";")
    s"$id,$scoreStr,$feedback,$outcome,$meta"

object EvaluationResult:

  /** JSON import — round-trips [[toJson]]. Requires `Reader[I]` and `Reader[O]` in scope. */
  def fromJson[I, O](
    json: String
  )(using readerI: Reader[I], readerO: Reader[O]): Either[String, EvaluationResult[I, O]] =
    try
      val parsed: ujson.Value = ujson.read(json)
      val formatVersion: Int  = parsed("formatVersion").num.toInt
      if formatVersion != 1 then Left(s"Unsupported formatVersion: $formatVersion (expected 1)")
      else
        val score: Double               = parsed("score").num
        val rows: Vector[EvalRow[I, O]] = parsed("rows").arr.toVector.map(rowFromJson)
        Right(EvaluationResult(score, rows))
    catch case e: Exception => Left(e.getMessage)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def rowFromJson[I, O](
    js: ujson.Value
  )(using readerI: Reader[I], readerO: Reader[O]): EvalRow[I, O] =
    val exampleJs: ujson.Value = js("example")
    val input: I               = exampleJs("input").transform(readerI)
    val gold: O                = exampleJs("gold").transform(readerO)
    val id: Option[String] = exampleJs("id") match
      case ujson.Null => None
      case v          => Some(v.str)
    val meta: Map[String, String] = exampleJs("meta").obj.toMap.map { case (k, v) => k -> v.str }
    val example: Example[I, O]    = Example(input, gold, id, meta)

    val outcome: EvalOutcome[O] = js("outcome")("type").str match
      case "succeeded" => EvalOutcome.Succeeded(js("outcome")("value").transform(readerO))
      case "failed"    => EvalOutcome.Failed(new RuntimeException(js("outcome")("error").str))
      case other       => throw new RuntimeException(s"Unknown outcome type: $other")

    val scoreValue: Double = js("score")("value").num
    val feedback: Option[String] = js("score")("feedback") match
      case ujson.Null => None
      case v          => Some(v.str)
    val score: Score = Score(scoreValue, feedback)

    EvalRow(example, outcome, score)
