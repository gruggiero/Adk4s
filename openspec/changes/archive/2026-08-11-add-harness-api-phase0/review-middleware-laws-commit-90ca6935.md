# Review: middleware-laws spec — commit `90ca6935`

**Change**: `add-harness-api-phase0`
**Spec**: 6/6 — `middleware-laws`
**Commit**: `90ca6935` — "Implement middleware-laws spec: testkit module with L0-L11 laws + Ring 6 bridge"
**Reviewer**: Devin (openspec-verify-change skill)
**Date**: 2026-08-11
**Verdict**: **NOT READY FOR ARCHIVE** — 5 CRITICAL issues, 7 WARNINGs, 3 SUGGESTIONs

---

## Executive Summary

The commit introduces the `adk4s-harness-testkit` sbt module with `AgentMiddlewareLaws` (L0–L10), `SemilatticeLaws` (L11), `DeterministicChatModel`, Hedgehog generators, a `SimpleHarnessLoop` runner, and a Ring 6 `SemilatticeKernel` mirror with a bridge spec. The module structure, Ring 2 purity boundary, determinism of the model double, and the Ring 6 model + bridge are correctly implemented. **All 194 tests pass** (20 testkit + 7 bridge + 167 existing harness-api).

However, the test suite is substantially weaker than the spec demands:

1. **No `.cover` labels** on any property — the spec mandates specific coverage thresholds for nearly every law, and without them Hedgehog does not verify that generators exercise the named edge cases.
2. **`TestControl` never used** despite the spec's "MUST" for L0 interrupt and L11 mergeBack-order-independence scenarios.
3. **No adversarial/negative scenario tests** — the spec's Proof Obligations table lists 8 adversarial tests that confirm the properties are sensitive to violations; none exist.
4. **L0 does not test what the spec names** — it compares `SimpleHarnessLoop` empty-stack vs no-stack baseline, not `HarnessAgent(MiddlewareStack.empty)` vs `ReactAgentImpl`.
5. **`Any` type used in `SemilatticeLaws.scala`** — a direct AGENTS.md violation, with a trivial fix.

---

## Build & Test Verification

| Command | Result |
|---------|--------|
| `sbt adk4s-harness-testkit/compile` | ✓ success |
| `sbt adk4s-harness-testkit/test` | ✓ 20/20 pass (0 failed, 0 errors) |
| `sbt adk4s-harness-api/test` | ✓ 174/174 pass (incl. 7 `SemilatticeModelBridgeSpec`) |

Test breakdown (testkit): `SemilatticeLawsSpec` 4, `DeterministicChatModelSpec` 5, `AgentMiddlewareLawsSpec` 11.

---

## Spec Coverage Matrix

