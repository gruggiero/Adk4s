package org.adk4s.harness

import org.adk4s.core.tools.{ ToolInput, ToolOutput }

/**
 * State-threading tool execution payload: tool input and the current
 * harness state. State-threading is explicit in this product type rather
 * than buried in a `StateT` monad, so that checkpoint and interrupt paths
 * have the state value reified at suspension points.
 *
 * spec: agent-middleware — Requirement: ToolCallCtx and ToolCallOut thread harness state through tool execution
 */
final case class ToolCallCtx(
  input: ToolInput,
  state: HarnessState
)

/**
 * Tool execution response: tool output and the resulting harness state.
 * The output state is the input to the next tool call.
 *
 * spec: agent-middleware — Requirement: ToolCallCtx and ToolCallOut thread harness state through tool execution
 */
final case class ToolCallOut(
  output: ToolOutput,
  state: HarnessState
)
