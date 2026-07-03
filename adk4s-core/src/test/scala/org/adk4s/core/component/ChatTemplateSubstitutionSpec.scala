package org.adk4s.core.component

import cats.effect.IO
import munit.CatsEffectSuite
import org.llm4s.llmconnect.model.ToolMessage

class ChatTemplateSubstitutionSpec extends CatsEffectSuite:
  test("ChatTemplate substitutes placeholders in a ToolMessage and preserves toolCallId (fix #3)") {
    // spec: fix-llm4s-middleware-review-issues — ToolMessage substitution no longer skipped.
    // Previously `case other => other` no-op'd substitution for ToolMessage (and any
    // non-standard message), leaving {variable} placeholders unresolved in tool results
    // sent to the LLM. The match is now exhaustive and substitutes ToolMessage.content
    // while preserving toolCallId.
    val template: ChatTemplate[IO, Map[String, String]] =
      ChatTemplate.simple[IO](List(ToolMessage("{result}", toolCallId = "call-1")))
    for
      prompt <- template.format(Map("result" -> "ok"))
      toolMessages = prompt.conversation.messages.collect { case tm: ToolMessage => tm }
    yield
      assertEquals(toolMessages.size, 1)
      val tm: ToolMessage = toolMessages.headOption.getOrElse(fail("expected ToolMessage"))
      assertEquals(tm.content, "ok")
      assertEquals(tm.toolCallId, "call-1")
  }