| Spec Requirement / Property | Implementation | Status |
|---|---|---|
| Deterministic ChatModel double | `DeterministicChatModel.scala` — seed-based, no UUID/wall-clock | ✓ PASS |
| Double records base-step request trace | `RecordedRequest` captured in `generate` | ✓ PASS |
| Double handles tool-call responses deterministically | No multi-iteration scripted test | ✗ MISSING (W5) |
| L0 Conservative refactor equivalence (gatekeeper) | `l0ConservativeRefactor` — but compares loop-vs-baseline, not HarnessAgent-vs-ReactAgentImpl | ✗ DIVERGENT (C4) |
| L0 scenario: simple conversation | Covered by `l0ConservativeRefactor` with `NoTool` behavior | ◐ PARTIAL |
| L0 scenario: with tool calls | Covered by `SingleTool`/`MultiTool` behaviors | ◐ PARTIAL |
| L0 scenario: under interrupt (TestControl) | `InterruptOnFirst` behavior exists, but no `TestControl` | ✗ FAIL (C2) |
| L0 scenario: step budget exhaustion | Not realizable — no infinite-tool-call script | ✗ FAIL (W4) |
| L1 Monoid identity | `l1MonoidIdentity` with head/middle/tail positions | ✓ PASS (no cover labels — C1) |
| L2 Monoid associativity | `l2MonoidAssociativity` | ✓ PASS (no cover labels — C1) |
| L3 Hook distribution (model call) | `l3HookDistribution` — system prompt only | ◐ PARTIAL (W1, W2, W3) |
| L3 Hook distribution (tool call) | Not implemented | ✗ MISSING (W1) |
| L3 single-element stack identity | Not implemented | ✗ MISSING (W3) |
| L4 Default neutrality | `l4DefaultNeutrality` | ✓ PASS (no adversarial — C3) |
| L4 adversarial: non-default NOT neutral | Not implemented | ✗ MISSING (C3) |
| L5 Cell frame rule | `l5CellFrameRule` — beforeAgent + afterAgent | ✓ PASS (no cover labels — C1, no adversarial — C3) |
| L5 adversarial: cross-cell write detected | Not implemented | ✗ MISSING (C3) |
| L6 Disjoint commutativity | `l6DisjointCommutativity` | ✓ PASS (no cover labels — C1, no adversarial — C3) |
| L6 adversarial: overlapping cells do NOT commute | Not implemented | ✗ MISSING (C3) |
| L6 adversarial: request rewriters do NOT commute | Not implemented | ✗ MISSING (C3) |
| L7 Codec round-trip | `l7CodecRoundTrip` — Int/String/Bool/ListInt via `TypedCell` | ✓ PASS (no cover labels — C1) |
| L8 Restore leniency | `l8RestoreLeniency` — unknowns + new cells | ✓ PASS (no cover labels — C1) |
| L8 adversarial: corrupted cell is hard error | Tested in spec 1's `HarnessStateSpec`, not in testkit | ◐ PARTIAL (S3) |
| L9 Privacy | `l9Privacy` — project + mergeBack | ✓ PASS (Int only — W7) |
| L9 adversarial: child writes unobservable | Not implemented | ✗ MISSING (C3) |
| L10 Merge-back neutrality | `l10MergeBackNeutrality` — single Shared cell only | ◐ PARTIAL (W6) |
| L10 adversarial: non-idempotent merge breaks neutrality | Not implemented | ✗ MISSING (C3) |
| L11 commutativity | `l11Commutativity` | ✓ PASS (no cover labels — C1, `Any` — C5) |
| L11 associativity | `l11Associativity` | ✓ PASS (no cover labels — C1, `Any` — C5) |
| L11 idempotence | `l11Idempotence` | ✓ PASS (no cover labels — C1) |
| L11 mergeBack order-independence (TestControl) | `l11MergeBackOrderIndependence` — no `TestControl` | ✗ FAIL (C2) |
| L11 adversarial: non-commutative merge fails | Not implemented | ✗ MISSING (C3) |
| Testkit module in main scope | `build.sbt` — `munitMain`/`hedgehogMunitMain` main scope | ✓ PASS |
| Testkit no heavy deps | No neo4j/lucene/http4s/db drivers | ✓ PASS |
| Ring 6: `SemilatticeKernel` | `verified/.../SemilatticeKernel.scala` — `ensuring` clauses | ✓ PASS |
| Ring 6: bridge spec | `SemilatticeModelBridgeSpec.scala` — 7 tests | ✓ PASS |
| Compile-Negative: no `Arbitrary` | `grep` confirms absence | ✓ PASS |
| Compile-Negative: no wall-clock/I/O in double | `grep` confirms absence | ✓ PASS |
| Compile-Negative: no `Thread.sleep`/`TimeUnit` | `grep` confirms absence | ✓ PASS |
| `stryker4s.conf` retarget to testkit | Not done — still points at spec 2 files | ✗ MISSING (S1) |

---

## CRITICAL Issues (must fix before archive)

### C1. No `.cover` labels on any property

**Location**: `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/AgentMiddlewareLaws.scala`, `SemilatticeLaws.scala`

