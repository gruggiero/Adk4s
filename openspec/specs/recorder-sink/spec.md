# Spec: recorder-sink (Recorder[F] sink algebra)

<!-- Delta spec for the add-adk4s-record change. Defines the
     effect-polymorphic Recorder[F] sink algebra with three reference
     implementations (noop, inMemory, file), append-only invariant, bounded
     eviction, sequence numbering, and failure recording. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | The model call surface whose results the recorder stores and looks up | `openspec/concepts/chat-model.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `CallKey` | opaque type (`String`) | `org.adk4s.record` (introduced by `call-key` spec) |
| `CallKind` | generated enum (`MODEL`/`TOOL`/`EMBEDDING`) | `org.adk4s.record.canonical` (introduced by `call-key` spec, generated from Smithy IDL) |
| `Positive` | type alias (`Int :| numeric.Positive`, Iron) | `org.adk4s.core.types` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Recorder[F[_]]` | trait | Effect-polymorphic sink algebra: `lookup`, `record`, `nextSeq` |
| `CallRecord` | generated union (Smithy IDL) | `Succeeded(key, seq, kind, payload, classification)` / `Failed(key, seq, kind, error, classification)` — first-class failure recording (REC-21). Generated from Smithy IDL via smithy4s codegen, with `Schema[CallRecord]` for JSONL serialization via `smithy4s.json.Json`. |
| `RecordPayload` | generated union (Smithy IDL) | Typed payload per call kind: `model: ModelPayload` / `tool: ToolPayload` / `embedding: EmbeddingPayload`. Replaces untyped `JsonValue` for type-safe redaction and stable serialization. Flexible content (tool results) uses `smithy4s.Document`. |
| `ModelPayload` | generated structure (Smithy IDL) | Model call response: content, tool calls, finish reason, token usage. Converted from llm4s `Completion` at the boundary. |
| `ToolPayload` | generated structure (Smithy IDL) | Tool call response: name, result (`Document`), callId, isError. Converted from `ToolOutput` at the boundary. |
| `EmbeddingPayload` | generated structure (Smithy IDL) | Embedding response: vector, model, token count. |
| `RecordedError` | generated structure (Smithy IDL) | Captured failure detail for replay fidelity: error type, message, optional cause. |
| `Classification` | generated enum (Smithy IDL) | Data-classification marker traveling with every record (REC-23). |
| `Recorder.noop` | implementation | Records nothing, never hits |
| `Recorder.inMemory` | implementation | Bounded, evictable, for tests and optimizer sweeps |
| `Recorder.file` | implementation (Resource) | Append-only JSONL, for reproducible local runs |

## ADDED Requirements

### Requirement: Recorder is an effect-polymorphic sink algebra

The system SHALL define an effect-polymorphic sink algebra `Recorder[F[_]]`
with operations `lookup(key: CallKey): F[Option[CallRecord]]`,
`record(key: CallKey, outcome: CallRecord): F[Unit]`, and
`nextSeq: F[Long]`, over which all recording backends are implemented.

**Given** a `Recorder[F]` instance
**When** `lookup`, `record`, or `nextSeq` is called
**Then** the operation is effectful in `F` and returns `Option[CallRecord]`,
`Unit`, or `Long` respectively

**Rationale**: A pluggable sink algebra lets the bounded cache and the
append-only file share one wrapper layer, with invalidation semantics
differing only in the backend.

#### Scenario: Noop recorder returns None on lookup

**Given** a `Recorder.noop` instance
**When** `lookup(anyKey)` is called
**Then** the result is `None`

#### Scenario: Noop recorder's record is a no-op

**Given** a `Recorder.noop` instance
**When** `record(anyKey, anyRecord)` is called
**Then** the operation completes with `Unit` and stores nothing

### Requirement: Three reference implementations

The system SHALL ship three reference implementations: `Recorder.noop`
(records nothing, never hits), `Recorder.inMemory` (bounded, evictable, for
tests and optimizer sweeps), and `Recorder.file` (append-only JSONL, for
reproducible local runs).

