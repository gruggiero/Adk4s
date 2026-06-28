# Spec: [Capability Name]

<!-- This is a DELTA spec. Use ## ADDED Requirements for new content.
     Use ## MODIFIED Requirements to change existing requirements.
     Use ## REMOVED Requirements to delete requirements.
     Use ## RENAMED Requirements to rename requirement headers.

     Each spec is implemented and verified INDEPENDENTLY through the full
     ring pipeline. Keep specs self-contained — one capability per spec.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by the
       Given/When/Then clauses
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words ("valid", "fast", "reasonable", "correct") without a
       concrete definition next to them
     - Any "unreachable" claim needs a type-level proof or explicit runtime check
     - Enum/GADT extensions must state how existing pattern matches behave -->

## Concepts Used (from inventory)

<!-- Reference existing concepts from concept-inventory.md.
     These MUST already exist — do NOT invent concepts here.
     The implementation will import these, not recreate them. -->

| Concept | Kind | Package |
|---------|------|---------|
| <!-- e.g. AccountId --> | <!-- e.g. opaque type --> | <!-- e.g. domain --> |
| <!-- e.g. Balance --> | <!-- e.g. opaque type --> | <!-- e.g. domain --> |

## Concepts Introduced (new)

<!-- New types, traits, enums, or IDL operations this spec creates.
     These are COMMITMENTS — the implementation must create exactly these
     and NOTHING MORE (verified by the concept delta check at Step 12).
     After implementation, these will be added to concept-inventory.md. -->

| Concept | Kind | Description |
|---------|------|-------------|
| <!-- e.g. TransferResult --> | <!-- e.g. case class --> | <!-- e.g. Holds from/to balances after transfer --> |

## ADDED Requirements

### Requirement: [Requirement Name]

The system SHALL [normative statement of the obligation — one sentence; use
SHALL/MUST. This line is required by `openspec validate --strict`; the
Given/When/Then clauses below refine it into testable form].

**Given** [precondition — initial state or context]
**When** [trigger — action or event]
**Then** [outcome — observable result or state change]

**Rationale**: <!-- Why this requirement exists -->

#### Scenario: [Happy path]

**Given** [specific setup]
**When** [specific action]
**Then** [specific assertion]

#### Scenario: [Error path]

**Given** [specific setup leading to failure]
**When** [specific action]
**Then** [specific error result]

#### Scenario: [Edge case]

**Given** [boundary condition setup]
**When** [action at the boundary]
**Then** [expected boundary behavior]

<!-- Add more requirements and scenarios as needed.
     Each requirement should have at least one happy path
     and one error/edge case scenario. -->

## Properties (Ring 3)

<!-- ScalaCheck properties that the implementation MUST satisfy.
     Write as English invariants AND forAll pseudocode.
     These become the test harness BEFORE implementation (Step 2 test oracle) —
     they are the SPEC, not the tests. The implementation must satisfy them,
     not the other way around.

     EVERY property declares its generator strategy:
     - which Gen it uses (existing from inventory, or new)
     - constructive (preferred) or filtered (avoid heavy suchThat — discards
       weaken coverage)
     - which edge cases the generator covers
     - classification labels (ScalaCheck classify/collect) used to make
       case coverage visible -->

### Property: [Invariant Name]

**Invariant**: [English description of what must always be true]

**Generator strategy**: [Gen name — constructive/filtered — edge cases covered — classify labels]

```
forAll { (param1: Type1, param2: Type2) =>
  [predicate that must hold for all values]
}
```

### Property: [Another Invariant]

**Invariant**: [English description]

**Generator strategy**: [...]

```
forAll { (param: Type) =>
  [predicate]
}
```

