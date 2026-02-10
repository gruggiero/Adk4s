package org.adk4s.examples.eino.agent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: agent/plan_execute
 *
 * Demonstrates a Plan-and-Execute agent pattern:
 *   1. Planner agent decomposes a complex task into steps
 *   2. Executor agent executes each step sequentially
 *   3. Results from each step feed into the next
 *
 * This pattern is useful for complex multi-step tasks where
 * the LLM needs to reason about ordering and dependencies.
 */
object PlanExecuteExample extends IOApp.Simple:

  final case class PlanStep(index: Int, description: String, result: Option[String])

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Plan-Execute Example (Eino: agent/plan_execute)")
      chatModel <- ExampleUtils.createChatModel

      task = "Write a haiku about Scala programming, then translate it to Japanese, then explain the translation."

      // Step 1: Plan
      _ <- ExampleUtils.printSubSection("1. Planning Phase")
      _ <- IO.println(s"   Task: $task")
      plan <- createPlan(chatModel, task)
      _ <- plan.zipWithIndex.foldLeft(IO.unit) { case (acc, (step: PlanStep, _: Int)) =>
        acc *> IO.println(s"   Step ${step.index}: ${step.description}")
      }

      // Step 2: Execute each step
      _ <- ExampleUtils.printSubSection("2. Execution Phase")
      finalResults <- executePlan(chatModel, plan)
      _ <- finalResults.foldLeft(IO.unit) { case (acc, step: PlanStep) =>
        acc *>
          IO.println(s"\n   Step ${step.index}: ${step.description}") *>
          IO.println(s"   Result: ${step.result.getOrElse("(no result)").take(120)}")
      }

      // Step 3: Summary
      _ <- ExampleUtils.printSubSection("3. Summary")
      _ <- IO.println(s"   Completed ${finalResults.size} steps successfully.")

      _ <- IO.println("\nPlan-execute example completed.")
    yield ()

  private def createPlan(chatModel: ChatModel[IO], task: String): IO[List[PlanStep]] =
    val plannerConv: Conversation = Conversation(Seq(
      SystemMessage(
        """You are a task planner. Break down the given task into 3-5 simple sequential steps.
          |Output each step on a separate line, numbered like:
          |1. First step
          |2. Second step
          |3. Third step""".stripMargin
      ),
      UserMessage(s"Plan this task: $task")
    ))
    chatModel.generate(plannerConv).map { (completion: Completion) =>
      val lines: List[String] = completion.content.split("\n").toList
        .map(_.trim)
        .filter(_.nonEmpty)
        .filter((line: String) => line.headOption.exists(_.isDigit))
      if lines.isEmpty then
        // Fallback: create a simple 3-step plan
        List(
          PlanStep(1, "Write a haiku about Scala programming", None),
          PlanStep(2, "Translate the haiku to Japanese", None),
          PlanStep(3, "Explain the translation", None)
        )
      else
        lines.zipWithIndex.map { case (line: String, idx: Int) =>
          val description: String = line.replaceFirst("^\\d+\\.?\\s*", "")
          PlanStep(idx + 1, description, None)
        }
    }

  private def executePlan(
    chatModel: ChatModel[IO],
    plan: List[PlanStep]
  ): IO[List[PlanStep]] =
    plan.foldLeft(IO.pure(List.empty[PlanStep])) { case (accIO, step) =>
      accIO.flatMap { (completed: List[PlanStep]) =>
        val context: String = completed
          .flatMap(_.result)
          .zipWithIndex
          .map { case (r: String, i: Int) => s"Step ${i + 1} result: $r" }
          .mkString("\n")

        val executorConv: Conversation = Conversation(Seq(
          SystemMessage("You are a task executor. Complete the given step concisely. Use any provided context from previous steps."),
          UserMessage(
            s"${if context.nonEmpty then s"Previous results:\n$context\n\n" else ""}Execute step ${step.index}: ${step.description}"
          )
        ))

        chatModel.generate(executorConv).map { (completion: Completion) =>
          completed :+ step.copy(result = Some(completion.content))
        }
      }
    }
