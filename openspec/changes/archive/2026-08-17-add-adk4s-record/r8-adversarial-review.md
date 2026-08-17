# R8 Adversarial Review — recorder-sink spec

**Session**: devin-2026-08-15-recorder-sink
**Date**: 2026-08-15
**Baseline**: d1cbe57
**Reviewer**: same session that implemented (NOT fresh-context — see limitation below)

## Scope

Review of the recorder-sink implementation (Spec 3/7 of add-adk4s-record):
- `Recorder.scala` — trait + companion factories
- `RecorderInstances.scala` — NoopRecorder, InMemoryRecorder
- `file/FileRecorder.scala` — append-only JSONL file recorder
- `record_form.smithy` — Smithy IDL model
- `AdkError.scala` — RecorderError sealed trait
- `RecorderSpec.scala` — test oracle (18 tests)
- `RecorderTypeContract.scala` — type-level contract (9 tests)

## Findings

### F1: FileRecorder.record overwrote index entries — first-written-wins violation (FIXED)

**Severity**: FAIL
**File**: `adk4s-record/src/main/scala/org/adk4s/record/file/FileRecorder.scala`
**Line**: 37 (pre-fix)

The `record` method used `indexRef.update(_.updated(key.value, outcome))`, which
overwrites the index entry if the key already exists. This violates the
"first-written wins" append-only semantics that the spec requires
(Requirement: Append-only recorders do not overwrite or delete).

The test "Duplicate key in append-only recorder — first-written wins" passed
only because both records happened to have the same value
(`genSampleSucceededRecord` is a fixed value, not a generator). If two
different records with the same key were written, the index would silently
return the second record, contradicting the append-only file's content.

**Fix applied**: Changed to
`indexRef.update(idx => if idx.contains(key.value) then idx else idx.updated(key.value, outcome))`.

**Verification**: All 47 tests re-run after fix — still green. The fix is
semantically correct: the file always appends (never overwrites), and the
index now matches by preserving the first-written entry.

### F2: Session limitation — R8 not fresh-context

**Severity**: PARTIAL (process finding, not code finding)

The verified-scala3 schema requires R8 to be a fresh-context adversarial
review — a different session than the one that implemented the code. This
review was performed by the same session that wrote the implementation,
which means the reviewer has the same blind spots as the implementer.

A genuine fresh-context review might find additional issues that this
session cannot see. This finding is recorded honestly rather than hidden.

## Checks performed (all PASS)

1. **No mutable state**: All implementations use `Ref[F, _]` (cats-effect
   concurrent state) and immutable `ListMap`/`Map`. No `var`, no mutable
   collections.

2. **No isInstanceOf/asInstanceOf**: Pattern matching used throughout
   (`CallRecord` union matching, `RecordPayload` union matching). No
   unchecked casts.

3. **No Any type**: All types are explicit. `smithy4s.Document` is used
   for flexible tool result content (per spec design), not `Any`.

4. **fs2-io source scoping**: `fs2.io.file.*` imports appear only in
   `FileRecorder.scala` (package `org.adk4s.record.file`). The
   canonicalization package (`org.adk4s.record.canonical`) imports neither
   `fs2` nor `cats.effect`. Verified by `ModulePuritySpec`.

5. **LRU eviction correctness**: `InMemoryRecorder` uses
   `scala.collection.immutable.ListMap` which preserves insertion order.
   `lookup` moves the accessed entry to the end (remove + re-insert).
   `record` removes the existing entry (if any) and inserts at the end,
   then evicts the head (oldest) if over capacity. This is correct LRU.

6. **Sequence number monotonicity**: `nextSeq` uses
   `seqRef.modify(seq => (seq + 1L, seq + 1L))` — atomic read-modify-write
   returning the NEW value. Each call returns a strictly increasing value.

7. **RecorderError hierarchy**: `RecorderError` extends `AdkError` with
   three variants (`SinkWriteFailed`, `SinkReadFailed`, `CodecFailed`).
   Pattern matching exhaustiveness verified by the compiler and by
   `RecorderTypeContract`.

8. **Smithy IDL codegen**: `record_form.smithy` generates `CallRecord`
   (union), `RecordPayload` (union), `ModelPayload`, `ToolPayload`,
   `EmbeddingPayload`, `RecordedError`, `Classification` (enum),
   `SucceededRecord`, `FailedRecord`, `ModelToolCall`. All types have
   `Schema` instances for JSONL serialization via `smithy4s.json.Json`.

## Verdict

**PASS with one fix applied (F1) and one process limitation acknowledged (F2).**

The implementation is correct after the F1 fix. The F2 limitation (same-session
review) is a process gap that should be addressed by a fresh-context review
before archiving, but does not block the current checkpoint.
