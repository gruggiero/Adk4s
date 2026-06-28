package org.adk4s.examples.eino.workflow

import cats.data.Ior
import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOForkNode
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIOPureNode
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCEvent
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.WCEffect
import workflows4s.wio.WCEffectLift
import workflows4s.wio.internal.WakeupResult

import java.time.Instant

/**
 * Eino equivalent: compose/workflow/4_control_only_branch/main.go
 *
 * Branching via WIOForkNode with multiple branches.
 * Demonstrates conditional routing based on input value.
 */
object BranchWorkflowExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait BranchState
    final case class InputState(category: String, value: Int) extends BranchState
    final case class OutputState(category: String, result: String) extends BranchState

    sealed trait BranchEvent
    override type State = BranchState
    override type Event = BranchEvent

  import Ctx.BranchState
  import Ctx.InputState
  import Ctx.OutputState

  private given ErrorMeta[Nothing] = ErrorMeta.noError

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Branch Workflow Example (Eino: workflow/4_control_only_branch)")

      graph = buildGraph()
      wio <- graph.toWIO match
        case Right(wio) => IO.pure(wio)
        case Left(errors: NonEmptyChain[WIOGraphError]) =>
          IO.raiseError(new IllegalStateException(errors.toNonEmptyList.toList.mkString(", ")))

      // Test with "high" category
      _ <- ExampleUtils.printSubSection("1. High Priority Input")
      result1 <- executeWio(wio, InputState("high", 100))
      _ <- result1 match
        case output: OutputState => IO.println(s"   Category: ${output.category}, Result: ${output.result}")
        case other => IO.println(s"   Unexpected: $other")

      // Test with "low" category
      _ <- ExampleUtils.printSubSection("2. Low Priority Input")
      result2 <- executeWio(wio, InputState("low", 5))
      _ <- result2 match
        case output: OutputState => IO.println(s"   Category: ${output.category}, Result: ${output.result}")
        case other => IO.println(s"   Unexpected: $other")

      // Test with unknown category (falls to otherwise)
      _ <- ExampleUtils.printSubSection("3. Unknown Category (otherwise)")
      result3 <- executeWio(wio, InputState("unknown", 42))
      _ <- result3 match
        case output: OutputState => IO.println(s"   Category: ${output.category}, Result: ${output.result}")
        case other => IO.println(s"   Unexpected: $other")

      _ <- IO.println("\nBranch workflow example completed.")
    yield ()

  private def buildGraph(): WIOGraph[Ctx.Ctx, InputState, Nothing, BranchState] =
    val forkRef: WIONodeRef[Ctx.Ctx, InputState, OutputState] =
      WIONodeRef[Ctx.Ctx, InputState, OutputState](NodeKey.unsafeApply("fork"))
    val endRef: WIONodeRef[Ctx.Ctx, InputState, BranchState] =
      WIONodeRef[Ctx.Ctx, InputState, BranchState](NodeKey.unsafeApply("fork"))

    val highBranch: WIO[InputState, Nothing, OutputState, Ctx.Ctx] =
      WIONode.pure[Ctx.Ctx, InputState, OutputState]((input: InputState) =>
        OutputState(input.category, s"HIGH priority processing: value=${input.value}, expedited!")
      ).toWIO

    val lowBranch: WIO[InputState, Nothing, OutputState, Ctx.Ctx] =
      WIONode.pure[Ctx.Ctx, InputState, OutputState]((input: InputState) =>
        OutputState(input.category, s"LOW priority processing: value=${input.value}, queued.")
      ).toWIO

    val defaultBranch: WIO[InputState, Nothing, OutputState, Ctx.Ctx] =
      WIONode.pure[Ctx.Ctx, InputState, OutputState]((input: InputState) =>
        OutputState(input.category, s"DEFAULT processing: value=${input.value}, standard handling.")
      ).toWIO

    val forkNode: WIOForkNode[Ctx.Ctx, InputState, Nothing, OutputState] =
      WIOForkNode.withOtherwise[Ctx.Ctx, InputState, Nothing, OutputState](
        branches = List(
          WIOForkNode.Branch[Ctx.Ctx, InputState, Nothing, OutputState](
            predicate = (s: InputState) => if s.category == "high" then Some(s) else None,
            workflow = highBranch,
            branchName = Some("high")
          ),
          WIOForkNode.Branch[Ctx.Ctx, InputState, Nothing, OutputState](
            predicate = (s: InputState) => if s.category == "low" then Some(s) else None,
            workflow = lowBranch,
            branchName = Some("low")
          )
        ),
        otherwise = defaultBranch
      )

    WIOGraph[Ctx.Ctx, InputState, BranchState]
      .addNode("fork", forkNode)
      .setEntry(forkRef)
      .addEndNode(endRef)

  private def executeWio(
    wio: WIO[InputState, Nothing, BranchState, Ctx.Ctx],
    input: InputState
  ): IO[BranchState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("branch-workflow", "branch-workflow")
    val start: ActiveWorkflow[Ctx.Ctx] =
      ActiveWorkflow[Ctx.Ctx](workflowId, wio.provideInput(input), input)

    def proceedOnce(
      workflow: ActiveWorkflow[Ctx.Ctx]
    ): IO[(ActiveWorkflow[Ctx.Ctx], Boolean)] =
      val liftEffect: WCEffectLift[Ctx.Ctx, IO] = [A] => (fa: WCEffect[Ctx.Ctx][A]) => fa.asInstanceOf[IO[A]]
      val wakeup: WakeupResult[IO, WCEvent[Ctx.Ctx]] = workflow.proceed(Instant.EPOCH, liftEffect)
      wakeup match
        case WakeupResult.Noop() => IO.pure((workflow, false))
        case WakeupResult.Processed(io) =>
          io.asInstanceOf[IO[Ior[Instant, WCEvent[Ctx.Ctx]]]].map { (result: Ior[Instant, WCEvent[Ctx.Ctx]]) =>
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
