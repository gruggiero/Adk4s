package org.adk4s.orchestration.memory

import io.github.iltotore.iron.refineEither
import io.github.iltotore.iron.constraint.numeric.Positive0
import scala.annotation.targetName
import org.adk4s.core.types.NonNegative
import org.adk4s.core.error.ConfigError
import org.adk4s.memory.MemoryHit
import org.adk4s.memory.TemporalScope

/** Immutable configuration for the memory hook.
  *
  * Fields:
  *   - `recallK`: non-negative `Int` (refined via `NonNegative` Iron constraint)
  *   - `scope`: optional temporal scope for recall
  *   - `writeUserInput`: whether to persist the user's input as an `Episode`
  *   - `writeAssistantOutput`: whether to persist the assistant's output as an `Episode`
  *   - `render`: pure function from hit list to a labeled context string
  *
  * The constructor is private; the companion `applyEither` is the total smart
  * constructor returning `Either[ConfigError, MemoryPolicy]`. The throwing
  * `apply` overload delegates to `applyEither` and throws on `Left`.
  *
  * spec: add-iron-refined-types/memory-orchestration-hook — Requirement: MemoryPolicy.recallK is non-negative via typed refinement
  */
final case class MemoryPolicy private (
  recallK: NonNegative,
  scope: Option[TemporalScope],
  writeUserInput: Boolean,
  writeAssistantOutput: Boolean,
  render: List[MemoryHit] => String
)

object MemoryPolicy:

  /** Total smart constructor returning a typed error.
    *
    * Refines `recallK` via `refineEither[NonNegative]`, returning
    * `Left(ConfigError("recallK", …, "NonNegative"))` on negative input.
    * No `IllegalArgumentException` is thrown.
    *
    * spec: add-iron-refined-types/memory-orchestration-hook — Scenario: Negative recallK returns ConfigError
    */
  def applyEither(
    recallK: Int,
    scope: Option[TemporalScope] = None,
    writeUserInput: Boolean = true,
    writeAssistantOutput: Boolean = true,
    render: List[MemoryHit] => String = defaultRender
  ): Either[ConfigError, MemoryPolicy] =
    recallK.refineEither[Positive0].map { (refinedK: NonNegative) =>
      new MemoryPolicy(refinedK, scope, writeUserInput, writeAssistantOutput, render)
    }.left.map { (_: String) =>
      ConfigError("recallK", recallK.toString, "NonNegative")
    }

  /** Throwing smart constructor — delegates to `applyEither` and throws on `Left`.
    *
    * Preserves source compatibility for callers that catch
    * `IllegalArgumentException`. The thrown exception is a `ConfigError`
    * (which extends `AdkError` / `Throwable`).
    *
    * spec: add-iron-refined-types/memory-orchestration-hook — Scenario: Throwing overload preserved for source compatibility
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  @targetName("applyThrowing")
  def apply(
    recallK: Int,
    scope: Option[TemporalScope] = None,
    writeUserInput: Boolean = true,
    writeAssistantOutput: Boolean = true,
    render: List[MemoryHit] => String = defaultRender
  ): MemoryPolicy =
    applyEither(recallK, scope, writeUserInput, writeAssistantOutput, render).fold(
      err => throw err,
      identity
    )

  /** Default policy: `recallK = 5`, no scope, both write flags true, default render. */
  def default: MemoryPolicy = MemoryPolicy(recallK = 5)

  /** Default render: a labeled "Relevant memory:" block, one hit per line.
    * Pure, total, deterministic. Returns `""` for an empty list.
    */
  def defaultRender(hits: List[MemoryHit]): String =
    if hits.isEmpty then ""
    else
      val lines: List[String] = "Relevant memory:" :: hits.map((h: MemoryHit) => s"- ${h.text}")
      lines.mkString("\n")
