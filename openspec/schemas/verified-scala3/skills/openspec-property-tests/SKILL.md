---
name: openspec-property-tests
description: >
  Build the Ring 3 test oracle for an OpenSpec change: derive invariants,
  properties, and model-based tests from the specs (NOT from the
  implementation), then write property tests using the project's DETECTED
  test framework (ScalaTest+ScalaCheck, munit+ScalaCheck, or munit+Hedgehog).
  Runs BEFORE implementation so tests are an oracle derived from the spec,
  not a mirror of generated code. Ends with the ORACLE POLARITY run (red /
  green-by-design). Reads implementation-order.md and
  implementation-progress.md (tasks.md is derived output, not an input).
metadata:
  generatedBy: verified-scala3-schema/7.0.0
---

Build a spec-derived test oracle: analyse an OpenSpec change's artifacts to
surface testable properties, then write property tests for the detected
framework.

**Input**: Optionally specify a change name and a spec name. If omitted, infer
from context or prompt the user.

**Timing**: In the verified-scala3 workflow this skill runs at Step 2 of the
apply phase — AFTER the typed contract is approved (or, for combined-tier
specs, after it compiles) and BEFORE any implementation exists. The tests
must compile against the typed contract; the ORACLE POLARITY run (Step 8
below) then proves each test can fail. If invoked after implementation
(retro-fit), derive properties from the spec text only — do not read
implementation bodies to decide what to assert.

---

## Steps

### 1. Select the change and spec

If a name is provided, use it. Otherwise:
- Infer from conversation context if the user mentioned a change
- Auto-select if only one active change exists
- If ambiguous, run `openspec list --json` and ask the user

Announce: "Building test oracle for change: `<name>`, spec: `<spec>`"

---

### 2. Read all change artifacts

Read in order (each informs the next):

1. `openspec/changes/<name>/proposal.md` — what the change is for
2. `openspec/capability-profile.md` — the DETECTED stack:
   test framework, property library (with its coverage-assertion and
   seed-fixing facilities), deterministic concurrency test kit, actor test
   kits, module layout, exact compile/test commands
3. `openspec/changes/<name>/design.md` — domain types, invariants,
   invalid-state-prevention strategy, compatibility story, non-goals
4. `openspec/changes/<name>/implementation-order.md` and
   `implementation-progress.md` — which spec is current, its gate tier, and
   what it depends on (tasks.md is regenerated from progress — not an input)
5. `openspec/concept-inventory.md` — existing generators to
   REUSE, and exact packages for imports
6. The current spec `openspec/changes/<name>/specs/<capability>/spec.md` —
   the PRIMARY source: every **Requirement**, **Scenario**, **Property**
   (with its declared generator strategy), **Compile-Negative Obligation**,
   and **Proof Obligation**
