# Design: add-adk4s-record (deterministic call recording + content-hash caching)

## Package Structure

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|-------------|
| Canonical (pure) | `org.adk4s.record.canonical` | `org.adk4s.core.*`, `org.adk4s.record` (types only) | `cats.effect`, `fs2`, `llm4s` LLM client, `workflows4s`, `adk4s.orchestration` | AR-REC-1 (no ambient nondeterminism), AR-REC-2 (no unordered iteration) |
| Record types | `org.adk4s.record` | `org.adk4s.core.*` | `fs2.io`, `workflows4s`, `adk4s.orchestration`, `adk4s-optimize`, `adk4s-eval`, `logback` | Module purity (build.sbt dependency check) |
| Sink algebra | `org.adk4s.record` | `org.adk4s.core.*`, `org.adk4s.record` (types) | `workflows4s`, `adk4s.orchestration`, `adk4s-optimize`, `adk4s-eval`, `logback` | Module purity |
| File recorder | `org.adk4s.record.file` | `org.adk4s.record`, `fs2.io`, `upickle` | `workflows4s`, `adk4s.orchestration` | fs2-io source-scoped here |
| Wrappers | `org.adk4s.record` | `org.adk4s.core.*`, `org.adk4s.record` (types + sink) | `workflows4s`, `adk4s.orchestration`, `adk4s-optimize`, `adk4s-eval`, `logback` | Module purity |
| Laws (main scope) | `org.adk4s.record` | `org.adk4s.record`, `org.adk4s.harness.testkit`, `hedgehog` | `workflows4s`, `adk4s.orchestration` | Module purity |
| Verified model | `org.adk4s.verified` | (leaf, Scala 3.7.2, Stainless) | Any non-PureScala type | Ring 6 isolation |
| Example | `org.adk4s.examples.record` | `org.adk4s.record`, `org.adk4s.orchestration`, `org.adk4s.harness.testkit` | — | Application-edge (exempt from module purity) |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.record` | Record types / Sink algebra / Wrappers / Laws | All recording types, Recorder[F] trait, wrappers, RecorderLaws |
| `org.adk4s.record.canonical` | Canonical (pure) | Pure canonicalization functions: CanonicalForm construction, tool-call id normalization, key computation |
| `org.adk4s.record.file` | File recorder | fs2-io-backed append-only JSONL recorder (the only place fs2-io is imported) |
| `org.adk4s.examples.record` | Example | RecordReplayExample IOApp |

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `canonical.normalizeToolCallIds` | Positional tool-call id normalization (REC-4) | **Yes** — NormalizationModel mirror. Pure list transformation; inputs reducible to `List[Msg]` where `Msg` is an ADT. Idempotence and order-preservation are the proven properties. |
| `canonical.CanonicalForm.from` | Canonical form construction from request fields | **No** — requires llm4s types (Conversation, Message) not modelable in PureScala. CanonicalForm body is now a typed union generated from Smithy IDL (ModelBody/ToolBody/EmbeddingBody). RL2/RL3/RL4 (Ring 3) cover determinism, sensitivity, insensitivity. |
| `canonical.CallKey.fromCanonical` | Digest computation | **No** — hash function is an uninterpreted injective function in the model; collision-freedom is assumed (stated explicitly in RecorderCoherenceModel). Ring 3 covers determinism. |
| `RecorderCoherenceModel` | Finite-map model of record/lookup coherence (RL1) | **Yes** — direct PureScala model. `record`/`lookup` over `Map[Int, Int]` with `ensuring` clauses. |
| `Recorder.inMemory` | Bounded LRU cache with eviction | **No** — effectful (Concurrent, Ref). RL1/RL5/RL8 (Ring 3) cover coherence, zero-call hit, eviction. |
| `Recorder.file` | Append-only JSONL file recorder | **No** — effectful (Async, fs2-io). RL1/RL7/RL8 (Ring 3) cover coherence, codec round-trip, monotonicity. |
| `RecordedChatModel` | Recording + caching ChatModel wrapper | **No** — effectful (Monad, F[_]). RL0/RL5/RL10/RL11 (Ring 3) cover transparency, zero-call hit, write-failure containment, redaction neutrality. |
| `RecordedEmbedder` | Recording + caching Embedder wrapper | **No** — effectful. RL0/RL5 (Ring 3) cover transparency and zero-call hit. |
| `ToolMiddleware.recording` | Recording ToolMiddleware factory | **No** — effectful (Kleisli over IO). Scenario test covers composition. |
| `RecorderLaws` | Hedgehog property testkit | **No** — test infrastructure, not production code. Runs the laws (RL0–RL12) in Ring 3. |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `Recorder[F[_]]` | `F[_]: Applicative` (trait); `Concurrent` for inMemory; `Async` for file | Sink algebra: lookup, record, nextSeq |
| `Recorder.inMemory[F[_]: Concurrent]` | `F[_]: Concurrent` (Ref-backed) | Bounded LRU cache with eviction |
| `Recorder.file[F[_]: Async]` | `F[_]: Async` (Resource, fs2-io) | Append-only JSONL file recorder |
| `RecordedChatModel[F[_]: Monad]` | `F[_]: Monad` | Recording + caching ChatModel decorator |
| `RecordedEmbedder[F[_]: Monad]` | `F[_]: Monad` | Recording + caching Embedder decorator |
| `ToolMiddleware.recording` | `IO` (via Kleisli) | Recording tool middleware |

## Type Strategy — Invalid-State Prevention

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| Empty `RolloutId` cannot be constructed | Best | Iron `RefinedType` (`String :| NonEmpty`) + compile-negative test | Type-level enforcement; `refineEither` returns Left at runtime for dynamic strings; inline literals fail compilation. Follows `NodeKey` precedent. |
| `maxEntries` must be positive | Best | Iron `RefinedType` (`Int :| Positive`) + compile-negative test | Zero/negative capacity is degenerate. Type-level enforcement; `Recorder.inMemory(0)` fails to compile. |
| `CallRecord` failure/success discrimination | Best | Enum ADT (`Succeeded` / `Failed`) | Pattern matching is exhaustive; a record is one or the other, never both. |
| Canonical form excludes non-output-affecting fields | Good | Smart constructor (`CanonicalForm.from`) that omits excluded fields | The canonical form builder is the only way to construct a `CanonicalForm`; it selects fields by construction. RL4 (Ring 3) verifies insensitivity. |
| Tool-call ids are normalized before hashing | Good | `normalizeToolCallIds` applied inside `CanonicalForm.from` | Normalization is called by the canonical form builder, not by the caller. RL normalization-idempotence (Ring 3) + NormalizationModel (Ring 6) verify. |
| Append-only recorder does not overwrite | Good | `Recorder.file` implementation appends only; `lookup` returns first-written | The file backend's `record` appends a JSONL line; `lookup` reads the first match. RL8 (Ring 3) verifies monotonicity. |
| `keyVersion` isolation between algorithm versions | Good | `keyVersion` included in `CanonicalForm`; lookup compares versions | The key includes the version; mismatched versions produce different keys, so old records are invisible (not errors). RL12 (Ring 3) verifies. |
| Classification marker on every record | Best | `CallRecord` ADT requires `classification: Classification` field | Every variant carries the field; it cannot be omitted. |
| Sequence number on every record | Best | `CallRecord` ADT requires `seq: Long` field | Every variant carries the field. |
| Canonicalization is pure (no ambient nondeterminism) | Good | AR-REC-1 Scalafix rule + AR-REC-2 Scalafix rule | Static enforcement: the rules mechanically reject `System.currentTimeMillis`, `Instant.now`, `UUID.randomUUID`, `.hashCode`, and unsorted `Map`/`Set` iteration in the canonical package. |

## Refined Type Strategy

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| `RolloutId` | `String` | `NonEmpty` (Iron `RefinedType`) | Crosses API boundary (supplied to RecordedChatModel); persisted in records. Empty rollout id is semantically absent — must not be constructible. Reuses `NonEmpty` from `org.adk4s.core.types`. |
| `maxEntries` | `Int` | `numeric.Positive` (Iron `RefinedType`) | API boundary value (supplied to `Recorder.inMemory`); zero/negative is degenerate. Reuses `Positive` from `org.adk4s.core.types`. |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `CallKey` (opaque type `String`) | The key is a digest output — its constraint is "is a valid hash", which is not a structural property Iron can enforce. The opaque type prevents accidental String interpolation; the digest function guarantees well-formedness. |
| `CanonicalForm.body` (`CanonicalBody` union) | Internal computation intermediate; not an API boundary value. Generated from Smithy IDL as a typed union (ModelBody/ToolBody/EmbeddingBody). |
| `RecordedError` fields | Internal computation intermediate; error type/message are free-form strings. |
| `Classification` (enum) | Enum ADT — the closed variant set IS the constraint. |
| `CallKind` (enum) | Enum ADT — the closed variant set IS the constraint. |
| `keyVersion` (Int) | A constant, not a user-supplied value. No refinement needed. |
| `seq` (Long) | Monotonically assigned by the recorder, not user-supplied. |

## IDL Model Layout

This change introduces no Smithy/protobuf models. All types are Scala-native
case classes, enums, and opaque types. Recording is an internal library
concern, not a consumer-facing API surface.

## Error Strategy

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `RecorderError` (sealed trait extends `AdkError`) | `SinkWriteFailed(cause: Throwable)`, `SinkReadFailed(cause: Throwable)`, `CodecFailed(message: String, input: String)` | `Recorder.file` (sink I/O), `CallRecord.codec` (JSONL serialization) |

> `RecorderError` extends `AdkError` (the project's existing sealed error
> hierarchy). It is added to the `AdkError` sealed trait as a new variant.
> Existing pattern matches over `AdkError` that have a catch-all arm will
> handle it; matches without a catch-all will need a new case (see
> Type-Widening Impact below).

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Canonical (pure) → Pure | `Either[CanonicalError, CanonicalForm]` | `CanonicalForm.from(req)` returns `Left(CanonicalError)` if a required field is missing |
| Canonical (pure) → Effect | Lift into `F` via `MonadError` | `CanonicalForm.from(req).liftTo[F]` |
| Sink → Wrapper | `Recorder.record` raises in `F`; wrapper catches and surfaces via configured channel | `RecordedChatModel` catches `SinkWriteFailed` and logs it without failing the call (REC-15) |
| Wrapper → Caller | Success result returned; recording failure surfaced separately | Call result reaches caller; warning emitted |

### Type-Widening Impact

`RecorderError` is a new variant of `AdkError`. The inventory records 25
existing `AdkError` variants. Downstream pattern matches:

| Location | Has catch-all? | Behavior for `RecorderError` |
|----------|----------------|------------------------------|
| `ReactAgent.executeToolCalls` | No (exhaustive match) | Will need a new case — compiler error will surface this |
| `AgentRunner.run` | No (exhaustive match) | Will need a new case |
| `AgentEventEmitter` | No | n/a — does not pattern-match `AdkError` |
| Example error handlers | Yes (catch-all `case e: AdkError`) | Handled by catch-all |

> The compiler will surface all non-exhaustive matches as errors when
> `RecorderError` is added. This is the desired behavior — every match
> site must consciously decide how to handle recorder errors.

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `CallRecord` (file recorder) | JSONL (upickle `ReadWriter[CallRecord]`) | Round-trip law: `read(write(r)) == Right(r)` for all generated records | `RecorderCodecSpec` (RL7) |
| `CallRecord` (versioned) | JSONL with `keyVersion` field | Old fixtures written under `keyVersion = n` decode under `keyVersion = n+1` but are invisible to lookup (version isolation) | `CallKeySpec` (RL12) |
| `CanonicalForm` (inspectable) | Generated Schema (smithy4s.json.Json) | Round-trip: `CanonicalFormOps.fromJson(json) == Right(form)` | `CallKeySpec` |

**Fixture obligation**: `old JSONL fixture → decode → expected CallRecord`
and `new CallRecord → encode → decode → same CallRecord`. The first JSONL
file written by the file recorder becomes the v1 fixture; subsequent
canonicalization changes increment `keyVersion` and old fixtures remain
decodable (they carry their own version).

## Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `canonical.normalizeToolCallIds` | Positional tool-call id normalization | **Yes** — `NormalizationModel` mirror. Pure list transformation over `List[Msg]` ADT. Proves idempotence and order-preservation. Bridge test: `NormalizationBridgeSpec` runs shipped function and model on same generated inputs. |
| `RecorderCoherenceModel.record` / `lookup` | Finite-map record/lookup coherence | **Yes** — direct PureScala model. `Map[Int, Int]` with `ensuring` clauses. Proves RL1 pair (lookup after record returns the value; recording under a different key does not affect prior lookups). Bridge test: `RecorderCoherenceBridgeSpec`. |
| `canonical.CanonicalForm.from` | Canonical form construction | **No** — requires llm4s types not modelable in PureScala. CanonicalForm body is a typed union generated from Smithy IDL. RL2/RL3/RL4 (Ring 3) cover determinism, sensitivity, insensitivity. |
| `canonical.CallKey.fromCanonical` | Digest computation | **No** — hash function is uninterpreted in the model; collision-freedom is assumed (stated explicitly). Ring 3 covers determinism. |
| `Recorder.inMemory` eviction | LRU eviction | **No** — effectful (Concurrent, Ref). RL5/RL8 (Ring 3) cover zero-call hit and eviction-preserves-recency. |
| `Recorder.file` append | JSONL append | **No** — effectful (Async, fs2-io). RL7/RL8 (Ring 3) cover codec round-trip and monotonicity. |
| `RecordedChatModel` hit/miss | Cache lookup and recording | **No** — effectful (Monad, F[_]). RL0/RL5/RL10/RL11 (Ring 3) cover transparency, zero-call hit, write-failure containment, redaction neutrality. |
| `RecorderLaws` | Property testkit | **No** — test infrastructure. Runs Ring 3 laws. |

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `org.adk4s.record.canonical` (pure) | ✅ | ✅ | ✅ | ✅ | — | ✅ | partial (NormalizationModel only) | — | ✅ | — |
| `org.adk4s.record` (types + sink) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | partial (RecorderCoherenceModel only) | — | ✅ | — |
| `org.adk4s.record.file` (file recorder) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `org.adk4s.record` (wrappers) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `org.adk4s.record` (RecorderLaws) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.verified` (Ring 6 models) | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | ✅ | — |
| `org.adk4s.examples.record` | ✅ | — | — | ✅ | — | — | — | — | ✅ | — |