**Evidence**:
```
$ grep -rn "\.cover(" adk4s-harness-testkit/src/main/ adk4s-harness-testkit/src/test/
NONE
```

**Spec requirement**: The Properties section mandates specific coverage labels for nearly every law. Examples:
- L0 (line 696): `no-tools` ≥ 20%, `with-tools` ≥ 30%, `interrupt` ≥ 20%, `step-exhaustion` ≥ 15%
- L1 (line 722): `empty-stack` ≥ 10%, `head` ≥ 25%, `middle` ≥ 25%, `tail` ≥ 25%
- L5 (line 812): `single-cell` ≥ 30%, `multi-cell` ≥ 40%, `same-type-external` ≥ 20%
- L7 (line 859): `Int` ≥ 20%, `String` ≥ 20%, `List` ≥ 20%, `at-initial` ≥ 15%
- L8 (line 882): `with-unknown` ≥ 30%, `with-new-cells` ≥ 30%, `both` ≥ 20%
- L9 (line 907): `single-child` ≥ 25%, `multi-child` ≥ 40%, `writes-initial` ≥ 15%
- L10 (line 927): `private-only` ≥ 20%, `shared-union` ≥ 30%, `shared-max` ≥ 20%
- L11-commutativity (line 945): `union` ≥ 30%, `max` ≥ 30%, `equal-values` ≥ 15%
- L11-mergeBack (line 994): `single-child` ≥ 15%, `all-equal` ≥ 15%, `disjoint-writes` ≥ 30%

**Impact**: Without coverage labels, Hedgehog does not verify that the generators actually exercise the edge cases the spec names. A property could pass while never generating an interrupt, a List cell, an at-initial value, or a multi-child mergeBack. The coverage labels are the spec's mechanism for ensuring the properties are non-vacuous — their absence means the test oracle is strictly weaker than the spec demands.

**Recommendation**: Add the spec-mandated `.cover` labels to every property. For properties that run multiple cases (like L0 with 5 cases per run), add `.cover` calls per case; for single-draw properties, add them inside the `forAll` body. See `HarnessAgentSpec.scala` (spec 5) for the project's existing `.cover` pattern.

---

### C2. `TestControl` never used despite spec's MUST

**Location**: `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/SemilatticeLaws.scala` (lines 61-73), `AgentMiddlewareLaws.scala` (L0 body, lines 74-81)

**Evidence**:
```
$ grep -rn "TestControl" adk4s-harness-testkit/src/ | grep -v "//\|\\*"
(empty — only in comments)
```
`catsEffectTestkit` is declared as a dependency in `build.sbt` but `TestControl` is never imported or called.

**Spec requirement**:
- L0 interrupt scenario (line 157): "This scenario MUST be driven with `TestControl` for deterministic concurrency."
- L11 (line 606-607): "This is a CONCURRENT behavior property and MUST be tested with `TestControl` for deterministic execution."
- L11 mergeBack scenario (line 625): "This scenario MUST be driven with `TestControl`."
- Proof Obligations table (line 1105, 1133): L0-interrupt and L11-mergeBack-order-independence require `TestControl`.

**Impact**: The spec's CONCURRENCY RULE (line 19-20) states: "requirements about concurrent execution state a DETERMINISTIC observable, tested with cats-effect TestControl." The session context confirms: "check 18 (CONCURRENCY) **APPLIES** — deterministic test kit detected: TestControl testkit." The L0 interrupt and L11 mergeBack properties run as plain `IO` / pure computations without deterministic concurrency control. While these particular computations may be effectively deterministic today (no actual parallelism in `mergeBack`), the spec mandates `TestControl` to guarantee determinism under future changes to the merge implementation.

**Recommendation**: Wrap the L0 interrupt branch and the L11 `mergeBack` order-independence property in `TestControl`. The pattern (from `cats-effect-testkit`):
```scala
TestControl.execute(body).flatMap { control =>
  control.tickAll >> control.results
}
```

