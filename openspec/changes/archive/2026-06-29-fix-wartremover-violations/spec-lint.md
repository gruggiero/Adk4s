# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism

## Results

### Spec: specs/wartremover-option-partial/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 2 requirements + 5 scenarios have concrete clauses. |
| 2 | Then observable | ✅ | Thens are: WartRemover error count (0), `ujson.Value` identity, `Left(CheckpointNotFoundError)`, munit `FailException`, `Map#get` not flagged. |
| 3 | Scenarios testable | ✅ | `sbt compile`, munit assertions, existing `CheckpointStoreTest`. |
| 4 | Error paths specified | ✅ | Missing checkpoint → `Left(CheckpointNotFoundError)`; `None` → munit `fail`. |
| 5 | New concepts declared | ✅ | "(none)" — pure refactor. |
| 6 | Reused concepts resolved | ✅ | `NodeKey`, `AdkError`, `CheckpointStore`, `StateRef`, Tool tier — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties (Ring 9 not in stack). |
| 9 | No vague words | ✅ | No flagged words. |
| 10 | Unreachable claims | ✅ | None made. |
| 11 | Enum extension behavior | N/A | No enum/GADT extension. |
| 12 | Proof obligations complete | ✅ | 6 obligations, all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-iterable-ops/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 2 requirements + 5 scenarios. |
| 2 | Then observable | ✅ | Thens: WartRemover error count, `lastOption.getOrElse(default)`, `dropRight(1)` equality, defined fallback on empty. |
| 3 | Scenarios testable | ✅ | `sbt compile`, Hedgehog properties. |
| 4 | Error paths specified | ✅ | Empty collection → defined fallback (no throw). |
| 5 | New concepts declared | ✅ | "(none)". |
| 6 | Reused concepts resolved | ✅ | `RunPath`, `FieldPath`, `AdkError` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | None. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | N/A | No extension. |
| 12 | Proof obligations complete | ✅ | 5 obligations, all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-var/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 3 requirements + 6 scenarios. |
| 2 | Then observable | ✅ | Thens: byte-identical output, no `var` remains, `Ref[IO, Int]` assertions pass, `sbt compile` lint-clean. |
| 3 | Scenarios testable | ✅ | Hedgehog reference oracle, existing `RetrieverSpec`/`ComponentMockLLMClient`. |
| 4 | Error paths specified | ✅ | Empty string → `""` (no exception). |
| 5 | New concepts declared | ✅ | "(none)" — internal helpers only. |
| 6 | Reused concepts resolved | ✅ | `SchemaAlignedParser`, `ToolMiddleware`, `AdkError` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` with quote/escape coverage + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | None. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | N/A | No extension. |
| 12 | Proof obligations complete | ✅ | 7 obligations, all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-throw/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 3 requirements + 7 scenarios. |
| 2 | Then observable | ✅ | Thens: no `throw` keyword, `AdkError` variant surfaced, `RunResult.Interrupted`, `sbt compile` lint-clean. |
| 3 | Scenarios testable | ✅ | Hedgehog, existing `ToolsNodeInterruptTest`/`AgentRunnerTest`/`ToolsNodeTest`. |
| 4 | Error paths specified | ✅ | Missing checkpoint → `Left(NodeKeyError)`; tool failure → `ToolExecutionError`; interrupt → `RunResult.Interrupted`. |
| 5 | New concepts declared | ✅ | `NodeKeyError` declared in Concepts Introduced. |
| 6 | Reused concepts resolved | ✅ | `AdkError`, `NodeKey`, `AgentInterruptedException`, `WIOGraphError`, `GraphExecutor` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` + explicit edge cases + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | Fixed: `valid` label → `accepted` with concrete definition. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | ✅ | "Enum/GADT Extension Behavior" section added: `NodeKeyError` extends sealed `AdkError`; `Show[AdkError]` is variant-agnostic; exhaustive matches audited + compiler exhaustiveness obligation. |
| 12 | Proof obligations complete | ✅ | 8 obligations (incl. enum exhaustiveness), all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-asinstanceof/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 2 requirements + 6 scenarios. |
| 2 | Then observable | ✅ | Thens: `ObjectSchema` identity, no `asInstanceOf`, `WIO` behavior identity, `StringSchema` fallback, `sbt compile` lint-clean. |
| 3 | Scenarios testable | ✅ | Hedgehog reference oracle, existing `WIOGraphTest`/`WIORunnableNodeTest`/`WIONodeModifierTest`. |
| 4 | Error paths specified | ✅ | Unknown schema type → `StringSchema` (default); malformed JSON → `Left(message)`. |
| 5 | New concepts declared | ✅ | `ToolDispatchResult` (tentative) declared with waiver note. |
| 6 | Reused concepts resolved | ✅ | Tool tier, `ToolWrapper`, `SafeToolExecutable`, `ToolSchema`, `WIONode`, `WIONodeModifier`, `AdkError` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` over `ujson.Value` / `Resume` + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | Fixed: `correct` → explicit variant mapping; `valid`/`invalid` → `wellFormed`/`malformed` with concrete definitions. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | ✅ | `WIONode` is sealed; spec pattern-matches over existing variants (no new variant added). `ToolDispatchResult` (if introduced) is a new sealed trait, not an extension of an existing one — stated as tentative/droppable. |
| 12 | Proof obligations complete | ✅ | 8 obligations (incl. adversarial review), all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-default-arguments/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 2 requirements + 4 scenarios. |
| 2 | Then observable | ✅ | Thens: `Foo("x", 0)` value, `run(in)` uses `maxSteps=10`, no `$default$` accessor, `sbt compile` lint-clean. |
| 3 | Scenarios testable | ✅ | Hedgehog, `sbt adk4s-examples/compile`, reflective scan. |
| 4 | Error paths specified | ✅ | N/A — no failure path (pure API-surface refactor); the "error" is a compile failure if defaults reintroduced (covered by compile-negative). |
| 5 | New concepts declared | ✅ | "(none)" — companion factories are not new domain concepts. |
| 6 | Reused concepts resolved | ✅ | `AdkToolInfo`, `ToolWrapper`, `RunResult`, `Runnable` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | None. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | N/A | No extension. |
| 12 | Proof obligations complete | ✅ | 5 obligations, all with enforcement. |

**Verdict: PASS**

### Spec: specs/wartremover-any-string-plus-any/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | 2 requirements + 4 scenarios. |
| 2 | Then observable | ✅ | Thens: `message` string identity, no `Any`/`StringPlusAny` error, `sbt compile` lint-clean. |
| 3 | Scenarios testable | ✅ | Hedgehog reference oracle, `sbt compile`. |
| 4 | Error paths specified | ✅ | Edge case: pure-`String` interpolation not flagged (concretely defined). |
| 5 | New concepts declared | ✅ | "(none)". |
| 6 | Reused concepts resolved | ✅ | `AdkError`, `InterruptSignal`, `AgentEvent` — all in inventory. |
| 7 | Generator strategies | ✅ | Both properties declare constructive `Gen` + labels. |
| 8 | Temporal trigger/response | N/A | No temporal properties. |
| 9 | No vague words | ✅ | None. |
| 10 | Unreachable claims | ✅ | None. |
| 11 | Enum extension behavior | N/A | No extension. |
| 12 | Proof obligations complete | ✅ | 4 obligations, all with enforcement. |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/wartremover-option-partial/spec.md | PASS | 0 |
| specs/wartremover-iterable-ops/spec.md | PASS | 0 |
| specs/wartremover-var/spec.md | PASS | 0 |
| specs/wartremover-throw/spec.md | PASS | 0 |
| specs/wartremover-asinstanceof/spec.md | PASS | 0 |
| specs/wartremover-default-arguments/spec.md | PASS | 0 |
| specs/wartremover-any-string-plus-any/spec.md | PASS | 0 |

**Overall: 7/7 PASS.** All specs are unambiguous, testable with the detected
stack (munit + Hedgehog + sbt WartRemover), have complete proof obligations,
and declare their concepts. Implementation-order may be generated.

Fixes applied during lint:
- `wartremover-asinstanceof`: replaced vague "correct" with explicit
  variant mapping; replaced "valid/invalid JSON" with "well-formed/malformed"
  + concrete definitions.
- `wartremover-throw`: replaced vague `valid` label with `accepted`
  (non-empty, non-reserved); added "Enum/GADT Extension Behavior" section for
  `NodeKeyError extends AdkError` + compiler-exhaustiveness obligation.
