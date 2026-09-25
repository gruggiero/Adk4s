# Spec: Gate Event Compatibility

The gate is invoked by three different harnesses, each using its own event vocabulary. The
predecessor dispatches the names it recognises and routes everything else to the context
injection tier. The port rejects every unrecognised name with an error status, which turns
a vocabulary mismatch into a gate that does not run.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The gate is the last seam swapped and the only blocking hook. This spec restores the predecessor's dispatch behaviour at that seam. No protocol action changes. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `GateEvent` | enum (six tier events) | `org.sinemenda.probatio.core` |
| `GateDecision` | enum | `org.sinemenda.probatio.core` |
| `GatePayload` | final case class | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `BannerEngine` | object | `org.sinemenda.probatio.core` |
| `CliError` | sealed abstract class | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `EventDispatch` | enum (`Tier(GateEvent)`, `Injection(suppliedName)`) | The total classification of a supplied event name. `Injection` carries the name that was supplied, so a vocabulary mismatch stays visible in diagnostics instead of vanishing into a default branch. |

### Type-Widening Impact

No public type is widened. `GateEvent` keeps its six variants; `EventDispatch` is a new
closed type wrapping it. The event-name parse changes from a total function into the
six-variant type to a total function into `EventDispatch`, so the parse can no longer
fail — every caller of the old parse that handled a failure case must be rewritten, and
those call sites are enumerated in Implementation Anchors.

## ADDED Requirements

### Requirement: A recognised event name dispatches to its tier

Each of the six recognised event names SHALL dispatch to its corresponding tier, and no
recognised name MAY be routed to the injection tier.

**Given** a supplied event name that matches one of the six recognised names
**When** the gate dispatches
**Then** the corresponding tier runs

#### Scenario: Happy path — each recognised name reaches its tier

**Given** each of the six recognised event names in turn
**When** the gate dispatches
**Then** each reaches its own tier and no two names reach the same tier

#### Scenario: Adversarial — a recognised name is not absorbed by the injection tier

**Given** the recognised name for the pre-execution tier
**When** the gate dispatches
**Then** the pre-execution tier runs and the injection tier does not

### Requirement: An unrecognised event name routes to the injection tier

An event name that is not one of the six recognised names SHALL route to the context
injection tier and terminate with the clean status, and it MUST NOT terminate with an
error status.

**Given** a supplied event name that is not one of the six recognised names
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

**Rationale**: the predecessor behaves this way, and the harness adapters depend on it —
one harness names the prompt event `user-prompt-submit` while the gate's own vocabulary
calls it `prompt-submit`. Under the port that name produces an error status, which the
harness treats as a hook fault: the gate does not run at all. Restoring the fallback keeps
the vocabulary mismatch survivable while leaving it visible in the diagnostic output.

#### Scenario: Happy path — the alternate prompt-event name injects context

**Given** the alternate harness name for the prompt event
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

#### Scenario: Adversarial — an arbitrary unrecognised name does not error

**Given** an event name that no harness uses and that matches none of the six
**When** the gate dispatches
**Then** the process terminates with the clean status, not with an error status

#### Scenario: Edge case — an empty event name routes to the injection tier

**Given** an empty event name
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

### Requirement: The supplied name survives into the diagnostic output

The diagnostic output SHALL name the supplied value whenever an event name routes to the
injection tier because it was not recognised, and the fallback MUST NOT be silent.

**Given** an unrecognised event name routed to the injection tier
**When** the diagnostic output is produced
**Then** it states that the supplied name was not recognised and reproduces that name

**Rationale**: the predecessor's fallback is silent, which is why the vocabulary drift
between the adapters and the gate went unnoticed until it was measured. Restoring the
fallback without restoring the silence is not a verdict change — the tier that runs and
the exit status are unchanged; only the diagnostic gains a line.

#### Scenario: Happy path — an unrecognised name is named in the diagnostic

**Given** an unrecognised event name
**When** the gate runs
**Then** the diagnostic output contains that name

#### Scenario: Adversarial — a recognised name produces no unrecognised-name diagnostic

**Given** a recognised event name
**When** the gate runs
**Then** the diagnostic output contains no unrecognised-name line

## MUST-CONFIRM — the harness event vocabularies

The names each harness uses for its own hook events are **not** this repository's to
define. They are set by each harness's hook API, and this spec must not invent them.

