# Spec: LLM Judges

<!-- This is a DELTA spec for Phase 1 of the DSPy port
     (docs/dspy-port-operative-plan.md). It introduces two LLM-judge metrics
     (SemanticF1-style and CompleteAndGrounded-style) as StructuredLLM
     programs over Smithy schemas, with the eval-vs-optimization toggle
     (binarize when trace.isDefined) and the constraint-clamp + feedback
     path. Judge schemas are defined via hand-written Schema.instance
     definitions in adk4s-eval (⚠ VERIFY R1.11 — structured-llm-test-models
     is test-only codegen; judges are production code).

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code
     identifiers — class names, error variants, build commands — live in
     Implementation Anchors and the Concepts Introduced table. The full
     typed contract (Scala signatures) lives in design.md. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| metric-contract (NEW — created by eval-core spec) | The judges implement this trait; the trace-toggle drives the binarize behavior | `openspec/concepts/metric-contract.md` (created at apply Step 12 by eval-core spec) |
| StructuredLLM/complete | The judges call this action to obtain structured judge completions (precision/recall or completeness/groundedness) | [structured-llm.md](../../../concepts/structured-llm.md) |
| Schema | The judges carry output Schemas for their typed judge completions | [schema.md](../../../concepts/schema.md) |
| Constraint/check | Out-of-range precision/recall is clamped and flagged in feedback via this action | [constraint-validation.md](../../../concepts/constraint-validation.md) |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `Constraint.check` / `Constraint.assert` | constraint API | `org.adk4s.structured.core` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `ParseError` | sealed trait | `org.adk4s.structured.core` |
| `Applicative` | type class | `cats` |
| `Async` | type class | `cats.effect` |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Judges.semanticF1` | factory (Metric) | LLM-judge returning F1 of precision/recall from a Smithy-schema'd judge completion; binarized on `trace.isDefined` |
| `Judges.completeAndGrounded` | factory (Metric) | LLM-judge returning completeness/groundedness score; binarized on `trace.isDefined` |
| Judge schema: `SemanticF1Judge` | Smithy structure (hand-written `Schema.instance`) | `precision: Double`, `recall: Double`, `reasoning: String` — the judge's structured output |
| Judge schema: `CompleteAndGroundedJudge` | Smithy structure (hand-written `Schema.instance`) | `completeness: Double`, `groundedness: Double`, `reasoning: String` |

## ADDED Requirements

### Requirement: SemanticF1 judge metric

The semantic-F1 judge SHALL return a `Metric` that obtains precision and recall
in [0, 1] from a structured judge completion, returns the F1 as `Score.value`
with the judge's reasoning as `feedback` when `trace` is None, and returns the
binarized `Score(1.0)` if F1 >= threshold or `Score(0.0)` otherwise when `trace`
is defined.

**Given** a gold answer and a prediction, and a judge LLM that returns
precision=0.8 and recall=0.6
**When** the semantic-F1 metric is called with `trace = None`
**Then** the score value is `2 * 0.8 * 0.6 / (0.8 + 0.6) ≈ 0.686` (the F1
formula) and `feedback` contains the judge's reasoning.

**Rationale**: SemanticF1 is the standard LLM-judge metric for answer
similarity. The eval-vs-optimization toggle (binarize on `trace.isDefined`)
matches DSPy's pattern: evaluation uses the raw score, optimization uses the
pass/fail signal.

#### Scenario: Evaluation mode (trace = None)

**Given** precision=0.9, recall=0.8, threshold=0.66
**When** the metric is called with `trace = None`
**Then** `score.value == 2 * 0.9 * 0.8 / (0.9 + 0.8) ≈ 0.847` and
`score.feedback` contains the judge's reasoning string.

#### Scenario: Optimization mode (trace = Some)

**Given** precision=0.5, recall=0.5, threshold=0.66
**When** the metric is called with `trace = Some(trace)`
**Then** `score.value == 0.0` (F1 ≈ 0.5 < threshold 0.66 → binarized to 0.0)
and `feedback` is None (binarized scores carry no feedback).

#### Scenario: Optimization mode pass

**Given** precision=0.9, recall=0.9, threshold=0.66
**When** the metric is called with `trace = Some(trace)`
**Then** `score.value == 1.0` (F1 = 0.9 >= threshold → binarized to 1.0).

### Requirement: CompleteAndGrounded judge metric

The complete-and-grounded judge SHALL return a `Metric` that obtains
completeness and groundedness in [0, 1] from a structured judge completion,
returns the average as `Score.value` with reasoning as `feedback` when `trace`
is None, and returns the binarized score when `trace` is defined.

**Given** a gold answer and a prediction, and a judge LLM that returns
completeness=0.7 and groundedness=0.9
**When** the complete-and-grounded metric is called with `trace = None`
**Then** `score.value == (0.7 + 0.9) / 2 == 0.8` and `feedback` contains the
judge's reasoning.

**Rationale**: CompleteAndGrounded is the standard LLM-judge for factual
accuracy (completeness = covers all gold facts, groundedness = no
hallucinations). The average is the standard aggregate; the toggle matches
SemanticF1.

#### Scenario: Evaluation mode

**Given** completeness=0.8, groundedness=0.6, threshold=0.66
**When** the metric is called with `trace = None`
**Then** `score.value == 0.7` and `feedback` contains the reasoning.

#### Scenario: Optimization mode

**Given** completeness=0.8, groundedness=0.6, threshold=0.66
**When** the metric is called with `trace = Some(trace)`
**Then** `score.value == 1.0` (0.7 >= 0.66 → binarized to 1.0).

### Requirement: Judge schema definition strategy

Judge schemas SHALL be defined as hand-written `Schema.instance` definitions
in `adk4s-eval` (NOT in `structured-llm-test-models`, which is test-only
codegen). Each schema SHALL have a Smithy IDL string for prompt injection and
a smithy4s schema for JSON decoding.

**Given** a judge requiring precision/recall/reasoning fields
**When** the judge schema is defined
**Then** the schema is a `Schema.instance[SemanticF1Judge]` with a Smithy IDL
block and a smithy4s `Schema[SemanticF1Judge]`, living in
`adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` or a companion file.

**Rationale**: `structured-llm-test-models` is test-only codegen — production
code cannot depend on it. Hand-written `Schema.instance` definitions are the
lightweight alternative (the `Schema` typeclass bridges Smithy IDL and
smithy4s schemas without codegen). ⚠ VERIFY: check how `structured-llm` wires
smithy4s codegen in `build.sbt` and confirm hand-written definitions compile
without the codegen plugin.

#### Scenario: Schema compiles without codegen plugin

**Given** the `adk4s-eval` module without the smithy4s-sbt-codegen plugin
**When** `sbt adk4s-eval/compile` is called
**Then** the judge schema definitions compile successfully — they use
`Schema.instance` with hand-written Smithy IDL + smithy4s `Schema` values, not
generated code.

### Requirement: Judge parse failure surfaces as metric failure

A judge whose completion fails SAP parsing after `structured-llm` retries SHALL
surface as a metric failure (the eval-core failure-score path), never as a
harness crash.

**Given** a judge LLM that returns unparseable text for an example
**When** the metric is called
**Then** the metric raises (which the harness catches as `EvalOutcome.Failed`
+ `Score(failureScore)` per the eval-core failure-score substitution
requirement) — the harness does not crash.

**Rationale**: LLM judges are unreliable; a parse failure on one example must
not abort the evaluation. The metric delegates failure handling to the
harness by raising; the harness scores it `failureScore` and continues.

#### Scenario: Unparseable judge completion

**Given** a `StructuredLLM` mock that returns garbled text for one example
**When** the judge metric is called for that example
**Then** the metric raises (the SAP parse error propagates as an exception),
and the harness records `EvalOutcome.Failed` + `Score(failureScore)` for that
row — the run continues.

### Requirement: Out-of-range precision/recall clamped

Precision and recall values outside [0, 1] returned by the judge SHALL be
clamped to [0, 1] and the clamping SHALL be flagged in the `feedback` string.
This SHALL use the existing `Constraint.check` mechanism.

**Given** a judge LLM that returns precision=1.5 and recall=-0.2
**When** the semantic-F1 metric processes the judge completion
**Then** precision is clamped to 1.0, recall is clamped to 0.0, and
`feedback` contains a note that values were clamped (e.g. "clamped
precision 1.5→1.0, recall -0.2→0.0").

**Rationale**: LLMs occasionally return values outside the expected range.
Silently using them would corrupt the F1 calculation. Clamping + flagging is
the safe default; the feedback lets the caller know the judge was unreliable.

#### Scenario: Precision above 1.0

**Given** precision=1.2, recall=0.8
**When** the metric processes the completion
**Then** precision is clamped to 1.0, F1 is computed from the clamped values,
and `feedback` notes the clamp.

#### Scenario: Recall below 0.0

**Given** precision=0.7, recall=-0.1
**When** the metric processes the completion
**Then** recall is clamped to 0.0, F1 = 0.0 (recall is 0), and `feedback`
notes the clamp.

## Properties (Ring 3)

### Property: semantic-f1-eval-mode

**Invariant**: In evaluation mode (trace = None), the score value equals the F1
of the judge's precision and recall, and feedback contains the reasoning.

**Generator strategy**: `genPrecisionRecall` (constructive — two Doubles in
Range.linearF 0.0 1.0); `genReasoning` (Gen.string). Uses a mock
`StructuredLLM` that returns the given precision/recall/reasoning.

```
forAll { (precision: Double, recall: Double, reasoning: String) =>
  val metric = Judges.semanticF1[IO](mockJudge(precision, recall, reasoning), threshold = 0.66)
  val score = metric.apply(example, prediction, trace = None).unsafeRunSync()
  val expectedF1 = 2 * precision * recall / (precision + recall)
  score.value == expectedF1 && score.feedback == Some(reasoning)
}
```

### Property: semantic-f1-optimization-mode-binarized

**Invariant**: In optimization mode (trace = Some), the score value is 1.0 if
F1 >= threshold and 0.0 otherwise, and feedback is None.

**Generator strategy**: `genPrecisionRecall` (Range.linearF 0.0 1.0);
`genThreshold` (Range.linearF 0.1 0.9).

```
forAll { (precision: Double, recall: Double, threshold: Double) =>
  val metric = Judges.semanticF1[IO](mockJudge(precision, recall, ""), threshold)
  val score = metric.apply(example, prediction, trace = Some(Trace.empty)).unsafeRunSync()
  val f1 = 2 * precision * recall / (precision + recall)
  score.value == (if f1 >= threshold then 1.0 else 0.0) && score.feedback.isEmpty
}
```

### Property: out-of-range-clamped

**Invariant**: Precision/recall outside [0, 1] are clamped to [0, 1] before F1
computation, and feedback notes the clamping.

**Generator strategy**: `genOutOfRange` (Double in Range.linearF -1.0 2.0,
excluding [0, 1] via filter — small range, low discard rate). Classify by
direction (below 0, above 1).

```
forAll { (rawPrecision: Double, rawRecall: Double) =>
  val metric = Judges.semanticF1[IO](mockJudge(rawPrecision, rawRecall, ""), threshold = 0.5)
  val score = metric.apply(example, prediction, trace = None).unsafeRunSync()
  val clampedP = math.max(0.0, math.min(1.0, rawPrecision))
  val clampedR = math.max(0.0, math.min(1.0, rawRecall))
  val expectedF1 = 2 * clampedP * clampedR / (clampedP + clampedR)
  score.value == expectedF1 && score.feedback.exists(_.contains("clamp"))
}
```

### Property: complete-and-grounded-eval-mode

**Invariant**: In evaluation mode, the score value equals the average of
completeness and groundedness, and feedback contains the reasoning.

**Generator strategy**: `genCompletenessGroundedness` (two Doubles in
Range.linearF 0.0 1.0); `genReasoning` (Gen.string).

```
forAll { (completeness: Double, groundedness: Double, reasoning: String) =>
  val metric = Judges.completeAndGrounded[IO](mockJudge(completeness, groundedness, reasoning), threshold = 0.66)
  val score = metric.apply(example, prediction, trace = None).unsafeRunSync()
  score.value == (completeness + groundedness) / 2 && score.feedback == Some(reasoning)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Judge schema defined in `structured-llm-test-models` | That module is test-only codegen; judges are production code | import audit (Ring 2) — `adk4s-eval` MUST NOT depend on `structured-llm-test-models` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| SemanticF1 returns F1 in eval mode | Requirement: SemanticF1 judge metric + Scenario: Evaluation mode | property test (semantic-f1-eval-mode) | JudgesSpec |
| SemanticF1 binarized in optimization mode | Requirement: SemanticF1 judge metric + Scenario: Optimization mode | property test (semantic-f1-optimization-mode-binarized) | JudgesSpec |
| CompleteAndGrounded returns average in eval mode | Requirement: CompleteAndGrounded judge metric + Scenario: Evaluation mode | property test (complete-and-grounded-eval-mode) | JudgesSpec |
| CompleteAndGrounded binarized in optimization mode | Requirement: CompleteAndGrounded judge metric + Scenario: Optimization mode | scenario test | JudgesSpec |
| Judge schemas in adk4s-eval, not test-models | Requirement: Judge schema definition strategy + Scenario: Schema compiles without codegen | import audit (Ring 2) + compile test (sbt adk4s-eval/compile without codegen plugin) | JudgesTypeContract |
| Judge parse failure → metric failure, not crash | Requirement: Judge parse failure surfaces as metric failure + Scenario: Unparseable judge completion | scenario test (mock returns garbled → EvalOutcome.Failed) | JudgesSpec |
| Out-of-range clamped + flagged | Requirement: Out-of-range precision/recall clamped + Scenarios | property test (out-of-range-clamped) | JudgesSpec |
| Constraint.check used for clamping | Requirement: Out-of-range precision/recall clamped | adversarial review (check that Constraint.check is called, not manual if/else) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Judges` | object | `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` | `semanticF1[F]`, `completeAndGrounded[F]` factory methods |
| `SemanticF1Judge` | Smithy structure (hand-written) | `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` | `precision: Double`, `recall: Double`, `reasoning: String` — `Schema.instance` definition |
| `CompleteAndGroundedJudge` | Smithy structure (hand-written) | `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` | `completeness: Double`, `groundedness: Double`, `reasoning: String` — `Schema.instance` definition |
| Judge prompt text | string constants | `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` | ported from DSPy's published SemanticF1 prompt shape as the starting text (⚠ MUST-CONFIRM — do not invent: source is DSPy's repo) |
