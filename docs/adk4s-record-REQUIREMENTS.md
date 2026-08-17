# REQUIREMENTS — `adk4s-record`

**Deterministic call recording and content-hash caching for ADK4S**

**Status:** Draft for OpenSpec change authoring
**Target modules:** `adk4s-record` (new), `verified` (Ring 6 model), `adk4s-examples` (demo)
**Depends on:** `adk4s-core` (`ChatModel`, `InvokableTool`, `ToolInput`/`ToolOutput`, `Embedder`, `AdkError`)
**Consumed by:** `adk4s-optimize` (Phase 1+), DACE working-memory spike, future `adk4s-journal`
**Informed by:** `activegraph-port-analysis.md` §2/§6/§12/§15, `dspy-port-analysis.md` §2 row 11, §3 (gap row), §4.7, §5 hard-part 4, §7 risk 2

---

## 0. Verification note

This document was written against `build.sbt` at `main`. Two things could not be
verified from that file and **must be checked against the landed source before
the change is authored**:

- **V1.** Whether `AgentMiddleware.promptSections` takes a `HarnessState`
  parameter. The Phase 0 design specified it static (`def promptSections:
  List[PromptSection]`), which prevents any middleware whose prompt contribution
  is derived from state — memory recall, skills, and DACE's notebook — from
  using the section mechanism. Not blocking for this change, but it is a
  prerequisite for the DACE change that follows.
- **V2.** The exact ring numbering of the middle rings. Ring 0 (scalac
  exhaustiveness escalation), Ring 1 (WartRemover), Ring 2 (module purity), and
  Ring 6 (Stainless) are confirmed by `build.sbt` comments. Rings 3–5 are
  referenced by name below rather than number.

---

## 1. Motivation

Three independent roadmaps converge on one component. `dspy-port-analysis.md`
§4.7 states it directly: the rollout-id cache "is the same recording middleware
the ActiveGraph analysis wants for replay. One component, two roadmaps served."
It is three:

| Consumer | Needs recording for | Blocking? |
|---|---|---|
| `adk4s-optimize` Phase 1+ | Candidate evaluation without re-spending tokens; deliberate resampling for `BestOfN`/bootstrap rounds | **Yes** — §7 risk 2: "Do not ship BootstrapRS/GEPA before the content-hash cache exists" |
| DACE working memory | Replayable consolidation; fork-and-diff to tune retention weights empirically | **Yes** — without it, compaction policy is untunable folklore |
| `adk4s-journal` / replay | Deterministic replay over nondeterministic model calls | Yes, when scheduled |

The component is small, has no unresolved design risk, and is a strict subset of
the event-log substrate — nothing built here is wasted if the reactive kernel is
later built on top.

### 1.1 The tension this change resolves

The two source analyses assume **different storage shapes** for the same
component:

- **ActiveGraph's** version is *log-backed*: the cache **is** the event log, a
  hit is a lookup of a recorded `llm.responded` event within a run, the record
  is the audit artifact, and it **never invalidates**.
- **DSPy's** version is a *side cache*: keyed by content hash plus `rolloutId`,
  scoped to an optimizer sweep, bounded and evictable, and it **must** invalidate.

These are compatible but not the same object. The resolution adopted here is a
single recording middleware over a **pluggable `Recorder[F]` sink** (§4.2), with
two conforming backends. Deciding this at design time is cheaper than building
one and retrofitting the other, because invalidation semantics differ in
opposite directions (§5.4).

---

## 2. Scope

### 2.1 In scope

1. A canonical, stable content-hash key (`CallKey`) over LLM, tool, and
   embedding calls.
2. A `Recorder[F]` sink algebra with in-memory and file-backed reference
   implementations.
3. Recording/caching wrappers: `RecordedChatModel[F]`, a recording
   `ToolMiddleware`, and `RecordedEmbedder[F]`.
