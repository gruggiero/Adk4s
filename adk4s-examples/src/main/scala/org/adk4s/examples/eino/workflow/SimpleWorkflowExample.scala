package org.adk4s.examples.eino.workflow

import cats.data.Ior
import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIOPureNode
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCEvent
import workflows4s.wio.WCState
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.internal.WakeupResult

import java.time.Instant
import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/workflow/1_simple/main.go
 *
 * Simple lambda chain via WIOGraph: node1 → node2 → node3.
 * Uses WIOGraph as an alternative to the unfinished Workflow API.
 * Demonstrates that WIOGraph provides event sourcing and BPMN for free.
 */
object SimpleWorkflowExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait WfState
    final case class InputState(value: Int) extends WfState
    final case class DoubledState(original: Int, doubled: Int) extends WfState
    final case class OutputState(original: Int, doubled: Int, message: String) extends WfState

    sealed trait WfEvent
    override type State = WfState
    override type Event = WfEvent

  import Ctx.WfState
  import Ctx.DoubledState
  import Ctx.InputState
  import Ctx.OutputState

  private given ErrorMeta[Nothing] = ErrorMeta.noError

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Simple Workflow Example (Eino: workflow/1_simple)")

      graph = buildGraph()
      wio <- graph.toWIO match
        case Right(wio) => IO.pure(wio)
        case Left(errors: NonEmptyChain[WIOGraphError]) =>
          IO.raiseError(new IllegalStateException(errors.toNonEmptyList.toList.mkString(", ")))

      // Run with input value 5
      input = InputState(5)
      result <- executeWio(wio, input)

      _ <- (result: WfState) match
        case output: OutputState =>
          IO.println(s"   Input: ${output.original}") *>
            IO.println(s"   Doubled: ${output.doubled}") *>
            IO.println(s"   Message: ${output.message}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      // Run with input value 10
      input2 = InputState(10)
      result2 <- executeWio(wio, input2)

      _ <- (result2: WfState) match
        case output: OutputState =>
          IO.println(s"   Input: ${output.original}") *>
            IO.println(s"   Doubled: ${output.doubled}") *>
            IO.println(s"   Message: ${output.message}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      _ <- IO.println("\nSimple workflow example completed.")
    yield ()

  private def buildGraph(): WIOGraph[Ctx.Ctx, InputState, Nothing, WfState] =
    val doubleRef: WIONodeRef[Ctx.Ctx, InputState, DoubledState] =
      WIONodeRef[Ctx.Ctx, InputState, DoubledState](NodeKey.unsafeApply("double"))
    val formatRef: WIONodeRef[Ctx.Ctx, DoubledState, OutputState] =
      WIONodeRef[Ctx.Ctx, DoubledState, OutputState](NodeKey.unsafeApply("format"))
    val endRef: WIONodeRef[Ctx.Ctx, DoubledState, WfState] =
      WIONodeRef[Ctx.Ctx, DoubledState, WfState](NodeKey.unsafeApply("format"))

    val doubleNode: WIOPureNode[Ctx.Ctx, InputState, Nothing, DoubledState] =
      WIONode.pure[Ctx.Ctx, InputState, DoubledState]((input: InputState) =>
        DoubledState(original = input.value, doubled = input.value * 2)
      )

    val formatNode: WIOPureNode[Ctx.Ctx, DoubledState, Nothing, OutputState] =
      WIONode.pure[Ctx.Ctx, DoubledState, OutputState]((state: DoubledState) =>
        OutputState(
          original = state.original,
          doubled = state.doubled,
          message = s"${state.original} * 2 = ${state.doubled}"
        )
      )

    WIOGraph[Ctx.Ctx, InputState, WfState]
      .addNode("double", doubleNode)
      .addNode("format", formatNode)
      .addEdge(doubleRef, formatRef)
      .setEntry(doubleRef)
      .addEndNode(endRef)

  private def executeWio(
    wio: WIO[InputState, Nothing, WfState, Ctx.Ctx],
    input: InputState
  ): IO[WfState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("simple-workflow", "simple-workflow")
    val start: ActiveWorkflow[Ctx.Ctx] =
      ActiveWorkflow[Ctx.Ctx](workflowId, wio.provideInput(input), input)

    def proceedOnce(
      workflow: ActiveWorkflow[Ctx.Ctx]
    ): IO[(ActiveWorkflow[Ctx.Ctx], Boolean)] =
      val wakeup: WakeupResult[WCEvent[Ctx.Ctx]] = workflow.proceed(Instant.EPOCH)
      wakeup.toRaw match
        case None => IO.pure((workflow, false))
        case Some(io: IO[Ior[Instant, WCEvent[Ctx.Ctx]]]) =>
          io.map { (result: Ior[Instant, WCEvent[Ctx.Ctx]]) =>
            val eventOpt: Option[WCEvent[Ctx.Ctx]] = result match
              case Ior.Right(event) => Some(event)
              case Ior.Both(_, event) => Some(event)
              case Ior.Left(_) => None
            eventOpt match
              case Some(event) =>
                (workflow.handleEvent(event).getOrElse(workflow), true)
              case None => (workflow, false)
          }

    def loop(workflow: ActiveWorkflow[Ctx.Ctx]): IO[ActiveWorkflow[Ctx.Ctx]] =
      proceedOnce(workflow).flatMap { case (next, continued) =>
        if continued then loop(next) else IO.pure(next)
      }

    loop(start).map(_.liveState)
