# Spec: Structured Test Framework

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `PromptTemplate[-I]` | trait | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `StructuredTest[I, A]` | case class | Test definition: name, template, input, schema, checks, asserts |
| `TestReport` | case class | Aggregated results: results, passed, failed, humanEvalRequired |
| `TestResult` | case class | Single test result: name, status, value, checks, error |
| `TestStatus` | enum | Passed / Failed / HumanEvalRequired |
| `runTests` | method | Runs a vector of StructuredTests against a StructuredLLM |

## ADDED Requirements

### Requirement: Structured test definitions

The system SHALL provide a `StructuredTest[I, A]` case class that defines a test case with a prompt template, input, schema, and validation checks.

**Given** a `StructuredTest` with a template, input, and checks
**When** the test is run against a `StructuredLLM`
**Then** the LLM is called with the rendered prompt, the response is parsed, and checks are evaluated

**Rationale**: A dedicated test framework enables CI/CD evaluation of structured output quality, which is a prerequisite for GEPA optimization.

#### Scenario: Passing test

**Given** a `StructuredTest` with a mock LLM returning valid output and checks that pass
**When** `runTests` is called
**Then** the `TestReport` has `passed=1, failed=0`

#### Scenario: Failing test — assert fails

**Given** a `StructuredTest` with a mock LLM returning output that fails an assert
**When** `runTests` is called
**Then** the `TestReport` has `passed=0, failed=1` with `TestStatus.Failed`

#### Scenario: Human eval required

**Given** a `StructuredTest` with checks that pass but no asserts
**When** `runTests` is called
**Then** the `TestReport` has `humanEvalRequired=1` (checks pass but human should verify)

### Requirement: Batch test execution

The system SHALL run a vector of `StructuredTest` instances against a `StructuredLLM` and aggregate results into a `TestReport`.

**Given** a vector of 10 `StructuredTest` instances
**When** `runTests(tests, llm)` is called
**Then** the result is a `TestReport` with aggregated counts (passed, failed, humanEvalRequired)

#### Scenario: Mixed results

**Given** 3 tests: 1 passing, 1 failing assert, 1 human-eval
**When** `runTests` is called
**Then** `TestReport(passed=1, failed=1, humanEvalRequired=1)`

## Properties (Ring 3)

### Property: Test report counts are consistent

**Invariant**: `passed + failed + humanEvalRequired == results.size` for any TestReport.

**Generator strategy**: `Gen` of test result lists with random statuses, classify by status-distribution.

```
forAll { (results: List[TestResult]) =>
  val report = TestReport(results.toVector)
  report.passed + report.failed + report.humanEvalRequired == results.size
}
```

### Property: Every test produces exactly one result

**Invariant**: For N tests, `runTests` produces exactly N results.

**Generator strategy**: `Gen` of test counts (1-20), classify by count.

```
forAll { (tests: Vector[StructuredTest[?, ?]]) =>
  val report = runTests(tests, mockLlm).unsafeRunSync()
  report.results.size == tests.size
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Test definition structure | Requirement 1 | Scenario test | TestFrameworkSpec |
| Batch execution aggregates | Requirement 2 | Hedgehog property | TestFrameworkSpec |
| Counts consistent | Property 1 | Hedgehog property | TestFrameworkSpec |
| One result per test | Property 2 | Hedgehog property | TestFrameworkSpec |
