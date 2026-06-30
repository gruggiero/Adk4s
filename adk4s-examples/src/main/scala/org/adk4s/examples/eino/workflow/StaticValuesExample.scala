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
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.WCEffect
import workflows4s.wio.WCEffectLift
import workflows4s.wio.internal.WakeupResult

import java.time.Instant

/**
 * Eino equivalent: compose/workflow/5_static_values/main.go
 *
 * Static value injection via WIOPureNode transforms.
 * Demonstrates how to inject constants into the graph pipeline.
 */
object StaticValuesExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait SvState
    final case class InputState(name: String) extends SvState
    final case class EnrichedState(name: String, greeting: String, timestamp: Long) extends SvState
    final case class OutputState(message: String) extends SvState

    sealed trait SvEvent
    override type State = SvState
    override type Event = SvEvent

  import Ctx.SvState
  import Ctx.EnrichedState
  import Ctx.InputState
  import Ctx.OutputState

  private given ErrorMeta[Nothing] = ErrorMeta.noError

  // Static values injected into the pipeline
  private val defaultGreeting: String = "Hello"
  private val appVersion: String = "1.0.0"

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Static Values Example (Eino: workflow/5_static_values)")

      wio <- buildGraph() match
        case Left(err: WIOGraphError) =>
          IO.raiseError(new IllegalStateException(s"Graph build failed: $err"))
        case Right(graph: WIOGraph[Ctx.Ctx, InputState, Nothing, SvState]) =>
          graph.toWIO match
            case Right(wio) => IO.pure(wio)
            case Left(errors: NonEmptyChain[WIOGraphError]) =>
              IO.raiseError(new IllegalStateException(errors.toNonEmptyList.toList.mkString(", ")))

      _ <- ExampleUtils.printSubSection("1. With name 'Alice'")
      result1 <- executeWio(wio, InputState("Alice"))
      _ <- result1 match
        case output: OutputState => IO.println(s"   ${output.message}")
        case other => IO.println(s"   Unexpected: $other")

      _ <- ExampleUtils.printSubSection("2. With name 'Bob'")
      result2 <- executeWio(wio, InputState("Bob"))
      _ <- result2 match
        case output: OutputState => IO.println(s"   ${output.message}")
        case other => IO.println(s"   Unexpected: $other")

      _ <- IO.println("\nStatic values example completed.")
    yield ()

  private def buildGraph(): Either[WIOGraphError, WIOGraph[Ctx.Ctx, InputState, Nothing, SvState]] =
    val enrichRef: WIONodeRef[Ctx.Ctx, InputState, EnrichedState] =
      WIONodeRef[Ctx.Ctx, InputState, EnrichedState](NodeKey.unsafeApply("enrich"))
    val formatRef: WIONodeRef[Ctx.Ctx, EnrichedState, OutputState] =
      WIONodeRef[Ctx.Ctx, EnrichedState, OutputState](NodeKey.unsafeApply("format"))
    val endRef: WIONodeRef[Ctx.Ctx, EnrichedState, SvState] =
      WIONodeRef[Ctx.Ctx, EnrichedState, SvState](NodeKey.unsafeApply("format"))

    // Node 1: Inject static values (greeting, timestamp) alongside dynamic input
    val enrichNode: WIOPureNode[Ctx.Ctx, InputState, Nothing, EnrichedState] =
      WIONode.pure[Ctx.Ctx, InputState, EnrichedState]((input: InputState) =>
        EnrichedState(
          name = input.name,
          greeting = defaultGreeting,
          timestamp = System.currentTimeMillis()
        )
      )

    // Node 2: Format output using both dynamic and static values
    val formatNode: WIOPureNode[Ctx.Ctx, EnrichedState, Nothing, OutputState] =
      WIONode.pure[Ctx.Ctx, EnrichedState, OutputState]((state: EnrichedState) =>
        OutputState(
          message = s"${state.greeting}, ${state.name}! (app v$appVersion, ts=${state.timestamp})"
        )
      )

    for
      g1 <- WIOGraph[Ctx.Ctx, InputState, SvState].addNode("enrich", enrichNode)
      g2 <- g1.addNode("format", formatNode)
      g3 <- g2.addEdge(enrichRef, formatRef)
      g4 <- g3.setEntry(enrichRef)
      g5 <- g4.addEndNode(endRef)
    yield g5

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def executeWio(
    wio: WIO[InputState, Nothing, SvState, Ctx.Ctx],
    input: InputState
  ): IO[SvState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("static-values", "static-values")
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
