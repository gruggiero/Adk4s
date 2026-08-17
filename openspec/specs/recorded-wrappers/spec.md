# Spec: recorded-wrappers (RecordedChatModel, recording ToolMiddleware, RecordedEmbedder)

<!-- Delta spec for the add-adk4s-record change. Defines the recording/caching
     wrappers that sit below the middleware stack: RecordedChatModel[F],
     a recording ToolMiddleware, and RecordedEmbedder[F]. Covers wrapper
     transparency, hit/miss semantics, write-failure containment, rollout-id
     sampling warnings, and redaction/classification. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | Wrapped by `RecordedChatModel`; `generate` calls are intercepted for recording and caching | `openspec/concepts/chat-model.md` |
| [Tool](../../../../concepts/tool.md) | Tool execution intercepted by recording `ToolMiddleware`; `callId` normalized in the canonical form | `openspec/concepts/tool.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait (`generate`, `stream`, `streamContent`, `withConfig`) | `org.adk4s.core.component` |
| `Embedder[F[_]]` | trait (`embed`, `embedBatch`, `dimension`) | `org.adk4s.core.component` |
| `InvokableTool[F[_]]` | trait (`run(ujson.Value): F[ujson.Value]`) | `org.adk4s.core.component` |
| `ToolMiddleware` | type alias (`ToolEndpoint => ToolEndpoint`) | `org.adk4s.core.tools` |
| `ToolInput` | case class (`name`, `arguments`, `callId`) | `org.adk4s.core.tools` |
| `ToolOutput` | case class (`name`, `result`, `callId`, `isError`) | `org.adk4s.core.tools` |
| `ModelStep[F[_]]` | type alias (`Kleisli[F, ModelRequest[F], ModelResponse]`) | `org.adk4s.harness` |
| `ModelRequest[F[_]]` | case class (`systemPrompt`, `messages`, `tools`, `options`, `state`) | `org.adk4s.harness` |
| `DeterministicChatModel` | test double (`ChatModel[IO]`, seed-based, `RecordedRequest` trace) | `org.adk4s.harness.testkit` |
| `RecordedRequest` | case class (`renderedSystemPrompt`, `messages`, `toolNames`) | `org.adk4s.harness.testkit` |
| `Observation` | case class (observational equivalence `≍`) | `org.adk4s.harness.testkit` |
| `CallKey` | opaque type (`String`) | `org.adk4s.record` (introduced by `call-key` spec) |
| `CallKind` | generated enum (`MODEL`/`TOOL`/`EMBEDDING`) | `org.adk4s.record.canonical` (introduced by `call-key` spec) |
| `RolloutId` | opaque type (`String :| NonEmpty`, Iron) | `org.adk4s.record` (introduced by `call-key` spec) |
| `Recorder[F[_]]` | trait | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `CallRecord` | generated union (Smithy IDL) | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `RecordPayload` | generated union (Smithy IDL) | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `Classification` | generated enum (Smithy IDL) | `org.adk4s.record` (introduced by `recorder-sink` spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RecordedChatModel[F[_]]` | wrapper | `ChatModel[F]` decorator with recording + caching; returns a `ChatModel[F]` for drop-in wiring |
| `ToolMiddleware.recording` | factory | Recording `ToolMiddleware` (Kleisli endomorphism) composing with existing `logging`/`timing`/`validation` set |
| `RecordedEmbedder[F[_]]` | wrapper | `Embedder[F]` decorator with recording + caching |
| `Redaction` | function type (`RecordPayload => RecordPayload`) | Payload redaction applied after key computation, before the record reaches the sink. Operates on the typed `RecordPayload` union (generated from Smithy IDL), not untyped JSON — type-safe, compile-time checked. |

## ADDED Requirements

### Requirement: Wrapped component is observationally equivalent with noop recorder

The system SHALL ensure that a wrapped component is observationally
equivalent to the component it wraps when configured with `Recorder.noop`.

**Given** a `ChatModel[F]` wrapped with `RecordedChatModel(under, noop)`
**When** the wrapped model is called with any conversation
**Then** the result is identical to calling the underlying model directly,
and zero records are stored

