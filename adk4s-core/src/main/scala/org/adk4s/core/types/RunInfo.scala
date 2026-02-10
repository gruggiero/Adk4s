package org.adk4s.core.types

import cats.Show
import java.time.Instant

case class RunInfo(
  nodeKey: NodeKey,
  componentType: String,
  nodeName: Option[String] = None,
  startTime: Option[Instant] = None,
  parentPath: List[NodeKey] = Nil
):
  def fullPath: List[NodeKey] = parentPath :+ nodeKey

object RunInfo:
  def forNode(key: NodeKey, componentType: String): RunInfo =
    RunInfo(key, componentType)

  def forNode(key: NodeKey, componentType: String, name: String): RunInfo =
    RunInfo(key, componentType, Some(name))

  given Show[RunInfo] = Show.show { info =>
    val name = info.nodeName.map(n => s" ($n)").getOrElse("")
    val path = if info.parentPath.isEmpty then "" else s" [${info.fullPath.map(_.value).mkString(" -> ")}]"
    s"${info.nodeKey.value}$name: ${info.componentType}$path"
  }
