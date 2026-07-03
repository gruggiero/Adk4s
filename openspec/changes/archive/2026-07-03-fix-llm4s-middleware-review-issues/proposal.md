# Proposal: Fix llm4s-middleware-and-dedup review issues

## Why

The code review in `docs/code-review-llm4s-middleware-and-dedup.md` found 7 issues
in the shipped `llm4s-middleware-and-dedup` change. The escape analysis
(`docs/escape-analysis-code-review-llm4s-middleware-and-dedup.md`) confirmed all 7
are real and attributed each to a workflow gap. This change fixes the 7 issues in
code and tightens the affected test oracles so the same classes cannot regress.
It is the code counterpart to the workflow-hardening change
`harden-verified-scala3-workflow`.

## What Changes

| # | Fix | File(s) |
|---|-----|---------|
| 1 | `fromClientWithRetry`: `RetryTrigger.LLMError` no longer enables parse-failure retry — parse-retry is now `Option`-valued and `LLMError` maps to `None` | `structured-llm/.../core/StructuredLLM.scala` |
| 2 | `MessageStream.concatenate(Tool)` keeps the tool role instead of silently mislabelling it `User` — exhaustive match over `MessageRole` | `adk4s-core/.../streaming/MessageStream.scala` |
| 3 | `PromptSyntax.messageForRole(Tool)` keeps the tool role instead of returning `UserMessage` | `structured-llm/.../template/PromptSyntax.scala` |
| 4 | `ChatTemplate.substituteMessageContent` now substitutes `ToolMessage` content (was a `case other => other` no-op); exhaustive match | `adk4s-core/.../component/ChatTemplate.scala` |
| 5 | Removed dead code orphaned by the `ToolWrapper` refactor: `SafeToolExecutable`, `ToolFunctionAdapter`, `StructuredToolFunction.toSafeExecutable` | `adk4s-core/.../tools/ToolsNodeConfig.scala`, `adk4s-core/.../tools/StructuredToolFunction.scala` |
| 6 | `StructuredToolFunction.toToolFunction` derives its LLM-facing schema from `inputSchema.jsonSchema` (parameters + types + required) instead of an empty schema | `adk4s-core/.../tools/StructuredToolFunction.scala` |
| 7 | `ClientStrategy.executeFallback` names fallback clients by index (`client-<i>`) instead of `"unknown"` when `clientNames` is shorter than `clients` (pre-existing) | `structured-llm/.../core/ClientStrategy.scala` |

## Approach

- **#1**: change the `parseTrigger` mapping from `ParseRetryTrigger` to
  `Option[ParseRetryTrigger]`; `LLMError → None`, the others wrap in `Some(...)`.
- **#2/#3/#4**: make each `Message`/`MessageRole` match exhaustive over the 4 llm4s
  variants; `Tool` is handled explicitly (preserves the role; `toolCallId` empty
  where the API has none, or preserved + content substituted where it does).
- **#5**: delete the orphaned adapter layer; update the two tests/type-contract
  comments that referenced it.
- **#6**: add `propertiesFromJsonSchema` mapping the derived JSON schema's
  `properties`/`required` to llm4s `PropertyDefinition`s (string/integer/number/
  boolean; fallback string with validation still in the handler).
- **#7**: index-based naming via `errors.size` instead of `headOption`/`drop`.

## Correctness Risk Level

**Medium** — fixes touch the LLM call/retry path, tool registry construction, and
message assembly; but each fix is local and covered by a new or tightened test.

## Verification Strategy

- [x] Ring 0 (compile) — `structured-llm/compile`, `adk4s-core/compile` clean.
- [x] Ring 1 (lint) — WartRemover (compiler plugin) clean; scalafix fetch failed
      (offline infra), but WartRemover is the dangerous-pattern gate and passed.
- [ ] Ring 2 (architecture) — N/A (no new layering); the new exhaustive-match rule
      from the hardening change is satisfied by construction (no catch-all remains
      in the touched domain-conversion matches).
- [x] Ring 3 (tests) — full `structured-llm/test` + `adk4s-core/test`: **480 passed,
      0 failed** (95 structured-llm + 385 adk4s-core). New/tightened tests: per-variant
      `fromClientWithRetry` LLMError (#1), schema-parameters exposure (#6), faithful
      `HandlerError` oracle (#6 error-flattening), Tool-role concatenate (#2),
      ToolMessage substitution (#4), index-based fallback naming (#7).
- [ ] Ring 4 (wire) — N/A (no serialization shape change).
- [ ] Ring 5 (mutation) — not run (offline).
- [x] Ring 8 (adversarial) — the external review IS the adversarial input; every
      finding is addressed and has a regression test.

## Typed Contract Decision

**Minimal** — no new public types; the changes are signature-preserving except the
removed dead code (which is internal). The `Option[ParseRetryTrigger]` mapping is
internal to a deprecated factory.

## New Concepts to Introduce

None.