**Authoritative source**: each harness's own hook documentation. **In-repository record**:
the three shipped adapter configurations under the schema's hook-adapter directory, and
the executable envelope contract under the schema's tool directory — these record the
names as observed, and are the artifacts the scenarios below assert against. Any name not
present in one of those records MUST be confirmed against the harness's documentation
before it is written into an implementation, not guessed from the pattern of the others.

This spec asserts only that a recognised name reaches its tier, that an unrecognised one
falls back, and that the envelope carries whatever name the adapter records — never that a
particular spelling is the right one.

### Requirement: The payload envelope reports the harness event name

The structured output envelope SHALL carry the harness's own name for the event, and it
MUST NOT carry the gate's internal name.

**Given** a gate invocation producing a structured envelope
**When** the envelope is emitted
**Then** its event field holds the harness name for that event

#### Scenario: Happy path — the prompt event emits the harness name

**Given** the prompt event and the structured output format
**When** the envelope is emitted
**Then** its event field holds the harness's prompt-event name

#### Scenario: Adversarial — the envelope never carries an internal enum name

**Given** each of the six recognised events in turn, with the structured output format
**When** each envelope is emitted
**Then** no envelope's event field holds the gate's internal name for that event

## Properties (Ring 3)

### Property: event-dispatch-is-total

**Invariant**: for every string, the event-name classification yields either a tier or the
injection fallback — it is total, and no input produces a failure.

**Generator strategy**: `genEventName` — constructive over a union of three closed
alphabets: the six recognised names, the known harness alternates, and arbitrary strings
drawn from `Gen.string` with lengths 0–40 over a mixed alphabet including hyphens and
Unicode. Union rather than filter, so the recognised names are hit by construction rather
than by chance. Edge cases: empty string, a recognised name with differing case, a
recognised name with surrounding whitespace, a very long name.

```
property("event dispatch is total") {
  for {
    name <- genEventName.forAll
    d     = classify(name)
  } yield Result.assert(d.isTier || d.isInjection)
}
```

### Property: recognised-names-never-fall-back

**Invariant**: for every one of the six recognised names, classification yields the tier
for that name and never the injection fallback; and the mapping from name to tier is
injective.

**Generator strategy**: enumerated, not sampled — the domain is the closed six-name set,
so the property loops over the full enumeration. This finite-domain limit is stated rather
than presented as sampled coverage.

```
property("recognised names never fall back") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    recognisedNames.forall(n => classify(n).isTier) &&
    recognisedNames.map(classify).distinct.length == recognisedNames.length
  )
}
```

### Property: unrecognised-names-exit-clean

**Invariant**: for every name outside the recognised set, the process terminates with the
clean status and the diagnostic output contains the supplied name.

**Generator strategy**: `genUnrecognisedName` — constructive: draws from `genEventName`
and maps recognised names onto a distinct unrecognised prefix, so the generator produces
unrecognised names by construction rather than by discarding recognised ones.

```
property("unrecognised names exit clean and are named") {
  for {
    name <- genUnrecognisedName.forAll
    run   = gate(name)
  } yield Result.assert(run.exitStatus.isClean && run.diagnostics.contains(name))
}
```

### Property: parity-with-predecessor-on-event-dispatch

**Invariant**: for every generated event name, the ported gate's exit status equals the
predecessor's for the same name and repository, with the predecessor run as the model.

**Generator strategy**: `genEventName` as above, crossed with a repository fixture drawn
from {a repository carrying the workflow, a repository without it}. Model-based: the
predecessor script runs as a subprocess. Determinism: no clock or subprocess timing is
observed — only the exit status and the presence of output.

