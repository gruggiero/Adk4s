package org.adk4s.harness

import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Message }
import org.adk4s.core.component.InvokableTool

/**
 * Per-request model call payload: system prompt, messages, tools, options,
 * and the current harness state.
 *
 * The tool list and system prompt are per-request values (not baked once at
 * agent construction), so that middleware can rewrite them per call via
 * `wrapModelCall`.
 *
 * spec: agent-middleware — Requirement: ModelRequest carries per-request model call payload with harness state
 */
final case class ModelRequest[F[_]](
  systemPrompt: Option[SystemPrompt],
  messages: List[Message],
  tools: List[InvokableTool[F]],
  options: CompletionOptions,
  state: HarnessState
)

/**
 * Model call response: completion and the harness state resulting from the
 * call. The response state flows into the next loop iteration.
 *
 * spec: agent-middleware — Requirement: ModelResponse carries completion and harness state
 */
final case class ModelResponse(
  completion: Completion,
  state: HarnessState
)
