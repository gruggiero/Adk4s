package org.adk4s.orchestration.state

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.runnable.Runnable

class StatefulNodeTest extends CatsEffectSuite:

  test("StatefulNode.wrap applies pre-handler before invoke") {
    case class State(calls: Int = 0)

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      preHandler: PreHandler[Int, State] = (i, s) => s.update(s => s.copy(calls = s.calls + 1)).map(_ => i + 1)
      node = StatefulNode.withPre(runnable, stateRef, preHandler)
      result <- node.invoke(5)
      state <- stateRef.get
    yield
      assertEquals(result, 12)
      assertEquals(state.calls, 1)
  }

  test("StatefulNode.wrap applies post-handler after invoke") {
    case class State(results: List[Int] = List.empty)

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      postHandler: PostHandler[Int, State] = (o, s) => s.update(s => s.copy(results = s.results :+ o)).map(_ => o + 5)
      node = StatefulNode.withPost(runnable, stateRef, postHandler)
      result <- node.invoke(5)
      state <- stateRef.get
    yield
      assertEquals(result, 15)
      assertEquals(state.results, List(10))
  }

  test("StatefulNode.wrap applies both pre and post handlers") {
    case class State(
      inputs: List[Int] = List.empty,
      outputs: List[Int] = List.empty
    )

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      preHandler: PreHandler[Int, State] = (i, s) => s.update(s => s.copy(inputs = s.inputs :+ i)).map(_ => i + 1)
      postHandler: PostHandler[Int, State] = (o, s) => s.update(s => s.copy(outputs = s.outputs :+ o)).map(_ => o + 5)
      node = StatefulNode.wrap(runnable, stateRef, StatefulNodeConfig(Some(preHandler), Some(postHandler)))
      result <- node.invoke(5)
      state <- stateRef.get
    yield
      assertEquals(result, 17)
      assertEquals(state.inputs, List(5))
      assertEquals(state.outputs, List(12))
  }

  test("StatefulNode.stream applies streamPostHandler") {
    case class State(count: Int = 0)

    val runnable = Runnable.fromInvoke[Int, Stream[IO, Int]](i => IO.pure(Stream.emits(List(i, i + 1, i + 2)).covary[IO]))

    for
      stateRef <- StateRef.of(State())
      streamPostHandler: StreamPostHandler[Stream[IO, Int], State] = (s, _) => s.evalMap(o => stateRef.update(st => st.copy(count = st.count + 1)).as(o))
      node = StatefulNode.wrap(runnable, stateRef, StatefulNodeConfig(streamPostHandler = Some(streamPostHandler)))
      result <- node.stream(1).compile.lastOrError.flatMap(_.compile.toList)
      state <- stateRef.get
    yield
      assertEquals(result, List(1, 2, 3))
      assertEquals(state.count, 1)
  }

  test("StatefulNode.collect applies streamPreHandler") {
    case class State(count: Int = 0)

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      streamPreHandler: StreamPreHandler[Int, State] = (s, _) => s.evalMap(i => stateRef.update(st => st.copy(count = st.count + 1)).as(i))
      node = StatefulNode.wrap(runnable, stateRef, StatefulNodeConfig(streamPreHandler = Some(streamPreHandler)))
      result <- node.collect(Stream.emits(List(1, 2, 3)))
      state <- stateRef.get
    yield
      assertEquals(result, 6)
      assertEquals(state.count, 3)
  }

  test("StatefulNode.transform applies both stream handlers") {
    case class State(
      inputCount: Int = 0,
      outputCount: Int = 0
    )

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      streamPreHandler: StreamPreHandler[Int, State] = (s, _) => s.evalMap(i => stateRef.update(st => st.copy(inputCount = st.inputCount + 1)).as(i))
      streamPostHandler: StreamPostHandler[Int, State] = (s, _) => s.evalMap(o => stateRef.update(st => st.copy(outputCount = st.outputCount + 1)).as(o))
      node = StatefulNode.wrap(runnable, stateRef, StatefulNodeConfig(streamPreHandler = Some(streamPreHandler), streamPostHandler = Some(streamPostHandler)))
      result <- node.transform(Stream.emits(List(1, 2, 3))).compile.toList
      state <- stateRef.get
    yield
      assertEquals(result, List(2, 4, 6))
      assertEquals(state.inputCount, 3)
      assertEquals(state.outputCount, 3)
  }

  test("StatefulNode.collect applies postHandler") {
    case class State(result: Int = 0)

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      postHandler: PostHandler[Int, State] = (o, s) => s.update(st => st.copy(result = o)).map(_ => o + 5)
      node = StatefulNode.withPost(runnable, stateRef, postHandler)
      result <- node.collect(Stream.emits(List(1, 2, 3)))
      state <- stateRef.get
    yield
      assertEquals(result, 11)
      assertEquals(state.result, 6)
  }

  test("StatefulNode.transform applies streamPreHandler") {
    case class State(count: Int = 0)

    val runnable = Runnable.fromInvoke[Int, Int](i => IO.pure(i * 2))

    for
      stateRef <- StateRef.of(State())
      streamPreHandler: StreamPreHandler[Int, State] = (s, _) => s.evalMap(i => stateRef.update(st => st.copy(count = st.count + 1)).as(i))
      node = StatefulNode.wrap(runnable, stateRef, StatefulNodeConfig(streamPreHandler = Some(streamPreHandler)))
      result <- node.transform(Stream.emits(List(1, 2, 3))).compile.toList
      state <- stateRef.get
    yield
      assertEquals(result, List(2, 4, 6))
      assertEquals(state.count, 3)
  }