> R0 (compile), R1 (lint/WartRemover), R2 (arch/Scalafix), R3 (property/Hedgehog),
> R4 (compatibility/fixture), R5 (mutation/Stryker4s), R6 (Stainless),
> R7 (model check), R8 (adversarial review), R9 (runtime monitor).
> R6 is partial: only the normalization algorithm and recorder coherence
> are modeled in PureScala. The rest of canonicalization (CanonicalForm.from,
> CallKey.fromCanonical) is covered by Ring 3 properties because the types
> involved (llm4s Conversation/Message) are not modelable in PureScala.

## Technical Decisions

### Decision: Content-hash key over provider request id

**Context**: The cache needs a key that is stable across processes. Provider
request ids are randomly generated per call and not repeatable.

**Options considered**:
1. Provider request id — rejected: not stable across processes, not
   repeatable for replay.
2. Sequential counter — rejected: not content-addressed; two identical
   calls get different keys.
3. Content hash of a canonical form — chosen: deterministic, stable across
   processes, content-addressed (identical calls share a key).

**Decision**: Content-hash key (`CallKey`) computed from a `CanonicalForm`
that includes all output-affecting fields and excludes non-output-affecting
fields.

**Consequences**: Canonicalization must be pure and total (AR-REC-1,
AR-REC-2). Tool-call ids must be normalized (REC-4) or multi-turn
conversations never hit beyond turn one. `keyVersion` isolates
algorithm changes.

