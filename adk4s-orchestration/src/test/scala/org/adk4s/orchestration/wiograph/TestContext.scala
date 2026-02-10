package org.adk4s.orchestration.wiograph

import workflows4s.wio.WorkflowContext

object TestContext extends WorkflowContext:
  sealed trait TestStateBase
  final case class TestState(value: Int) extends TestStateBase
  final case class StringState(text: String) extends TestStateBase

  sealed trait TestEvent
  final case class ValueAdded(delta: Int) extends TestEvent
  final case class SignalReceived(amount: Int) extends TestEvent
  final case class ForEachWrappedEvent(elem: String, inner: TestEvent) extends TestEvent
  final case class RunnableResult(value: Int) extends TestEvent
  final case class StringResult(text: String) extends TestEvent

  override type State = TestStateBase
  override type Event = TestEvent
