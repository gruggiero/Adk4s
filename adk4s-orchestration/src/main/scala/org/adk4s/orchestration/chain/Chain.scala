package org.adk4s.orchestration.chain

import cats.effect.IO
import org.adk4s.core.runnable.{Runnable, Lambda}
import org.adk4s.core.component.ChatModel
import org.adk4s.core.runnable.RunnableOps.{andThen, contramap}
import org.adk4s.orchestration.graph.Graph
import org.adk4s.core.types.NodeKey
import org.llm4s.llmconnect.model.{Conversation, Completion}
import scala.reflect.ClassTag

case class Chain[I, O] private (
  private val compileFn: IO[Runnable[I, O]]
):
  def appendLambda[O2](lambda: Lambda[O, O2]): Chain[I, O2] =
    Chain(compileFn.map { r1 =>
      r1.andThen(lambda.toRunnable)
    })

  def appendChatModel(model: ChatModel[IO])(ev: O <:< Conversation): Chain[I, Completion] =
    Chain(compileFn.map { r1 =>
      val chatModelRunnable = Runnable.fromInvoke[Conversation, Completion] { (conv: Conversation) =>
        model.generate(conv)
      }
      r1.andThen(chatModelRunnable.contramap(ev))
    })

  def appendBranch[O2](branch: ChainBranch[O, O2]): Chain[I, O2] =
    Chain(compileFn.map { r1 =>
      r1.andThen(branch.toRunnable)
    })

  def appendPassthrough: Chain[I, O] = this

  def compile: IO[Runnable[I, O]] = compileFn

  def toGraph(using runtime: cats.effect.unsafe.IORuntime, classTag: ClassTag[O]): IO[Graph[I, O]] = IO.delay {
    val identityRunnable = compileFn.unsafeRunSync()
    val g1 = Graph[I, O]
      .addLambdaNode("chain", Lambda((input: I) => identityRunnable.invoke(input)))
      .toOption.get
    val g2 = g1.graph.addEndNode(Graph.NodeRef[I, O](NodeKey.unsafeApply("chain"))).toOption.get
      .setEntry(Graph.NodeRef[I, I](NodeKey.unsafeApply("chain"))).toOption.get
    g2
  }

object Chain:
  def apply[I]: Chain[I, I] =
    Chain(IO.delay(Runnable.identity[I]))

  def fromRunnable[I, O](runnable: Runnable[I, O]): Chain[I, O] =
    Chain(IO.pure(runnable))

sealed trait ChainStep[I, O]
object ChainStep:
  case class LambdaStep[I, O](lambda: Lambda[I, O]) extends ChainStep[I, O]
  case class ChatModelStep(model: ChatModel[IO]) extends ChainStep[Any, Completion]
  case class BranchStep[I, O](branch: ChainBranch[I, O]) extends ChainStep[I, O]
