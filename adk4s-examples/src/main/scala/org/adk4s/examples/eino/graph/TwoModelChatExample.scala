package org.adk4s.examples.eino.graph

import cats.data.Ior
import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
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
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.WCEffect
import workflows4s.wio.WCEffectLift
import workflows4s.wio.internal.WakeupResult

import java.time.Instant
import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/two_model_chat/two_model_chat.go
 *
 * Writer/critic loop using WIOLoopNode.
 * Writer generates a draft, critic reviews it, loop continues until maxRounds.
 */
object TwoModelChatExample extends IOApp.Simple:

  private val maxRounds: Int = 3

  object Ctx extends WorkflowContext:
    sealed trait LoopState
    final case class ChatState(
      topic: String,
      writerDraft: String,
      criticFeedback: String,
      currentRound: Int,
      history: List[String]
    ) extends LoopState
    final case class FinalState(topic: String, finalDraft: String, rounds: Int, history: List[String]) extends LoopState

    sealed trait LoopEvent
    final case class WriterCompleted(draft: String) extends LoopEvent
    final case class CriticCompleted(feedback: String) extends LoopEvent

    override type State = LoopState
    override type Event = LoopEvent

  import Ctx.ChatState
  import Ctx.CriticCompleted
  import Ctx.FinalState
  import Ctx.LoopEvent
  import Ctx.LoopState
  import Ctx.WriterCompleted

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[WriterCompleted] = scala.reflect.ClassTag(classOf[WriterCompleted])
  private given ClassTag[CriticCompleted] = scala.reflect.ClassTag(classOf[CriticCompleted])

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Two Model Chat Example (Eino: graph/two_model_chat)")
      chatModel <- ExampleUtils.createChatModel

      // Build the loop body as a WIO
      loopBody: WIO[ChatState, Nothing, ChatState, Ctx.Ctx] = buildLoopBody(chatModel)

      // Create the loop using WIONode.loop
      loopWIO: WIO[ChatState, Nothing, ChatState, Ctx.Ctx] = WIONode.loop[Ctx.Ctx, ChatState, Nothing, ChatState](
        body = loopBody,
        stopCondition = (state: ChatState) => state.currentRound >= maxRounds,
        restart = WIO.Pure[Ctx.Ctx, ChatState, Nothing, ChatState](
          _ => (s: ChatState) => Right(s),
          WIO.Pure.Meta(ErrorMeta.noError, None)
        )
      ).toWIO

      // Wrap in a graph: init → loop → finalize
      initialState = ChatState(
        topic = "The benefits of functional programming",
        writerDraft = "",
        criticFeedback = "",
        currentRound = 0,
        history = List.empty[String]
      )

      result <- executeWio(loopWIO, initialState)

      _ <- (result: LoopState) match
        case state: ChatState =>
          IO.println(s"   Topic: ${state.topic}") *>
            IO.println(s"   Rounds completed: ${state.currentRound}") *>
            IO.println(s"   Final draft: ${state.writerDraft.take(100)}...") *>
            IO.println(s"   Last feedback: ${state.criticFeedback.take(100)}...") *>
            IO.println(s"\n   History:") *>
            state.history.zipWithIndex.foldLeft(IO.unit) { case (acc, (entry: String, idx: Int)) =>
              acc *> IO.println(s"   [$idx] ${entry.take(80)}...")
            }
        case final_ : FinalState =>
          IO.println(s"   Topic: ${final_.topic}") *>
            IO.println(s"   Rounds: ${final_.rounds}") *>
            IO.println(s"   Final: ${final_.finalDraft.take(100)}...")

      _ <- IO.println("\nTwo model chat example completed.")
    yield ()

  private def buildLoopBody(chatModel: ChatModel[IO]): WIO[ChatState, Nothing, ChatState, Ctx.Ctx] =
    // Writer step
    val writerNode: WIORunIONode[Ctx.Ctx, ChatState, Nothing, WriterCompleted, ChatState] =
      WIONode.runIO[Ctx.Ctx, ChatState, WriterCompleted, ChatState](
        runIO = (state: ChatState) =>
          val prompt: String =
            if state.writerDraft.isEmpty then
              s"Write a short paragraph about: ${state.topic}"
            else
              s"Revise this draft based on feedback.\nDraft: ${state.writerDraft}\nFeedback: ${state.criticFeedback}"
          val conversation: Conversation = Conversation(Seq(
            SystemMessage("You are a writer. Write concise, clear prose."),
            UserMessage(prompt)
          ))
          chatModel.generate(conversation).map((c: Completion) => WriterCompleted(c.content)),
        handleEvent = (state: ChatState, evt: WriterCompleted) =>
          state.copy(
            writerDraft = evt.draft,
            history = state.history :+ s"[Writer R${state.currentRound + 1}] ${evt.draft.take(60)}"
          )
      )

    // Critic step
    val criticNode: WIORunIONode[Ctx.Ctx, ChatState, Nothing, CriticCompleted, ChatState] =
      WIONode.runIO[Ctx.Ctx, ChatState, CriticCompleted, ChatState](
        runIO = (state: ChatState) =>
          val conversation: Conversation = Conversation(Seq(
            SystemMessage("You are a critic. Give brief, constructive feedback."),
            UserMessage(s"Review this draft:\n${state.writerDraft}")
          ))
          chatModel.generate(conversation).map((c: Completion) => CriticCompleted(c.content)),
        handleEvent = (state: ChatState, evt: CriticCompleted) =>
          state.copy(
            criticFeedback = evt.feedback,
            currentRound = state.currentRound + 1,
            history = state.history :+ s"[Critic R${state.currentRound + 1}] ${evt.feedback.take(60)}"
          )
      )

    // Chain: writer → critic
    writerNode.toWIO.flatMap((_: ChatState) => criticNode.toWIO)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def executeWio(
    wio: WIO[ChatState, Nothing, LoopState, Ctx.Ctx],
    input: ChatState
  ): IO[LoopState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("two-model-chat", "two-model-chat")
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
