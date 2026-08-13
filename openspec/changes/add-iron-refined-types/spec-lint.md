# Spec Lint Report

## Mechanical pre-pass

**openspec validate --strict**: PASS (`✓ change/add-iron-refined-types; Totals: 1 passed, 0 failed`)
**spec-lint.sh**: `9 spec file(s), 0 FAIL, 28 WARN` — exit 0. All F-checks pass. Warnings are judgment calls (W1 vague-word candidates, W3 negative-requirement confirmations, W7 altitude candidates) — adjudicated below.

### Applicability (CONTEXT block verbatim)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v12
  behavioural registry  openspec/concepts/             PRESENT (33 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
  type inventory        openspec/concept-inventory.md  PRESENT (157 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | PRESENT (capability profile) | APPLIES |
| 6 · reused concepts resolved | PRESENT (157 typed rows) | APPLIES |
| 17 · ALTITUDE | PRESENT (33 concepts) | APPLIES |
| 18 · CONCURRENCY | PRESENT (TestControl testkit detected) | APPLIES (but this change introduces no concurrent behavior — no CONCURRENCY scenarios needed) |

## Checks

Each spec is checked against:
1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**`
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant
2. Every `Then` is observable
3. Every scenario is testable with the detected stack
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement with a declared enforcement mechanism
13. Every consumer-facing surface has a scenario asserting what the consumer observes
14. Every asserted error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL — every "only"/"never"/"must not" requirement has a scenario whose input the requirement forbids
16. MUST-CONFIRM — externally-sourced classification tables are marked MUST-CONFIRM
17. ALTITUDE — no code identifiers in Given/When/Then; concepts cited in "Concepts Used (behavioral)"
18. CONCURRENCY — concurrent-behavior requirements name deterministic observables

## Results

### Spec: specs/error-hierarchy-dedup/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | All 3 open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A — no enum/dispatch preservation requirement |
| 2 | Then observable | ✅ | All Then are return values or compilation results |
| 3 | Scenarios testable | ✅ | munit + Hedgehog |
| 4 | Error paths specified | ✅ | Each requirement has error/edge scenarios |
| 5 | New concepts declared | ✅ | ConfigError, GraphCompilationError in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | AdkError, NodeKeyError, GraphValidationError in inventory |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A — no temporal properties |
| 9 | No vague words | ✅ | W1 "Valid input" in scenario title is a scenario name, not a vague Then; the Then is concrete ("Right containing the refined value") |
| 10 | Unreachable claims proven | ✅ | No unreachability claims |
| 11 | Enum/GADT extension | ✅ | "Existing AdkError pattern matches" requirement explicitly states how matches behave for new variants (Scenario: catch-all vs non-catch-all) |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; all 3 requirements named by obligations |
| 13 | Consumer-facing surface | ✅ | N/A — no LLM/IDL surface |
| 14 | Error variant type-feasible | ✅ | ConfigError/GraphCompilationError are AdkError variants — feasible |
| 15 | Adversarial | ✅ | "cannot be constructed" has negative scenarios |
| 16 | MUST-CONFIRM | ✅ | N/A — no external classifications |
| 17 | ALTITUDE | ✅ | W7: 'AdkError' in clause — ACCEPTED: AdkError is the domain type being specified (the sealed hierarchy itself), not an implementation detail. It appears in the requirement's normative statement as the subject, which is behavioral vocabulary. |
| 18 | CONCURRENCY | ✅ | N/A — no concurrent behavior |

**Verdict: PASS**

### Spec: specs/core-types/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | All 3 open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | All Then are compilation results, return values, or Left/Right |
| 3 | Scenarios testable | ✅ | munit + Hedgehog + compile-negative |
| 4 | Error paths specified | ✅ | Empty/reserved rejection scenarios |
| 5 | New concepts declared | ✅ | NodeKey (refined), ReservedNodeKey, Positive, NonNegative, ConfigError |
| 6 | Reused concepts resolved | ✅ | NodeKey, NodeKeyError, AdkError, RunInfo in inventory |
| 7 | Generator strategies | ✅ | All 3 properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | No W1 warnings for this spec |
| 10 | Unreachable claims proven | ✅ | "cannot be constructed as a NodeKey" → compile-negative obligations |
| 11 | Enum/GADT extension | ✅ | N/A — no enum extension (ReservedNodeKey is a new enum, not an extension) |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; all 3 requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant |
| 15 | Adversarial | ✅ | "cannot be constructed" has empty/reserved input scenarios |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | W7: 'NodeKey' in clause — ACCEPTED: NodeKey is the domain type being refined, the subject of the spec. It is behavioral vocabulary (the user-facing node identifier concept), not a code identifier. |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/harness-state/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | All 3 open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Return values, Left/Right, JSON round-trip results |
| 3 | Scenarios testable | ✅ | munit + Hedgehog + iron-upickle |
| 4 | Error paths specified | ✅ | Empty/malformed rejection scenarios |
| 5 | New concepts declared | ✅ | MiddlewareName (refined), CellId (refined) |
| 6 | Reused concepts resolved | ✅ | MiddlewareName, StateCell.CellId, StateCell, ConfigError in inventory |
| 7 | Generator strategies | ✅ | All 3 properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid" in "all valid cell ids decode" — the requirement defines valid as "containing cell ids" from a pre-migration snapshot; concrete enough in context. W1 "valid id" in scenario title — the scenario body defines it as `owner/name` format. ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | Compile-negative obligations for empty/malformed |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; all 3 requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant |
| 15 | Adversarial | ✅ | "cannot be constructed" has empty/malformed input scenarios |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | W7: 'MiddlewareName' in clause — ACCEPTED: MiddlewareName is the domain type being refined, the subject of the spec. Behavioral vocabulary. |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/checkpoint-store-fpoly/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | All 3 open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Return values, compilation failures, JSON round-trip |
| 3 | Scenarios testable | ✅ | munit + Hedgehog + iron-upickle |
| 4 | Error paths specified | ✅ | Empty string rejection, raw String rejection |
| 5 | New concepts declared | ✅ | CheckpointId (refined) |
| 6 | Reused concepts resolved | ✅ | CheckpointStore.CheckpointId, CheckpointStore, ConfigError in inventory |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "any String" in rationale — this is describing the pre-migration state concretely (transparent alias accepts any String). ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | Compile-negative for raw String assignment |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; all 3 requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant |
| 15 | Adversarial | ✅ | W3: "serializes via iron-upickle" flagged as negative — but this is not a "must not" requirement; it's a positive round-trip requirement with a concrete scenario. The W3 is a false positive from the word "via" being misread. ACCEPTED. |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | No W7 warnings for this spec |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/tools-node/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | Both requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | Both open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Return values, Left/Right |
| 3 | Scenarios testable | ✅ | munit + Hedgehog |
| 4 | Error paths specified | ✅ | Zero/negative rejection scenarios |
| 5 | New concepts declared | ✅ | None (reuses Positive) — stated correctly |
| 6 | Reused concepts resolved | ✅ | ToolsNodeConfig, ToolsNodeConfigBuilder, Positive, ConfigError |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid Positive" — defined concretely as the value 10 (default). ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | N/A — no unreachability claims (runtime refinement) |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; both requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant |
| 15 | Adversarial | ✅ | Zero/negative input scenarios |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | No W7 warnings |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/react-agent/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | Both requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | Both open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | IO failure, event traces, test pass/fail |
| 3 | Scenarios testable | ✅ | munit + Hedgehog + DeterministicChatModel |
| 4 | Error paths specified | ✅ | Zero/negative maxSteps rejection |
| 5 | New concepts declared | ✅ | None (reuses Positive) — stated correctly |
| 6 | Reused concepts resolved | ✅ | ReactAgent, AgentRunner, HarnessAgent, Positive, ConfigError |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid inputs" — defined concretely as "positive maxSteps" in the requirement body. W1 "valid maxSteps" in scenario — the scenario body gives the concrete value (5, 10). ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | N/A |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; both requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant; IO.raiseError can carry AdkError (it extends Throwable) |
| 15 | Adversarial | ✅ | W3: "refined to Positive" flagged — has zero/negative input scenarios. ACCEPTED. |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | W7: 'AgentEvent', 'RunResult', 'HarnessResult' in clause — ACCEPTED: these are the domain event/result types the agent produces, behavioral vocabulary describing what the caller observes. Not implementation details. |
| 18 | CONCURRENCY | ✅ | N/A — no concurrent behavior introduced (refinement is pure; the ReAct loop is sequential) |

**Verdict: PASS**

### Spec: specs/memory-orchestration-hook/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | The requirement has G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | Opens with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Left/Right, ConfigError, recallK value |
| 3 | Scenarios testable | ✅ | munit + Hedgehog |
| 4 | Error paths specified | ✅ | Negative recallK → ConfigError scenario |
| 5 | New concepts declared | ✅ | None (reuses NonNegative) — stated correctly |
| 6 | Reused concepts resolved | ✅ | MemoryPolicy, NonNegative, ConfigError |
| 7 | Generator strategies | ✅ | All 3 properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid for NonNegative" — defined concretely as "zero is valid for NonNegative" with the value 0. ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | N/A |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; requirement named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant; Either[ConfigError, MemoryPolicy] is feasible |
| 15 | Adversarial | ✅ | "no IllegalArgumentException is thrown" — has negative recallK scenario that must produce ConfigError instead |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | No W7 warnings |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/structured-llm/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | Both requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | Both open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Return values, Left/Right, test pass/fail |
| 3 | Scenarios testable | ✅ | munit + Hedgehog |
| 4 | Error paths specified | ✅ | Zero/negative rejection |
| 5 | New concepts declared | ✅ | None (reuses Positive) — stated correctly |
| 6 | Reused concepts resolved | ✅ | StructuredLLM, Positive, ConfigError |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid maxParseAttempts" — defined concretely as the value 1 (default). ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | N/A |
| 11 | Enum/GADT extension | ✅ | N/A |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; both requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | ConfigError is an AdkError variant |
| 15 | Adversarial | ✅ | Zero/negative input scenarios |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | No W7 warnings |
| 18 | CONCURRENCY | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/wio-graph/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have G/W/T |
| 1b | SHALL/MUST normative opener | ✅ | All 3 open with SHALL |
| 1c | Per-variant behavior-preservation | ✅ | N/A |
| 2 | Then observable | ✅ | Error variants, validation results, grep results |
| 3 | Scenarios testable | ✅ | munit + Hedgehog + static check |
| 4 | Error paths specified | ✅ | Failed compile → GraphCompilationError |
| 5 | New concepts declared | ✅ | ValidatedGraph |
| 6 | Reused concepts resolved | ✅ | GraphExecutor, Graph, GraphValidationError, GraphCompiledError, GraphEntryMissingError, GraphEndNodesMissingError, GraphCompilationError |
| 7 | Generator strategies | ✅ | Both properties declare Gen strategy |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 "valid graph" — defined concretely as "passes graph.compile" and "valid entry node, edges, and end nodes". ACCEPTED. |
| 10 | Unreachable claims proven | ✅ | "cannot be constructed without successful compile" → compile-negative + property + adversarial review |
| 11 | Enum/GADT extension | ✅ | N/A — no enum extension (GraphCompilationError is a new AdkError variant, handled by error-hierarchy-dedup spec's exhaustiveness requirement) |
| 12 | Proof Obligations complete | ✅ | F6/F7 pass; all 3 requirements named |
| 13 | Consumer-facing surface | ✅ | N/A |
| 14 | Error variant type-feasible | ✅ | GraphCompilationError is an AdkError variant; IO.raiseError can carry it |
| 15 | Adversarial | ✅ | W3: "cannot be constructed" — has invalid graph (cycle) scenario. ACCEPTED. |
| 16 | MUST-CONFIRM | ✅ | N/A |
| 17 | ALTITUDE | ✅ | W7: 'AdkError' in clause — ACCEPTED: AdkError is the domain error hierarchy being extended, behavioral vocabulary. GraphExecutor.scala references removed from clauses (moved to Implementation Anchors). |
| 18 | CONCURRENCY | ✅ | N/A — no concurrent behavior |

**Verdict: PASS**

## Summary

| Spec | Verdict |
|------|---------|
| specs/error-hierarchy-dedup/spec.md | PASS |
| specs/core-types/spec.md | PASS |
| specs/harness-state/spec.md | PASS |
| specs/checkpoint-store-fpoly/spec.md | PASS |
| specs/tools-node/spec.md | PASS |
| specs/react-agent/spec.md | PASS |
| specs/memory-orchestration-hook/spec.md | PASS |
| specs/structured-llm/spec.md | PASS |
| specs/wio-graph/spec.md | PASS |

**All 9 specs PASS.** 0 FAIL, 28 WARN (all adjudicated as accepted — W7 type
names are domain vocabulary, W1 "valid" has concrete definitions nearby, W3
negative requirements have adversarial scenarios). No spec is too ambiguous
to implement safely. Proceed to design.
