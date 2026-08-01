# Spec Lint Report

## Mechanical pre-pass

**openspec validate --strict**: PASS — `Change 'add-eval-core' is valid`

**spec-lint.sh**: 2 spec files, 0 FAIL, 10 WARN — PASSED (no F-checks)

```
spec-lint: openspec/changes/add-eval-core/specs/eval-core/spec.md
  WARN W3 line 144: requirement "Failure-score substitution" is negative — confirm at least one scenario input is forbidden by it
  WARN W3 line 234: requirement "Trace is None in evaluation mode" is negative — confirm at least one scenario input is forbidden by it
  WARN W3 line 256: requirement "Feedback preserved and inert" is negative — confirm at least one scenario input is forbidden by it
  WARN W3 line 298: requirement "JSON export round-trip" is negative — confirm at least one scenario input is forbidden by it
  WARN W3 line 350: requirement "CSV export column contract" is negative — confirm at least one scenario input is forbidden by it
  WARN W1 line 380: vague word in requirement "JSONL dataset reader": "valid lines"
  WARN W1 line 387: vague word in requirement "JSONL dataset reader": "Valid JSONL"
  WARN W1 line 395: vague word in requirement "JSONL dataset reader": "not valid JSON"
spec-lint: openspec/changes/add-eval-core/specs/llm-judges/spec.md
  WARN W3 line 122: requirement "Judge schema definition strategy" is negative — confirm at least one scenario input is forbidden by it
  WARN W3 line 150: requirement "Judge parse failure surfaces as metric failure" is negative — confirm at least one scenario input is forbidden by it
spec-lint: 2 spec file(s), 0 FAIL, 10 WARN
```

### W3 (adversarial confirmation) — all confirmed

Each W3 warning asks whether a negative-sounding requirement has a scenario whose input the requirement forbids. All confirmed:

- **Failure-score substitution** (eval-core): Scenario "Poisoned example among 100" — the poisoned example IS the forbidden input (a program that raises); the requirement says the harness SHALL continue, not abort. Adversarial scenario present.
- **Trace is None in evaluation mode** (eval-core): Scenario "Counting metric verifies trace is None" — the forbidden input is the harness passing `Some(trace)`; the scenario asserts every recorded trace is `None`. Adversarial scenario present.
- **Feedback preserved and inert** (eval-core): Scenario "Feedback does not affect aggregate" — the forbidden input is feedback leaking into the aggregate; the scenario asserts two runs with different feedback produce the same score. Adversarial scenario present.
- **JSON export round-trip** (eval-core): Scenario "Round-trip with feedback and failures" — the forbidden input is a result that doesn't round-trip; the scenario asserts equality. The requirement also says "not on the Evaluate call" — the adversarial input is requiring writers on `Evaluate` itself; the spec explicitly excludes this. Adversarial scenario present.
- **CSV export column contract** (eval-core): Scenario "Mixed outcomes" — the forbidden input is a CSV with wrong columns or missing outcome values; the scenario asserts the exact column header and outcome values. Adversarial scenario present.
- **Judge schema definition strategy** (llm-judges): Scenario "Schema compiles without codegen plugin" — the forbidden input is defining schemas in `structured-llm-test-models` (test-only); the scenario asserts compilation without the codegen plugin. Adversarial scenario present.
- **Judge parse failure surfaces as metric failure** (llm-judges): Scenario "Unparseable judge completion" — the forbidden input is a harness crash on parse failure; the scenario asserts the metric raises and the harness records `EvalOutcome.Failed`. Adversarial scenario present.

### W1 (vague words) — all justified

- **"valid lines" / "Valid JSONL" / "not valid JSON"** in "JSONL dataset reader": "valid" here means "well-formed JSON" — the concrete definition is stated in the requirement ("one JSON object per line") and the malformed-line scenario specifies the exact forbidden input (line 15 is not valid JSON → error naming line 15). Not vague in context.

## Checks

