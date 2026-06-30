package org.adk4s.orchestration.state

import cats.effect.IO
import munit.CatsEffectSuite

class EventSourcedStateTest extends CatsEffectSuite:

  test("AgentStateContext initial state is correct") {
    val state = AgentStateContext.initialState
    assertEquals(state.messages, List.empty)
    assertEquals(state.stepCount, 0)
    assertEquals(state.toolCallCount, 0)
    assertEquals(state.metadata, Map.empty)
  }

  test("MessageAdded event appends message to state") {
    val message = org.adk4s.structured.core.Message.user("test")
    val event = AgentStateContext.MessageAdded(message)

    val initialState = AgentStateContext.AgentState()
    val newState = AgentStateContext.applyEvent(initialState, event)

    assertEquals(newState.messages.length, 1)
    assertEquals(newState.messages.headOption.getOrElse(fail("expected non-empty list")), message)
    assertEquals(newState.stepCount, 0)
    assertEquals(newState.toolCallCount, 0)
  }

  test("StepCompleted event updates stepCount") {
    val event = AgentStateContext.StepCompleted(5)

    val initialState = AgentStateContext.AgentState()
    val newState = AgentStateContext.applyEvent(initialState, event)

    assertEquals(newState.stepCount, 5)
    assertEquals(newState.messages, List.empty)
    assertEquals(newState.toolCallCount, 0)
  }

  test("ToolCalled event increments toolCallCount") {
    val event = AgentStateContext.ToolCalled("test_tool")

    val initialState = AgentStateContext.AgentState(toolCallCount = 3)
    val newState = AgentStateContext.applyEvent(initialState, event)

    assertEquals(newState.toolCallCount, 4)
    assertEquals(newState.messages, List.empty)
    assertEquals(newState.stepCount, 0)
  }

  test("MetadataUpdated event adds to metadata") {
    val event = AgentStateContext.MetadataUpdated("key1", "value1")

    val initialState = AgentStateContext.AgentState()
    val newState = AgentStateContext.applyEvent(initialState, event)

    assertEquals(newState.metadata, Map("key1" -> "value1"))
    assertEquals(newState.messages, List.empty)
    assertEquals(newState.stepCount, 0)
  }

  test("Multiple events are applied correctly") {
    val msg1 = org.adk4s.structured.core.Message.user("hello")
    val msg2 = org.adk4s.structured.core.Message.assistant("hi")

    val events = List(
      AgentStateContext.MessageAdded(msg1),
      AgentStateContext.MessageAdded(msg2),
      AgentStateContext.StepCompleted(2),
      AgentStateContext.ToolCalled("tool1"),
      AgentStateContext.MetadataUpdated("key", "value")
    )

    val initialState = AgentStateContext.AgentState()
    val finalState = events.foldLeft(initialState)((s, e) => AgentStateContext.applyEvent(s, e))

    assertEquals(finalState.messages.length, 2)
    assertEquals(finalState.messages, List(msg1, msg2))
    assertEquals(finalState.stepCount, 2)
    assertEquals(finalState.toolCallCount, 1)
    assertEquals(finalState.metadata, Map("key" -> "value"))
  }
