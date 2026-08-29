# Spec: Gate Event Completeness

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler Migration Protocol | The gate is the last seam and the only blocking one; this spec supplies the behaviour its swap presumed | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |
| Schema | The gate is the schema's enforcement surface — the mechanism that decides whether a check runs at all | [schema.md](../../../../concepts/schema.md) |

This spec does not alter either concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `GateEvent` | enum | `org.sinemenda.probatio.core` |
| `GateDecision` | enum (Allow, Block) | `org.sinemenda.probatio.core` |
| `BlockReason` | sealed hierarchy | `org.sinemenda.probatio.core` |
| `SpecPhase` | enum | `org.sinemenda.probatio.core` |
| `PredecessorCheck` | object | `org.sinemenda.probatio.core` |
| `GrantWaiver` | object | `org.sinemenda.probatio.core` |
| `PresentationMarker` | object | `org.sinemenda.probatio.core` |
| `GatePayload` | final case class | `org.sinemenda.probatio.core` |
| `HookSpecificOutput` | final case class | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class | `org.sinemenda.probatio.core` |
| `Ring` | enum | `org.sinemenda.probatio.core` |
| `Validator` | object | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |
| `SchemaPolicy` | object | `org.sinemenda.probatio.core` |
| `CliContext` | final case class | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `HarnessPayload` | final case class | The structured input a harness supplies on the gate's input channel: tool name, tool input, tool response, interruption flag. Read at most once per run |
| `ToolOutcome` | enum (`Exit(code)`, `Skip(reason)`) | The classification of a harness tool response. A harness refusal, an interruption, and an unrecognised shape are all `Skip` — never an exit code |
| `GateStateDir` | final case class | The resolved per-repository state: heartbeat, per-session suppression fingerprint, grant tokens, presentation markers, oracle phase |
| `SessionId` | opaque type over `String` | A session identity whose filename-safe encoding is lossless, so two distinct sessions never collide onto one state file |
| `RefusalBudget` | final case class | The per-turn refusal allowance — the data behind "at most one refusal per turn, and fail open when the bounding state is unavailable" |
| `HeartbeatRecord` | final case class | The installation-probe record: whether the gate has run, when, and for which event |

## ADDED Requirements

### Requirement: The gate handles every event its installed adapters emit

The gate SHALL accept every event name its installed harness adapters are
configured to send, and SHALL NOT reject a configured event name as unknown.

**Given** the set of event names the installed adapters send
**When** the gate is invoked with any of them
**Then** the gate handles it and exits with a status documented for that event

**Rationale**: The adapters are configured to send six event names; the ported
gate recognises five. The missing one is the ambient evidence writer — the
observation channel that turns a run into a recorded fact. Its absence is why two
whole test files fail entirely.

#### Scenario: Happy path — the post-tool observation event is handled

**Given** the gate is invoked with the post-tool observation event and a harness
payload describing a completed shell command
**When** the gate runs
**Then** it exits clean, and if the command matched a ring-shaped pattern a
ledger row is appended recording the harness-observed outcome

#### Scenario: Adversarial — an event name no adapter sends is still rejected

**Given** the gate is invoked with an event name that is not in the adapter set
**When** the gate runs
**Then** it reports the unknown event name and exits with the finding status —
unknown events are not silently treated as the default event

#### Scenario: Edge case — the post-tool observation event never blocks

**Given** the gate is invoked with the post-tool observation event under any
payload, including a payload it cannot classify
**When** the gate runs
**Then** it exits clean

### Requirement: An observed outcome is recorded only when the harness reported one

The gate SHALL record a run's outcome only when the harness response carries a
command outcome, and SHALL record nothing when the response is a refusal, an
interruption, or a shape it does not recognise.

**Given** a harness response
**When** the gate classifies it
**Then** it yields either an exit code the harness reported, or a skip carrying the
reason it was not an outcome

**Rationale**: Reading any non-outcome response as a failure would file a
permission denial as a red test run — a fabricated fact, and precisely the class
of thing the evidence record exists to make impossible.

#### Scenario: Happy path — a successful response records a green row