### Decision: Tool-call id normalization to positional identifiers

**Context**: Providers mint tool-call ids randomly per response. If a raw id
enters the hash, every conversation turn after the first tool call has an
unrepeatable key.

**Options considered**:
1. Strip tool-call ids entirely — rejected: loses the assistant/tool-reply
   pairing, which is semantically meaningful.
2. Hash the ids — rejected: still produces different keys for the same
   logical conversation.
3. Normalize to positional identifiers (`call_0`, `call_1`, ...) in call
   order, applied to both assistant and tool-reply messages — chosen:
   preserves pairing, produces stable keys.

**Decision**: Positional normalization (`normalizeToolCallIds`) applied
inside `CanonicalForm.from`, before hashing.

**Consequences**: Normalization must be idempotent and order-preserving
(proven by `NormalizationModel` at Ring 6). The bridge test
(`NormalizationBridgeSpec`) binds the shipped function to the verified
model.

### Decision: Iron refined types for RolloutId and maxEntries

**Context**: D1 confirmed Iron IS present in the stack (iron + iron-cats
3.3.2 + iron-upickle). The `add-iron-refined-types` change migrated the
project's newtypes to Iron `RefinedType`.

**Options considered**:
1. Plain opaque types with runtime validation — rejected: weaker than
   Iron; doesn't give compile-time checking for literals.
