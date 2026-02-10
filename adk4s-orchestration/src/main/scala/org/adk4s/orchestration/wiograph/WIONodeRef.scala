package org.adk4s.orchestration.wiograph

import workflows4s.wio.WorkflowContext

case class WIONodeRef[Ctx <: WorkflowContext, I, O](key: NodeKey)