4. `RolloutId` semantics for deliberate resampling.
5. Sequenced records sufficient for later strict-replay divergence detection.
6. A `RecorderLaws` property testkit in **main scope** (the `adk4s-optimize`
   precedent, which ships `OptimizerLaws` in main scope rather than adding a
   separate testkit module).
7. A redaction hook and a data-classification marker on records.
8. A Ring 6 Stainless model of recorder coherence and canonicalization
   idempotence.

### 2.2 Out of scope (this change)

- **Streaming recording.** `ChatModel.stream`/`streamContent` pass through
  unrecorded. Consistent with the Phase 0 harness design, which deferred
  streaming through `wrapModelCall` for the same reason: no current consumer
  needs it, and replaying a chunk sequence without original timing is a
  different contract that should be designed when something needs it. See §9.1.
- **Persistent event log / journal.** No SQLite or Postgres backend, no
  `runId`-scoped store, no fork. `Recorder[F]` is shaped so those slot in behind
  it unchanged.
- **Strict replay execution.** This change records the data strict replay needs
  (§4.5) and specifies the divergence contract as a law, but the replay driver
  itself belongs to the journal change.
- **Any optimizer.** `adk4s-optimize` Phase 1 is a separate change that depends
  on this one.
- **Cache eviction policy beyond a size bound.** No LRU tuning, no TTL.

---

## 3. Module placement

```scala
// ── adk4s-record — deterministic call recording + content-hash cache ───────
// CallKey (canonical content hash), Recorder[F] sink algebra, CallRecord ADT,
// RecordedChatModel/RecordedEmbedder/ToolMiddleware.recording, RolloutId,
// Redaction, and RecorderLaws (main scope — the adk4s-optimize precedent).
// Depends on adk4s-core for ChatModel, InvokableTool, ToolInput/ToolOutput,
// Embedder, AdkError, and the ujson serialization currency.
// Depends on `verified` at Test scope for the Ring 6 bridge (TASTy is
// backward compatible: 3.8.4 reads 3.7.2).
// MUST NOT depend on workflows4s, adk4s-orchestration, adk4s-optimize,
// adk4s-eval, fs2-io, or logback (Ring 2 purity rule).
lazy val `adk4s-record` = (project in file("adk4s-record"))
  .dependsOn(`adk4s-core`, `verified` % Test)
  .settings(
    name := "adk4s-record",
    libraryDependencies ++= Seq(
      catsEffect, fs2Core, munitMain, munitCatsEffect, hedgehogMunit
    ) ++ upickle ++ testDeps,
    scalacOptions ++= scala3Options
  )
```

**Placement rationale.** The wrappers target `adk4s-core` types, so the module
must sit directly above core and below every consumer. It must *not* live in
`adk4s-core` itself: core stays free of caching and provenance vocabulary, the
same reasoning that produced `adk4s-memory-api` and `adk4s-harness-api`.

`adk4s-orchestration` gains no dependency in this change — recording is opt-in
at wiring time by wrapping the `ChatModel` a caller passes in.

---

## 4. Functional requirements

Requirements use EARS phrasing. `REC-n` are numbered for OpenSpec traceability.

### 4.1 Call keys

- **REC-1 (ubiquitous).** The system shall compute a `CallKey` as a
  cryptographic digest of a canonical form of the call request.
- **REC-2 (ubiquitous).** The canonical form shall include, for a model call:
  provider id, model id, the ordered message list, the ordered tool-definition
  list (name, description, parameter schema), the system prompt, the completion
  options that affect output (`temperature`, `maxTokens`, `topP`,
  `stopSequences`), the output schema where one is supplied, the `RolloutId`
  where one is supplied, and the `keyVersion` constant.
- **REC-3 (ubiquitous).** The canonical form shall exclude wall-clock
  timestamps, provider request ids, latency, token-usage figures, and any field
  that does not affect the model's output distribution.