2. Smart constructors with `Either` returns — rejected: the project
   already standardized on Iron `RefinedType` for this pattern.
3. Iron `RefinedType` following `NodeKey` precedent — chosen: consistent
   with the project's established pattern, compile-time for literals,
   `refineEither` for runtime strings.

**Decision**: `RolloutId` as `String :| NonEmpty`, `maxEntries` as
`Int :| Positive`, both reusing constraints from `org.adk4s.core.types`.

**Consequences**: No new dependency (Iron is already in `adk4s-core`).
`RolloutId("")` fails compilation for literals and returns `Left` at
runtime. `Recorder.inMemory(0)` fails compilation.

### Decision: RecorderLaws in main scope (not Test scope)

**Context**: The laws must be consumable by downstream backend authors who
depend on `adk4s-record` as a regular library.

**Options considered**:
1. Test scope only — rejected: downstream modules cannot access Test-scope
   code; they would need to copy the laws.
2. Separate testkit module (like `adk4s-harness-testkit`) — rejected:
   overkill for a single class; the laws are tightly coupled to the
   Recorder trait.
3. Main scope with `hedgehogMunitMain` dependency — chosen: follows the
   `AgentMiddlewareLaws` and `OptimizerLaws` precedents; downstream
   modules get the laws by depending on `adk4s-record`.