---

### C3. No adversarial/negative scenario tests

**Location**: `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/AgentMiddlewareLawsSpec.scala`, `SemilatticeLawsSpec.scala`

**Evidence**:
```
$ grep -rni "adversarial\|negative\|NOT neutral\|NOT commute\|breaks neutrality\|fails the\|hard error\|corrupted\|cross-cell\|overlapping" adk4s-harness-testkit/src/
Generators.scala:34: * per the spec's Compile-Negative obligations.
```
`AgentMiddlewareLawsSpec` has 11 positive properties only. `SemilatticeLawsSpec` has 4 positive properties only.

**Spec requirement**: The Proof Obligations table (lines 1112-1134) lists 8 adversarial scenario tests:

| Adversarial scenario | Spec line | Purpose |
|---|---|---|
| L4: non-default middleware is NOT neutral | 1112 | Confirms L4 is sensitive to non-default behavior |
| L5: cross-cell write detected (property fails) | 1115 | Confirms L5 detects cross-cell writes |
| L6: overlapping cells do NOT commute | 1117 | Confirms L6 preconditions are honest |
| L6: request rewriters do NOT commute | 1118 | Confirms L6 preconditions are honest |
| L8: corrupted cell is a hard error | 1124 | Confirms restore fails on type mismatch |
| L9: child writes to Private are unobservable | 1127 | Confirms privacy holds under aggressive child writes |
| L10: non-idempotent merge breaks neutrality | 1129 | Confirms L10 is sensitive to idempotence precondition |
| L11: non-commutative merge fails the laws | 1134 | Confirms semilattice laws flag unsuitable merges |

**Impact**: The spec's rationale is explicit (e.g., L4 line 309): "this confirms the law is sensitive to non-default behavior, not trivially true." Without adversarial tests, a property could pass vacuously — for example, L5's frame rule would pass even if `beforeAgent` did nothing, because the external cell would be unchanged either way. The adversarial test (a middleware that DOES write to an external cell) confirms the property actually detects the violation. The L8 corrupted-cell scenario IS tested in `HarnessStateSpec.scala` (spec 1, line 95), but the spec assigns it to `AgentMiddlewareLawsSpec`.

**Recommendation**: Add negative scenario tests to `AgentMiddlewareLawsSpec` and `SemilatticeLawsSpec`. Each should construct a middleware/cell that violates the property's preconditions and assert the property fails (or the law reports failure). For L11, construct a `Shared` cell with `merge = (a, b) => a ++ b` (list concatenation, non-commutative) and assert `l11Commutativity` fails.

---

### C4. L0 property does not test what the spec requires

**Location**: `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/AgentMiddlewareLaws.scala` (lines 74-81)

**Evidence**:
```scala
private def l0Body(c: L0Case): IO[Boolean] =
  val signal: InterruptSignal = InterruptSignal.simple("sig")
  for
    (_, loop1, _) <- loopFor(c.behavior, signal)
    (_, loop2, _) <- loopFor(c.behavior, signal)
    r1            <- loop1.run(MiddlewareStack.empty[IO], c.conversation, c.maxSteps)
    r2            <- loop2.runBaseline(c.conversation, c.maxSteps)
  yield r1 ≍ r2
```
This compares `SimpleHarnessLoop.run(emptyStack, ...)` vs `SimpleHarnessLoop.runBaseline(...)` — a no-stack baseline within the same loop.

**Spec requirement** (line 112-117):
> "The testkit SHALL provide an L0 property asserting that `HarnessAgent(MiddlewareStack.empty)` is observationally equivalent (`≍`) to the current `ReactAgentImpl` on the deterministic `ChatModel` double..."

The spec names `HarnessAgent` and `ReactAgentImpl` explicitly. The Proof Obligations table (lines 1103-1106) assigns L0 to `AgentMiddlewareLawsSpec`.

