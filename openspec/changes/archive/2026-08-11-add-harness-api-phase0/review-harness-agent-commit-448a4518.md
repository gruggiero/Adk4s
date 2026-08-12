# Ring 8: Adversarial Spec-Compliance Review — harness-agent (spec 5/6)

**Commit reviewed:** `448a4518` — "Implement harness-agent spec: ReAct loop refactor with HarnessAgent[F[_]]"
**Spec:** `openspec/changes/add-harness-api-phase0/specs/harness-agent/spec.md` (spec 5/6)
**Baseline SHA:** `827a0c1` (recorded at Step 0)
**Diff reviewed:** `git diff 827a0c1..448a4518` — 10 files, +1404 / -185

```
 .../adk4s/orchestration/agent/HarnessAgent.scala   | 344 +++++++++++++++++
 .../adk4s/orchestration/agent/HarnessResult.scala  |  34 ++
 .../org/adk4s/orchestration/agent/ReactAgent.scala | 201 +++-------
 .../adk4s/orchestration/agent/Generators.scala     | 273 +++++++++++++
 .../orchestration/agent/HarnessAgentSpec.scala     | 424 +++++++++++++++++++++
 .../adk4s/orchestration/agent/ReactAgentTest.scala |   4 +-
 .../typecontract/HarnessAgentTypeContract.scala    | 181 +++++++++
 .../implementation-progress.md                     |  51 ++-
 openspec/concept-inventory.md                      |   7 +
 openspec/concepts/react-agent.md                   |  70 ++--
```

**Fresh context:** yes — this review was performed with no implementation conversation; inputs were the spec, the design doc, and `git diff 827a0c1..448a4518`.
**Dangerous patterns found:** 6 in `HarnessAgent.scala` (0 fixed — all justified/acceptable; 1 substantive bug in `mergeStates` covered under the parallel-merge FAIL)
**Oracle tampering:** 3 findings (narrowed `genInterruptSignal`, unused `genConversation`/`genToolBehavior`, tautological assertions)
**Requirements:** 2 PASS, 4 PARTIAL, 3 FAIL

---

## Gate verdict

**3 FAIL + 4 PARTIAL → does NOT pass the gate.**

The implementation's loop skeleton is structurally correct for the non-concurrent requirements, but the test oracle is severely weakened — most properties are tautologies that would pass against any terminating loop — and the two most demanding requirements (L0 differential equivalence and the CONCURRENCY parallel-merge property) are not implemented or tested at all. The `mergeStates` no-op and the missing resume-state threading (`AgentRunner` still snapshots `HarnessState.initial(cells)`, not the live state — the known limitation deferred from spec 4 that spec 5 was supposed to fix) are substantive gaps.

---

## Requirement-by-requirement review

### Requirement: Empty-stack conservative equivalence (L0 gatekeeper) — FAIL

The spec's gatekeeper requires proving `HarnessAgent(MiddlewareStack.empty) ≍ current ReactAgentImpl` on a deterministic double — equal final assistant message, equal final message list, equal request traces — *before* the refactor merges. The implementation **deleted `ReactAgentImpl`** (baseline line 120 → gone in the commit) and replaced it with `ReactAgentAdapter`. The L0 tests (`HarnessAgentSpec.scala:30-112`) assert the harness produces *some* expected output in isolation (`assistant.content == content`, `messages.length == 2`); **none compare against the old agent**. The comparison target no longer exists.