**Decision**: `RecorderLaws` in main scope (`org.adk4s.record`), with
`hedgehogMunitMain` as a main-scope dependency.

**Consequences**: `hedgehog-munit` is added to main scope (not `% Test`).
This is the same pattern used by `adk4s-harness-testkit`.

### Decision: Recorder write-failure containment (cache vs journal semantics)

**Context**: When recording fails, should the call fail (journal semantics)
or succeed (cache semantics)?

**Options considered**:
1. Always fail the call on recording failure — rejected: a cache is an
   optimization; a failed write must not take down an agent run.
2. Always swallow the failure silently — rejected: silent failures hide
   bugs.
3. Configurable: `OnWriteFailure.Continue` (default, cache semantics) or
   `OnWriteFailure.Fail` (journal semantics) — chosen: the caller decides
   based on whether the recorder is a cache or an audit journal.

**Decision**: Configurable `OnWriteFailure` enum, defaulting to `Continue`.
The failure is surfaced through the configured warning channel (not
silent), but the call result reaches the caller.

**Consequences**: The `RecordedChatModel` factory takes an optional
`onWriteFailure: OnWriteFailure = OnWriteFailure.Continue` parameter.
This is an open question (§9.3 in the proposal) that the design phase
resolves: the default is `Continue` (cache semantics), with `Fail`
available for journal-backed deployments where the record is the audit
artifact.

### Decision: fs2-io source-scoped to file recorder

**Context**: D3 established that fs2-io is needed for the file recorder but
must be source-scoped per Ring 2.

**Options considered**:
1. fs2-io as a module-wide dependency — rejected: violates Ring 2 purity;
   canonicalization would have access to streaming types.
2. fs2-io in a separate sub-module — rejected: overkill for one file.
3. fs2-io as a module dependency but confined by convention to
   `org.adk4s.record.file` — chosen: the import audit scenario and AR
   rules enforce the boundary.

**Decision**: `fs2-io` is a module-level dependency but imports are
confined to `org.adk4s.record.file`. The canonicalization package
(`org.adk4s.record.canonical`) imports neither `fs2` nor `cats.effect`.

**Consequences**: The "Canonicalization has no fs2 imports" scenario test
verifies the boundary. A future Scalafix rule could mechanically enforce
this if needed.
