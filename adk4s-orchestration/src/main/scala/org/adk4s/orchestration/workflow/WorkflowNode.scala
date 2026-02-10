package org.adk4s.orchestration.workflow

import org.adk4s.core.runnable.Lambda
import org.adk4s.core.component.ChatModel
import cats.effect.IO
import org.llm4s.llmconnect.model.{Conversation, Completion}

sealed trait WorkflowNode[I, O]
object WorkflowNode:
  case class Lambda[I, O](lambda: org.adk4s.core.runnable.Lambda[I, O]) extends WorkflowNode[I, O]
  case class ChatModel(model: org.adk4s.core.component.ChatModel[IO]) extends WorkflowNode[Conversation, Completion]
