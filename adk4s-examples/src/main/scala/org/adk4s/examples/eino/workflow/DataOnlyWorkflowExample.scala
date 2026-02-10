package org.adk4s.examples.eino.workflow

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.runnable.Runnable
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: compose/workflow/3_data_only
 *
 * Demonstrates data-only dependencies in a workflow. In Eino, WithNoDirectDependency()
 * declares that a node receives data from another node without an execution dependency.
 * In adk4s, we model this with parallel execution and explicit field extraction.
 *
 * The workflow:
 *   - Input: { add: [2, 5], multiply: 3 }
 *   - adder: sums the "add" list → 7
 *   - multiplier: multiplies adder result (7) by "multiply" field (3) → 21
 *   - Output: 21
 *
 * Eino uses FromField("Add"), ToField("A"), MapFields("Multiply","B"), and
 * WithNoDirectDependency() to wire fields. In Scala, we use typed lambdas.
 */
object DataOnlyWorkflowExample extends IOApp.Simple:

  // --- Domain types ---

  final case class CalculatorInput(
    add: List[Int],
    multiply: Int
  )

  final case class MulInput(
    a: Int,
    b: Int
  )

  // --- Nodes ---

  // Eino: adder := func(ctx, in []int) (int, error) { sum }
  private val adder: Runnable[List[Int], Int] =
    Runnable.fromInvoke[List[Int], Int] { (nums: List[Int]) =>
      IO.pure(nums.sum)
    }

  // Eino: multiplier := func(ctx, m mul) (int, error) { m.A * m.B }
  private val multiplier: Runnable[MulInput, Int] =
    Runnable.fromInvoke[MulInput, Int] { (m: MulInput) =>
      IO.pure(m.a * m.b)
    }

  // --- Main ---

  def run: IO[Unit] =
    val input: CalculatorInput = CalculatorInput(
      add = List(2, 5),
      multiply = 3
    )
    for
      _ <- ExampleUtils.printSection("DataOnly Workflow Example (Eino: workflow/3_data_only)")

      _ <- ExampleUtils.printSubSection("Input")
      _ <- IO.println(s"   add:      ${input.add}")
      _ <- IO.println(s"   multiply: ${input.multiply}")

      // Step 1: adder — Eino: FromField("Add") extracts the add list
      addResult <- adder.invoke(input.add)
      _ <- ExampleUtils.printSubSection("Step 1: Adder")
      _ <- IO.println(s"   sum(${input.add}) = $addResult")

      // Step 2: multiplier — Eino: ToField("A") maps adder output to A,
      //   MapFields("Multiply","B") + WithNoDirectDependency() maps input.multiply to B
      // In Scala, we explicitly construct MulInput from both sources
      mulInput = MulInput(a = addResult, b = input.multiply)
      mulResult <- multiplier.invoke(mulInput)
      _ <- ExampleUtils.printSubSection("Step 2: Multiplier")
      _ <- IO.println(s"   $addResult * ${input.multiply} = $mulResult")

      _ <- ExampleUtils.printSubSection("Final Result")
      _ <- IO.println(s"   $mulResult")

      _ <- IO.println("\n=== DataOnly Workflow Example Completed ===")
    yield ()