**Given** a harness response indicating the command ran and succeeded
**When** the gate classifies it
**Then** the outcome is the success exit code and a row is recorded

#### Scenario: Happy path — a failing response records the reported code

**Given** a harness response carrying a specific non-zero exit code
**When** the gate classifies it
**Then** the outcome is exactly that code, not a truncated or defaulted value

#### Scenario: Adversarial — a harness refusal records nothing

**Given** a harness response stating that the command required approval, or that a
path did not exist
**When** the gate classifies it
**Then** the outcome is a skip and no row is recorded — the response is not read as
a failure

#### Scenario: Adversarial — an interrupted run records nothing

**Given** a harness response marked interrupted
**When** the gate classifies it
**Then** the outcome is a skip and no row is recorded

#### Scenario: Adversarial — a command whose reported status is not the ring's records nothing

**Given** a command that is a pipeline or a chain, whose reported status belongs to
its last element rather than to the ring command
**When** the gate classifies it
**Then** the outcome is a skip and no row is recorded

#### Scenario: Edge case — a redirected command is still recorded

**Given** a ring command with output redirection, whose reported status is still
the ring command's
**When** the gate classifies it
**Then** the outcome is the reported exit code and a row is recorded

### Requirement: The blocking tiers consult repository state and fail open when it is unavailable

The pre-execution and completion tiers SHALL base their decision on state read from
the repository, and SHALL allow the action when that state cannot be read.

**Given** a pre-execution or completion event
**When** the gate decides
**Then** the decision is derived from the read state, and if the state directory
cannot be read the decision is to allow

**Rationale**: Two failure modes are on record. The ported gate refuses every
pre-execution event unconditionally because it has no state reader, which strands
the agent — observed in this session, where a working gate denied every tool call
until the shim was reverted. The predecessor's opposite failure would be to treat
unreadable state as clean. The rule is: read the state; if you cannot, allow, and
say why.

#### Scenario: Happy path — a verified and checkpointed predecessor allows the action

**Given** state in which every prior spec is verified and carries a presentation
marker
**When** a pre-execution event is evaluated
**Then** the decision is to allow

#### Scenario: Error path — a verified but uncheckpointed predecessor blocks with the distinct reason

**Given** state in which a prior spec is verified but carries no presentation marker
**When** a pre-execution event is evaluated
**Then** the decision is to block, and the reason names not-checkpointed —
distinct from the not-verified reason

#### Scenario: Adversarial — an unreadable state directory allows rather than refusing

**Given** a repository whose state directory does not exist
**When** a pre-execution event is evaluated
**Then** the decision is to allow, and the diagnostic channel states that the state
could not be read

#### Scenario: Adversarial — the escape hatch bypasses both checks under either name

**Given** the escape hatch is set, under its current name or under its deprecated
alias
**When** a pre-execution event is evaluated against state that would otherwise
block
**Then** the decision is to allow, and setting the deprecated alias additionally
emits the deprecation notice

### Requirement: At most one refusal is issued per turn

The gate SHALL issue at most one refusal within a turn, and SHALL allow subsequent
actions in that turn once a refusal has been issued.

**Given** a turn in which the gate has already refused once
**When** a further action in the same turn is evaluated
**Then** the decision is to allow

**Rationale**: An unbounded refusal loop is indistinguishable from a broken
harness: the agent cannot act, cannot record evidence, and cannot reach the state
that would unblock it. The predecessor bounds refusals for exactly this reason, and
the oracle asserts "exactly one refusal, bounded, not zero".

#### Scenario: Happy path — the first refusal in a turn is issued

**Given** a turn with no prior refusal and state that warrants blocking
**When** an action is evaluated
**Then** the decision is to block

#### Scenario: Adversarial — the second refusal in the same turn is not issued

**Given** a turn in which a refusal has already been issued
**When** a second action warranting a block is evaluated
**Then** the decision is to allow

#### Scenario: Edge case — the budget resets on a new turn

**Given** a turn in which a refusal was issued, followed by a new turn
**When** an action warranting a block is evaluated in the new turn
**Then** the decision is to block

