package org.adk4s.harness

/**
 * A named, ordered section of the system prompt.
 *
 * Middlewares contribute sections via `promptSections(state)`; the loop
 * folds them in stack order after the base prompt to produce a
 * `SystemPrompt`.
 *
 * spec: harness-state — Requirement: SystemPrompt composes base prompt with named sections in stack order
 */
final case class PromptSection(name: String, body: String)