- **REC-4 (event-driven).** When the canonical form contains provider-generated
  tool-call identifiers, the system shall normalize them to positional
  identifiers (`call_0`, `call_1`, …) in call order before hashing.

  > *Rationale — this is the failure mode that silently destroys hit rates.*
  > Providers mint tool-call ids randomly per response. If a raw id enters the
  > hash, then every conversation turn after the first tool call has an
  > unrepeatable key and the cache never hits beyond turn one. Normalization
  > must be order-preserving and applied consistently to both the
  > `AssistantMessage.toolCalls` ids and the matching `ToolMessage` reply ids,
  > so the pairing survives.

- **REC-5 (ubiquitous).** Canonicalization shall be a pure, total function with
  no dependency on ambient time, locale, iteration order of unordered
  collections, or system entropy.
- **REC-6 (unwanted).** If two requests differ in any field named in REC-2, then
  the system shall produce different `CallKey` values.
- **REC-7 (unwanted).** If two requests differ only in fields named in REC-3,
  then the system shall produce identical `CallKey` values.
- **REC-8 (ubiquitous).** The system shall expose the canonical form as an
  inspectable value (not only its digest), so that a key mismatch can be
  diagnosed by diffing canonical forms rather than comparing hashes.

### 4.2 The `Recorder[F]` sink

- **REC-9 (ubiquitous).** The system shall define an effect-polymorphic sink
  algebra over which all recording backends are implemented.
- **REC-10 (ubiquitous).** The system shall ship three reference
  implementations: `Recorder.noop` (records nothing, never hits),
  `Recorder.inMemory` (bounded, evictable, for tests and optimizer sweeps), and
  `Recorder.file` (append-only JSONL, for reproducible local runs).
- **REC-11 (state-driven).** While a recorder is in append-only mode, the system
  shall not overwrite or delete a previously written record.
- **REC-12 (event-driven).** When a bounded recorder exceeds its configured
  entry limit, the system shall evict entries and shall not fail the call.

### 4.3 Wrapper transparency

- **REC-13 (ubiquitous).** A wrapped component shall be observationally
  equivalent to the component it wraps when configured with `Recorder.noop`.
- **REC-14 (event-driven).** When a lookup hits, the system shall return the
  recorded result and shall perform zero calls against the wrapped component.
- **REC-15 (event-driven).** When a lookup misses, the system shall call the
  wrapped component, record the result under the computed key, and return it.
- **REC-16 (unwanted).** If recording a result fails, then the system shall
  return the call's result to the caller and shall surface the recording failure
  through the configured failure channel without failing the call.

  > *Rationale.* A cache is an optimization; a failed write must not take down
  > an agent run. Note this requirement is **inverted** for a journal-backed
  > recorder, where the record *is* the audit artifact — see §9.3.

### 4.4 Rollout ids and sampling

- **REC-17 (optional feature).** Where a `RolloutId` is supplied, the system
  shall include it in the canonical form, such that distinct rollout ids produce
  distinct keys for otherwise identical requests.
- **REC-18 (unwanted).** If a request specifies a nonzero temperature and no
  `RolloutId`, then the system shall record the call and emit a diagnostic
  warning that a sampled result is being made deterministic by caching.

  > *Rationale.* Silently serving one sample forever from a temperature-1.0
  > call corrupts `BestOfN` and every bootstrap round. The behavior is
  > deliberate (deterministic replay sometimes wants exactly this), so it warns
  > rather than failing — but it must not be silent.

### 4.5 Sequencing for replay

- **REC-19 (ubiquitous).** The system shall assign each recorded call a
  monotonically increasing sequence number within its recording scope.
- **REC-20 (ubiquitous).** A record shall carry both its `CallKey` and its
  sequence number, so that a later replay driver can detect divergence by
  sequence while still serving hits by content.
- **REC-21 (ubiquitous).** The system shall record failures as first-class
  outcomes, distinguishable from successes.

  > *Rationale.* If failures are not recorded, replaying a run that failed
  > diverges at the failure point, and the runs most worth replaying are the
  > ones that went wrong.

### 4.6 Redaction and classification