### Requirement: Two distinct sessions never share suppression state

The gate SHALL derive each session's state file name by a lossless encoding of the
session identity, so that two distinct identities never map to the same name.

**Given** two distinct session identities
**When** their state file names are derived
**Then** the names differ

**Rationale**: A lossy sanitisation collapses characters outside the safe set onto
one replacement, so two genuinely different identities can land on the same file —
a brand-new session then reads another session's suppression state and stays
silent when it should inject. The oracle carries this case explicitly.

#### Scenario: Adversarial — identities differing only in unsafe characters get different files

**Given** two session identities that are identical except for one character that
is not filename-safe, and those characters differ
**When** their state file names are derived
**Then** the names differ

#### Scenario: Happy path — the same identity yields the same file

**Given** the same session identity supplied twice
**When** its state file name is derived
**Then** the two names are equal

#### Scenario: Edge case — an identity is resolved from the strongest available signal

**Given** an explicit session identity, a harness-supplied identity, and a generic
override all present
**When** the identity is resolved
**Then** the explicit identity is used

### Requirement: The installation probe reports whether the gate has run

The gate SHALL support an installation probe that reports whether it has run in
this repository and, if so, when and for which event.

**Given** the installation probe
**When** it is invoked
**Then** it emits a record stating whether the gate is installed, and exits clean

**Rationale**: The probe is how a session learns that the enforcement mechanism is
present at all. The ported gate does not recognise the probe's flag, so the answer
is currently an argument-parsing error — which a caller reads as "not installed"
only by accident. Verified 2026-08-29: the predecessor answers with a record and
exits clean; the port answers with an unrecognised-flag error and exits 1.

#### Scenario: Happy path — a repository where the gate has run reports installed

**Given** a repository in which the gate has previously run
**When** the probe is invoked
**Then** the record states installed, carries the time of the last run and the
event, and the exit status is clean

#### Scenario: Error path — a repository where the gate has never run reports not installed

**Given** a repository in which the gate has never run
**When** the probe is invoked
**Then** the record states not installed, and the exit status is clean

#### Scenario: Edge case — a repository outside the workflow writes no state

**Given** a repository that does not contain the workflow's directory
**When** the gate runs any event
**Then** no state is written and no context is emitted

### Requirement: The emitted envelope names the harness's own event name

When the gate emits its structured envelope, the event name it carries SHALL be
the name the harness uses for that event.

**Given** an event and the structured output format
**When** the gate emits its envelope
**Then** the envelope's event-name field carries the harness's name for that event

**Rationale**: The ported gate emits its internal enum name. Observed 2026-08-29:
the envelope carried the internal name where the contract requires the harness's
prompt-submit name, so the envelope fails the contract checker.

#### Scenario: Happy path — the prompt event carries the harness's name

**Given** the prompt event and the structured output format
**When** the gate emits its envelope
**Then** the event-name field carries the harness's name for the prompt event, and
the envelope satisfies the envelope contract

#### Scenario: Adversarial — the internal name never appears in the envelope

**Given** any event and the structured output format
**When** the gate emits its envelope
**Then** the event-name field is not the internal enum name where that differs from
the harness name

## Properties (Ring 3)

### Property: outcome-classification-is-total-and-conservative

**Invariant**: For every harness response, classification yields either an exit
code the response explicitly carried, or a skip. No response is classified as an
exit code it did not carry.

**Generator strategy**: `genHarnessResponse` — constructive: generates the
object-shaped success response (with and without the interruption flag), the
error-string shape carrying a generated exit code 0–255, refusal strings drawn from
a fixed corpus, and arbitrary other strings and shapes. Hedgehog `cover`:
`object-success` ≥ 15%, `error-with-code` ≥ 20%, `refusal` ≥ 15%,
`interrupted` ≥ 10%, `unrecognised` ≥ 15%.

```
forAll { (resp: HarnessResponse) =>
  classify(resp) match
    case ToolOutcome.Exit(c) => resp.explicitlyCarries(c)
    case ToolOutcome.Skip(r) => r.nonEmpty
}
```

### Property: post-tool-observation-never-blocks

