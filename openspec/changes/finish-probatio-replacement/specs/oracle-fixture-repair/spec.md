# Spec: Oracle Fixture Repair

Seventeen acceptance tests fail identically under both implementations, and have been
carried as "pre-existing" across three changes without being re-established. Eleven were
diagnosed on 2026-09-25: they are stale fixtures. The pre-execution locks work, and the
tests never supply the tool name a harness supplies. The other six, in the correctness-
verdict suite, are not yet root-caused.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | Every oracle edit this spec makes goes through the protocol's sanctioned-modification rule, introduced by `oracle-independence`. This spec is that rule's first user. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `GateDecisions` | object | `org.sinemenda.probatio.core` |
| `GateDecision` | enum | `org.sinemenda.probatio.core` |
| `ChainState` | object | `org.sinemenda.probatio.core` |
| `PrePassOutcome` | enum | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class, private constructor | `org.sinemenda.probatio.core` |
| `HarnessPayload` | final case class | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ToolNameSource` | enum (`Supplied(name)`, `Absent`) | Whether the pre-execution tier received a tool name. `Absent` keeps the predecessor's allow decision and makes the absence visible in diagnostics. |

### Type-Widening Impact

No public type is widened. `ToolNameSource` is new; the pre-execution tier's tool-name
read, which today yields an empty string on absence, yields it instead.

## ADDED Requirements

### Requirement: The lock fixtures supply the tool name a harness supplies

A lock test SHALL supply the tool name, as every harness adapter does — every test in the
suite files oracle-ordering-lock.bats and human-grant-lock.bats that exercises the
pre-execution tier — and a lock test MUST NOT pass or fail because a tool name was absent.

**Given** a lock test in either of those suite files that exercises the pre-execution tier
**When** it invokes the gate
**Then** it supplies the name of the edit tool it models, and the assertion observes the
lock's decision

**Rationale**: all eleven failures in these two files share one cause, measured on
2026-09-25. The gate treats an absent tool name as read-only and allows. With a name
supplied — `Edit`, `Write`, `MultiEdit`, or pi's lowercase `edit`, `write`, `bash` — both
locks block, in both implementations.

#### Scenario: Happy path — a production edit in the oracle phase is blocked

**Given** a change whose spec is in the oracle phase, and a production-source edit with the
tool name supplied
**When** the pre-execution tier decides
**Then** the edit is blocked and the reason names the spec and the oracle phase

#### Scenario: Happy path — a spec start without a grant is blocked

**Given** an edit that starts the next spec with no grant for the prior spec, and the tool
name supplied
**When** the pre-execution tier decides
**Then** the edit is blocked

#### Scenario: Adversarial — a read-only tool is not blocked

**Given** the same production path in the oracle phase and a read-only tool name
**When** the pre-execution tier decides
**Then** it allows

### Requirement: An absent tool name keeps parity and is stated

The pre-execution tier SHALL allow when no tool name is supplied, as the predecessor does,
and it MUST NOT allow silently — the diagnostic output states that the tool name was
absent.

**Given** a pre-execution call with no tool name
**When** the tier decides
**Then** it allows, and the diagnostic output states that no tool name was supplied

**Rationale**: an absent tool name failing open is predecessor behaviour, and changing it
would alter a verdict. Making it visible is not a verdict change. It is also the only
signal that a harness is delivering its payload in a shape the gate cannot read.

#### Scenario: Happy path — an absent name allows with a stated reason

**Given** a pre-execution call on a production path with no tool name
**When** the tier decides
**Then** it allows and the diagnostic names the absent tool name

#### Scenario: Adversarial — a supplied name produces no absence diagnostic

**Given** a pre-execution call with a read-only tool name
**When** the tier decides
**Then** it allows and no absent-name diagnostic appears

### MUST-CONFIRM — the Devin payload's tool-name field

The Devin adapter invokes the gate without a tool-name flag and relies on the hook payload
to carry the tool name. The field Devin uses is **set by Devin's hook API, not by this
repository**, and must not be guessed from the Claude Code field.

**Authoritative source**: Devin's hook documentation. **In-repository record**: the Devin
adapter configuration under the schema's hook-adapter directory, and the gate's payload
reader.

### Requirement: The Devin adapter delivers the tool name

The Devin adapter SHALL deliver the tool name to the pre-execution tier in a field the gate
reads, and the delivery MUST be confirmed against Devin's documentation before this spec's
checkpoint.

**Given** a Devin pre-execution hook invocation for an edit
**When** the gate reads the payload
**Then** it obtains the tool name, and does not take the absent-name path

#### Scenario: Happy path — a Devin-shaped payload yields the tool name

**Given** a payload in the shape Devin's documentation specifies for an edit
**When** the gate reads it
**Then** it obtains the edit tool's name

#### Scenario: Adversarial — an unconfirmed field is not assumed

**Given** no confirmation from Devin's documentation of the tool-name field
**When** this spec reaches its checkpoint
**Then** the requirement is recorded as unconfirmed and the checkpoint does not report it
as satisfied

### Requirement: The correctness-verdict fixture failures are root-caused before they are fixed

Each of the six tests in the suite file chain-state.bats that fails under both implementations SHALL
have its root cause established and recorded, with the evidence that established it,
before any change to fixture or implementation; and a fix MUST NOT alter a verdict without
a recorded feature-freeze exception.

**Given** the six shared failures in that suite file
**When** this spec is implemented
**Then** each has a recorded root cause, and each is resolved by a fixture repair sanctioned
by this requirement — or, if its root cause is an implementation defect shared by both
implementations, it is recorded and left for a scoped exception

**Rationale**: each reports `discharged: 0` and a degraded graph on a fixture its test
calls fully satisfied. The hypothesis — the fixture writes its evidence as unwitnessed
testimony while discharge now requires corroboration — is plausible and unverified. A fix
made on a hypothesis is how a "pre-existing" failure became three changes old.

#### Scenario: Happy path — a stale fixture is repaired and the test passes

**Given** a shared failure whose recorded root cause is a fixture that records evidence
differently from how the workflow records it
**When** the fixture is repaired under this requirement's sanction
**Then** the test passes under both implementations

#### Scenario: Adversarial — a fix with no recorded root cause is not accepted

**Given** a change to one of that suite file's fixtures with no recorded root cause
**When** the checkpoint reviews it
**Then** it is rejected

#### Scenario: Adversarial — a shared implementation defect is not silently fixed

**Given** a shared failure whose root cause is an implementation defect in both
implementations
**When** this spec is implemented
**Then** the defect is recorded, no verdict is altered, and the test is listed as awaiting
a scoped exception

## Properties (Ring 3)

### Property: lock-decision-is-independent-of-name-case-and-channel

**Invariant**: for every edit-tool name in either case, supplied by flag or by payload, the
pre-execution tier's decision on a production path in the oracle phase is block; for every
read-only tool name it is allow.

**Generator strategy**: `genToolName` — constructive over the closed set of edit and
read-only tool names, each rendered in title case and in lower case, crossed with the two
delivery channels. The domain is finite and fully enumerated; that limit is stated.

```
property("lock decision is independent of case and channel") {
  for {
    tool    <- genToolName.forAll
    channel <- Gen.element(Flag, List(Payload)).forAll
    d        = preExecution(oraclePhaseFixture, tool, channel)
  } yield Result.assert(d.isBlock == tool.isEdit)
}
```

### Property: absent-name-always-allows-and-says-so

**Invariant**: for every fixture in which no tool name is supplied, the decision is allow
and the diagnostic states the absence; for every fixture with a supplied name, no absence
diagnostic appears.

**Generator strategy**: `genPreExecutionFixture` — constructive over phase {oracle,
implementation, verified} × path {production, test, artifact} × name {absent, each closed
tool name}. No filtering.

```
property("an absent name allows and says so") {
  for {
    fx <- genPreExecutionFixture.forAll
    d   = preExecution(fx)
  } yield Result.assert(
    fx.nameAbsent == (d.isAllow && d.diagnostics.mentionsAbsentName)
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A tool name represented as a possibly-empty string at the decision site | An empty string is how absence became indistinguishable from a read-only tool | `assertDoesNotCompile("GateDecisions.preExecution(toolName = \"\")")` — the decision takes a `ToolNameSource` |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: the lock decision is already mirrored by the gate kernel,
and this spec changes fixtures and adds a diagnostic, not the decision.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A production edit in the oracle phase is blocked | Requirement: The lock fixtures supply the tool name a harness supplies + Scenario: Happy path — a production edit in the oracle phase is blocked | bats oracle | `oracle-ordering-lock.bats` |
| A spec start without a grant is blocked | Requirement: The lock fixtures supply the tool name a harness supplies + Scenario: Happy path — a spec start without a grant is blocked | bats oracle | `human-grant-lock.bats` |
| A read-only tool is not blocked | Requirement: The lock fixtures supply the tool name a harness supplies + Scenario: Adversarial — a read-only tool is not blocked | scenario tests | `GateEventSpec` (supplied `Read` on a production path allows), `GateDecisionSpec` (read-only name set) |
| The decision is independent of case and channel | Property: lock-decision-is-independent-of-name-case-and-channel | Hedgehog property (enumerated) | `GateDecisionSpec` |
| An absent name allows with a stated reason | Requirement: An absent tool name keeps parity and is stated + Scenario: Happy path — an absent name allows with a stated reason | scenario test | `GateEventSpec` |
| A supplied name produces no absence diagnostic | Requirement: An absent tool name keeps parity and is stated + Scenario: Adversarial — a supplied name produces no absence diagnostic | scenario test | `GateEventSpec` |
| An absent name always allows and says so | Property: absent-name-always-allows-and-says-so | Hedgehog property | `GateDecisionSpec` |
| An empty-string tool name is unconstructible at the decision | Compile-Negative: A tool name represented as a possibly-empty string at the decision site | compile-negative test | `GateEventCompletenessTypeContract` |
| The Devin field is confirmed against its documentation | MUST-CONFIRM: the Devin payload's tool-name field | manual confirmation against Devin's documentation, recorded with its source | `GateEventSpec` |
| A Devin-shaped payload yields the tool name | Requirement: The Devin adapter delivers the tool name + Scenario: Happy path — a Devin-shaped payload yields the tool name | scenario test on the confirmed payload shape | `GateEventSpec` |
| An unconfirmed field is not reported satisfied | Requirement: The Devin adapter delivers the tool name + Scenario: Adversarial — an unconfirmed field is not assumed | manual review at the checkpoint | `GateEventSpec` |
| A repaired stale fixture passes under both | Requirement: The correctness-verdict fixture failures are root-caused before they are fixed + Scenario: Happy path — a stale fixture is repaired and the test passes | bats oracle compared against the predecessor control | `chain-state.bats` |
| A fix without a recorded root cause is rejected | Requirement: The correctness-verdict fixture failures are root-caused before they are fixed + Scenario: Adversarial — a fix with no recorded root cause is not accepted | manual review at the checkpoint; the root causes are recorded in the change's progress record | `chain-state.bats` |
| A shared implementation defect is not silently fixed | Requirement: The correctness-verdict fixture failures are root-caused before they are fixed + Scenario: Adversarial — a shared implementation defect is not silently fixed | manual review, plus the feature-freeze guard | `NonGoalsGuardSpec` |
| The three suite files pass under both implementations | Criterion: this spec's exit criterion | bats oracle compared against the independent predecessor control | `oracle-ordering-lock.bats`, `human-grant-lock.bats`, `chain-state.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Lock fixtures | bats suites | `openspec/schemas/verified-scala3/tests/oracle-ordering-lock.bats` (7 failing), `human-grant-lock.bats` (4 failing) | Their gate helpers pass no `--tool`; the edits here are sanctioned by this spec's first requirement |
| Absent-name path | adapter | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` (pre-execution tier; moves with `entrypoint-split`) | Trace today: `read-only tool '' on production path, allow` |
| Devin adapter | configuration | `openspec/schemas/verified-scala3/hooks/adapters/devin.hooks.v1.json` | No `--tool`; relies on the payload |
| Chain-state fixtures | bats suite | `openspec/schemas/verified-scala3/tests/chain-state.bats` — tests 3, 5, 8, 9, 16, 17 | The fixture builder appends rows with `ledger append` and no source |
| Ring 5 note | — | `stryker4s.conf` | Retarget to the pre-execution tier's file after the split |