- **REC-22 (optional feature).** Where a redaction function is configured, the
  system shall apply it to the record payload before the record reaches the
  sink, and shall apply it after key computation so that redaction does not
  change hit rates.
- **REC-23 (ubiquitous).** Every record shall carry a data-classification marker
  supplied at recorder construction.

  > *Rationale.* A record contains the full prompt and completion. In a
  > regulated deployment that is a classified artifact, and the classification
  > must travel with the data rather than being a property of where it happens
  > to be stored.

---

## 5. Data model

Illustrative, not prescriptive; the OpenSpec change may refine names.

```scala
package org.adk4s.record

import cats.effect.*
import cats.syntax.all.*

/** Content hash of a canonical request form. Stable across processes and JVMs. */
opaque type CallKey = String
object CallKey:
  def fromCanonical(c: CanonicalForm): CallKey = ...
  extension (k: CallKey) def value: String = k

/** Deliberate-resampling discriminator. Absent = deterministic mode. */
opaque type RolloutId = String
object RolloutId:
  def apply(s: String): Either[String, RolloutId] = ...   // Iron: non-empty
  extension (r: RolloutId) def value: String = r

/** Inspectable canonical form — REC-8. Diffable when keys mismatch. */
final case class CanonicalForm(keyVersion: Int, kind: CallKind, body: ujson.Obj)

enum CallKind:
  case Model, Tool, Embedding

enum CallRecord:
  case Succeeded(key: CallKey, seq: Long, kind: CallKind,
                 payload: ujson.Value, classification: Classification)
  case Failed(key: CallKey, seq: Long, kind: CallKind,
              error: RecordedError, classification: Classification)

trait Recorder[F[_]]:
  def lookup(key: CallKey): F[Option[CallRecord]]
  def record(key: CallKey, outcome: Outcome): F[Unit]
  def nextSeq: F[Long]

object Recorder:
  def noop[F[_]: Applicative]: Recorder[F]                          = ...
  def inMemory[F[_]: Concurrent](maxEntries: Int): F[Recorder[F]]   = ...
  def file[F[_]: Async](path: fs2.io.file.Path): Resource[F, Recorder[F]] = ...
```

### 5.1 Wrappers

```scala
object RecordedChatModel:
  def apply[F[_]: Monad](
    under: ChatModel[F],
    recorder: Recorder[F],
    rollout: Option[RolloutId] = None
  ): ChatModel[F]

object ToolMiddleware:
  /** Composes with the existing logging/timing/retry/validation set. */
  def recording[F[_]: Monad](recorder: Recorder[F]): ToolMiddleware[F]
```

`RecordedChatModel` returns a `ChatModel[F]`, so it is a drop-in at every
existing call site including `ReactAgent.create` and the harness `ModelStep`
base — no orchestration change required.

### 5.2 Where `fs2` is and is not used

`fs2Core` is a dependency for the file recorder's JSONL append path only.
Canonicalization and key computation are pure and must remain free of streaming
and effect types (Ring 2 rule, §7.3).

### 5.3 Interaction with the harness

Recording sits **below** the middleware stack: it wraps the `ModelStep` base,
not the stack. This matters because middlewares rewrite requests
(`SummarizationMiddleware` compacts messages; a memory middleware injects a
prompt section), and the key must reflect what was actually sent to the
provider, not what the caller originally supplied.

### 5.4 Invalidation

There is exactly one invalidation axis, and it is not semantic.

Because instructions, prompts, adapter output and tool schemas all live *inside*
the hashed canonical form, a changed instruction is already a changed key — no
explicit invalidation is required for any semantic change. `dspy-port-analysis.md`
§5 lists "cache invalidation when adapters/instructions change" as a hard part;
a faithful content hash dissolves it.

What *does* require invalidation is a change to the canonicalization algorithm
itself. Hence:

- **REC-24 (ubiquitous).** The system shall include a `keyVersion` integer in
  every canonical form, and shall increment it whenever canonicalization changes
  in any way that alters computed keys.
