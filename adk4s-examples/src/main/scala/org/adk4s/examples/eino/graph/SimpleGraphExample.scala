package org.adk4s.examples.eino.graph

import cats.data.Ior
import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.examples.eino.common.MockChatModel
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIOPureNode
import org.adk4s.orchestration.wiograph.WIORunIONode
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCEvent
import workflows4s.wio.WCState
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.WCEffect
import workflows4s.wio.WCEffectLift
import workflows4s.wio.internal.WakeupResult

import java.time.Instant
import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/simple/graph.go
 *
 * Simple graph: START → ChatTemplate → ChatModel → END
 * Demonstrates basic WIOGraph construction with typed nodes and edges.
 */
object SimpleGraphExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class InputState(topic: String) extends GraphState
    final case class ConversationState(conversation: Conversation) extends GraphState
    final case class OutputState(response: String) extends GraphState

    sealed trait GraphEvent
    final case class ChatCompleted(completion: Completion) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.ChatCompleted
  import Ctx.ConversationState
  import Ctx.GraphState
  import Ctx.InputState
  import Ctx.OutputState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[ChatCompleted] = scala.reflect.ClassTag(classOf[ChatCompleted])

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Simple Graph Example (Eino: graph/simple)")
      chatModel <- ExampleUtils.createChatModel

      graph = buildGraph(chatModel)
      wio <- graph.toWIO match
        case Right(wio) => IO.pure(wio)
        case Left(errors: NonEmptyChain[WIOGraphError]) =>
          IO.raiseError(new IllegalStateException(s"Graph errors: ${errors.toNonEmptyList.toList.mkString(", ")}"))

      input = InputState("cats")
      result <- executeWio(wio, input)

      _ <- (result: GraphState) match
        case output: OutputState =>
          IO.println(s"   Topic: cats") *>
            IO.println(s"   Response: ${output.response}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      _ <- IO.println("\nSimple graph example completed.")
    yield ()

  private def buildGraph(chatModel: ChatModel[IO]): WIOGraph[Ctx.Ctx, InputState, Nothing, GraphState] =
    val templateRef: WIONodeRef[Ctx.Ctx, InputState, ConversationState] =
      WIONodeRef[Ctx.Ctx, InputState, ConversationState](NodeKey.unsafeApply("template"))
    val chatRef: WIONodeRef[Ctx.Ctx, ConversationState, OutputState] =
      WIONodeRef[Ctx.Ctx, ConversationState, OutputState](NodeKey.unsafeApply("chat"))
    val endRef: WIONodeRef[Ctx.Ctx, ConversationState, GraphState] =
      WIONodeRef[Ctx.Ctx, ConversationState, GraphState](NodeKey.unsafeApply("chat"))

    val templateNode: WIOPureNode[Ctx.Ctx, InputState, Nothing, ConversationState] =
      WIONode.pure[Ctx.Ctx, InputState, ConversationState]((input: InputState) =>
        ConversationState(Conversation(Seq(
          SystemMessage(s"You are an expert on ${input.topic}."),
          UserMessage(s"Tell me about ${input.topic}.")
        )))
      )

    val chatNode: WIORunIONode[Ctx.Ctx, ConversationState, Nothing, ChatCompleted, OutputState] =
      WIONode.runIO[Ctx.Ctx, ConversationState, ChatCompleted, OutputState](
        runIO = (state: ConversationState) =>
          chatModel.generate(state.conversation).map((c: Completion) => ChatCompleted(c)),
        handleEvent = (_: ConversationState, evt: ChatCompleted) =>
          OutputState(response = evt.completion.content)
      )

    WIOGraph[Ctx.Ctx, InputState, GraphState]
      .addNode("template", templateNode)
      .addNode("chat", chatNode)
      .addEdge(templateRef, chatRef)
      .setEntry(templateRef)
      .addEndNode(endRef)

  private def executeWio(
    wio: WIO[InputState, Nothing, GraphState, Ctx.Ctx],
    input: InputState
  ): IO[GraphState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("simple-graph", "simple-graph")
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
                val next: ActiveWorkflow[Ctx.Ctx] = workflow.handleEvent(event).getOrElse(workflow)
                (next, true)
              case None => (workflow, false)
          }

    def loop(workflow: ActiveWorkflow[Ctx.Ctx]): IO[ActiveWorkflow[Ctx.Ctx]] =
      proceedOnce(workflow).flatMap { case (next, continued) =>
        if continued then loop(next) else IO.pure(next)
      }

    loop(start).map(_.liveState)