<!-- Common property patterns:
     - Conservation: total money is preserved across operations
     - Monotonicity: balance increases on deposit, decreases on withdraw
     - Roundtrip: decode(encode(x)) == Right(x) for all serialization
     - Idempotence: repeated reads return same result
     - Error totality: every error variant is reachable by some input
     - Commutativity: order-independent operations produce same result

     MODEL-BASED patterns (include where the detected stack makes them relevant):
     - Event sourcing: for all valid command sequences, replaying persisted
       events yields the same state as live command handling
     - Messaging ingestion: malformed payloads → explicit errors; commit only
       after successful processing; duplicate/out-of-order behavior as specified
     - DSL/evaluator: desugaring laws (e.g. IN ⇒ OR of equalities,
       BETWEEN ⇒ GTE AND LTE); typechecker total over decoded raw expressions;
       evaluator agrees with the reference semantics; missing values propagate
       consistently
     - Serialization: round-trip per wire format; old persisted fixtures decode;
       snapshot compatibility -->

## Compile-Negative Obligations

<!-- When this spec says something must NOT be constructible, list it here.
     Each row becomes an assertDoesNotCompile test in the test oracle
     (use the detected test framework's facility).
     Delete this section if nothing is type-level forbidden. -->

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| <!-- e.g. Expr.Count(c, b, body, CmpOp.IN, n) --> | <!-- IN is not a count-threshold op --> | <!-- assertDoesNotCompile("...") --> |

## Formal Contracts (Ring 6)

<!-- Stainless require/ensuring contracts for pure functions.
     Only include if the proposal's verification strategy checks Ring 6.
     These annotate the implementation during the apply phase.

     Delete this section if Ring 6 does not apply. -->

### Contract: [Function Name]

**Precondition** (`require`): [what must be true before the function runs]
**Postcondition** (`ensuring`): [what must be true after the function returns]

```scala
def functionName(param: Type): ReturnType = {
  require(/* precondition */)
  // implementation
}.ensuring(result => /* postcondition */)
```

## Temporal Properties (Ring 9)

<!-- EARS natural language patterns for runtime monitoring.
     Only include if the proposal's verification strategy checks Ring 9
     AND the telemetry stack is detected (capability-profile.md).
     Every temporal property MUST name its trigger and response events.

     EARS patterns:
     - "When <trigger>, the system shall <response>"
     - "While <condition>, the system shall <behavior>"
     - "After <event>, until <termination>, the system shall <constraint>"

     Delete this section if Ring 9 does not apply. -->

### Temporal: [Property Name]

**EARS**: "When [trigger], the system shall [response]"
**Trigger event**: [event/span name]
**Response event**: [event/span name]

**Monitor sketch**:
```
always {
  case [TriggerEvent](params) =>
    hot {
      case [ExpectedResponse](params) => ok
    }
}
```

## Proof Obligations

<!-- MANDATORY. Map EVERY requirement, scenario, invariant, and type
     constraint above to its enforcement mechanism. No spec enters
     implementation while any obligation lacks a declared mechanism.

     Mechanisms (strongest first):
     - type system (unrepresentable — cite the type + compile-negative test)
     - smart constructor (cite constructor + rejection property)
     - property test / scenario test (cite the planned test)
     - compatibility fixture test (Ring 4)
     - static rule (Scalafix/WartRemover — cite the rule)
     - formal contract (Ring 6) / model check (Ring 7)
     - runtime monitor (Ring 9)
     - adversarial review (Ring 8)
     - manual review (allowed, but must be explicit) -->

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| <!-- e.g. CmpOp.IN never maps to OrderOp --> | <!-- requirement/scenario name --> | <!-- type split + compile-negative test --> | <!-- EvalSpec, TypeContract --> |
| <!-- e.g. Missing attribute gives Tri.U --> | <!-- scenario --> | <!-- ScalaCheck property --> | <!-- EvalSpec --> |
| <!-- e.g. Replay equals live transition --> | <!-- invariant --> | <!-- model/property test --> | <!-- ReplaySpec --> |
| <!-- e.g. Invalid ID unrepresentable --> | <!-- type strategy --> | <!-- opaque type + constructor property --> | <!-- domain spec --> |