**Given** the recorder companion object
**When** `Recorder.noop`, `Recorder.inMemory(maxEntries)`, or
`Recorder.file(path)` is called
**Then** a recorder (or a resource-managed recorder for the file backend) is
produced with the specified behavior

**Rationale**: The three backends cover the two storage shapes (bounded
cache vs append-only log) plus a transparent no-op for unwrapped operation.

#### Scenario: InMemory recorder is bounded

**Given** a `Recorder.inMemory(maxEntries = 3)` with 3 entries already
recorded
**When** a 4th entry is recorded
**Then** one existing entry is evicted and the recorder holds at most 3
entries

#### Scenario: File recorder appends JSONL

**Given** a `Recorder.file(path)` resource
**When** a record is written
**Then** a JSONL line is appended to the file at `path`

### Requirement: Append-only recorders do not overwrite or delete

The system SHALL NOT overwrite or delete a previously written record while a
recorder is in append-only mode.

**Given** an append-only recorder (file backend) with a record at key K
**When** `record(K, newRecord)` is called
**Then** the original record remains unchanged (a new line may be appended,
but the original is not overwritten or deleted)

**Rationale**: An append-only journal is an audit artifact. Overwriting or
deleting destroys the audit trail.

#### Scenario: Duplicate key in append-only recorder

**Given** a file recorder with a record at key K
**When** the same key K is recorded again
**Then** the original record is still retrievable via `lookup(K)` (the
first-written record wins for lookup in append-only mode)

### Requirement: Bounded recorder evicts without failing the call

The system SHALL evict entries when a bounded recorder exceeds its
configured entry limit, and SHALL NOT fail the call when eviction occurs.

**Given** a bounded in-memory recorder at capacity
**When** a new entry is recorded that exceeds the limit
**Then** an entry is evicted and the `record` operation completes
successfully with `Unit`

**Rationale**: A cache is an optimization; eviction is normal operation,
not an error.

#### Scenario: Eviction at capacity

**Given** a `Recorder.inMemory(maxEntries = 2)` with entries at keys A and B
**When** key C is recorded
**Then** one of A or B is evicted, C is stored, and `record` returns `Unit`

### Requirement: Sequence numbers are monotonically increasing

The system SHALL assign each recorded call a monotonically increasing
sequence number within its recording scope.

**Given** a recorder with current sequence number N
**When** `nextSeq` is called
**Then** the result is N+1, and the next call returns N+2

**Rationale**: Sequence numbers enable a later replay driver to detect
divergence by sequence while still serving hits by content.

#### Scenario: Sequence increments on each call

**Given** a fresh recorder
**When** `nextSeq` is called three times
**Then** the results are 1, 2, 3 (or 0, 1, 2 — the starting value is
implementation-defined but the increment is monotonic)

### Requirement: Records carry both key and sequence number

The system SHALL ensure a record carries both its `CallKey` and its
sequence number, so that a later replay driver can detect divergence by
sequence while still serving hits by content.

**Given** a recorded call
**When** the `CallRecord` is inspected
**Then** it contains both `key: CallKey` and `seq: Long`

**Rationale**: Without the sequence number, replay can detect content
mismatches but not ordering mismatches.

#### Scenario: Record contains key and seq

**Given** a call recorded at key K with sequence number 5
**When** the record is looked up
**Then** the returned `CallRecord` has `key == K` and `seq == 5`

### Requirement: Failures are recorded as first-class outcomes

The system SHALL record failures as first-class outcomes, distinguishable
from successes, so that replaying a run that failed diverges at the failure
point rather than silently succeeding.

**Given** a call that failed with an error
**When** the call is recorded
**Then** the stored record is a failure variant carrying the error
detail, not a success variant

**Rationale**: If failures are not recorded, replaying a run that failed
diverges at the failure point, and the runs most worth replaying are the
ones that went wrong.

#### Scenario: Failed call is recorded as failure variant

**Given** a model call that returned an error
**When** the call is recorded
**Then** looking up the call's key returns a failure record carrying the
key, sequence number, call kind, error detail, and classification