### Spec: specs/eval-core/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 13 requirements have concrete Given/When/Then clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 13 requirements open with SHALL before the first **Given** (fixed: "Max-errors abort" rephrased to lead with "The harness SHALL") |
| 1c | Per-variant behavior-preservation scenarios | ✅ | No enum/dispatch-parameter behavior-preservation requirements in this spec |
| 2 | Then observable | ✅ | Every Then is a return value, error value, row count, or equality assertion |
| 3 | Scenarios testable | ✅ | All scenarios use IO, Ref, Deferred, TestControl — detected stack |
| 4 | Error paths specified | ✅ | Failure-score substitution, maxErrors abort, malformed JSONL line, empty devset — all error paths have scenarios |
| 5 | New concepts declared | ✅ | All 14 new concepts in "Concepts Introduced" table |
| 6 | Reused concepts resolved | ✅ | All 7 reused concepts exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | All 9 properties declare generator strategy (Gen name, constructive/filtered, edge cases, classify labels) |
| 8 | Temporal trigger/response | ✅ | N/A — no temporal properties (Ring 9 not applicable) |
| 9 | No vague words | ✅ | W1 warnings on "valid" in JSONL context are justified (see above) |
| 10 | Unreachable claims proven | ✅ | No "unreachable" claims; EvalOutcome exhaustiveness enforced by compile-negative test |
| 11 | Enum extension / type-widening behavior | ✅ | No enum extensions of existing types; EvalOutcome and EvalError are new enums |
| 12 | Proof obligations complete (F6/F7/F8) | ✅ | All 16 obligations have resolvable Sources in mandated format; all 13 requirements named by at least one obligation (F7 reachability) |
| 13 | Consumer-facing surface asserted | ✅ | CSV export column contract + JSON formatVersion are consumer-facing surfaces with scenarios |
| 14 | Error variants type-feasible | ✅ | EvalError.TooManyErrors is raised via F.raiseError — type-feasible against F[EvaluationResult] return |
| 15 | Adversarial scenarios for negatives | ✅ | All W3 negatives confirmed (see above) |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced classification tables in this spec (judge prompt text is in llm-judges spec, marked MUST-CONFIRM there) |
| 17 | Altitude respected | ✅ | No code identifiers in Given/When/Then; all code names in Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | "Max-errors abort" scenario uses Deferred-based cancellation probe (deterministic, not wall-clock); "Parallelism 4 with randomized latency" asserts ordering, not timing |

**Verdict: PASS**

### Spec: specs/llm-judges/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have concrete Given/When/Then clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 5 requirements open with SHALL before the first **Given** |
| 1c | Per-variant behavior-preservation scenarios | ✅ | No enum/dispatch-parameter behavior-preservation requirements |
| 2 | Then observable | ✅ | Every Then is a score value, feedback string, or error outcome |
| 3 | Scenarios testable | ✅ | All scenarios use mock StructuredLLM, IO — detected stack |
| 4 | Error paths specified | ✅ | Parse failure → metric failure (R1.12), out-of-range → clamped (R1.13) — both have scenarios |
| 5 | New concepts declared | ✅ | All 4 new concepts (Judges.semanticF1, Judges.completeAndGrounded, 2 judge schemas) in "Concepts Introduced" |
| 6 | Reused concepts resolved | ✅ | All 10 reused concepts exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | All 4 properties declare generator strategy |
| 8 | Temporal trigger/response | ✅ | N/A — no temporal properties |
| 9 | No vague words | ✅ | No vague words detected |
| 10 | Unreachable claims proven | ✅ | No unreachable claims |
| 11 | Enum extension / type-widening behavior | ✅ | No enum extensions |
| 12 | Proof obligations complete (F6/F7/F8) | ✅ | All 8 obligations have resolvable Sources; all 5 requirements named by at least one obligation |
| 13 | Consumer-facing surface asserted | ✅ | Judge schemas are consumer-facing (LLM sees the schema); scenario "Schema compiles without codegen plugin" asserts the surface |
| 14 | Error variants type-feasible | ✅ | Metric raises (caught by harness as EvalOutcome.Failed) — type-feasible against F[Score] return |
| 15 | Adversarial scenarios for negatives | ✅ | All W3 negatives confirmed (see above) |
| 16 | MUST-CONFIRM marks present | ✅ | Judge prompt text in Implementation Anchors marked "⚠ MUST-CONFIRM — do not invent: source is DSPy's repo" |
| 17 | Altitude respected | ✅ | No code identifiers in Given/When/Then; all code names in Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | No concurrent behavior in this spec |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/eval-core/spec.md | PASS | 0 — all 13 requirements + 9 properties + 16 proof obligations verified |
| specs/llm-judges/spec.md | PASS | 0 — all 5 requirements + 4 properties + 8 proof obligations verified |

Both specs PASS. Implementation-order may be generated.