**Rationale**: This is the gatekeeper property (RL0). If wrapping changes
observable behavior, every consumer is subtly different when recording is
enabled.

#### Scenario: Noop-wrapped ChatModel is transparent

**Given** a deterministic model double and a recording chat model wrapping it
with a no-op recorder
**When** both are called with the same conversation
**Then** the completions are equal and the noop recorder holds zero entries

### Requirement: Hit returns recorded result with zero underlying calls

The system SHALL, when a lookup hits, return the recorded result and perform
zero calls against the wrapped component.

**Given** a `RecordedChatModel` with a warm recorder (a prior call's record
is stored)
**When** the same request is made again
**Then** the recorded completion is returned and the underlying `ChatModel`
is called zero times

**Rationale**: This is the core caching property — a hit must not re-spend
tokens.

#### Scenario: Second identical call hits cache

**Given** a recording chat model wrapping a call-counting deterministic
model double, with the first call recorded
**When** the same conversation is sent again
**Then** the call counter on the underlying model does not increase, and
the returned completion matches the first call's result

### Requirement: Miss calls underlying component and records result

The system SHALL, when a lookup misses, call the wrapped component, record
the result under the computed key, and return it.

**Given** a `RecordedChatModel` with a cold recorder (no prior record)
**When** a call is made
**Then** the underlying `ChatModel` is called, the result is recorded under
the computed `CallKey`, and the result is returned to the caller

#### Scenario: First call misses and records

**Given** a `RecordedChatModel` with an empty recorder
**When** a model call is made
**Then** the underlying model is called once, the recorder holds one
`Succeeded` record, and the completion is returned

### Requirement: Recording failure does not fail the call

The system SHALL, if recording a result fails, return the call's result to
the caller and surface the recording failure through the configured failure
channel without failing the call.

**Given** a `RecordedChatModel` whose recorder's `record` operation raises
an error
**When** a model call is made and the underlying call succeeds
**Then** the successful completion is returned to the caller, and the
recording failure is surfaced (e.g. logged or emitted) without propagating
as an exception

**Rationale**: A cache is an optimization; a failed write must not take down
an agent run. Note: this requirement is inverted for journal-backed
recorders where the record is the audit artifact (open question §9.3).

#### Scenario: Recorder failure does not propagate

**Given** a `RecordedChatModel` with a recorder that throws on `record`
**When** a model call is made
**Then** the completion is returned successfully and the recording error is
surfaced without throwing

### Requirement: Nonzero temperature without RolloutId emits a diagnostic warning

The system SHALL, when a request specifies a nonzero temperature and no
`RolloutId`, record the call and emit a diagnostic warning that a sampled
result is being made deterministic by caching.

**Given** a `RecordedChatModel` call with `temperature > 0` and no
`RolloutId` supplied
**When** the call is made and the result is cached
**Then** a diagnostic warning is emitted (via the configured warning channel)

**Rationale**: Silently serving one sample forever from a temperature-1.0
call corrupts `BestOfN` and every bootstrap round. The behavior is
deliberate (deterministic replay sometimes wants exactly this), so it warns
rather than failing — but it must not be silent.

#### Scenario: Temperature 1.0 without rollout warns

**Given** a call with `temperature = 1.0` and no `RolloutId`
**When** the call is recorded
**Then** a diagnostic warning is emitted mentioning that a sampled result
is being cached deterministically

#### Scenario: Temperature 0.0 without rollout does not warn

**Given** a call with `temperature = 0.0` and no `RolloutId`
**When** the call is recorded
**Then** no diagnostic warning is emitted (temperature 0 is already
deterministic)

#### Scenario: Temperature 1.0 with RolloutId does not warn

**Given** a call with `temperature = 1.0` and `RolloutId("run-1")`
**When** the call is recorded
**Then** no diagnostic warning is emitted (the rollout id makes the
resampling deliberate)

### Requirement: Redaction applies after key computation

The system SHALL, where a redaction function is configured, apply it to the
record payload before the record reaches the sink, and SHALL apply it after
key computation so that redaction does not change hit rates.