- **REC-25 (ubiquitous).** Records written under a different `keyVersion` shall
  be treated as absent by lookup rather than as errors.

This gives the two backends the divergent behavior they need without divergent
code: an append-only journal keeps old-version records forever and simply stops
hitting them; a bounded cache evicts them on the next sweep.

---

## 6. Laws — `RecorderLaws`

Packaged in main scope (the `adk4s-optimize` `OptimizerLaws` precedent),
parameterized over a `Recorder[F]` under test, producing Hedgehog properties any
backend author can run.

| Law | Statement |
|---|---|
| **RL0 — Transparency** | `RecordedChatModel(under, noop) ≍ under` across generated conversations, tool sets, and failures. The gatekeeper property, mirroring harness L0. |
| **RL1 — Record/lookup coherence** | `lookup(k) . record(k, v) == Some(v)`, and `lookup(k) . record(j, v) == lookup(k)` for `k ≠ j`. |
| **RL2 — Key determinism** | Canonicalizing the same request twice, in different processes, yields equal keys. |
| **RL3 — Key sensitivity** | For every generated mutation of a REC-2 field, the key changes. |
| **RL4 — Key insensitivity** | For every generated mutation of a REC-3 field, including tool-call id regeneration, the key is unchanged. |
| **RL5 — Zero-call hit** | With a warm recorder and a call-counting `ChatModel` double, a hit performs exactly zero underlying calls and returns the recorded completion. |
| **RL6 — Rollout separation** | Distinct rollout ids ⇒ distinct keys; equal rollout ids ⇒ equal keys. |
| **RL7 — Codec round trip** | `read(write(record)) == record` for every generated record, including failures. |
| **RL8 — Append-only monotonicity** | For an append-only recorder, sequence numbers are strictly increasing and no `lookup` mutates state. |
| **RL9 — Failure fidelity** | A recorded failure replays as an equal failure, not as a success and not as a different error. |
| **RL10 — Write-failure containment** | When the sink throws, the wrapped call's result still reaches the caller (REC-16). |
| **RL11 — Redaction neutrality** | Redaction changes the stored payload and does not change the key (REC-22). |
| **RL12 — Version isolation** | Records written under `keyVersion = n` are invisible to lookups at `keyVersion = n+1` (REC-25). |

RL3 and RL4 together are the real specification of the canonicalizer; they
should be written as generator-driven mutation properties over a
`RequestMutation` ADT, not as example tests.

---

## 7. Verified-scala3 ring acceptance

### 7.1 Ring 0 — compiler

Compiles under `scala3Options` with `PatternMatchExhaustivity` and
`MatchCaseUnreachable` escalated to errors. `RolloutId` construction is
Iron-refined non-empty; `maxEntries` is refined positive.

### 7.2 Ring 1 — WartRemover / Scalafix

Clean under `Warts.unsafe` minus the three project-wide exclusions
(`TripleQuestionMark`, `Any`, `DefaultArguments`). No new exclusions may be
introduced by this module.

### 7.3 Ring 2 — module purity and semantic rules

Dependency rule: `adk4s-record` must not depend on `workflows4s`,
`adk4s-orchestration`, `adk4s-optimize`, `adk4s-eval`, `fs2-io` outside the file
recorder, or `logback`.

Two module-specific `scalafix-arch-rules` are proposed:

- **AR-REC-1 — No ambient nondeterminism in canonicalization.** No reference to
  `System.currentTimeMillis`, `Instant.now`, `java.util.Random`, `UUID.randomUUID`,
  or `.hashCode` on a non-value type within the canonicalization package. This is
  the mechanical enforcement of REC-5, and `.hashCode` is included because JVM
  identity hashes are the classic silent cross-process key instability.
- **AR-REC-2 — No unordered iteration in canonicalization.** No direct iteration
  over `Map`/`Set` without an explicit sort in the canonicalization package.

### 7.4 Property testing

Hedgehog properties RL0–RL12, run against all three reference recorders.
RL3/RL4 must be mutation-generator driven.

