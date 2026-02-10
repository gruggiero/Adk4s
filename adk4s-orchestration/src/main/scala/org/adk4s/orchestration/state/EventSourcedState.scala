package org.adk4s.orchestration.state

import cats.effect.IO
import workflows4s.wio.{WIO, WorkflowContext}

trait AdkWorkflowContext extends WorkflowContext:
  def initialState: State
  def applyEvent(state: State, event: Event): State

object AgentStateContext extends AdkWorkflowContext:
  case class AgentState(
    messages: List[org.adk4s.structured.core.Message] = Nil,
    stepCount: Int = 0,
    toolCallCount: Int = 0,
    metadata: Map[String, String] = Map.empty
  )

  sealed trait AgentEvent
  case class MessageAdded(message: org.adk4s.structured.core.Message) extends AgentEvent
  case class StepCompleted(stepNumber: Int) extends AgentEvent
  case class ToolCalled(toolName: String) extends AgentEvent
  case class MetadataUpdated(key: String, value: String) extends AgentEvent

  override type State = AgentState
  override type Event = AgentEvent

  def initialState: AgentState = AgentState()

  def applyEvent(state: AgentState, event: AgentEvent): AgentState =
    event match
      case MessageAdded(msg) =>
        state.copy(messages = state.messages :+ msg)
      case StepCompleted(step) =>
        state.copy(stepCount = step)
      case ToolCalled(_) =>
        state.copy(toolCallCount = state.toolCallCount + 1)
      case MetadataUpdated(key, value) =>
        state.copy(metadata = state.metadata + (key -> value))
