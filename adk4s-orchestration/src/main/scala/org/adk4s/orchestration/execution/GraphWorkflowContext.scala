package org.adk4s.orchestration.execution

import workflows4s.wio.WorkflowContext

/** WorkflowContext for Graph WIO compilation.
  *
  * Uses `Any` as the State type to allow heterogeneous node output types
  * to be composed in WIO chains. This enables type-safe graph construction
  * while still permitting WIO compilation across different node types.
  */
object GraphWorkflowContext extends WorkflowContext:
  override type State = Any

  /** Event type for graph node execution results. */
  sealed trait Event
  case class NodeResult(value: Any) extends Event
