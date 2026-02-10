package org.adk4s.orchestration.state

import cats.effect.IO
import munit.CatsEffectSuite

class StateRefTest extends CatsEffectSuite:

  test("StateRef.of creates StateRef with initial state") {
    for
      stateRef <- StateRef.of(10)
      value <- stateRef.get
    yield assertEquals(value, 10)
  }

  test("StateRef.set updates state") {
    for
      stateRef <- StateRef.of(10)
      _ <- stateRef.set(20)
      value <- stateRef.get
    yield assertEquals(value, 20)
  }

  test("StateRef.update modifies state") {
    for
      stateRef <- StateRef.of(10)
      _ <- stateRef.update(_ + 5)
      value <- stateRef.get
    yield assertEquals(value, 15)
  }

  test("StateRef.modify updates state and returns result") {
    for
      stateRef <- StateRef.of(10)
      result <- stateRef.modify(s => (s + 5, s * 2))
      value <- stateRef.get
    yield
      assertEquals(result, 20)
      assertEquals(value, 15)
  }

  test("StateRef.getAndUpdate returns previous state") {
    for
      stateRef <- StateRef.of(10)
      previous <- stateRef.getAndUpdate(_ + 5)
      current <- stateRef.get
    yield
      assertEquals(previous, 10)
      assertEquals(current, 15)
  }

  test("StateRef.updateAndGet returns new state") {
    for
      stateRef <- StateRef.of(10)
      newValue <- stateRef.updateAndGet(_ + 5)
      current <- stateRef.get
    yield
      assertEquals(newValue, 15)
      assertEquals(current, 15)
  }

  test("StateRef.empty creates no-op StateRef") {
    val emptyRef = StateRef.empty(10)
    for
      value1 <- emptyRef.get
      _ <- emptyRef.set(20)
      value2 <- emptyRef.get
      _ <- emptyRef.update(_ + 5)
      value3 <- emptyRef.get
      previous <- emptyRef.getAndUpdate(_ + 5)
      newValue <- emptyRef.updateAndGet(_ + 5)
      finalValue <- emptyRef.get
    yield
      assertEquals(value1, 10)
      assertEquals(value2, 10)
      assertEquals(value3, 10)
      assertEquals(previous, 10)
      assertEquals(newValue, 15)
      assertEquals(finalValue, 10)
  }

  test("StateRef.fromRef wraps existing Ref") {
    for
      ref <- cats.effect.Ref.of[IO, Int](10)
      stateRef = StateRef.fromRef(ref)
      value <- stateRef.get
      _ <- stateRef.set(20)
      valueAfterSet <- stateRef.get
    yield
      assertEquals(value, 10)
      assertEquals(valueAfterSet, 20)
  }
