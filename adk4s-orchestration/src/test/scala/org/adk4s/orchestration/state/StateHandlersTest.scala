package org.adk4s.orchestration.state

import cats.effect.IO
import munit.CatsEffectSuite

class StateHandlersTest extends CatsEffectSuite:

  test("identityPre returns input unchanged") {
    for
      stateRef <- StateRef.of(0)
      result <- StateHandlers.identityPre[Int, Int](42, stateRef)
    yield assertEquals(result, 42)
  }

  test("identityPost returns output unchanged") {
    for
      stateRef <- StateRef.of(0)
      result <- StateHandlers.identityPost[Int, Int](42, stateRef)
    yield assertEquals(result, 42)
  }

  test("accumulate adds input to state") {
    case class State(values: List[Int])

    for
      stateRef <- StateRef.of(State(List.empty))
      result1 <- StateHandlers.accumulate[Int, State](_.values, (s, v) => s.copy(values = v))(1, stateRef)
      result2 <- StateHandlers.accumulate[Int, State](_.values, (s, v) => s.copy(values = v))(2, stateRef)
      result3 <- StateHandlers.accumulate[Int, State](_.values, (s, v) => s.copy(values = v))(3, stateRef)
      state <- stateRef.get
    yield
      assertEquals(result1, 1)
      assertEquals(result2, 2)
      assertEquals(result3, 3)
      assertEquals(state.values, List(1, 2, 3))
  }

  test("fromState returns value from state") {
    case class State(value: Int)

    for
      stateRef <- StateRef.of(State(42))
      result <- StateHandlers.fromState[Int, State](_.value)(0, stateRef)
    yield assertEquals(result, 42)
  }

  test("storeOutput updates state with output") {
    case class State(values: List[Int])

    for
      stateRef <- StateRef.of(State(List.empty))
      result1 <- StateHandlers.storeOutput[Int, State]((s, v) => s.copy(values = s.values :+ v))(1, stateRef)
      result2 <- StateHandlers.storeOutput[Int, State]((s, v) => s.copy(values = s.values :+ v))(2, stateRef)
      state <- stateRef.get
    yield
      assertEquals(result1, 1)
      assertEquals(result2, 2)
      assertEquals(state.values, List(1, 2))
  }

  test("combinePre applies handlers in sequence") {
    case class State(value: Int)

    val add5: PreHandler[Int, State] = (i, s) => s.update(s => s.copy(value = s.value + 5)).map(_ => i + 10)
    val multiply2: PreHandler[Int, State] = (i, s) => s.update(s => s.copy(value = s.value * 2)).map(_ => i * 2)

    for
      stateRef <- StateRef.of(State(0))
      result <- StateHandlers.combinePre(add5, multiply2)(3, stateRef)
      state <- stateRef.get
    yield
      assertEquals(result, 26)
      assertEquals(state.value, 10)
  }

  test("combinePost applies handlers in sequence") {
    case class State(value: Int)

    val add5: PostHandler[Int, State] = (o, s) => s.update(s => s.copy(value = s.value + 5)).map(_ => o + 10)
    val multiply2: PostHandler[Int, State] = (o, s) => s.update(s => s.copy(value = s.value * 2)).map(_ => o * 2)

    for
      stateRef <- StateRef.of(State(0))
      result <- StateHandlers.combinePost(add5, multiply2)(3, stateRef)
      state <- stateRef.get
    yield
      assertEquals(result, 26)
      assertEquals(state.value, 10)
  }
