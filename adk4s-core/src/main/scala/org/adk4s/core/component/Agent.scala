package org.adk4s.core.component

import cats.effect.IO
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Message

/** Minimal agent interface for use with AgentTool — implemented by ReactAgent in orchestration. */
trait Agent:
  def name: String
  def description: String
  def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage]