**Invariant**: For every payload, the post-tool observation event exits clean.

**Generator strategy**: `genHarnessPayload` — constructive: pairs
`genHarnessResponse` with generated tool names (Bash and non-Bash) and generated
command strings (ring-shaped, pipeline, chained, redirected, unrelated). Hedgehog
`cover`: `non-bash-tool` ≥ 15%, `pipeline` ≥ 15%, `ring-shaped` ≥ 25%.

```
forAll { (p: HarnessPayload) =>
  gate.run(PostToolObservation, p).exitStatus == Clean
}
```

### Property: refusal-budget-is-bounded-and-nonzero

**Invariant**: For every non-empty sequence of blockable actions within one turn,
exactly one refusal is issued — not zero and not more than one.

**Generator strategy**: `genTurnSequence` — constructive: a turn is a non-empty
list (1–6) of actions, each independently blockable or not, with the blockable
count generated separately so sequences with zero blockable actions are excluded by
construction rather than by filtering. Hedgehog `cover`: `single-blockable` ≥ 25%,
`multiple-blockable` ≥ 40%, `blockable-not-first` ≥ 20%.

```
forAll { (turn: TurnSequence) =>
  turn.hasBlockable ==> runTurn(turn).count(_.isRefusal) == 1
}
```

### Property: session-identity-encoding-is-injective

**Invariant**: For all pairs of session identities, equal encoded names imply equal
identities.

**Generator strategy**: `genSessionIdentity` — constructive: strings over an
alphabet deliberately including filename-unsafe characters (slash, backslash,
question mark, exclamation mark, space, colon, non-ASCII), lengths 1–40. Hedgehog
`cover`: `contains-unsafe-char` ≥ 60%, `differs-only-in-unsafe-char` ≥ 20% (the
generator emits such pairs directly).

```
forAll { (a: String, b: String) =>
  encodeSessionFile(a) == encodeSessionFile(b) ==> a == b
}
```

### Property: unreadable-state-allows

**Invariant**: For every blocking-tier event, when the state directory is absent or
unreadable the decision is to allow.

**Generator strategy**: `genBlockingEvent` × `genUnreadableStateShape` —
constructive: the state shape generator produces absent-directory, present-but-empty,
and present-but-unreadable variants. Hedgehog `cover`: each shape ≥ 25%.

```
forAll { (ev: BlockingEvent, shape: UnreadableStateShape) =>
  gate.decide(ev, readState(shape)) == GateDecision.Allow
}
```

### Property: envelope-conforms-to-contract

**Invariant**: For every event emitted in the structured format, the envelope
satisfies the envelope contract and its event-name field is the harness's name.

**Generator strategy**: `genGateEvent` — constructive, exhaustive over the event
enum (small). Model-based against the contract checker executed as a subprocess.
Hedgehog `cover`: each event ≥ 10%.

```
forAll { (ev: GateEvent) =>
  val out = gate.emitEnvelope(ev)
  contractChecker(out).isSuccess && eventNameOf(out) == harnessName(ev)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `ToolOutcome.Exit` constructed from a harness response classified as a refusal | Fabricating an exit code from a non-outcome is the defect the classification exists to prevent; the constructor is reachable only through `classify` | `assertDoesNotCompile` on the direct constructor, in `GateEventSpec` |
| `GateEvent` pattern match omitting the post-tool observation case | Adding the sixth event must force every existing match to be updated; exhaustiveness is escalated to an error | `assertDoesNotCompile` in `GateEventSpec` |
| A file read or `System.getenv` call inside `probatio-core`'s gate decision functions | State must arrive as values from the CLI layer (R-ARCH1 placement rule) — the existing discipline of the predecessor and grant-waiver checks | `assertDoesNotCompile` in `GateEventSpec` |
| A `SessionId` constructed from a raw string without passing through its encoder | A lossily-sanitised identity is the collision defect | `assertDoesNotCompile` in `GateEventSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. Two decisions are modelled: the refusal budget and the
outcome classification.

### Contract: refusalBudget

**Precondition** (`require`): the action list is non-empty and contains at least one
blockable action.

