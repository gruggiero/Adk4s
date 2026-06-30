package org.adk4s.orchestration.wiograph

sealed trait WIOGraphError

object WIOGraphError:
  case class CycleDetected(cycle: List[NodeKey]) extends WIOGraphError
  case object MissingEntry extends WIOGraphError
  case class UnreachableEnd(endNodes: Set[NodeKey]) extends WIOGraphError
  case class NodeAlreadyExists(key: String) extends WIOGraphError
  case class EntryNodeNotFound(key: String) extends WIOGraphError
  case class EndNodeNotFound(key: String) extends WIOGraphError
  case class SourceNodeNotFound(key: String) extends WIOGraphError
  case class TargetNodeNotFound(key: String) extends WIOGraphError
  case class NodeNotFoundInGraph(key: String) extends WIOGraphError
  case class MultipleOutgoingEdges(key: String) extends WIOGraphError
  case class UnsupportedNodeType(nodeType: String) extends WIOGraphError
  case class SubGraphCompilationFailed(errors: String) extends WIOGraphError
  case class ForkEdgeBranchMismatch(edgeCount: Int, branchCount: Int) extends WIOGraphError