```
property("event dispatch agrees with the predecessor") {
  for {
    name    <- genEventName.forAll
    fixture <- genRepoFixture.forAll
    ported   = runPorted(name, fixture)
    model    = runPredecessor(name, fixture)
  } yield Result.assert(ported.exitStatus == model.exitStatus)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| An event classification that can fail | A failable parse is what produces the error status this spec removes | `assertDoesNotCompile("val e: Option[EventDispatch] = classify(\"x\")")` — the classification returns `EventDispatch`, not an optional or an either |
| An injection dispatch built without the supplied name | A fallback that discards what it fell back from is the silent drift this spec keeps visible | `assertDoesNotCompile("EventDispatch.Injection()")` — the variant requires the supplied name |

## Formal Contracts (Ring 6)

The dispatch classification is a total function over a closed name set and is already
mirrored by the dispatch kernel; this spec extends that mirror with totality and
injectivity.

### Contract: classify

```
def classify(name: String): EventDispatch = {
  ...
} ensuring { result =>
  // totality: every input lands in exactly one arm
  (result.isTier || result.isInjection) &&
  // recognised names land on a tier
  (recognisedNames.contains(name) == result.isTier) &&
  // the fallback preserves what it fell back from
  (result.isInjection ==> result.suppliedName == name)
}
```

A bridge property binds the shipped classification to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Each recognised name reaches its own tier | Requirement: A recognised event name dispatches to its tier + Scenario: Happy path — each recognised name reaches its tier | scenario test | `GateEventSpec` |
| A recognised name is not absorbed by the injection tier | Requirement: A recognised event name dispatches to its tier + Scenario: Adversarial — a recognised name is not absorbed by the injection tier | scenario test | `GateEventSpec` |
| Recognised names never fall back, and the mapping is injective | Property: recognised-names-never-fall-back | Hedgehog property (enumerated finite domain) | `GateEventSpec` |
| The alternate prompt-event name injects context | Requirement: An unrecognised event name routes to the injection tier + Scenario: Happy path — the alternate prompt-event name injects context | bats oracle | `workflow-hygiene.bats` |
| An arbitrary unrecognised name does not error | Requirement: An unrecognised event name routes to the injection tier + Scenario: Adversarial — an arbitrary unrecognised name does not error | scenario test + bats oracle | `GateEventSpec`, `workflow-hygiene.bats` |
| An empty event name routes to the injection tier | Requirement: An unrecognised event name routes to the injection tier + Scenario: Edge case — an empty event name routes to the injection tier | scenario test | `GateEventSpec` |
| Unrecognised names exit clean and are named | Property: unrecognised-names-exit-clean | Hedgehog property | `GateEventSpec` |
| Dispatch is total over all strings | Property: event-dispatch-is-total | Hedgehog property | `GateEventSpec` |
| A failable classification is unconstructible | Compile-Negative: An event classification that can fail | compile-negative test | `GateEventCompletenessTypeContract` |
| An injection dispatch without the supplied name is unconstructible | Compile-Negative: An injection dispatch built without the supplied name | compile-negative test | `GateEventCompletenessTypeContract` |
| An unrecognised name appears in the diagnostic | Requirement: The supplied name survives into the diagnostic output + Scenario: Happy path — an unrecognised name is named in the diagnostic | scenario test | `GateEventSpec` |
| A recognised name produces no unrecognised-name line | Requirement: The supplied name survives into the diagnostic output + Scenario: Adversarial — a recognised name produces no unrecognised-name diagnostic | scenario test | `GateEventSpec` |
| The envelope carries the harness event name | Requirement: The payload envelope reports the harness event name + Scenario: Happy path — the prompt event emits the harness name | wire-contract check (executable envelope contract) | `gate-payload.bats` |
| No envelope carries an internal enum name | Requirement: The payload envelope reports the harness event name + Scenario: Adversarial — the envelope never carries an internal enum name | wire-contract check over all six events × both formats | `gate-payload.bats` |
| The ported dispatch agrees with the predecessor | Property: parity-with-predecessor-on-event-dispatch | Hedgehog model-based property (predecessor run as a subprocess) | `SubprocessConformanceSpec` |
| Classification totality and fallback fidelity are verified | Invariant: classification is total and preserves the supplied name | Stainless verification + bridge property test | `DispatchKernel` (extended) + `GateBridgeSpec` |
| The suite file reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `workflow-hygiene.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `eventFromString` | private method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala:327` | The defect site: total over six names, `None` otherwise, mapped to an error status by the caller. Becomes total into `EventDispatch`. |
| `eventToString` | private method | same file, `:454` | The reverse mapping; unchanged |
| `EventDispatch` | new enum | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | New; lives in core because it is a decision, not I/O |
| `GateCmd.run` | object method | `.../cli/SubcommandEntrypoints.scala:34` | The caller that currently maps `None` to an error status; rewritten to dispatch on `EventDispatch` |
| `gate.sh.predecessor.bak` | predecessor implementation | `openspec/schemas/verified-scala3/hooks/` | The model: five tier names dispatched at lines 388, 1016, 1117, 1373, 1573; everything else falls through to injection |
| `claude.settings.json`, `devin.hooks.v1.json`, `verified-scala3-gate.ts` | harness adapters | `openspec/schemas/verified-scala3/hooks/adapters/` | The source of the vocabulary mismatch; not edited by this spec — the gate accommodates them |
| `workflow-hygiene.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Tests 5, 6, 9, 10 are the regression, all four caused by the rejected alternate name |
| `DispatchKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/DispatchKernel.scala` | Extended with the totality contract |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
