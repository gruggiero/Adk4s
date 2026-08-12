# Ring 8: Adversarial Spec-Compliance Review — harness-agent (FIX Re-review)

**Code reviewed:** working tree (uncommitted fixes on top of `448a4518` — "the fix for the last commit")
**Spec:** `openspec/changes/add-harness-api-phase0/specs/harness-agent/spec.md` (spec 5/6)
**Baseline SHA:** `827a0c1` (recorded at Step 0)
**Diff reviewed:** `git diff 827a0c1` (commit + working tree) — 20 files, +2582 / −459, plus untracked `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/LegacyReactAgent.scala`

```
 M .../agent/AgentRunner.scala          (+223/−…)   — harnessView fast-path, live-state snapshot, resume
 M .../agent/CheckpointStateV2.scala    (doc order / modifier normalize)
 M .../agent/DynamicToolRegistry.scala  (formatting only)
 M .../agent/HarnessAgent.scala         (+406)      — loop + resume() + real mergeStates + parTraverse
 M .../agent/HarnessResult.scala        (+34)
 M .../agent/ReactAgent.scala           — sugar + harnessView + fromHarness
 M .../wiograph/*, fork/*, package.scala (formatting only)
 M .../agent/Generators.scala           (+629)      — full generator suite + subscribing doubles
 M .../agent/HarnessAgentSpec.scala     (+745)      — differential L0 + all properties
 M .../typecontract/HarnessAgentTypeContract.scala (+220) — real compileErrors
?? .../agent/LegacyReactAgent.scala    — faithful pre-refactor loop (test-only differential target)
```

**Fresh context:** yes — I did not participate in writing the fix. Inputs were the spec, the prior review
(`review-harness-agent-commit-448a4518.md`, used as the finding list to re-verify), and the diff.
**Rings verified live during this review:** `sbt adk4s-orchestration/test` → **269 tests, 0 failed**;
`sbt adk4s-orchestration/compile` → clean (WartRemover is compile-gated → Ring 1 green);
`sbt adk4s-examples/compile` → clean (55+ examples source-compatible); no `Thread.sleep` in
`HarnessAgentSpec` (TestControl obligation satisfied statically).

---

## Gate verdict

**9 PASS, 0 PARTIAL (implementation), 3 documented divergences needing human sign-off, 2 process items open.**

