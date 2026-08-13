package org.adk4s.orchestration.workflow

import cats.effect.IO
import org.adk4s.core.types.{NodeKey, FieldPath}
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.error.NodeKeyError

case class Workflow[I, O] private (
  private val nodes: Map[NodeKey, WorkflowNode[?, ?]],
  private val inputs: Map[NodeKey, List[(NodeKey, FieldMapping)]],
  private val endNode: Option[NodeKey]
):
  def addLambdaNode[A, B](key: String, lambda: Lambda[A, B]): Either[NodeKeyError, WorkflowNodeBuilder[I, O, A, B]] =
    NodeKey.from(key).map { (nodeKey: NodeKey) =>
      WorkflowNodeBuilder(this, nodeKey, WorkflowNode.Lambda[A, B](lambda))
    }

  def end: WorkflowEndBuilder[I, O] =
    WorkflowEndBuilder(this)

  def compile: IO[Runnable[I, O]] = IO.raiseError(
    new UnsupportedOperationException("Workflow execution not yet implemented")
  )

  private[workflow] def addNode[A, B](key: NodeKey, node: WorkflowNode[A, B]): Workflow[I, O] =
    copy(nodes = nodes + (key -> node), inputs = inputs + (key -> List.empty))

  private[workflow] def addInputToNode(from: NodeKey, to: NodeKey, mapping: FieldMapping): Workflow[I, O] =
    val currentInputs = inputs.getOrElse(to, List.empty)
    copy(inputs = inputs + (to -> (currentInputs :+ (from, mapping))))

  private[workflow] def withEndNode(key: NodeKey): Workflow[I, O] =
    copy(endNode = Some(key))

object Workflow:
  def apply[I, O]: Workflow[I, O] = Workflow(Map.empty, Map.empty, None)

case class WorkflowNodeBuilder[I, O, A, B](
  workflow: Workflow[I, O],
  key: NodeKey,
  node: WorkflowNode[A, B]
):
  def addInput(from: String): Either[NodeKeyError, WorkflowNodeBuilder[I, O, A, B]] =
    NodeKey.from(from).map { (fromKey: NodeKey) =>
      addInputWithNodeKey(fromKey, FieldMapping(FieldPath.Root, FieldPath.Root))
    }

  def addInput(from: String, mapping: FieldMapping): Either[NodeKeyError, WorkflowNodeBuilder[I, O, A, B]] =
    NodeKey.from(from).map { (fromKey: NodeKey) =>
      addInputWithNodeKey(fromKey, mapping)
    }

  def addLambdaNode[C, D](key: String, lambda: Lambda[C, D]): Either[NodeKeyError, WorkflowNodeBuilder[I, O, C, D]] =
    workflow.addLambdaNode(key, lambda)

  private def addInputWithNodeKey(from: NodeKey, mapping: FieldMapping): WorkflowNodeBuilder[I, O, A, B] =
    copy(workflow = workflow.addInputToNode(from, key, mapping))

  def done: Workflow[I, O] = workflow

case class WorkflowEndBuilder[I, O](workflow: Workflow[I, O]):
  def at(key: String): Either[NodeKeyError, Workflow[I, O]] =
    NodeKey.from(key).map { (nodeKey: NodeKey) =>
      workflow.withEndNode(nodeKey)
    }
