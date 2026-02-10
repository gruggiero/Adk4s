package org.adk4s.orchestration.workflow

import org.adk4s.core.types.{NodeKey, FieldPath}

case class FieldMapping(
  from: FieldPath,
  to: FieldPath,
  fromNode: Option[NodeKey] = None
)

object FieldMapping:
  def apply(from: String, to: String): FieldMapping =
    FieldMapping(FieldPath(from), FieldPath(to))

  def rootRoot: FieldMapping =
    FieldMapping(FieldPath.Root, FieldPath.Root)

  def withNode(from: String, to: String, nodeKey: NodeKey): FieldMapping =
    FieldMapping(FieldPath(from), FieldPath(to), Some(nodeKey))