**Given** a `RecordedChatModel` with a redaction function that masks
API keys in the payload
**When** a call is recorded
**Then** the stored record's payload has API keys masked, but the `CallKey`
is computed from the unredacted canonical form

**Rationale**: If redaction entered the key, two requests differing only in
redacted content would share a key, collapsing distinct calls. Redaction
must be payload-only, post-key.

#### Scenario: Redacted payload, unredacted key

**Given** a call with an API key in the prompt, and a redaction function
that replaces API keys with `[REDACTED]`
**When** the call is recorded and looked up
**Then** the stored payload has `[REDACTED]` where the API key was, and a
second identical call (with the same API key) hits the cache

#### Scenario: Redaction does not affect hit rate

**Given** two identical calls with redactable content
**When** both are recorded with a redaction function
**Then** the second call hits the cache (the key was computed before
redaction)

### Requirement: Recording ToolMiddleware composes with existing middleware

The system SHALL provide a recording `ToolMiddleware` that composes with
the existing `logging`/`timing`/`validation` middleware set, recording tool
call results under their computed `CallKey`.

**Given** a `ToolsNodeConfig` with existing middleware plus
`ToolMiddleware.recording(recorder)`
**When** a tool call is executed
**Then** the tool result is recorded under its `CallKey`, and the existing
middleware (logging, timing, etc.) still runs

**Rationale**: Recording must not replace the existing middleware stack;
it composes with it.

#### Scenario: Recording composes with logging

**Given** a `ToolMiddleware.recording(recorder)` composed with
`ToolMiddleware.logging(log)`
**When** a tool call is executed
**Then** the tool result is recorded AND a log line is emitted

### Requirement: RecordedEmbedder wraps Embedder with recording and caching

The system SHALL provide a `RecordedEmbedder[F]` that wraps an `Embedder[F]`
with recording and caching, following the same hit/miss semantics as
`RecordedChatModel`.

**Given** an embedding component wrapped with a recording embedder
**When** an embedding is requested
**Then** on miss, the underlying embedder is called and the result is
recorded; on hit, the recorded embedding is returned with zero underlying
calls

#### Scenario: Embedding cache hit

**Given** a `RecordedEmbedder` with a prior embedding for text T stored
**When** `embed(T)` is called again
**Then** the recorded embedding is returned and the underlying embedder is
called zero times

## Properties (Ring 3)

### Property: transparency-noop

**Invariant**: `RecordedChatModel(under, noop)` is observationally
equivalent to `under` across generated conversations, tool sets, and
failures.

**Generator strategy**: `genConversation` + `genToolSet` — constructive
over message lists, tool definitions, and completion options. Uses
`DeterministicChatModel` as the underlying model. Edge cases: empty
conversation, single message, no tools, failing completion. Coverage
labels: `cover 80 "has_tools"`, `cover 20 "no_tools"`.

```
forAll { (conv: Conversation, tools: List[ToolDef], opts: CompletionOptions) =>
  for {
    under <- DeterministicChatModel(seed, script)
    recorded = RecordedChatModel(under, Recorder.noop)
    r1 <- under.generate(conv, opts)
    r2 <- recorded.generate(conv, opts)
  } yield r1 ≍ r2
}
```

### Property: zero-call-hit

**Invariant**: With a warm recorder and a call-counting `ChatModel` double,
a hit performs exactly zero underlying calls and returns the recorded
completion.

**Generator strategy**: `genConversation` — constructive. Uses a
call-counting wrapper around `DeterministicChatModel`. Edge cases: single
call, multiple sequential hits.

```
forAll { (conv: Conversation, opts: CompletionOptions) =>
  for {
    counter <- Ref.of[IO, Int](0)
    under = callCounting(DeterministicChatModel(seed, script), counter)
    recorder <- Recorder.inMemory[IO](100)
    recorded = RecordedChatModel(under, recorder)
    _ <- recorded.generate(conv, opts)  // miss
    c1 <- counter.get
    _ <- recorded.generate(conv, opts)  // hit
    c2 <- counter.get
  } yield (c1 == 1) && (c2 == 1)  // counter did not increase on hit
}
```

### Property: write-failure-containment