7. The approved typed contract file (in the module's test sources) — the
   signatures the tests compile against

Do NOT read implementation method bodies to decide assertions — the spec is
the oracle.

---

### 3. Determine the test framework — from the profile, never assumed

Read the testing section of `openspec/capability-profile.md`:

**If ScalaTest + ScalaCheck (scalatestplus)**:

```scala
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

final class <Name>Properties extends AnyFunSuite with ScalaCheckPropertyChecks {
  test("<scenario name>") { ... }                  // scenarios & boundaries
  test("<property name>") { forAll(gen) { x => ... } }  // properties
}
```
Compile-negative obligations use ScalaTest's `assertDoesNotCompile("...")`.

**If munit + munit-scalacheck**:

```scala
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

final class <Name>Properties extends ScalaCheckSuite {
  property("<property name>") { forAll(gen) { x => ... } }
  test("<boundary>") { ... }
}
```

**If munit + Hedgehog (hedgehog-munit)**:

```scala
import hedgehog.*
import hedgehog.munit.HedgehogSuite

final class <Name>Properties extends HedgehogSuite {
  property("<property name>") {
    for x <- genFoo.forAll yield <Result assertion>
  }
  test("<boundary>") { ... }
}
```
Hedgehog has integrated shrinking, explicit `Range` sizing, NO Arbitrary
typeclass, and `cover` (a coverage ASSERTION — fails when a label's
percentage is unmet). Compile-negative obligations use munit's
`compileErrors("...")`.

**If the property library is missing from the build**: report it and confirm
with the user before adding the dependency matching the detected framework.
Never introduce a second test framework.

**If the spec involves actors** (per the detected stack): use the detected
test kits — `ActorTestKit`/`BehaviorTestKit` for command handling,
`TestProbe[T]` for message-delivery assertions, `PersistenceTestKit` for
event-sourcing scenarios. There is no valid reason to defer an
actor-behavior scenario to integration testing when these are available.

**If the spec involves concurrency, timeouts, cancellation, or
interruption**: use the detected deterministic test kit (e.g. cats-effect
`TestControl`) — assert deterministic observables (ordering, final state,
emitted-event set), never wall-clock timing; `sleep`-and-hope tests are
forbidden. TAG these tests with the convention recorded in the profile so
Ring 3 can repeat-run them (flake discipline).

---

### 4. Extract testable properties from the specs

Work through the spec systematically. For each **Scenario**, **Requirement**,
and declared **Property**, identify its categories:

#### A. Validation totality
Every validator is total — never throws, always returns `Left` or `Right`.
```
forAll(anyInput) { x => validate(x) match { case Left(_) | Right(_) => true } }
```

#### B. Valid-input acceptance
```
forAll(validInputGen) { x => validate(x).isRight }
```

#### C. Invalid-input rejection
```
forAll(invalidInputGen) { x => validate(x).isLeft }
```

#### D. Boundary exactness
Exact boundary values in `test` blocks (not `forAll`): `validate(0).isRight`,
`validate(-1).isLeft` for non-negative; etc.

#### E. Error message structure
```
forAll(invalidInputGen) { x =>
  validate(x).left.forall(e => e.fieldName.nonEmpty && e.detail.nonEmpty)
}
```

#### F. Optional promotion consistency
`optional(None) == Right(None)`;
`optional(Some(x)) == required(x).map(Some(_))`.

#### G. Round-trip laws — per DETECTED wire format
```
forAll(validDomainValueGen) { x => decode(encode(x)) == Right(x) }
```
JSON, protobuf, Smithy codec — one law per format the spec touches. Plus
old-fixture decoding for Ring 4 when the spec touches persisted data:
`decode(oldFixtureBytes) == Right(expectedValue)`.

#### H. Semantic distinctness / compile-negative
Forbidden constructions from the spec's Compile-Negative Obligations become
compile-negative tests (`assertDoesNotCompile` / `compileErrors`) or
documented compile-time guarantees citing the opaque type definition.

#### I. Model-based properties — where the detected stack makes them relevant

**Event sourcing** (if a persistence/event-sourcing stack is detected):
```
forAll(validCommandSequenceGen) { cmds =>
  stateAfterLiveHandling(cmds) == stateAfterReplaying(persistedEvents(cmds))
}
```

**Messaging ingestion** (if Kafka or similar is detected):
- malformed payloads produce explicit errors (not silence)
- commit/ack occurs only after successful processing
- duplicate and out-of-order events behave exactly as the spec states

**DSL/evaluator logic** (if the spec touches a DSL):
- desugaring laws (e.g. `IN` ⇒ OR of equalities; `BETWEEN` ⇒ `GTE AND LTE`)
- typechecker is total over decoded raw expressions
- evaluator agrees with the reference semantics
- missing values propagate consistently

---

### 5. Build generators — constructive, reviewed, coverage-visible

For each property, define the generators. REUSE generators from
openspec/concept-inventory.md before creating new ones.

**Rules:**
- Prefer CONSTRUCTIVE generators (build valid values directly) over
  filtering — heavy filtering discards cases and silently weakens coverage.
- Every generator must cover the edge cases its property needs (empty,
  boundary, missing, maximal).
- Make case coverage VISIBLE with the framework's facility — and where it
  supports coverage ASSERTIONS, use them for conditioned properties:
  - Hedgehog: `cover(percentage, "label", condition)` — FAILS when unmet
  - ScalaCheck: `classify`/`collect` — informational; note the coverage in
    the generator table
- GENERATOR FAITHFULNESS: once the oracle is approved, generators are part
  of it. Narrowing a generator's domain later (filter, tightened Range,
  dropped edge case) is oracle tampering and needs human re-approval.

**Generator review table** — produce before writing tests:

| Property | Generator | Constructive? | Edge cases included? | Coverage labels/assertions |
|----------|-----------|---------------|----------------------|----------------------------|
| Missing propagates to U | genExprWithMissingPath | Yes | Yes | missing-depth ≥ 20% (cover) |
| JSON round-trip | genValidDomainValue | Yes | Yes | empty, large |