#### Scenario: Successful call is recorded as success variant

**Given** a model call that returned a completion
**When** the call is recorded
**Then** looking up the call's key returns a success record carrying the
key, sequence number, call kind, payload, and classification

### Requirement: Every record carries a data-classification marker

The system SHALL ensure every record carries a data-classification marker
supplied at recorder construction, so that the classification travels with
the data rather than being a property of where it happens to be stored.

**Given** a recorder constructed with classification `Classification.Internal`
**When** any call is recorded
**Then** the stored `CallRecord` carries `classification ==
Classification.Internal`

**Rationale**: A record contains the full prompt and completion. In a
regulated deployment that is a classified artifact, and the classification
must travel with the data.

#### Scenario: Classification travels with record

**Given** a recorder constructed with `Classification.Confidential`
**When** a call is recorded and looked up
**Then** the record's `classification` field is `Classification.Confidential`

## Properties (Ring 3)

### Property: record-lookup-coherence

**Invariant**: `lookup(k)` after `record(k, v)` returns `Some(v)`, and
`lookup(k)` after `record(j, v)` (j != k) returns the prior value of
`lookup(k)` (unaffected).

**Generator strategy**: `genCallKey` + `genCallRecord` — constructive over
distinct keys and arbitrary records (both `Succeeded` and `Failed`
variants). Edge cases: same key recorded twice, empty recorder.

```
forAll { (k: CallKey, j: CallKey, v: CallRecord) =>
  for {
    _ <- recorder.record(k, v)
    r1 <- recorder.lookup(k)
    _ <- recorder.record(j, v)
    r2 <- recorder.lookup(k)
  } yield (r1 == Some(v)) && (r2 == r1)
}
```

### Property: append-only-monotonicity

**Invariant**: For an append-only recorder, sequence numbers are strictly
increasing and no `lookup` mutates state.

**Generator strategy**: `genSeqOps` — constructive over sequences of
`nextSeq` and `lookup` calls. Edge cases: only lookups (no nextSeq), only
nextSeq, interleaved.

```
forAll { (ops: List[RecorderOp]) =>
  for {
    seqs <- ops.collect { case NextSeq => recorder.nextSeq }.sequence
    _ <- ops.collect { case Lookup(k) => recorder.lookup(k) }.sequence
  } yield seqs.zip(seqs.tail).forall { case (a, b) => a < b }
}
```

### Property: codec-round-trip

**Invariant**: `read(write(record)) == record` for every generated record,
including failures.

**Generator strategy**: `genCallRecord` — constructive over both
`Succeeded` and `Failed` variants with arbitrary payloads, errors, and
classifications. Edge cases: empty payload, error with empty message,
minimal classification.

```
forAll { (record: CallRecord) =>
  val json = smithy4s.json.Json.writeBlob(record)(using CallRecord.schema).toUTF8String
  val decoded = smithy4s.json.Json.read[CallRecord](smithy4s.Blob(json))
  decoded == Right(record)
}
```

### Property: failure-fidelity

**Invariant**: A recorded failure replays as an equal failure, not as a
success and not as a different error.

**Generator strategy**: `genRecordedError` — constructive over
`RecordedError` values with various error types and messages. Edge cases:
empty error message, unknown error type.

```
forAll { (err: RecordedError, key: CallKey) =>
  for {
    _ <- recorder.record(key, CallRecord.Failed(key, 0, CallKind.MODEL, err, Classification.Internal))
    lookup <- recorder.lookup(key)
  } yield lookup match {
    case Some(CallRecord.Failed(_, _, _, e, _)) => e == err
    case _ => false
  }
}
```

### Property: bounded-eviction-preserves-recency

**Invariant**: A bounded recorder evicts the least-recently-used entry when
at capacity, and the most-recently-written entry is always present.

**Generator strategy**: `genBoundedOps` — constructive over sequences of
`record` and `lookup` calls with a bounded recorder of known capacity.
Edge cases: capacity 1, record exactly at capacity.