**Invariant**: When the sink throws on `record`, the wrapped call's result
still reaches the caller.

**Generator strategy**: `genConversation` — constructive. Uses a recorder
that throws on `record`. Edge cases: recorder throws on first call,
recorder throws on second call.

```
forAll { (conv: Conversation, opts: CompletionOptions) =>
  for {
    under <- DeterministicChatModel(seed, script)
    throwingRecorder = new Recorder[IO] {
      def lookup(k: CallKey) = IO.pure(None)
      def record(k: CallKey, r: CallRecord) = IO.raiseError(new Exception("sink"))
      def nextSeq = IO.pure(0)
    }
    recorded = RecordedChatModel(under, throwingRecorder)
    result <- recorded.generate(conv, opts).attempt
  } yield result.isRight
}
```

### Property: redaction-neutrality

**Invariant**: Redaction changes the stored payload and does not change the
key.

**Generator strategy**: `genConversation` + `genRedaction` — constructive
over conversations and redaction functions (identity, mask-all, mask-pairs).
Edge cases: identity redaction, full redaction.

```
forAll { (conv: Conversation, redaction: Redaction) =>
  for {
    recorder <- Recorder.inMemory[IO](100)
    recorded = RecordedChatModel(under, recorder, redaction = Some(redaction))
    _ <- recorded.generate(conv, opts)
    key = CallKey.fromCanonical(CanonicalFormOps.from(conv))
    record <- recorder.lookup(key)
  } yield record.isDefined && record.get.payload == redaction(originalPayload)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `RecordedChatModel(under, null)` | Null recorder must not be accepted; use `Recorder.noop` for transparent mode | type system (`Recorder[F]` is not nullable in Scala 3) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Noop transparency | Requirement: Wrapped component is observationally equivalent with noop recorder | property test (transparency-noop) | `RecordedWrappersSpec.scala` |
| Hit returns recorded result, zero calls | Requirement: Hit returns recorded result with zero underlying calls | property test (zero-call-hit) + scenario test | `RecordedWrappersSpec.scala` |
| Miss calls underlying and records | Requirement: Miss calls underlying component and records result | scenario test | `RecordedWrappersSpec.scala` |
| Recording failure does not fail call | Requirement: Recording failure does not fail the call | property test (write-failure-containment) + scenario test | `RecordedWrappersSpec.scala` |
| Nonzero temp without rollout warns | Requirement: Nonzero temperature without RolloutId emits a diagnostic warning | scenario test (adversarial: verifies warning is emitted, not silent) | `RecordedWrappersSpec.scala` |
| Redaction after key computation | Requirement: Redaction applies after key computation | property test (redaction-neutrality) + scenario test | `RecordedWrappersSpec.scala` |
| ToolMiddleware composes | Requirement: Recording ToolMiddleware composes with existing middleware | scenario test | `RecordedWrappersSpec.scala` |
| RecordedEmbedder wraps Embedder | Requirement: RecordedEmbedder wraps Embedder with recording and caching | scenario test | `RecordedWrappersSpec.scala` |
| Streaming passes through unrecorded | Criterion: streaming-out-of-scope | manual review (documented as out of scope in proposal §2.2) | n/a |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RecordedChatModel` | object/factory | `org.adk4s.record` | `apply[F[_]: Monad](under: ChatModel[F], recorder: Recorder[F], rollout: Option[RolloutId] = None, redaction: Option[Redaction] = None): ChatModel[F]` |
| `ToolMiddleware.recording` | factory | `org.adk4s.record` | `recording[F[_]: Monad](recorder: Recorder[F]): ToolMiddleware` |
| `RecordedEmbedder` | object/factory | `org.adk4s.record` | `apply[F[_]: Monad](under: Embedder[F], recorder: Recorder[F]): Embedder[F]` |
| `Redaction` | type alias | `org.adk4s.record` | `RecordPayload => RecordPayload` (typed union generated from Smithy IDL) |
| `OnWriteFailure` | enum (design-phase) | `org.adk4s.record` | `Continue` (default, cache semantics) / `Fail` (journal semantics) — open question §9.3 |
