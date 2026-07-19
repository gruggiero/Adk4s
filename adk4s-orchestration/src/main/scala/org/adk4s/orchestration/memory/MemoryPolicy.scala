package org.adk4s.orchestration.memory

import org.adk4s.memory.MemoryHit
import org.adk4s.memory.TemporalScope

/** Immutable configuration for the memory hook.
  *
  * Fields:
  *   - `recallK`: non-negative `Int` (smart constructor enforces `>= 0`)
  *   - `scope`: optional temporal scope for recall
  *   - `writeUserInput`: whether to persist the user's input as an `Episode`
  *   - `writeAssistantOutput`: whether to persist the assistant's output as an `Episode`
  *   - `render`: pure function from hit list to a labeled context string
  *
  * The constructor is private; the companion `apply` is the smart constructor
  * that enforces `recallK >= 0` via `require`.
  */
final case class MemoryPolicy private (
  recallK: Int,
  scope: Option[TemporalScope],
  writeUserInput: Boolean,
  writeAssistantOutput: Boolean,
  render: List[MemoryHit] => String
)

object MemoryPolicy:
  def apply(
    recallK: Int,
    scope: Option[TemporalScope] = None,
    writeUserInput: Boolean = true,
    writeAssistantOutput: Boolean = true,
    render: List[MemoryHit] => String = defaultRender
  ): MemoryPolicy =
    require(recallK >= 0, s"recallK must be non-negative, got $recallK")
    new MemoryPolicy(recallK, scope, writeUserInput, writeAssistantOutput, render)

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
