package org.adk4s.orchestration.wiograph

sealed trait WIOGraphError

object WIOGraphError:
  case class CycleDetected(cycle: List[NodeKey]) extends WIOGraphError
  case object MissingEntry extends WIOGraphError
  case class UnreachableEnd(endNodes: Set[NodeKey]) extends WIOGraphError