For domain-specific generators, compose via the same smart constructors the
typed contract declares.

---

### 6. Determine test file location and name

Use the module that owns the implementation (module layout from
openspec/capability-profile.md), test sources of that module. Name the file
`<Capability>Properties.scala` (e.g. `EligibilityEvalProperties.scala`);
model-based suites may be separate (e.g. `ReplaySpec.scala`,
`CodecCompatSpec.scala`).

---

### 7. Show the property inventory and oracle tables before writing

Display a numbered property list with the spec source of each, the generator
review table, and the planned coverage cross-references:

```
## Property Inventory: <change-name> / <spec>

1. [totality] validate never throws for any String input
   Source: <spec> — "returns explicit errors instead of throwing"
2. [model/replay] replay(persistedEvents) == liveState
   Source: <spec> — event-sourcing invariant
...

## Coverage Cross-References (planned)
A) | Spec Heading | Test Name | Status |
B) | Concept | Source | Test Reference | Status |
C) | Proof Obligation | Enforcement | Test | Status |
```

Every Requirement, Scenario, declared Property, concept (Used + Introduced),
and test-enforced Proof Obligation must have a row.

---

### 8. Write the test file(s), then run the ORACLE POLARITY check

**Writing rules:**

- Every test traceable to a spec heading:
  `// spec: <spec-name> — Scenario: <heading>` (or `— Property: <name>`)
- One test per Scenario, named after the scenario heading
- One property per declared invariant, with its declared generator strategy
- Boundary tests in `test` blocks with exact values, not `forAll`
- Compile-negative obligations as `assertDoesNotCompile` / `compileErrors`
- Round-trip properties test both directions when both exist
- Do not invent properties the spec does not state
- Do not remove existing unit tests — property tests supplement them
- The file must COMPILE against the typed contract:
  `sbt <module>/Test/compile`

**ORACLE POLARITY** — prove the oracle CAN fail. Run the new suites once
NOW (before any implementation) and classify every new test:

- **RED** — asserts new behavior; MUST fail at this run. A red-classified
  test that passes pre-implementation is a broken oracle (vacuous,
  tautological, or asserting already-existing behavior) — fix it before the
  gate; it is currently proving nothing.
- **GREEN-BY-DESIGN** — behavior-preservation/regression tests for
  refactors; MUST pass at this run (against the OLD code) and still pass
  after implementation. Their pre-implementation pass IS their evidential
  value — record it.

A test that cannot be classified does not enter the oracle. Produce the
polarity table: `| Test | Polarity | Pre-impl result |`.

---

### 9. Output summary — this is the test-oracle human gate

```
## Test Oracle Written

**Change:** <name>  **Spec:** <spec>
**Test file(s):** <paths>
**Framework:** <detected — e.g. munit + Hedgehog>

### Properties (N total)
- categories breakdown (totality/acceptance/rejection/boundary/error/
  optional/round-trip/compile-negative/model-based/concurrency)

### Oracle polarity
| Test | Polarity | Pre-impl result |
(all RED failing, all GREEN-BY-DESIGN passing — anything else is unresolved)

### Coverage
- Requirements/Scenarios covered: X/X
- Concepts referenced in executable code: Y/Y
- Test-enforced proof obligations: Z/Z

Compiles: ✓ sbt <module>/Test/compile
Run later with: sbt <module>/test
```

Ask the user to confirm or trim. For combined-tier specs, present the
approved typed contract alongside this summary — it is the single gate.

---

## Guardrails

- **Spec first, implementation never** — properties trace to spec
  requirements/design decisions; when retro-fitting, do not derive
  assertions from implementation bodies
- **Detected framework only** — never generate munit tests in a ScalaTest
  project or vice versa; never add a second framework
- **Show the inventory and get confirmation before implementing** — the
  oracle is an approved artifact; changing it later needs re-approval
- **Generators are part of the oracle** — narrowing one post-approval is
  tampering
- **Every RED test must be seen to fail before implementation** — that is
  the polarity check, not optional
- **Constructive generators; coverage visible (asserted where supported)**
- **Boundary tests in `test` blocks, not property blocks**
- **Concurrency scenarios use the deterministic test kit, tagged for
  repeat runs — never wall-clock sleeps**
- **Actor scenarios use the detected test kits — never deferred to
  integration testing**
- **tasks.md is derived from implementation-progress.md** — read progress
  from implementation-order.md / implementation-progress.md