**Postcondition** (`ensuring`): exactly one action in the returned decision list is
a refusal, and it is the first blockable action.

```scala
def refusalBudget(blockable: List[Boolean]): List[Boolean] = {
  require(blockable.nonEmpty && blockable.contains(true))
  // pure model: returns the refusal decision per action
}.ensuring { decisions =>
  decisions.length == blockable.length &&
  decisions.count(d => d) == 1 &&
  decisions.indexWhere(d => d) == blockable.indexWhere(b => b)
}
```

### Contract: classifyOutcome

**Precondition** (`require`): the response shape code is in `[0, 4]` (object,
object-interrupted, error-with-code, refusal-string, other).

**Postcondition** (`ensuring`): an exit code is returned only for the object and
error-with-code shapes; every other shape yields a skip.

```scala
def classifyOutcome(shape: BigInt, carriedCode: BigInt): Option[BigInt] = {
  require(shape >= 0 && shape <= 4)
  // pure model
}.ensuring { res =>
  (res.isDefined ==> (shape == 0 || shape == 2)) &&
  (shape == 2 ==> res == Some(carriedCode)) &&
  (shape == 0 ==> res == Some(BigInt(0)))
}
```

**Bridge property test**: `GateBridgeSpec` runs the shipped `RefusalBudget` and
`classify` against the mirrors on the same generated inputs.