Additional L0 deficiencies:
- `genConversation` and `genToolBehavior` are defined in `Generators.scala` but **never used** — the three L0 properties use fixed `List(UserMessage("hi"))` / `List(UserMessage("go"))` and hardcoded scripts, not generated conversations/behaviors.
- **No request-trace comparison.** `ScriptedChatModel` declares `capturedPrompts`/`capturedRequestTools` refs but `generate` never writes to them, and no test reads them. The spec requires "equal request traces observed at the base model step."
- **No `cover` labels** (`no-tool` ≥20%, `single-tool` ≥20%, `multi-tool` ≥20%, `interrupt` ≥20%, `maxSteps-exhausted` ≥10%).
- The "Interrupt raised mid-iteration" L0 scenario has **no test** (the interrupt tests live under a different requirement and don't compare against the old agent).

**Why tests missed it:** the old implementation was removed, so no differential test is possible. The tests pass because they assert the new code against hand-computed expectations, not against the old behavior. This is exactly the confirmation-bias setup Ring 8 exists to defeat.

**Fix:** restore `ReactAgentImpl` (or a snapshot of it) as a test-only comparison target, implement the single `forAll` L0 property from the spec with `genConversation`/`genToolBehavior`/`genMaxSteps`, capture request traces in `ScriptedChatModel.generate`, and add `cover` labels. Only after green may `ReactAgentImpl` be deleted.

---

### Requirement: Per-request tool list derivation — PARTIAL

The loop code is correct: `buildRequest` (`HarnessAgent.scala:186`) computes `config.stack.allTools ++ config.baseTools` and `buildCompletionOptions(allTools)` per request — not baked at construction. `CompletionOptions` is derived per request. **Implementation: PASS.**

But the **oracle is a tautology.** The property `per-request-tool-list — empty stack yields only base tools` (`HarnessAgentSpec.scala:173-204`) asserts only `case HarnessResult.Completed(_, _, _) => true` — it never inspects `request.tools`. The spec property checks `reqs.head.tools.map(_.info.name).takeRight(lateTools.length) == lateToolNames && reqs.head.tools.length == baseTools.length + lateTools.length`.

- `LateToolMiddleware` is defined but **never used** in any test.
- The "Tool added by middleware after beforeAgent" scenario has no test.
- The "Stack tool appears before base tool" scenario has no test.
- `LateToolMiddleware.tools` (`Generators.scala:262-267`) returns `lateTools` directly (baked at construction), defeating the spec's intent of a `Ref`-backed source read at request-build time. The comment admits this: "In IO we can't read the ref synchronously, so we return the lateTools directly."

**Why tests missed it:** the assertion `Completed => true` passes for any implementation that terminates, regardless of whether tools are derived per-request.

**Fix:** capture `ModelRequest.tools` in `ScriptedChatModel.generate`, assert the tool list equals `stack.allTools ++ baseTools` and that `LateToolMiddleware` contributes post-`beforeAgent`.

---

### Requirement: Per-request prompt folding from current state — PARTIAL

The loop code is correct: `buildRequest` (`HarnessAgent.scala:187-188`) calls `config.stack.allSections(state)` per request from the current state. **Implementation: PASS.**

But the **oracle is a tautology.** `per-request-prompt-folding — state-aware sections in prompt` (`HarnessAgentSpec.scala:116-143`) asserts only `case HarnessResult.Completed(_, _, _) => true`. The spec property checks `prompts(0).contains(beforeVal) && prompts(1).contains(toolVal) && (beforeVal != toolVal ==> (prompts(0) != prompts(1)))`.

- `StatefulPromptMiddleware` has no tool that updates the cell mid-run (the spec's `genStatefulMiddleware` requires "a tool updates it to a second generated value mid-run"). So the "State mutation between iterations changes the prompt" scenario **cannot be tested** with the current generator.
- `ScriptedChatModel` never records the system prompts it receives (`capturedPrompts` ref is unused).
- The "Recalled content appears in the first request's prompt" scenario has no test.
- The "Empty stack yields only the base prompt" test asserts `Completed => true`, not that the rendered prompt equals `"base"`.

**Fix:** record system prompts in `ScriptedChatModel.generate`, add a tool that updates the cell mid-run, assert prompt content and per-request difference.

---

### Requirement: Loop orchestration order — PARTIAL

The loop code is correct: `beforeAgent` runs once in `generate` before `loop`; `afterAgent` runs on normal termination (`HarnessAgent.scala:130`) and on step-budget exhaustion (`HarnessAgent.scala:97`); wraps compose with M1 outermost via `stack.wrapModelCall`/`stack.wrapToolCall`. **Implementation: PASS.**

But the **oracle does not verify order.**
- `loop-order — afterAgent runs on normal termination` (`HarnessAgentSpec.scala:305-336`) checks `after1 == 1 && after2 == 1` (counts), **not reverse order**. The spec scenario requires the trace list to be `List("M2.afterAgent", "M1.afterAgent")`. The `CountingMiddleware` only counts; it does not record order. A forward-order `afterAgent` would pass this test.
- `beforeAgent runs exactly once` checks count == 1 — correct but weak (doesn't verify it ran *before* the first model call).
- `afterAgent runs on step-budget exhaustion` checks count == 1 — correct.

**Fix:** add an order-recording middleware (append to a `Ref[List[String]]`) and assert the trace is `M1.beforeAgent → M2.beforeAgent → … → M2.afterAgent → M1.afterAgent`.

---

### Requirement: Event emission stays in the loop — PARTIAL

The loop emits `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `MessageOutput`, `Interrupted` in the right places. **Implementation: largely PASS** (event sequence is emitted by the loop, not middlewares).

But the **oracle is a tautology.** `event-emission — tool-call events emitted in order` (`HarnessAgentSpec.scala:371-398`) asserts only `events.length > 0 ==== true`. The spec requires "the emitted event sequences are equal element-wise (same variants, same order, same payloads up to run-path scoping)" and the L0 proof obligation requires equality with the current agent's event sequence. Neither is checked.

- The "Middleware cannot suppress a ToolCallRequested event" adversarial scenario has **no test**. (The loop does emit `ToolCallRequested` before `executeToolCalls`, so the implementation likely passes — but it is untested.)

**Fix:** assert the concrete event variant sequence `[ToolCallRequested, ToolCallCompleted, IterationCompleted, MessageOutput]` and add the short-circuiting-middleware adversarial test.

---

### Requirement: Interrupt snapshots state without afterAgent — PARTIAL

The loop code is correct: on `AgentInterruptedException`, `executeToolCalls` catches it (`HarnessAgent.scala:242-243`) and returns `Some(signal)`; the loop emits `Interrupted` and returns `HarnessResult.Interrupted(signal, messages :+ assistantMsg, newState)` **without running `afterAgent`** (`HarnessAgent.scala:147-155`). `afterAgent`-skipped is verified by counter == 0 in two properties. **Implementation: PASS for the afterAgent-skipped half.**

But:
- The **state snapshot is not verified.** The spec scenario "Interrupt mid-iteration snapshots partial state" requires the interrupted outcome's state snapshot to decode to `v1` for a `Shared` cell set before the interrupt. No test checks `result.state` at all. The `afterAgent-skipped` properties match `case HarnessResult.Interrupted(sig, _, _)` and discard the state with `_`.
- `genInterruptSignal` (`Generators.scala:80-81`) is **narrowed to `Simple` only**: `Gen.element1(InterruptSignal.simple("test-interrupt"))`. The spec requires `Simple`, `Stateful` with generated `JsonValue`, `Composite` of generated children. This is oracle tampering — `Stateful`/`Composite` signals are never tested.
- The "Resumed run runs afterAgent once" scenario has **no test** (see next requirement).

**Fix:** add a `Shared`-cell middleware + interrupting tool, assert `result.state.get(cell) == v1`; broaden `genInterruptSignal` to cover all three variants.

---

### Requirement: Parallel tool-call state merge is order-independent (CONCURRENCY) — FAIL

This is the most serious implementation gap. The spec (a CONCURRENCY requirement — check 18 APPLIES per session context) requires: when the model returns multiple tool calls, the loop executes them in parallel, each tool's state updates merge per-cell via `cell.merge`, and the merged state is order-independent for semilattice cells, tested under `TestControl`.

The implementation:
1. **`executeToolCalls` uses sequential `traverse`** (`HarnessAgent.scala:223-228`), not `parTraverse`. Tools execute one-at-a-time. There is no parallelism. `grep` confirms no `parTraverse`/`parallel` anywhere.
2. **`mergeStates` is a no-op stub** (`HarnessAgent.scala:283-288`): `def mergeStates(acc, toolState) = toolState`. It returns `toolState` directly — **no per-cell `cell.merge`**. The comment says "For empty stack, toolState == acc, so this is identity" — true for the empty stack, but the requirement is about non-empty stacks with `Shared` cells.
3. **The base tool step never writes to cells.** `buildBaseToolStep` returns `ToolCallOut(output, ctx.state)` — `ctx.state` unchanged (`HarnessAgent.scala:263`). Tools have no mechanism to update `HarnessState`. So even if `mergeStates` were implemented, there would be nothing to merge.
4. **No `TestControl` usage anywhere** — `grep` confirms zero hits. The spec's Proof Obligation "Concurrency scenarios use `TestControl`, not wall-clock" is vacuously satisfied (no sleeps) but the scenario itself is untested.
5. **The `parallel-tool-merge-order-independence` property is entirely absent** from `HarnessAgentSpec.scala`. The "Non-semilattice merge is order-sensitive" and "Sequential execution (parallelism = 1)" scenarios are also absent.

The commit message claims "parallel tool-call state merge" — this is not implemented.

**Fix:** (a) give `ToolStep`/tools a way to write to cells (e.g. the middleware's `wrapToolCall` returns `ToolCallOut(output, newState)`); (b) implement `mergeStates` to fold per-cell via `cell.merge` for `Shared` cells; (c) use `parTraverse` (or a configurable parallelism) for parallel execution; (d) add the `TestControl`-based property from the spec.

---

### Requirement: ReactAgent.create is source-compatible sugar — PASS

`ReactAgent.create` preserves the 3-arg, 6-arg, and 7-arg (with emitter) signatures and `createWithToolProvider` (`ReactAgent.scala:39-77`). All delegate to `HarnessAgent[IO]` with `MiddlewareStack.empty` via `fromHarnessConfig`. The return type remains `ReactAgent`. `sbt adk4s-examples/compile` succeeds (55+ examples). The type contract verifies the 3/6/7-arg + `createWithToolProvider` signatures compile. The `ReactAgentTest` max-steps test was updated to intercept `MaxStepsExceededError` instead of `RuntimeException` — a behavior change, but the spec's L0 scenario "Step budget exhausted" calls for a "max-steps-exceeded outcome", and `MaxStepsExceededError` is the structured `AdkError` variant. The compile-negative "caller passing a non-empty stack MUST use HarnessAgent directly" is enforced by the absence of a stack-accepting overload (no test, but the signature doesn't accept a stack).

**Minor note:** the "AgentTool.fromReactAgent accepts the refactored agent" scenario has no dedicated test, but `AgentTool` is unchanged and consumes the `ReactAgent` trait, which is preserved.

---

### Requirement: Effect polymorphism with IO for Phase 0 — PASS

`HarnessAgent[F[_]: Async]` is F-polymorphic. `IOHarnessAgent = HarnessAgent[IO]` (`HarnessAgent.scala:320`). `generate` returns `F[HarnessResult]`, `stream` returns `Stream[F, StreamedChunk]`. The type contract verifies `IOHarnessAgent` is `HarnessAgent[IO]` and `generate` returns `IO[HarnessResult]`. The `Async[F]` context bound is present. The "Non-IO F is not instantiated in Phase 0" honest edge is documented (no `Async[Option]`).

---

## Compile-Negative Obligations — FAIL

The spec's Compile-Negative Obligations table requires `assertDoesNotCompile(...)` tests for:
1. `HarnessResult` non-exhaustive match
2. `ReactAgent.create` called with a `MiddlewareStack` argument
3. `HarnessAgent` constructed without `Async[F]`
4. `afterAgent` invoked directly on `HarnessResult`

The type contract (`HarnessAgentTypeContract.scala:165-181`) implements all four as:
```scala
assert(true) // exhaustiveness is compile-time, not runtime
assert(true) // documented compile-negative obligation
assert(true) // documented compile-negative obligation
```
This is the exact anti-pattern that Ring 8 Finding #2 from spec 1 flagged and fixed (replacing `assert(true)` with real `compileErrors`/`assertDoesNotCompile` tests). The fix was not carried forward. Obligation #2 (stack argument to `ReactAgent.create`) has no test at all — not even an `assert(true)` placeholder.

**Fix:** replace each `assert(true)` with a real `assertDoesNotCompile("...")` (munit supports `assertDoesNotCompile`/`compileErrors`).

---

## Dangerous-pattern hunt

`danger-scan.sh 827a0c1` flags 6 hits in `HarnessAgent.scala`:
- `244: case other: Throwable =>` — re-raises non-interrupt errors in `executeToolCalls.handleErrorWith`. **Justified:** the `AgentInterruptedException` case is handled explicitly; `other` is re-raised, not mapped to a valid domain value. This is correct error propagation, not a silent fallback.
- `261: case other => other.toString` — in `buildBaseToolStep`, converts non-`Str` `ujson.Value` results to string. **Acceptable:** `ujson.Value` is not a domain ADT; `toString` is a display conversion for tool output, not a fallback mapping for invalid input.
- `268: case other: Throwable =>` — in `buildBaseToolStep`, converts tool execution errors to an error `ToolOutput`. **Justified:** this is the existing ToolsNode pattern (errors become `isError = true` tool messages visible to the model).
- `333-334: case _: SystemMessage / case _` — in `buildConversation`, checks if messages already contain a system message. **Acceptable:** boolean check, not a domain-value mapping.

None of these map an invalid variant to a *valid* domain value. The substantive danger is the `mergeStates` no-op (covered under the parallel-merge FAIL).

---

## Oracle-tampering check

1. **`genInterruptSignal` narrowed** — spec requires `Simple`/`Stateful`/`Composite`; implementation generates only `Simple`. The `afterAgent-skipped-on-interrupt` property never exercises `Stateful` or `Composite` signals.
2. **`genConversation` and `genToolBehavior` defined but unused** — the L0 properties use fixed inputs instead of the spec-approved generated conversations and tool-behavior branches. No `cover` labels.
3. **Multiple properties weakened to tautologies** — `per-request-prompt-folding`, `per-request-tool-list`, and `event-emission` all assert `Completed => true` or `events.length > 0` instead of the spec's structural invariants. These pass for any terminating implementation.

---

## Summary

```
Ring 8: Adversarial Spec-Compliance Review — harness-agent

Fresh context: yes
Baseline: 827a0c1   Diff: 10 files
Dangerous patterns: 6 (0 fixed — all justified/acceptable; 1 substantive bug in mergeStates)
Oracle tampering: 3 findings (narrowed genInterruptSignal, unused genConversation/genToolBehavior, tautological assertions)
Requirements: 2 PASS, 4 PARTIAL, 3 FAIL

- Empty-stack conservative equivalence (L0): FAIL — ReactAgentImpl deleted before equivalence proven; no differential test; genConversation/genToolBehavior unused; no request-trace capture; no cover labels
- Per-request tool list derivation: PARTIAL — loop correct; oracle is tautology (Completed => true); LateToolMiddleware unused and defeats Ref-based intent
- Per-request prompt folding: PARTIAL — loop correct; oracle is tautology; no prompt capture; no mid-run cell update
- Loop orchestration order: PARTIAL — loop correct; oracle checks counts not reverse order
- Event emission stays in the loop: PARTIAL — loop correct; oracle asserts events.length > 0; no sequence equality; short-circuit adversarial untested
- Interrupt snapshots state without afterAgent: PARTIAL — afterAgent-skip correct; state snapshot unverified; genInterruptSignal narrowed to Simple only
- Parallel tool-call state merge (CONCURRENCY): FAIL — sequential traverse not parallel; mergeStates is no-op; tools never write cells; no TestControl; property entirely absent
- ReactAgent.create source-compatible sugar: PASS
- Effect polymorphism with IO: PASS
- Compile-Negative Obligations: FAIL — 3 of 4 are assert(true) placeholders; 1 has no test at all
```

---

## Recommended fixes (strongest first)

1. **Restore `ReactAgentImpl`** as a test-only comparison target and implement the real L0 differential property with trace capture + `cover` labels. Delete `ReactAgentImpl` only after green.
2. **Implement `mergeStates`** with per-cell `cell.merge` for `Shared` cells; give tools/middleware a path to write cells; switch to `parTraverse`; add the `TestControl` parallel-merge property.
3. **Thread restored `HarnessState` into `HarnessAgent.generate` on resume** (add a `resume(messages, restoredState, maxSteps)` entry point); update `AgentRunner.resume` to call it; add the "Resumed run runs afterAgent once" test.
4. **Replace every `assert(true)` compile-negative** with a real `assertDoesNotCompile(...)`.
5. **Replace every tautological `Completed => true` assertion** with the spec's structural invariant (capture prompts/tools/events in `ScriptedChatModel` and assert content/order).
6. **Broaden `genInterruptSignal`** to `Simple`/`Stateful`/`Composite`; wire `genConversation`/`genToolBehavior` into the L0 property.
