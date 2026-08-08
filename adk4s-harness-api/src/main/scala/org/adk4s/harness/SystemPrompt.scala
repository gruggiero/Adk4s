package org.adk4s.harness

/** Composed system prompt: an optional base string plus named sections.
  *
  * The loop builds this per request as `SystemPrompt(basePrompt,
  * stack.allSections(state))`. The `render` method produces the final
  * prompt string by concatenating the base and sections in order.
  *
  * spec: harness-state — Requirement: SystemPrompt composes base prompt with named sections in stack order
  */
final case class SystemPrompt(base: Option[String], sections: List[PromptSection]):
  def render: String =
    val baseStr: String = base.getOrElse("")
    if sections.isEmpty then baseStr
    else baseStr + "\n\n" + sections.map(s => s"## ${s.name}\n${s.body}").mkString("\n\n")
