# Spec: record-replay-example (zero-provider-call replay example)

<!-- Delta spec for the add-adk4s-record change. Defines an example in
     adk4s-examples that runs the same agent twice against a warm file
     recorder and demonstrates a zero-provider-call second run producing
     an identical final AssistantMessage. This is exit criteria §8.5 and
     §8.6 from the requirements doc. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | The model call surface wrapped by RecordedChatModel in the example | `openspec/concepts/chat-model.md` |
| [ReactAgent](../../../../concepts/react-agent.md) | The agent that runs the ReAct loop; the example wraps its ChatModel with recording | `openspec/concepts/react-agent.md` |
| [Tool](../../../../concepts/tool.md) | The example uses a multi-turn tool-calling conversation to exercise REC-4 normalization | `openspec/concepts/tool.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` |
| `RunResult` | sealed trait (`Completed`/`Interrupted`/`Failed`) | `org.adk4s.orchestration.agent` |
| `DeterministicChatModel` | test double | `org.adk4s.harness.testkit` |
| `RecordedChatModel` | wrapper | `org.adk4s.record` (introduced by `recorded-wrappers` spec) |
| `Recorder.file` | implementation | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `Recorder.noop` | implementation | `org.adk4s.record` (introduced by `recorder-sink` spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RecordReplayExample` | `IOApp.Simple` object | Example demonstrating zero-provider-call replay against a warm file recorder |

## ADDED Requirements

### Requirement: Example runs same agent twice with zero-provider-call second run

The system SHALL provide an example in `adk4s-examples` that runs the same
agent twice against a warm file recorder, demonstrating that the second run
performs zero provider calls and produces an identical final
`AssistantMessage`.

**Given** a `RecordReplayExample` with a deterministic model double wrapped
by the recording chat model using a file-backed recorder
**When** the agent is run twice with the same input
**Then** the first run calls the underlying model (populating the
recorder), and the second run performs zero underlying model calls while
producing an identical final `AssistantMessage`

**Rationale**: This is the acceptance test for the recording + caching
pipeline (exit criteria §8.5). It demonstrates the end-to-end value: run
once, replay for free.

#### Scenario: Second run is zero-call

**Given** a warm file recorder from the first run
**When** the agent is run a second time with the same input
**Then** the underlying model's call counter is unchanged
and the final assistant message content matches the first run's output

### Requirement: Multi-turn tool-calling conversation achieves full cache hit on replay

The system SHALL include a multi-turn tool-calling conversation in the
example that achieves a full cache hit on replay, exercising the tool-call
id normalization (REC-4).

**Given** a multi-turn conversation where the agent calls tools across
multiple turns (producing provider-generated tool-call ids)
**When** the conversation is replayed from a warm recorder
**Then** every turn hits the cache (full cache hit), demonstrating that
tool-call id normalization preserves keys across turns

**Rationale**: This is the acceptance test for REC-4 (exit criteria §8.6)
— the one most likely to fail first if normalization is wrong.

#### Scenario: Multi-turn replay hits on every turn

**Given** a 3-turn conversation with tool calls in turns 1 and 2, recorded
in the first run
**When** the same conversation is replayed
**Then** all 3 turns hit the cache (zero underlying calls) and the final
output matches

### Requirement: Example runs without an API key

The system SHALL ensure the example runs without an API key environment
variable, using a deterministic model double as the underlying model so the
example is reproducible without network access.

**Given** the example with no API key set in the environment
**When** the example is run
**Then** it uses a deterministic model double and completes successfully
without network calls

**Rationale**: Examples must be runnable in any environment. Using a
deterministic double makes the example self-contained and the replay
demonstration deterministic.

#### Scenario: Example runs without API key

**Given** no API key in the environment
**When** the example's entry point is executed
**Then** the example completes and prints the zero-call replay result

## Properties (Ring 3)

> This spec is an example (application-edge code), not a library spec.
> It does not carry its own property tests — its correctness is demonstrated
> by the `RecorderLaws` (RL0–RL12) in the `recorder-laws` spec and by the
> scenario assertions above, which are runnable as integration tests.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Second run is zero-call | Requirement: Example runs same agent twice with zero-provider-call second run | scenario test (integration) | `RecordReplayExampleSpec.scala` or manual run |
| Multi-turn full cache hit | Requirement: Multi-turn tool-calling conversation achieves full cache hit on replay | scenario test (integration) | `RecordReplayExampleSpec.scala` or manual run |
| Example runs without API key | Requirement: Example runs without an API key | scenario test (manual run) | `./adk4s-examples/run-example.sh recordreplay` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RecordReplayExample` | `IOApp.Simple` | `org.adk4s.examples.record` | `adk4s-examples/src/main/scala/org/adk4s/examples/record/RecordReplayExample.scala` |
| `run-example.sh` | shell script | `adk4s-examples/run-example.sh` | add `recordreplay` entry |
| `DeterministicChatModel` | test double | `org.adk4s.harness.testkit` | used as underlying model (no API key needed) |
| `Recorder.file` | file recorder | `org.adk4s.record` | JSONL backend for the warm recorder |
| `adk4s-examples → adk4s-record` | build wiring | `build.sbt` | `adk4s-examples` gains a dependency on `adk4s-record` |