**Delegated to Ring 3**: session-identity injectivity is delegated — it quantifies
over strings, which the solver does not discharge; `session-identity-encoding-is-injective`
covers it.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every adapter-configured event name is handled | Requirement: The gate handles every event its installed adapters emit | scenario test reading the adapter configuration files and invoking each name | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| An unconfigured event name is still rejected | Scenario: Adversarial — an event name no adapter sends is still rejected | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| Adding the sixth event forces every match to be updated | Compile-Negative: GateEvent pattern match omitting the post-tool observation case | type system (exhaustiveness escalated to error) + compile-negative test | `GateEvent` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/GateEvent.scala`; `GateEventSpec` |
| The post-tool observation event never blocks | Requirement: The gate handles every event its installed adapters emit + Property: post-tool-observation-never-blocks | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| A recorded outcome was one the harness reported | Requirement: An observed outcome is recorded only when the harness reported one + Property: outcome-classification-is-total-and-conservative | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ToolOutcomeSpec.scala` |
| A refusal, an interruption, and an unrecognised shape record nothing | Scenario: Adversarial — a harness refusal records nothing + Scenario: Adversarial — an interrupted run records nothing | scenario tests | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ToolOutcomeSpec.scala` |
| A compound command's status is not attributed to the ring | Scenario: Adversarial — a command whose reported status is not the ring's records nothing | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ToolOutcomeSpec.scala` |
| A redirected ring command is still recorded | Scenario: Edge case — a redirected command is still recorded | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ToolOutcomeSpec.scala` |
| A fabricated exit code cannot be constructed from a non-outcome | Compile-Negative: A ToolOutcome.Exit constructed from a harness response classified as a refusal | smart constructor + compile-negative test | `ToolOutcome` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/ToolOutcome.scala`; `GateEventSpec` |
| Blocking decisions derive from read state | Requirement: The blocking tiers consult repository state and fail open when it is unavailable + Scenario: Error path — a verified but uncheckpointed predecessor blocks with the distinct reason | scenario tests | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateStateDirSpec.scala` |
| Unreadable state allows rather than refusing | Scenario: Adversarial — an unreadable state directory allows rather than refusing + Property: unreadable-state-allows | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateStateDirSpec.scala` |
| The escape hatch works under both names and the alias warns | Scenario: Adversarial — the escape hatch bypasses both checks under either name | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| Exactly one refusal per turn — not zero, not more | Requirement: At most one refusal is issued per turn + Property: refusal-budget-is-bounded-and-nonzero + Contract: refusalBudget | property test + formal contract (Ring 6) + bridge test | `GateEventSpec`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/GateKernel.scala`; `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateBridgeSpec.scala` |
| The budget resets on a new turn | Scenario: Edge case — the budget resets on a new turn | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| Distinct sessions never collide onto one state file | Requirement: Two distinct sessions never share suppression state + Property: session-identity-encoding-is-injective | opaque type + smart constructor + property test | `SessionId` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/SessionId.scala`; `SessionIdSpec` |
| A raw session string cannot bypass the encoder | Compile-Negative: A SessionId constructed from a raw string without passing through its encoder | opaque type + compile-negative test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| The strongest available identity signal is used | Scenario: Edge case — an identity is resolved from the strongest available signal | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateStateDirSpec.scala` |
| The installation probe answers and exits clean | Requirement: The installation probe reports whether the gate has run + Scenario: Error path — a repository where the gate has never run reports not installed | scenario tests executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| A non-workflow repository gets no state and no context | Scenario: Edge case — a repository outside the workflow writes no state | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateStateDirSpec.scala` |
| The envelope carries the harness's event name and satisfies the envelope contract | Requirement: The emitted envelope names the harness's own event name + Property: envelope-conforms-to-contract | Ring 4 contract-conformance test executing the envelope contract checker | `openspec/schemas/verified-scala3/scanner/gate-hookjson-contract.jq` via `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateBannerCompatSpec.scala` (existing suite, extended) |
| The internal enum name never leaks into the envelope | Scenario: Adversarial — the internal name never appears in the envelope | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateBannerCompatSpec.scala` |
| Gate decision functions perform no file I/O and read no environment | Compile-Negative: A file read or System.getenv call inside probatio-core's gate decision functions | compile-negative test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateEventSpec.scala` |
| Outcome classification is conservative under the formal model | Requirement: An observed outcome is recorded only when the harness reported one + Property: outcome-classification-is-total-and-conservative + Contract: classifyOutcome | formal contract (Ring 6) + bridge property test | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/GateKernel.scala`; `GateBridgeSpec` |
| The gate's bats files reach parity with the predecessor control | Requirement: The gate handles every event its installed adapters emit | differential oracle run (see the cutover-gate spec) | `hook-tiers.bats`, `gate-payload.bats`, `ambient-capture-wiring.bats`, `ambient-evidence-capture.bats`, `oracle-ordering-lock.bats`, `human-grant-lock.bats` under the differential harness |
| No blocking behaviour was added or relaxed relative to the predecessor | Requirement: The blocking tiers consult repository state and fail open when it is unavailable | adversarial review (Ring 8), fresh context, verifying against the built artifact driven as a hook | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `GateCmd.Event` | enum | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | gains the post-tool observation case; every match over it must be updated or Ring 0 fails |
| `GateCmd.run` / `runEvent` | methods | same file | gains the state-directory reader, the refusal budget, the installation probe flag, and the harness-payload reader |
| `HarnessPayloadReader` | object (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/HarnessPayloadReader.scala` | reads the input channel at most once, in the top-level process — not inside a subshell-equivalent |
| `GateStateDirReader` | object (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/GateStateDirReader.scala` | the only file-reading component for gate state; resolves under the repository's git directory |
| `ToolOutcome`, `SessionId`, `RefusalBudget`, `HeartbeatRecord` | new types | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | pure data + classification |
| `PredecessorCheck`, `GrantWaiver` | objects | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | unchanged; wired to the new reader |
| `SchemaPolicy` | object | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SchemaPolicy.scala` | supplies the escape-hatch alias handling and its deprecation notice |
| `GateKernel` | Stainless object (new) | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/GateKernel.scala` | refusal budget + outcome classification mirrors |
| `hooks/gate.sh.predecessor.bak` | reference implementation | `openspec/schemas/verified-scala3/hooks/` | the model for tier behaviour |
| `hooks/adapters/claude.settings.json`, `hooks/adapters/devin.hooks.v1.json`, `hooks/adapters/pi/verified-scala3-gate.ts` | adapter configuration | `openspec/schemas/verified-scala3/hooks/adapters/` | the authoritative source of the configured event-name set read by the first requirement's test |
| `sbt "probatio-cli/test"` | build step | `probatio-cli` | Ring 3 |