**Implementation's defense** (SimpleHarnessLoop.scala lines 87-93):
> "Spec 5 refactored `ReactAgent.create` to delegate to `HarnessAgent` with the empty stack, so at the orchestration level the two share a code path and that comparison is covered by `HarnessAgentSpec` (spec 5). The testkit's L0 compares the empty-stack loop vs a no-stack baseline."

**Assessment**: The deferral to spec 5 is reasonable in intent — `HarnessAgentSpec` does contain a differential L0 test (`HarnessAgent` vs `LegacyReactAgent`). However, the spec's literal text requires the testkit to provide the L0 property, and the testkit's L0 is a **different, weaker property**: it proves the empty stack is a no-op within `SimpleHarnessLoop`, not that `HarnessAgent` ≍ `ReactAgentImpl`. The testkit cannot import `HarnessAgent` or `ReactAgentImpl` (Ring 2 purity forbids the orchestration dependency), so the spec's literal requirement is **unrealizable in the testkit's layering** as written.

**Recommendation**: Either:
- (a) **Amend the spec** to acknowledge that the testkit-level L0 is a loop-level no-op property and the HarnessAgent-vs-ReactAgentImpl L0 lives in `HarnessAgentSpec` (spec 5). Update the Proof Obligations table to assign the orchestration-level L0 to `HarnessAgentSpec`.
- (b) **Add a property in the orchestration test scope** (which can import both `HarnessAgent` and `LegacyReactAgent` and the testkit's `DeterministicChatModel`) that calls into the testkit's laws or replicates the L0 comparison at the orchestration level.

Option (a) is the lower-risk fix and aligns with the implementation's actual architecture.

---

### C5. `Any` type used in `SemilatticeLaws.scala` — AGENTS.md violation

**Location**: `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/SemilatticeLaws.scala` (lines 46-47)

**Evidence**:
```scala
val left: Any   = tc.merge(a, tc.merge(b, cc))
val right: Any  = tc.merge(tc.merge(a, b), cc)
val ok: Boolean = left == right
```

**Rule violation**: AGENTS.md: "NEVER use the 'Any' type. If you must use Any, ask the user for help."

**False claim in progress**: `implementation-progress.md` lines 235 and 256 both state "no `Any` or `asInstanceOf` used (TypedCell ADT pattern per AGENTS.md rules)."

**Note**: `Wart.Any` is globally excluded in `build.sbt` (line 29) due to a known Scala 3 false positive with string interpolation, so WartRemover does not catch this. The `Any` annotation here is NOT a string-interpolation false positive — it is a real `Any` type annotation.

**Fix**: The `Any` is unnecessary. The commutativity law on line 38 compares `tc.merge(a, b) == tc.merge(b, a)` without `Any`. The associativity law can be written directly:
```scala
val ok: Boolean = tc.merge(a, tc.merge(b, cc)) == tc.merge(tc.merge(a, b), cc)
```
The `Any` annotations appear to be a workaround for a type inference issue with the existential `SharedTypedCell[?]`, but pattern matching on `AssociativityCase[A]` captures `A`, so `tc.merge(a, ...)` should resolve to type `A` directly.

**Recommendation**: Remove the `Any` annotations and compare directly. If the compiler rejects the direct comparison due to the existential, use a type-preserving helper on `SharedTypedCell[A]` (e.g., `def associative(a: A, b: A, c: A): Boolean = merge(a, merge(b, c)) == merge(merge(a, b), c)`) and call it from the pattern match.

---

## WARNING Issues (should fix)

### W1. L3 only tests `wrapModelCall`, not `wrapToolCall`

**Location**: `AgentMiddlewareLaws.scala` lines 125-138

**Spec requirement** (line 240-243): "for a two-element stack `[m1, m2]`, `stack.wrapModelCall(base)` equals `m1.wrapModelCall(m2.wrapModelCall(base))` **(and likewise for `wrapToolCall`)**, tested by request-trace equality."

**Impact**: The tool-call wrapping distribution law is untested. A bug where `wrapToolCall` composes in the wrong order (e.g., `m2(m1(base))` instead of `m1(m2(base))`) would not be caught.

**Recommendation**: Add a `wrapToolCall` distribution check to `l3Body`. Generate two `TraceMiddleware` instances that record their name around tool execution, run `stack.wrapToolCall(baseTool)` and `m1.wrapToolCall(m2.wrapToolCall(baseTool))`, and assert the trace order matches.

---

### W2. L3 compares only system prompt, not full trace

**Location**: `AgentMiddlewareLaws.scala` lines 136-138

**Evidence**:
```scala
t1 <- m1.capturedRequests.get
t2 <- m2.capturedRequests.get
yield t1.headOption.map(_.renderedSystemPrompt) == t2.headOption.map(_.renderedSystemPrompt)
```

**Spec requirement** (line 249-250): "matching `m1.wrapModelCall(m2.wrapModelCall(base))` element-for-element." The trace contains `renderedSystemPrompt`, `messages`, and `toolNames` — only the first is compared.

**Recommendation**: Compare full `RecordedRequest` equality: `t1.headOption == t2.headOption` (or field-by-field if `RecordedRequest` lacks a suitable `equals`).

---

### W3. L3 missing "Single-element stack is identity for distribution" scenario

**Location**: `AgentMiddlewareLaws.scala` — `L3Case` always has two middlewares (`m1`, `m2`).

**Spec requirement** (line 271-276): "Given a one-element stack `[m1]` ... Then the trace equals `m1.wrapModelCall(base)` directly — no extra wrapping layer is introduced by the stack."

**Recommendation**: Add a single-element L3 case where `stack.wrapModelCall(base)` is compared to `m1.wrapModelCall(base)` directly.

---

### W4. L0 step-budget exhaustion scenario not realizable

**Location**: `Generators.scala` — `genScript` and `DeterministicChatModel.fallbackCompletion`

**Spec requirement** (line 159-166): "a conversation where the model always returns a tool call (never terminates) and maxSteps = 3."

**Evidence**: `fallbackCompletion` (DeterministicChatModel.scala line 100-107) returns `AssistantMessage(Some(s"fallback-$idx"))` — a final text response with no tool calls. No `ToolBehavior` script produces infinite/repeating tool calls. Step exhaustion only occurs when `maxSteps = 1` and the first response is a tool call (the loop consumes the one step on the tool-call iteration, then `remaining <= 0` triggers exhaustion before the next model call). This is a narrower edge case than the spec describes.

**Recommendation**: Add a `NeverTerminates` tool behavior whose script is a repeating list of tool-call completions (or make the fallback return tool calls for this behavior). Then the L0 property with `maxSteps = 3` would exercise true step-budget exhaustion: the model calls a tool, the loop continues, the model calls a tool again, the loop continues, the model calls a tool, and the budget is exhausted.

---

### W5. `DeterministicChatModelSpec` missing "tool-call responses deterministically" scenario

**Location**: `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/DeterministicChatModelSpec.scala`

**Spec requirement** (Proof Obligations line 1102): "Double handles tool-call responses deterministically — scenario test (scripted multi-iteration)."

**Evidence**: The spec has 5 tests: determinism, no-UUID id, no-wall-clock created, request trace capture, deterministic call id. None drive the model through two iterations with a `[toolCallCompletion, textCompletion]` script.

**Recommendation**: Add a test that scripts `[toolCallCompletion(seed, 0, "echo", "{}"), textCompletion(seed, 1, "done")]`, calls `generate` twice, asserts the first result has a tool call and the second has final text, and asserts both are deterministic across repeated runs with the same seed.

---

### W6. L10 only tests single Shared cell, no Private-only or mixed cases

**Location**: `AgentMiddlewareLaws.scala` lines 373-376

**Evidence**:
```scala
val l10Case: Gen[L10Case] =
  genSharedTypedCell.flatMap { (stc: SharedTypedCell[?]) =>
    stc.genStateWithValue.map(parent => L10Case(parent, List(stc: TypedCell[?])))
  }
```
This generates a single `SharedTypedCell` (Max/Min/Union) — no Private cells, no mixed cases.

**Spec requirement** (line 562-568): The L10 scenario includes both a `Shared` cell `s` and a `Private` cell `p`. The cover labels (line 927) require `private-only` ≥ 20%, `shared-union` ≥ 30%, `shared-max` ≥ 20%.

**Recommendation**: Extend `l10Case` to generate mixed Shared+Private cell lists. Add cover labels per the spec.

---

### W7. L9 only tests `StateCell[Int]`

**Location**: `Generators.scala` lines 503-508

**Evidence**:
```scala
val genPrivateCellWithValue: Gen[(StateCell[Int], Int)] =
  for
    owner <- genOwner
    name  <- genCellName
    v     <- genInt
  yield (StateCell[Int](owner, name, 0, visibility = CellVisibility.Private), v)
```

**Spec requirement** (line 901): "a `Private` `StateCell[A]`" — the generator strategy implies type variation.

**Recommendation**: Use the `TypedCell` ADT to generate private cells of multiple types (Int, String, Boolean, List[Int]).

---

## SUGGESTION Issues (nice to fix)

### S1. `stryker4s.conf` not retargeted to testkit

**Location**: `stryker4s.conf`

**Spec requirement** (Implementation Anchors line 1162): "point `mutate` at `**/harness/testkit/*.scala` for Ring 5 (testkit is main-scope; mutation threshold applies to the law logic)."

**Evidence**: `stryker4s.conf` still points at `**/harness/AgentMiddleware.scala` and `**/harness/ToolStep.scala` from spec 2.

**Recommendation**: Update `stryker4s.conf` `mutate` to include `**/harness/testkit/*.scala`.

---

### S2. `Observation.≍` compares tool names as sets, weakening L0/L3

**Location**: `SimpleHarnessLoop.scala` lines 61-70

**Evidence**:
```scala
a.toolNames.toSet == b.toolNames.toSet
```

**Assessment**: This is justified for L6 (disjoint commutativity requires tool order to be unobservable), but it weakens L0 and L3 where the spec says "equal request traces" and "element-for-element." If tool order were ever observable in L0 (e.g., the model receives tools in a specific order and that affects its response), this comparison would miss the difference.

**Recommendation**: Use list equality for L0/L3 and set equality only for L6, or document the weakening in the `Observation.≍` scaladoc.

---

### S3. L8 corrupted-cell scenario covered by spec 1, not testkit

**Location**: `adk4s-harness-api/src/test/scala/org/adk4s/harness/HarnessStateSpec.scala` line 95

**Evidence**: The "Corrupted cell value is a hard error" scenario IS tested in `HarnessStateSpec.scala` (spec 1), but the spec's Proof Obligations (line 1124) assign it to `AgentMiddlewareLawsSpec`.

**Recommendation**: Consider adding a mirror test in the testkit for completeness, or amend the Proof Obligations table to acknowledge the spec 1 coverage.

---

## What IS Correctly Implemented

### Module Structure ✓
- `adk4s-harness-testkit` is a separate sbt module with `.dependsOn(adk4s-harness-api, verified % Test)`.
- `munitMain` and `hedgehogMunitMain` are in **main** scope (not `% Test`), matching the `AgentMemoryLaws` precedent.
- `adk4s-orchestration` has a `% Test` dependency on the testkit.
- `hedgehogMunitMain` added to `Dependencies.scala`.

### Ring 2 Purity ✓
- No dependency on `adk4s-orchestration`, `workflows4s`, `llm4s` LLM client, `fs2-io`, or `logback`.
- The `SimpleHarnessLoop` is intentionally simpler than `HarnessAgent.loop` to avoid the orchestration dependency.

### `DeterministicChatModel` Determinism ✓
- No `System.currentTimeMillis`, `Clock`, `UUID`, or `URLConnection`.
- `Completion.id` derived from seed and index: `s"det-$seed-$idx"`.
- `created = 0L` always.
- `DeterministicChatModelSpec` verifies determinism (same seed + script → same `Completion`), no-UUID id, no-wall-clock created, request trace capture, deterministic call id.

### No `Arbitrary` ✓
- All generators use explicit `Gen` + `Range`.
- `grep -rn "Arbitrary\|arbitrary" adk4s-harness-testkit/src/main/` → only in comments.

### No `Thread.sleep`/`TimeUnit.sleep` ✓
- `grep -rn "Thread.sleep\|TimeUnit.sleep\|currentTimeMillis" adk4s-harness-testkit/src/test/` → none.

### Ring 6: `SemilatticeKernel` ✓
- `verified/src/main/scala/org/adk4s/verified/SemilatticeKernel.scala` (Scala 3.7.2, PureScala).
- `commutative`, `associative`, `idempotent`, `isSemilattice` with `ensuring` clauses.
- `intMax`/`intMin` concrete merges with lemma functions (`intMaxCommutative`, etc.) that have `ensuring(_ == true)`.
- `stainlessEnabled := false` by default — bridge compiles as plain Scala.

### Ring 6: Bridge Spec ✓
- `SemilatticeModelBridgeSpec.scala` in `adk4s-harness-api/src/test/` — 7 tests.
- Compares real `StateCell.merge` (Int max, Int min) vs model on same generated values.
- Verifies commutativity, associativity, idempotence for both intMax and intMin, plus `isSemilattice` for intMax.

### `TypedCell`/`SharedTypedCell` ADT ✓ (mostly)
- Avoids `asInstanceOf` in most places by pattern matching on typed variants.
- `IntCell`, `StringCell`, `BoolCell`, `ListIntCell` for L7–L10.
- `MaxCell`, `MinCell`, `UnionCell` for L11.
- Exception: the `Any` in `SemilatticeLaws.scala` (C5).

### Positive Law Properties ✓ (intent)
- L1, L2, L4, L5, L6, L7, L8, L9, L10 law bodies match the spec's invariant intent.
- L11 commutativity, associativity, idempotence, mergeBack order-independence properties exist.
- The `Observation.≍` relation compares outcome, final assistant, final state snapshot, and request traces.

---

## Summary Scorecard

| Dimension    | Status |
|--------------|--------|
| Completeness | 11/11 law properties exist; 20 testkit + 7 bridge tests pass; **0/15 cover labels; 0/8 adversarial scenarios** |
| Correctness  | **L0 diverges from spec (C4); TestControl never used (C2); L3 missing wrapToolCall (W1); L0 step-exhaustion not realizable (W4)** |
| Coherence    | `Any` in SemilatticeLaws (C5); stryker4s.conf not retargeted (S1); module structure + Ring 2 purity correct |

---

## Final Assessment

**5 CRITICAL issues found. Fix before archiving.**

The implementation builds and all 194 tests pass, but the test suite is substantially weaker than the spec demands:

- **C1 (no cover labels)**: Properties may pass vacuously without exercising named edge cases.
- **C2 (no TestControl)**: The spec's deterministic-concurrency mandate is unmet.
- **C3 (no adversarial tests)**: Properties' sensitivity to violations is unverified — they could be trivially true.
- **C4 (L0 divergence)**: The testkit's L0 is a weaker property than the spec names; requires spec amendment or additional orchestration-level property.
- **C5 (`Any` usage)**: Direct AGENTS.md violation with a trivial fix.

The most impactful fixes are C1, C2, and C3 — together they ensure the test oracle is non-vacuous, exercises the spec's edge cases, and verifies deterministic concurrency. C4 may be resolvable by spec amendment. C5 is a one-line fix.
