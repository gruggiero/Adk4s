package org.adk4s.orchestration.interrupt

import cats.effect.IO
import munit.CatsEffectSuite

class InterruptibleNodeTest extends CatsEffectSuite:

  private val alwaysInterrupt: Int => Boolean = (_: Int) => true
  private val neverInterrupt: Int => Boolean = (_: Int) => false
  private val interruptOnNegative: Int => Boolean = (n: Int) => n < 0

  private val doubleAction: Int => IO[Int] = (n: Int) => IO.pure(n * 2)

  private val makeInterruptInfo: Int => IO[InterruptInfo] = (n: Int) =>
    IO.pure(InterruptInfo(
      checkpointId = s"cp-$n",
      description = s"Interrupted on input $n",
      serializedState = n.toString.getBytes
    ))

  test("invoke completes when predicate is false") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](neverInterrupt, doubleAction, makeInterruptInfo, store)
      result <- node.invoke(5)
    yield result match
      case InterruptResult.Completed(output) => assertEquals(output, 10)
      case other => fail(s"Expected Completed, got $other")
  }

  test("invoke interrupts when predicate is true") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](alwaysInterrupt, doubleAction, makeInterruptInfo, store)
      result <- node.invoke(5)
      storedKeys <- store.keys
    yield {
      result match
        case InterruptResult.Interrupted(info) =>
          assertEquals(info.checkpointId, "cp-5")
          assertEquals(info.description, "Interrupted on input 5")
        case other => fail(s"Expected Interrupted, got $other")
      assertEquals(storedKeys, List("cp-5"))
    }
  }

  test("invoke selectively interrupts based on predicate") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](interruptOnNegative, doubleAction, makeInterruptInfo, store)
      result1 <- node.invoke(5)
      result2 <- node.invoke(-3)
    yield {
      result1 match
        case InterruptResult.Completed(output) => assertEquals(output, 10)
        case other => fail(s"Expected Completed for 5, got $other")
      result2 match
        case InterruptResult.Interrupted(info) => assertEquals(info.checkpointId, "cp--3")
        case other => fail(s"Expected Interrupted for -3, got $other")
    }
  }

  test("resume with approval executes the action") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](alwaysInterrupt, doubleAction, makeInterruptInfo, store)
      _ <- node.invoke(7) // triggers interrupt, stores checkpoint
      result <- node.resume("cp-7", approved = true, input = 7)
      storedKeys <- store.keys
    yield {
      result match
        case InterruptResult.Completed(output) => assertEquals(output, 14)
        case other => fail(s"Expected Completed after resume, got $other")
      assertEquals(storedKeys, List.empty[String]) // checkpoint cleaned up
    }
  }

  test("resume with rejection returns Rejected") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](alwaysInterrupt, doubleAction, makeInterruptInfo, store)
      _ <- node.invoke(7)
      result <- node.resume("cp-7", approved = false, input = 7)
      storedKeys <- store.keys
    yield {
      result match
        case InterruptResult.Rejected(cpId) => assertEquals(cpId, "cp-7")
        case other => fail(s"Expected Rejected, got $other")
      assertEquals(storedKeys, List.empty[String])
    }
  }

  test("resume with unknown checkpoint raises error") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](alwaysInterrupt, doubleAction, makeInterruptInfo, store)
      result <- node.resume("unknown", approved = true, input = 1).attempt
    yield assert(result.isLeft)
  }

  test("toRunnable wraps invoke") {
    for
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[Int, Int](neverInterrupt, doubleAction, makeInterruptInfo, store)
      runnable = node.toRunnable
      result <- runnable.invoke(5)
    yield result match
      case InterruptResult.Completed(output) => assertEquals(output, 10)
      case other => fail(s"Expected Completed, got $other")
  }