### 7.5 Mutation testing

Stryker4s threshold met on the canonicalization package and the `Recorder`
implementations. These are the two places where a surviving mutant means a
silently wrong key or a silently lost record, so the threshold should be set at
or above the project default rather than relaxed.

### 7.6 Ring 6 — Stainless

A pure model mirror in `verified`, deliberately small, with two targets:

1. **Recorder coherence** — the RL1 pair, in the same shape as the
   `HarnessState` get/set coherence proof already carried out for
   `adk4s-harness-api`. The model is a finite map; the proof is mechanical and
   the precedent exists.
2. **Normalization idempotence and order preservation** — modelling tool-call
   ids as an abstract ordered list, prove that `normalize . normalize ==
   normalize` and that normalization preserves call order and the
   assistant/tool pairing relation. This is the REC-4 property, and it is the
   one canonicalization detail where a bug is both easy to introduce and
   invisible in ordinary tests.

Hash collision-freedom is assumed, not proven; the model treats the digest as an
injective abstract function and states that assumption explicitly.

---

## 8. Exit criteria

1. `adk4s-record` compiles under the full ring set; AR-REC-1 and AR-REC-2 are
   implemented in `scalafix-arch-rules` and green.
2. RL0–RL12 green against `noop`, `inMemory`, and `file` recorders.
3. Stryker4s threshold met on canonicalization and recorders.
4. Ring 6 model in `verified` discharges recorder coherence and normalization
   idempotence.
5. An example in `adk4s-examples` runs the same agent twice against a warm file
   recorder and demonstrates a zero-provider-call second run producing an
   identical final `AssistantMessage`.
6. A multi-turn tool-calling conversation achieves a full cache hit on replay —
   the acceptance test for REC-4, and the one most likely to fail first.
7. `adk4s-optimize` can depend on `adk4s-record` without a cycle, unblocking
   its Phase 1.

---

## 9. Open questions

1. **Streaming.** Recording `ChatModel.stream` requires deciding whether replay
   re-emits recorded chunks (losing original timing) or materializes to a single
   completion. Deferred until a consumer needs it; the harness deferred the
   parallel question for the same reason.
2. **Single-flight.** Two identical concurrent calls both miss and both spend
   tokens. A `MapRef`-based single-flight would fix it. Proposed as an optional
   recorder decorator rather than a requirement, since it adds a concurrency
   invariant to a module that otherwise has none.
3. **REC-16 inversion for journal backends.** Write-failure containment is right
   for a cache and wrong for an audit log, where an unrecorded call is a hole in
   the record. Proposal: make the failure policy a recorder-construction
   parameter (`OnWriteFailure.Continue | Fail`), defaulting to `Continue`, and
   let the journal change set it to `Fail`. Flagged rather than decided.
4. **Embedding calls.** `RecordedEmbedder` is in scope, but embedding batches
   are large and the payload dominates record size. Whether to store vectors or
   only a digest plus a pointer is unresolved; storing vectors is proposed for
   the MVP with a note that a journal backend may want otherwise.
5. **Key stability across llm4s upgrades.** If llm4s changes its message
   representation, canonical forms may shift without an intentional
   `keyVersion` bump. Proposal: a golden-file test pinning canonical forms for a
   fixed corpus, so an accidental shift fails CI rather than silently emptying
   every cache.

---

## 10. What follows this change

**DACE v0 spike** (separate change, ~1–2 pw): the dense working-memory codec,
symbol table, retention scorer, and deterministic renderer, measured against
`adk4s-eval` rather than eyeballed — probe questions at 8k/4k/2k/1k budgets,
producing an empirical rate–distortion curve. That change depends on this one
for replayable consolidation and on the V1 `promptSections` question being
resolved.

**`adk4s-optimize` Phase 1** (separate change): `BootstrapFewShot` and
`BootstrapRS`, now unblocked.

Both consume `adk4s-record` without either depending on the other, which is the
test that this module's boundary is real.