```
forAll { (ops: List[RecordOp], capacity: Int) =>
  capacity > 0 ==>
    for {
      recorder <- Recorder.inMemory[IO](capacity)
      _ <- ops.traverse_(op => recorder.record(op.key, op.record))
      lastKey = ops.lastOption.map(_.key)
      lookup <- lastKey match {
        case Some(k) => recorder.lookup(k).map(_.isDefined)
        case None => IO.pure(true)
      }
    } yield lookup
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `Recorder.inMemory(0)` | Zero-capacity recorder is degenerate; `maxEntries` must be positive | `assertLeft(Recorder.inMemory(0))` or Iron `Positive` constraint rejects 0 |
| `Recorder.inMemory(-1)` | Negative capacity is nonsensical | Iron `Positive` constraint rejects at type level |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Recorder is effect-polymorphic | Requirement: Recorder is an effect-polymorphic sink algebra | type system (trait `Recorder[F[_]]`) + scenario test | `RecorderSpec.scala` |
| Three reference implementations | Requirement: Three reference implementations | scenario test | `RecorderSpec.scala` |
| Append-only does not overwrite | Requirement: Append-only recorders do not overwrite or delete | property test (append-only-monotonicity) + scenario test | `RecorderSpec.scala` |
| Bounded eviction does not fail | Requirement: Bounded recorder evicts without failing the call | property test (bounded-eviction-preserves-recency) + scenario test | `RecorderSpec.scala` |
| Sequence numbers monotonic | Requirement: Sequence numbers are monotonically increasing | property test (append-only-monotonicity) | `RecorderSpec.scala` |
| Records carry key and seq | Requirement: Records carry both key and sequence number | type system (CallRecord fields) + scenario test | `RecorderSpec.scala` |
| Failures recorded as first-class | Requirement: Failures are recorded as first-class outcomes | property test (failure-fidelity) + scenario test | `RecorderSpec.scala` |
| Classification marker on every record | Requirement: Every record carries a data-classification marker | type system (CallRecord.classification field) + scenario test | `RecorderSpec.scala` |
| Codec round-trip | Property: codec-round-trip | property test (codec-round-trip) | `RecorderSpec.scala` |
| maxEntries positive | Requirement: Bounded recorder evicts without failing the call | type system (Iron `Positive`) + compile-negative test | `RecorderTypeContract.scala` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Recorder[F[_]]` | trait | `org.adk4s.record` | `lookup`, `record`, `nextSeq` |
| `CallRecord` | generated union | `org.adk4s.record` | Generated from Smithy IDL. `Succeeded` / `Failed` ADT with `Schema[CallRecord]` for JSONL via `smithy4s.json.Json`. |
| `RecordPayload` | generated union | `org.adk4s.record` | Generated from Smithy IDL. `model: ModelPayload` / `tool: ToolPayload` / `embedding: EmbeddingPayload`. |
| `ModelPayload` | generated structure | `org.adk4s.record` | Model call response fields. Converted from llm4s `Completion` at boundary. |
| `ToolPayload` | generated structure | `org.adk4s.record` | Tool response: name, result (`Document`), callId, isError. |
| `EmbeddingPayload` | generated structure | `org.adk4s.record` | Embedding response: vector, model, token count. |
| `RecordedError` | generated structure | `org.adk4s.record` | error type, message, optional cause |
| `Classification` | generated enum | `org.adk4s.record` | `Public`, `Internal`, `Confidential`, `Restricted` (MUST-CONFIRM — do not invent: final set confirmed at design phase) |
| `Recorder.noop` | object/factory | `org.adk4s.record` | `Recorder[F[_]: Applicative]` |
| `Recorder.inMemory` | factory | `org.adk4s.record` | `Recorder[F[_]: Concurrent](maxEntries: Int :| Positive): F[Recorder[F]]` |
| `Recorder.file` | factory (Resource) | `org.adk4s.record` | `Recorder[F[_]: Async](path: fs2.io.file.Path): Resource[F, Recorder[F]]` — fs2-io source-scoped to this file |