Every FAIL/PARTIAL from the commit review has been addressed with *implementation* fixes and *real* oracles —
no tautological assertions remain, all generators from the spec are now wired in, compile-negatives are real
`compileErrors`, and `mergeStates` is a real per-cell merge fed by `parTraverse`. Three semantic divergences
from the pre-refactor agent (extra `Interrupted` event, interrupt message list, `MaxStepsExceededError`,
non-streaming `stream`) are all honestly captured — normalized/documented in the differential oracle and in the
`react-agent.md` concept file — but they are behavior *changes*, and the checkpoint should carry them
explicitly. Ring 5 (mutation) has not been run and `stryker4s.conf` is not retargeted (still points at
spec 2's files).

---

## Requirement-by-requirement review

### Requirement: Empty-stack conservative equivalence (L0 gatekeeper) — PASS (was FAIL)

- `LegacyReactAgent` (test-only) is a faithful restoration of the deleted `ReactAgentImpl`: same loop shape,
  same `ToolsNode` execution, same event calls, same baked `CompletionOptions`, same `buildConversation`.
  Its only deltas are observability probes (returns the message list; `emitFromToolsNode` toggle) — both
  exhaustively documented in the file header.
- The property (`HarnessAgentSpec.scala:164-191`) generates batches of 5 `L0Case`s from bucket-weighted
  `genL0Case` (conversation via `genConversation` — now used; behavior via `genToolBehavior` — now used;
  `genMaxSteps`) and asserts, per case:
  - `hTrace == lTrace` — `ScriptedChatModel` now *actually records* every request
    (`Generators.scala:212-238`: `capturedRequests` updated inside `generate`), including rendered system
    prompt, full message list, and tool names. Equal single-element/two-element traces per scenario.
  - outcomes: `Completed` compares final assistant **and full message list**; `Interrupted` compares the
    signal; `Failed` asserts harness-`MaxStepsExceeded` vs legacy-"max steps".
  - events: element-wise `==` over full event values (payloads included), with one documented normalization
    (see Divergence D1).
- Cover labels: all five (`no-tool 20`, `single-tool 20`, `multi-tool 20`, `interrupt 20`,
  `maxSteps-exhausted 10`) — with a documented batching strategy (`exists` over a 5-case batch) whose
  rationale (near-tight per-case rates are flaky at 100 runs) is explained in the file header. Hedgehog
  enforces covers as test failures; the suite is green.
- The legacy double-emission quirk is pinned by a dedicated test (`HarnessAgentSpec.scala:193-225`): the
  harness emits `ToolCallRequested` exactly once; faithful legacy emits more.

### Requirement: Per-request tool list derivation — PASS (was PARTIAL)

- Implementation unchanged and correct: `buildRequest` (`HarnessAgent.scala:177-189`) computes
  `stack.allTools ++ baseTools` per request; `CompletionOptions` derived per request.
- Oracle is now real: `LateToolMiddleware` reads its contribution from an `AtomicReference` flipped by
  `beforeAgent` (`Generators.scala:404-426`) — the spec's "mutable source read at request-build time"
  semantics, restored. The property asserts `firstTools == uniqLate ++ uniqBase` **and**
  `length == base + late` over generated name lists (0–3 each side, uniquified).
- Both scenarios have dedicated tests: "Stack tool appears before base tool" →
  `assertEquals(List("t-stack", "t-base"))`; "Empty stack yields only base tools" → `List("t-base")`.

### Requirement: Per-request prompt folding from current state — PASS (was PARTIAL)

- Oracle is now real: `StatefulPromptMiddleware` (`Generators.scala:364-393`) — `beforeAgent` writes
  `beforeVal`; `wrapToolCall` writes `toolVal` **after the base tool runs** (the missing mid-run cell
  update from the commit review). `ScriptedChatModel` captures rendered system prompts. The property
  asserts `prompt0.contains(beforeVal)`, `prompt1.contains(toolVal)`, and
  `prompt0 != prompt1` when the values differ — with covers `prompt-changed 50` / `prompt-unchanged 30`
  (`genPromptCase` forces equality 40% of the time, an honest generator fix for an astronomically rare
  natural hit, documented in the generator).
- "Empty stack yields only the base prompt" now asserts `renderedSystemPrompt == Some(basePrompt)` — the
  tautology is gone.

### Requirement: Loop orchestration order — PASS (was PARTIAL)

- `OrderRecordingMiddleware` (`Generators.scala:312-341`) appends each hook (`beforeAgent`,
  `wrapModelCall`, `wrapToolCall`, `afterAgent`) to a shared trace ref. The test asserts the exact
  ten-element trace: `M1.beforeAgent, M2.beforeAgent, M1.wrapModelCall, M2.wrapModelCall, M1.wrapToolCall,
  M2.wrapToolCall, M1.wrapModelCall, M2.wrapModelCall, M2.afterAgent, M1.afterAgent` — beforeAgent in stack
  order, afterAgent reversed, M1 outermost on both wraps. Count-based assertion is gone.
- `beforeAgent` exactly-once and afterAgent-on-exhaustion remain (the latter as a property over
  `maxSteps ∈ [1,2]`, asserting `HarnessResult.Failed(MaxStepsExceededError)` + `afterCount == 1` —
  exhaustion is normal termination, matching the spec).

### Requirement: Event emission stays in the loop — PASS (was PARTIAL)

- Exact variant sequence asserted: `[ToolCallRequested, ToolCallCompleted, IterationCompleted,
  MessageOutput, IterationCompleted]` for a one-tool run.
- The adversarial scenario is now tested: `ShortCircuitMiddleware.wrapToolCall` returns a synthetic
  `ToolCallOut` **without calling `next`**; the test asserts the full event sequence is intact (the
  loop emits `ToolCallRequested` before delegating) AND that `ToolCallCompleted.result ==
  "synthetic-result"` — proving the wrap short-circuited while the loop kept ownership.
- Element-wise event equality is also enforced across every L0 branch by the differential property
  (with the D1 normalization for the interrupt branch).

### Requirement: Interrupt snapshots state without afterAgent — PASS (was PARTIAL)

- `genInterruptSignal` is broadened to all three variants — weighted `Simple 32 / Stateful 36 /
  Composite 32` with generated `JsonValue` and children (`Generators.scala:148-163`). The
  `stateful-signal 30` cover exists. The narrowing flag is cleared.
- The snapshot scenario is now tested (`HarnessAgentSpec.scala:339-360`): a `Shared` cell is set to
  `"v1-value"` by `beforeAgent`; the interrupted outcome's state reads back `v1` **and** a
  `HarnessState.restore(cells, state.snapshot)` round-trip also reads back `v1` — snapshot fidelity, not
  just counter zero.
- Adversarial "counter is zero" holds in the property across stack sizes 1–3 (cover `stack-size-1 30`,
  `stack-size-3 20`).
- "Resumed run runs afterAgent once" is implemented end-to-end at the loop level
  (`HarnessAgentSpec.scala:475-520`): interrupt → `HarnessState.restore(stack.allCells,
  state.snapshot)` → `harness.resume(messages, restored, 5)` → completes; the
  `ResumeProbeMiddleware.afterObserved` ref proves `afterAgent` fired exactly once, on the restored state
  (`List("restored-cell-value")`), and never before resume. `HarnessAgent.resume`
  (`HarnessAgent.scala:83-86`) correctly skips `beforeAgent` on re-entry.
- `AgentRunner` integration is now real: `runHarness` persists `state.snapshot` (the LIVE state) on
  interrupt (`AgentRunner.scala:117-119`) and `resume` feeds the restored state to `harness.resume` —
  closing the spec-4 deferred limitation (progress doc line 151) exactly as demanded.

### Requirement: Parallel tool-call state merge is order-independent (CONCURRENCY) — PASS (was FAIL)

All four sub-failures from the commit review are fixed:
1. **Real parallelism**: `executeToolCalls` uses `parTraverse` when `config.parallelToolExecution`
   (`HarnessAgent.scala:227-231`), `traverse` otherwise (the spec's "parallelism = 1" degenerate case,
   tested: merged cell is the union under sequential execution).
2. **Real merge**: `mergeStates` (`HarnessAgent.scala:283-299`) folds per cell — `Shared` via
   `cell.merge(acc.get(cell), toolState.get(cell))`; `Private`/`Inherited` take the written value, with an
   honest comment distinguishing intra-agent stepping from cross-agent projection. For a `Shared` cell
   never written by a tool step, `merge(initial, initial)` is the initial under idempotence — and the
   spec only claims the property for semilattice cells, so this is in-contract.
3. **Tools can write cells**: via middleware `wrapToolCall` returning `ToolCallOut(out, newState)` —
   exercised by `CellWritingMiddleware` (`Generators.scala:437-455`).
4. **TestControl**: the property wraps execution in `TestControl.executeEmbed`
   (`HarnessAgentSpec.scala:457-470`), each generated case runs 3× with byte-identical snapshot
   assertions, covers `2-tools 30 / 3-tools 20`.
- The honest edge is tested deterministically: last-write-wins merge yields `Set("b")` — justified
  because `parTraverse` preserves call-list order in results, so the fold order is deterministic even
  for non-semilattice merges. The test comment says exactly this.

### Requirement: ReactAgent.create is source-compatible sugar — PASS

- 3/4/6/7-arg (emitter) and `createWithToolProvider` preserved; `adk4s-examples/compile` green.
- `harnessView` is a **new trait member with a default** (`= None`) — additive, no call-site breakage.
- `fromHarness` is new public surface providing the stack-bearing path that integrates with
  `AgentRunner` interrupt/resume — consistent with the spec's "the stack-bearing path is the new API".

### Requirement: Effect polymorphism with IO for Phase 0 — PASS

- `HarnessAgent[F[_]: Async]`; `IOHarnessAgent` alias; type contract verifies both. Harmless unused local
  `val agentAsIO` in the type contract — cosmetic only.

### Compile-Negative Obligations — PASS (was FAIL)

Three of four obligations are now real `compileErrors` tests with non-empty assertions:
`ReactAgent.create(..., stack)` rejected, `new HarnessAgent[F](cfg)` without `Async[F]` rejected,
`result.afterAgent` rejected. The fourth (non-exhaustive `HarnessResult` match) is enforced by
`variantId`'s exhaustive match compiled under the project's `-Wconf:name=PatternMatchExhaustivity:e`
escalation plus a runtime test pinning every variant — with an explicit comment explaining why
`compileErrors` cannot carry the build's warning escalation. This is the same mechanism the
harness-state type contract uses; the `assert(true)` placeholders are gone.

---

## Dangerous-pattern hunt

`danger-scan.sh 827a0c1`: 35 candidates. Judged:

- `HarnessAgent.scala:271,295` — `case other: Throwable => Async[F].raiseError(other)` / error→
  `ToolOutput(isError = true)`: re-raise and the ToolsNode-pattern error channel respectively. **Justified.**
- `HarnessAgent.scala:288` — `case other => other.toString` on `ujson.Value`: display conversion of a
  JSON value, not a domain-ADT fallback. **Acceptable.**
- `HarnessAgent.scala:395-396` — `SystemMessage`-detection boolean check. **Acceptable.**
- `AgentRunner.scala:81,133` — non-AdkError wrapped into `GenericError` and surfaced as
  `RunResult.Failed` + `ErrorOccurred`: error normalization, not a valid-value mapping. **Justified.**
- `AgentRunner.scala:213,216` — `case _: RunResult.Completed / case _` checkpoint-cleanup dispatch:
  boolean classification. **Acceptable.**
- `DynamicToolRegistry.scala:29,32,35` — `[unsafe-get]` hits are `Ref[IO, _].get` (effectful get in `IO`),
  not `Option.get`. Scanner false positive. **Acceptable.**
- `WIOGraph*.scala`, `WIONodeModifier.scala` casts — **pre-existing**; those files enter this diff only
  through scalafmt reflow (a dangerous scan against baseline attributes the whole formatted file to the
  change). Out of this spec's scope; flagged for the record.

No `case _` maps an invalid variant to a valid domain value. The `mergeStates` no-op is gone.

## Oracle-tampering check

None. All three commit-review findings cleared: `genInterruptSignal` covers all variants;
`genConversation`/`genToolBehavior` drive the L0 property; no tautological assertions remain (verified by
reading every assertion in `HarnessAgentSpec` — all are structural: equality, containment, or cover
requirements).

---

## Documented divergences (require human sign-off at the checkpoint)

- **D1 — The harness loop emits an `AgentEvent.Interrupted` event; the legacy loop did not** (legacy
  surfaced interrupts only via the exception + `AgentRunner`-level emission). The differential property
  normalizes this for interrupt cases (`eventsMatch` filters harness-side `Interrupted`) and the
  normalization is documented in the test. This is a strict superset of legacy behavior and matches the
  loop-owns-the-channel principle, but it *is* an observable event-sequence change.
- **D2 — Interrupt message list**: `HarnessResult.Interrupted` carries `messages :+ assistantMsg`; the
  legacy agent's exception channel carried no list. `outcomesMatch` compares only the signal for interrupt
  cases — the spec's "same partial message list" is unverifiable against a channel that never carried one.
  Carrying the assistant message is the *correct* behavior for resume fidelity (and exercised by the
  resume test), but it should be signed off as a deliberate improvement, alongside…
- **D3 — `MaxStepsExceededError` replaces `RuntimeException`**, and `HarnessAgent.stream` re-runs the loop
  non-streaming then re-streams the final message (and, for non-empty stacks, silently drops stack prompt
  sections on that final re-stream — `stream` builds `SystemPrompt(config.basePrompt, Nil)` at
  `HarnessAgent.scala:94`). D3 is already documented in `react-agent.md` (concept fidelity is good);
  the stream/stack-section caveat is worth one line in the progress doc before Ring 5.

## Process items

- `stryker4s.conf` still mutates `AgentMiddleware.scala`/`ToolStep.scala` (spec 2's targets) — the spec's
  Implementation Anchors require retargeting to `**/agent/HarnessAgent.scala` + `HarnessResult.scala`
  before Ring 5 (threshold 85%).
- `implementation-progress.md` spec-5 section describes the commit state (Step 3/R0–R4 as of `448a4518`);
  it does not yet record the fix round (this review, the `AgentRunner` live-state fix closing the spec-4
  deferral, or the pending Ring 5/6/7 runs).
- Ring 5 (mutation), Ring 6/7: not run — out of scope for this review, but the gate in the prior round's
  guidance stands: mutation must run on this final code (the tests added here are the ones that will kill
  mutants).

---

## Summary

```
Ring 8: Adversarial Spec-Compliance Review — harness-agent (fix re-review)

Fresh context: yes
Baseline: 827a0c1   Diff: 20 files (+ LegacyReactAgent.scala, untracked)
Dangerous patterns found: 35 candidates (0 needing fix — justified/acceptable/pre-existing)
Oracle tampering: none
Requirements: 9 PASS, 0 PARTIAL, 0 FAIL — with 3 documented divergences (D1–D3) for human sign-off

- Empty-stack conservative equivalence (L0): PASS — LegacyReactAgent restored; differential property with
  real trace/outcome/event comparison; all 5 cover labels enforced by Hedgehog
- Per-request tool list derivation: PASS — Ref-backed LateToolMiddleware; exact order+length assertions; both scenarios tested
- Per-request prompt folding: PASS — mid-run cell update via wrapToolCall; prompts captured and asserted; covers enforced
- Loop orchestration order: PASS — exact 10-entry hook trace (beforeAgent forward / afterAgent reverse / M1 outermost)
- Event emission stays in the loop: PASS — exact sequence + short-circuit adversarial + L0 element-wise equality
- Interrupt snapshots state without afterAgent: PASS — state + snapshot round-trip asserted; signals cover all variants;
  resume path proves afterAgent exactly once on restored state; AgentRunner snapshot deferral closed
- Parallel tool-call state merge (CONCURRENCY): PASS — real parTraverse + per-cell merge + cell-writing path;
  TestControl property (3× snapshot equality, covers) + honest-edge + sequential degenerate tests
- ReactAgent.create source-compatible sugar: PASS — signatures preserved; examples compile; additive harnessView/fromHarness
- Effect polymorphism with IO: PASS
- Compile-Negative Obligations: PASS — 3 real compileErrors + Ring-0-enforced exhaustiveness pin

GATE: passes Ring 8. Proceed to Ring 5 after: (1) retarget stryker4s.conf; (2) checkpoint records D1–D3 sign-off;
(3) one progress-doc line on the stream/stack-section caveat.
```
