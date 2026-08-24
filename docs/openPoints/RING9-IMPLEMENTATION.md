# Ring 9: Trace-Based Verification — Implementation Design (schema v13)

**Status:** design, ready for `openspec new change` against the verified-scala3 schema
**Supersedes:** `RING5-IMPLEMENTATION.md` (2026-07-29, written against schema **v3**)
**Supersedes transitively:** `ring5-telemetry-verification.md` (concept note)
**Target:** the verified-scala3 schema itself (v13 → v14), dogfooded in `adk4s`
**Date:** 2026-08-22

---

## 0. What changed since the v3 document

The predecessor document is still right about the *substance*: past-time properties,
CI before production, contracts only where refined types cannot reach, widen generators
rather than add assertions, never write observed behaviour into a spec. All of that is
carried forward.

What it is wrong about is the *machinery*, because the workflow it targeted no longer
exists. Between schema v3 and v13 the rings were renumbered, a definition of "correct"
was added, and an evidence substratum was built underneath every ring. A design that
does not bind to that substratum produces a ring that reports PASS on evidence nobody
obtained — which is the exact defect class v12 exists to remove.

### 0.1 Ring renumbering

The v3 document's "Ring 5" is v13's **Ring 9**. Every other ring it names also moved:

| v3 name in the old doc | v13 ring | Note |
|---|---|---|
| Ring 0 — compile | Ring 0 | unchanged |
| Ring 1 — lint | Ring 1 | unchanged |
| — | **Ring 2 — architecture** | new: layer/purity/raw-primitive rules |
| Ring 2 — "generators" / ScalaCheck | **Ring 3 — test oracle** | now MANDATORY for every code-changing spec |
| — | **Ring 4 — wire/persistence compatibility** | new |
| Ring 3 — mutation (stryker) | **Ring 5 — mutation** | dynamically targeted per spec |
| Ring 4 — Stainless | **Ring 6 — formal** | now via the VERIFIED-MIRROR pattern, not "add it to the set" |
| — | **Ring 7 — model checking** | new, optional |
| — | **Ring 8 — adversarial spec-compliance review** | new, MANDATORY, runs BEFORE 5/6/7 |
| **Ring 5 — telemetry** | **Ring 9 — telemetry** | this document |

Read the old document with that substitution and roughly §§1–6 still parse. §7 does not:
every schema file it patches has been rewritten.

### 0.2 What v12/v13 added that this design must now satisfy

| Added | Where | Consequence for Ring 9 |
|---|---|---|
| **A definition of "correct"** — bound → resolved → discharged | `schema.yaml` `invariant_banner`, `description` | A temporal property is not "checked" because a monitor exists. It is checked when a **run** of that monitor is recorded at this baseline. §6.4 |
| **Evidence ledger** (JSONL, append-only, tri-state exit) | `scanner/ledger.sh`, `scanner/ledger-record-contract.jq` | Ring 9 must emit `R9` rows. The ring domain is already closed and **already contains `R9`** — `ledger-record-contract.jq:34`. No ledger change needed. §11.5 |
| **`chain-state.sh`** — bound/resolved/discharged per requirement | `scanner/chain-state.sh` | A temporal property bound only by a Ring 9 monitor shows as undischarged until a green R9 row exists |
| **`checkpoint.sh report`** — Step 13 ring results generated from ledger rows, pasted verbatim | `scanner/checkpoint.sh:61` (`KNOWN_RINGS` already lists `R9`) | The agent may no longer *write* "Ring 9 ✅". It runs `report` and pastes. §6.4 |
| **Pre-execution gate tier** (`tool-call`) + human-grant lock | `hooks/gate.sh:346+` | The v3 doc's writeback rule was prose in Step 8. v13's own changelog says a prose-only control plane is the defect. The rule becomes a machine-decidable refusal. §7.3 |
| **Ring 8 before the expensive rings** | `schema.yaml` STEP 8 (line 1359) | Ring 9 (STEP 11) now runs *after* an adversarial reviewer has already read the diff against the spec. Several v3-era Ring 9 contracts are redundant with Ring 8's findings — the redundancy gate must say so. §6.1 |
| **Capability profile is the authority on the stack** | `openspec/capability-profile.md` | Ring 9 may not assume otel4s. The stack is DETECTED. This is why the core moves to a host-neutral trace model. §3 |
| **Behavioural concept registry** | `openspec/concepts/` (36 files) | Ring 9's own types are concepts and must be registered, or `registry-check.sh` fails |
| **Proof-obligation binding, machine-checked** | `spec-lint.sh` F5–F9 | `Temporal: <n>` is **already** a typed, F6/F8-resolvable Source kind, and "runtime monitor (Ring 9)" is **already** a listed enforcement mechanism (`templates/spec.md:310`). The v3 doc's "add a Ring 5 column to the cross-reference table" is unnecessary — the binding surface exists. §6.3 |
| **Verified-mirror pattern for Ring 6** | `templates/verified-mirror.md`, STEP 10(b) | "Put `SpanContractEvaluator` in the Stainless set" is not a plan. A leaf mirror + a mandatory bridge property test is. §4.4 |
| **bats + shellcheck + shfmt as a declared prerequisite set** | capability profile, `tests/*.bats` (17 suites) | Any scanner check this change ships needs bats tests and a shellcheck-clean script. §11.6 |

### 0.3 What the v3 document got right and is carried forward verbatim in substance

- Ring 9 **detects**; it does not prove. Under sampling it cannot reliably detect.
- **CI first, production staged.** Every hard problem (sampling, lateness, clock skew,
  dropped spans, cardinality) disappears in CI and none of them disappear in production.
- **Past-time, not future-time.** `hot { … }` asserts the inverse of "shall have".
- **Contracts only where the type system cannot reach.** A refined opaque type cannot
  put an invalid value on a span; asserting that it does not is tautological.
- **A violation widens a generator; it does not add an assertion.**
- **Never author a spec scenario from observed behaviour.**
- **Generate inputs and fixtures; never generate properties.**

---

## 1. The thesis, restated under the invariant

> The same specification is enforced at compile time (Ring 0 + types), at test time
> (Ring 3), and at **trace time** (Ring 9); trace-time findings feed back by widening
> generators, not by adding assertions.

v13 adds one clause, and it is the clause that makes Ring 9 a ring rather than a folder
of monitors:

> A temporal property is **bound** when an obligation names it, **resolved** when the
> monitor artifact exists as a tracked file, and **discharged** when a run of that
> monitor is recorded in the evidence ledger at the current baseline. Anything short of
> all three is a claim.

Concretely: `## Temporal Properties (Ring 9)` in a spec is *bound*. `Ring9Contracts.scala`
plus a gate suite is *resolved* (spec-lint F9 checks it at Step 12). An `R9` ledger row
with `exit: 0` at the current baseline SHA is *discharged*. Today the workflow can express
the first two and has never produced the third.

---

## 2. Where Ring 9 already exists in the workflow — and where it is dark

Ring 9 is not greenfield. Its *plumbing* is complete; its *substance* is empty. Both halves
of that statement are load-bearing, and both are verified in this repo today.

### 2.1 The plumbing that already exists (verified 2026-08-22, `adk4s@e45c7fd`)

| Mechanism | Anchor | State |
|---|---|---|
| Ledger accepts `R9` rows | `scanner/ledger-record-contract.jq:34` | ✅ in the closed ring domain |
| Checkpoint renders `R9` | `scanner/checkpoint.sh:61` `KNOWN_RINGS` | ✅ |
| Spec template has the section | `templates/spec.md:240` | ✅ (but see §11.1 — it still teaches the bug) |
| spec-lint checks temporal blocks | `scanner/spec-lint.sh:26` (F5), `:314` | ✅ trigger + response required |
| `Temporal: <n>` is a typed obligation Source | `schema.yaml:510`, `spec-lint.sh:460` | ✅ F6/F8-resolvable |
| "runtime monitor (Ring 9)" is a listed mechanism | `templates/spec.md:310` | ✅ |
| Proposal has the Ring 9 checkbox | `templates/proposal.md:60` | ✅ |
| Applicability is stack-conditional | `schema.yaml:156` | ✅ ("AND the telemetry stack is present per capability-profile") |
| Apply step exists | `schema.yaml:1476` STEP 11 | ⚠️ four bullets; names no artifact, no evaluator, no ledger row |

### 2.2 The gap, stated as a number

```
$ cat $(find openspec -name evidence-ledger.jsonl) | jq -r .ring | sort | uniq -c
    202 R3   33 R0   22 R8   13 R2   12 R6   12 R1   4 R5   3 R4   1 manual
```

**302 recorded evidence rows across every change this repo has shipped. Zero are `R9`.**
Zero `### Temporal:` entries exist in any active or archived adk4s spec; the one spec that
has the section fills it with `Ring 9 is NOT checked (no telemetry stack detected)`
(`openspec/specs/cross-run-memory-example/spec.md:334`).

The v3 document's gap statement was *"21 specs declare temporal properties that nothing
checks."* In this repo the gap is sharper and cheaper to state: **the ring has never
produced a single row of evidence, and the workflow's own capability profile marks it
skipped** (`openspec/capability-profile.md:64` — `Telemetry | none | — | No otel4s/Daut.
Ring 9 skip.`).

That is not an argument for building it. It is the baseline any proposal must beat: Ring 9
is the only ring in this workflow with full plumbing, zero evidence, and no host that
needs it yet.

### 2.3 The consequence for targeting

A Ring 9 design that is otel4s-shaped is unimplementable in the repository where the schema
lives, and therefore untestable by the workflow's own dogfooding discipline. That is the
single biggest change in this document, and §3 acts on it.

---

## 3. Architecture — a host-neutral core with adapters

The v3 design bound the core model to `org.typelevel.otel4s.sdk.trace.data.SpanData`. Under
v13 that is a schema violation: the stack is DETECTED, not assumed, and the detected stack
in the schema's own repo has no telemetry library at all.

```
              ┌──────────────────────────────────────────────────┐
              │  openspec/specs/<capability>/spec.md              │
              │    ## Temporal Properties (Ring 9)     ← EARS     │
              │    ## Implementation Anchors           ← names    │
              │    ## Proof Obligations  (Source: Temporal: n)    │
              └────────────────────┬─────────────────────────────┘
                                   │ hand-written (P1) → parsed (P4)
                                   ▼
              ┌──────────────────────────────────────────────────┐
              │  SpanContract · TemporalProperty   (pure ADTs)    │
              │  TraceEvent                        (the model)    │
              │  Ring9Evaluator                    (pure, total)  │
              └────────────────────┬─────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
┌────────────────┐      ┌────────────────────┐     ┌────────────────────┐
│ ADAPTER: otel4s│      │ ADAPTER: in-process│     │ ADAPTER: fixture   │
│ SpanData →     │      │ event stream →     │     │ OTLP/JSON file →   │
│ TraceEvent     │      │ TraceEvent         │     │ TraceEvent         │
│ (loyalty-      │      │ (adk4s AgentEvent  │     │ (replay of a       │
│  engine)       │      │  + RunPath)        │     │  production find)  │
└───────┬────────┘      └─────────┬──────────┘     └─────────┬──────────┘
        └────────────────────────┬┴──────────────────────────┘
                                 ▼
        ┌────────────────────────────────────────────────────┐
        │ RING 9-CI   TraceGate → List[Violation]            │
        │   ↓ zero violations = green                        │
        │   ↓ ledger.sh append --ring R9                     │
        │   ↓ checkpoint.sh report renders it                │
        └────────────────────────┬───────────────────────────┘
                                 │ violation
                                 ▼
        ┌────────────────────────────────────────────────────┐
        │ TRIAGE (§7) → finding → widened generator (Ring 3) │
        │            → mutation-delta acceptance (Ring 5)    │
        └────────────────────────────────────────────────────┘
```

### 3.1 `TraceEvent` — the one model everything reduces to

```scala
/** A completed, correlated unit of work. The Ring 9 core knows nothing else. */
final case class TraceEvent(
    name:        String,
    correlation: CorrelationId,        // trace id, run id, session id — adapter's choice
    parent:      Option[EventId],
    id:          EventId,
    attributes:  Map[String, AttrValue],
    status:      StatusKind,           // Ok | Error(message) | Unset
    startNanos:  Long,
    endNanos:    Long
)

enum AttrValue:
  case S(v: String)
  case L(v: Long)
  case D(v: Double)
  case B(v: Boolean)
  case Seq(v: List[AttrValue])
```

`AttrValue` is a closed ADT rather than `Any` (the project forbids `Any`; see CLAUDE.md
style rules) and rather than otel4s's `Attribute[?]` (which would drag the dependency into
the core). Adapters lower into it; the evaluator only ever sees it.

### 3.2 The three adapters, and why each earns its place

| Adapter | Source | Why it exists |
|---|---|---|
| **otel4s** | `SpanData` from a `SpanProcessor.onEnd` or `TracesTestkit.inMemory` | The production-grade path. `SpanData` is fully readable — name, attributes, status, timing, span context. `Span[F]` is write-only and always will be; this is the correct injection point. |
| **in-process event stream** | e.g. adk4s's `AgentEvent` + `RunPath` (`org.adk4s.core.interrupt`) | Makes Ring 9 implementable with **no new dependency** in a host that has structured events but no tracer. `RunPath`/`RunStep` is already a hierarchy with a correlation key; `AgentEvent` already carries `ToolCallRequested`/`ToolCallCompleted`/`IterationCompleted`/`Interrupted`. This is a trace in everything but name. |
| **fixture replay** | a captured OTLP span batch or serialized event list on disk | The only way a *production* finding enters CI (§7.4), and the only way a Ring 9 regression test survives the scenario becoming unreproducible. |

The third is not optional. §7.4's load-bearing rule — *a production finding that cannot be
made to fail in CI is not yet understood* — has no mechanism without it.

### 3.3 Non-goals

Stated explicitly, because the concept note blurred them and the v3 doc's list predates
four rings:

- **Ring 9 does not enforce.** It runs after the unit of work ended. Enforcement belongs
  to the domain layer (refined types, explicit policy checks), verified by Rings 0–3.
- **Ring 9 does not check what Rings 0–8 already discharge.** If a refined type proves it,
  Ring 9 skips it. If Ring 4 proves the wire round-trip, Ring 9 skips it. If Ring 8's
  fresh-context reviewer would catch the drift by reading the diff, prefer Ring 8 — it is
  cheaper and already mandatory. §6.1.
- **Ring 9 does not replace alerting or SLOs.**
- **Ring 9-CI requires no collector, no Docker, no Jaeger.** In-memory only.
- **Ring 9 is not a second oracle.** The oracle is Ring 3 and it is approved by a human
  before implementation. Ring 9 findings feed *back into* that oracle; they never
  constitute one.

---

## 4. Core model

New package (host-neutral): `<root>.verification.ring9`
In adk4s that is `org.adk4s.ring9`; in loyalty-engine
`it.poste.platform.loyalty.observability.ring9`. Either way it sits **under an existing
architectural layer** so no Ring 2 layer rule changes.

### 4.1 Contracts

```scala
/** A contract on a single event, derived from a spec's Ring 9 section. */
enum SpanContract:
  case Present(eventName: String)
  case RequiresAttribute(eventName: String, key: String)
  case AttributeMatches(eventName: String, key: String, constraint: Constraint)
  case StatusOnSuccess(eventName: String, expected: StatusKind)

/** Mirrors the constraint vocabulary the host's IDL/refinement layer already has
  * (Smithy length/range/pattern/enum; Iron's Length/Match/Positive; …). */
enum Constraint:
  case Length(min: Option[Int], max: Option[Int])
  case Range(min: Option[BigDecimal], max: Option[BigDecimal])
  case Pattern(regex: String)
  case OneOf(values: Set[String])
```

### 4.2 Violation carries provenance — this is what makes a finding nameable

```scala
final case class Violation(
    contract:   ContractRef,
    provenance: Provenance,     // ← v3 doc added this in Phase 1; keep it there
    eventName:  String,
    correlation: CorrelationId,
    eventId:    EventId,
    detail:     String,
    confidence: Confidence
)

/** Where this contract came from. Without it a finding cannot cite its spec, and
  * the writeback triage in §7 has nothing to key on. */
final case class Provenance(specName: String, temporalId: String)   // e.g. ("react-agent", "EARS-1")

enum Confidence:
  case Confirmed        // complete evidence — CI, or a closed trace in production
  case PendingEvidence  // window not yet closed; MUST NOT be acted on
```

`Provenance.temporalId` is the same identifier the spec's Proof-Obligations table cites as
`Temporal: EARS-1`. That string is the join key between the spec, the contract, the
violation, the finding file, and the ledger row's `obligation` field. One identifier, five
artifacts — pick it deliberately.

### 4.3 The evaluator is pure and total

```scala
object Ring9Evaluator:

  def check(contract: SpanContract)(event: TraceEvent): List[Violation] =
    contract match
      case SpanContract.RequiresAttribute(name, key) if event.name == name =>
        if event.attributes.contains(key) then Nil
        else List(violation(contract, event, s"missing attribute '$key'"))

      case SpanContract.AttributeMatches(name, key, c) if event.name == name =>
        event.attributes.get(key).toList
          .flatMap(v => Constraint.violations(c, v).map(violation(contract, event, _)))

      case SpanContract.StatusOnSuccess(name, expected) if event.name == name =>
        if event.status == expected then Nil
        else List(violation(contract, event, s"status ${event.status} != $expected"))

      case _ => Nil   // wrong event name, or Present — checked at trace level, §5
```

Pure, total, no `F[_]`. Four consequences, and the fourth is new since v3:

1. Unit-testable without an effect runtime.
2. Reusable verbatim in CI and in production.
3. **Ring 3-friendly**: contracts and events are both generable, so the evaluator's own
   laws are ordinary properties (§8.6).
4. **Ring 6-eligible via the verified-mirror pattern** — §4.4. Not "add it to the
   Stainless set".

### 4.4 Ring 6 on the evaluator: mirror, not inclusion

The v3 doc said the evaluator "is PureScala — it belongs in the Stainless-verified set."
Under v13 that sentence is a skip-by-reflex in disguise: the shipped evaluator will use
`Map`, `Option`, opaque/refined types and (in the otel4s adapter) library types Stainless
cannot ingest, and the Stainless frontend is pinned to a different Scala version than the
main build (`capability-profile.md:84` — 3.7.2 vs 3.8.4).

STEP 10(b) names the correct pattern, and it is the only one that makes the ring mean
anything:

- a **leaf mirror module** pinned to the Stainless frontend version, depending on nothing
  project-local (`verified/` already exists and already holds `PredictorKernel`);
- a **PureScala model** reducing the evaluator to its OBSERVABLE EFFECT — for
  `Ring9Evaluator`, that is *which contracts a given event violates*, over a list-of-pairs
  attribute model rather than `Map`;
- a **mandatory bridge property test** running shipped code and model on the same
  generated inputs (adk4s's precedent: `PredictorModelBridgeSpec` in `adk4s-optimize/test`);
- a **scope note** naming every law delegated back to Ring 3.

Without the bridge test the proof says nothing about the shipped system, and spec-lint W6
flags a Ring 6 declaration with no obligation naming a bridge artifact.

**Worth stating in the proposal:** a compliance monitor whose decision procedure is
machine-checked, and bound to the shipped decision procedure by a property test, is a
materially different artifact from one that is not. That argument survives from v3; only
the mechanism changed.

---

## 5. Temporal properties: past-time, scoped, with a direction

The concept note's monitor was wrong three ways — future-time operator, no scope, wrong
correlation key. The correction is unchanged from v3 and is repeated here because the
schema template **still ships the bug** (`templates/spec.md:260`).

```scala
final case class TemporalProperty(
    id:       String,              // "EARS-1" — the join key, §4.2
    name:     String,
    scope:    Scope,
    direction: Direction,
    trigger:  EventPattern,
    requires: PastCondition
)

enum Scope:
  case WithinTrace                              // same correlation id
  case WithinSession(attributeKey: String)
  case WithinWindow(attributeKey: String, duration: FiniteDuration)

enum Direction:
  case Past       // checkable in CI and in production
  case Future     // checkable in CI ONLY — see the table below

enum PastCondition:
  case Previously(pattern: EventPattern)                  // P(φ)
  case PreviouslyWithin(pattern: EventPattern, d: FiniteDuration)
  case NotSince(forbidden: EventPattern, since: EventPattern)
```

EARS maps directly:

| EARS pattern | Form | Direction |
|---|---|---|
| "When X, the system shall have Y" | `Previously(Y)`, scoped | Past |
| "When X, the system shall Y" | future obligation | **Future — CI only.** In production you cannot distinguish "not yet" from "never" |
| "While X, the system shall Y" | `NotSince(¬Y, X)` | Past |
| "After X, until Y, the system shall Z" | `NotSince(¬Z, X)` until `Y` | Past |

`Direction` is a field, not a comment, precisely so that §11.2's lint can refuse a
production-targeted `Future` property mechanically instead of leaving it to review.

The evaluator over a *complete* trace is a fold with an accumulated fact set — constant
space per correlation key, linear in events:

```scala
def evaluate(prop: TemporalProperty, events: List[TraceEvent]): List[Violation] =
  events
    .groupBy(correlationKey(prop.scope))
    .toList
    .flatMap: (_, evs) =>
      evs.sortBy(_.startNanos)
         .foldLeft((Set.empty[Fact], List.empty[Violation])):
           case ((facts, vs), e) =>
             val facts1 = record(prop, facts, e)
             if matches(prop.trigger, e) && !satisfied(prop.requires, facts1, e)
             then (facts1, violation(prop, e) :: vs)
             else (facts1, vs)
         ._2
         .reverse
```

`sortBy(_.startNanos)` is sound in CI (single JVM, one clock). It is **not** sound in
production — §10.2.

---

## 6. Ring 9-CI: the trace gate

This is the deliverable that pays for itself, and under v13 it has one more job than it had
under v3: producing evidence.

### 6.1 What is actually worth checking — the redundancy gate

The central finding survives: a refined opaque type cannot place an invalid value on a
span, so checking it there proves nothing. Under v13 the bar is higher, because four more
rings now hold jurisdiction. A Ring 9 contract must survive **all** of these:

| If the property is… | …the ring that owns it | Ring 9 verdict |
|---|---|---|
| a constraint on a value that is *parsed* through a refined type | Ring 0 (types) | **redundant — reject** |
| a layering/purity/raw-primitive rule | Ring 2 | redundant — reject |
| an input→output invariant reachable by a generator | Ring 3 | redundant — reject |
| a round-trip or old-fixture decode | Ring 4 | redundant — reject |
| "the code satisfies the tests but violates the English spec" | Ring 8 | redundant — reject (cheaper, already mandatory) |
| a law about a pure algorithm | Ring 6 | redundant — reject |
| **an ordering between operations, observable only in a real run** | — | **Ring 9** |
| **a value crossing a boundary the type system never sees** | — | **Ring 9** |
| **behaviour of data-defined logic (rules interpreted at runtime)** | — | **Ring 9** |

The three surviving rows are the whole of Ring 9's jurisdiction. Concretely, per host:

| Boundary | Why types cannot see it | adk4s anchor | loyalty-engine anchor |
|---|---|---|---|
| LLM responses | free-form text from a third-party model, coerced by a lenient parser | `SchemaAlignedParser`, `structured-llm` | — |
| Tool-call arguments | JSON authored by a model, not by a schema-checked caller | `ToolsNode`, `JsonFixMiddleware` | — |
| Agent step ordering | the ReAct loop's ordering is emergent, not typed | `ReactAgent`, `AgentEvent`, `RunPath` | — |
| Interrupt/resume state | state crosses a checkpoint boundary and returns | `AgentRunner`, `CheckpointStore` | — |
| External HTTP clients | third-party response shapes | — | `platform-common/clients/*` |
| Warehouse / message-bus reads | rows and payloads predate the constraint | — | Databricks, Kafka consumers |
| **Runtime-interpreted rules** | the rules are *data*; types constrain the interpreter, not the rules | dynamic tool providers, agent-authored plans | eligibility DSL |

The last row is the strongest case in both codebases and for the same reason: **Rings 0–8
verify the interpreter, never the interpreted.** Whether an uploaded rule set consults the
customer record before scoring, or whether an agent's plan calls the retrieval tool before
answering, is a property only observable in a run.

**Rule for the proposal:** a Ring 9 contract whose property is dischargeable by any of the
first six rows is redundant and is rejected in review. The redundancy statement is written
into the spec, not left implicit (§11.2 makes it mechanical).

### 6.2 The gate

```scala
object TraceGate:

  /** Run a scenario under a trace source and check Ring 9 contracts. */
  def check[F[_]: Monad](
      source:    TraceSource[F],
      contracts: List[SpanContract],
      temporal:  List[TemporalProperty]
  )(scenario: F[Unit]): F[List[Violation]] =
    source.record(scenario).map: events =>
      contracts.flatMap(c => events.flatMap(Ring9Evaluator.check(c))) ++
      temporal .flatMap(p => TemporalEvaluator.evaluate(p, events))

/** The only thing an adapter has to provide. */
trait TraceSource[F[_]]:
  def record(scenario: F[Unit]): F[List[TraceEvent]]
```

Two adapters, both ~30 lines:

```scala
// otel4s: TracesTestkit.inMemory (verify the exact API in the first hour — §13)
final class Otel4sTraceSource[F[_]](testkit: TracesTestkit[F]) extends TraceSource[F]

// in-process: drain the host's own event stream
final class AgentEventTraceSource(emitter: AgentEventEmitter) extends TraceSource[IO]
```

Used from the detected test framework — munit + Hedgehog in adk4s, munit/ScalaTest +
ScalaCheck in loyalty-engine:

```scala
class ReactAgentTraceGateSuite extends CatsEffectSuite:

  // spec: react-agent-tracing — Temporal: EARS-1 (tool result precedes final answer)
  test("EARS-1: no final answer is emitted before a tool result in the same run"):
    TraceGate
      .check(source, Ring9Contracts.reactAgent, Ring9Temporal.reactAgent)(runScenario)
      .map(vs => assertEquals(vs, Nil, vs.map(_.detail).mkString("\n")))
```

**Every objection to production monitoring is void here:** 100% of events, single clock,
deterministic ordering, complete traces, a handful of correlation keys, and a violation is
a build failure rather than a page.

### 6.3 Binding to proof obligations — no new mechanism needed

The v3 document proposed adding a Ring 5 column to the cross-reference table. Under v13
that is unnecessary and would fragment the binding. The schema already gives Ring 9 a
first-class slot in the obligation chain:

| Chain link | Mechanism that already exists | Checked by |
|---|---|---|
| bound | Source cell `Temporal: EARS-1` — a TYPED, resolvable reference | spec-lint F6, F7 |
| the heading exists | `### Temporal: EARS-1 …` in this spec | spec-lint F8 |
| the block is well-formed | `**Trigger event**` + `**Response event**` | spec-lint F5 |
| the mechanism is named | Enforcement cell: `runtime monitor (Ring 9)` | `templates/spec.md:310` |
| resolved | Artifact cell: `ReactAgentTraceGateSuite` resolves to a tracked file | spec-lint **F9** at Step 12 |
| discharged | an `R9` ledger row at this baseline | `ledger.sh` → `chain-state.sh` → `checkpoint.sh report` |

A worked row:

| Obligation | Source | Enforcement | Artifact |
|---|---|---|---|
| A final answer never precedes its tool result in the same run | Temporal: EARS-1 | runtime monitor (Ring 9) | `ReactAgentTraceGateSuite` |
| Tool arguments decode to the declared schema | Requirement: Tool arguments are schema-valid | smart constructor + property test | `ToolSchemaSpec` |
| ~~`NodeKey` is non-empty~~ | — | — | *(not written: Ring 0 discharges it — refined type)* |

The struck row matters as much as the filled ones: the redundancy check (§6.1) was
performed and the contract deliberately not written. §11.2 makes that statement mandatory
rather than customary.

### 6.4 Discharging Ring 9 — the part v3 could not have

Running the gate is not passing the ring. Recording the run is.

```bash
scanner/ledger.sh append \
  --change  add-ring9-trace-gate \
  --spec    react-agent-tracing \
  --ring    R9 \
  --obligation "Temporal: EARS-1" \
  --artifact   "adk4s-orchestration/src/test/scala/.../ReactAgentTraceGateSuite.scala" \
  --command 'sbt "adk4s-orchestration/testOnly *ReactAgentTraceGateSuite"' \
  --exit 0 \
  --baseline "$BASELINE_SHA"
```

Then at Step 13 the agent does **not** write "Ring 9 ✅". It runs

```bash
scanner/checkpoint.sh report --ledger <f> --change <c> --spec <s> \
  --baseline <sha> --rings "R0 R1 R2 R3 R8 R9" --format text
```

and pastes the output verbatim. A Ring 9 that shows as having no recorded evidence means
exactly that — the ring did not run, or a `ledger.sh append` was missed. The fix is to run
it, never to describe it as run.

The ring domain and `KNOWN_RINGS` already accept `R9`, so this needs **zero** changes to
the ledger, the record contract, chain-state, or checkpoint. That is the cheapest good news
in this document and it should be stated in the proposal: the substratum was built
ring-generic and Ring 9 is the proof.

---

## 7. The feedback loop and the writeback discipline

§6 covers detection. This section covers what a violation is allowed to change — and it is
the section where v13 most improves on v3, because v13's own changelog diagnoses the
failure mode: *a prose-only control plane is not a control plane.*

### 7.1 The triage question that decides everything

When the gate fires, ask one question first:

> **Does a Scenario in the spec already describe the behaviour this violation contradicts?**

| Answer | Meaning | Artifact produced | Spec touched? |
|---|---|---|---|
| Yes | The spec was right; the implementation drifted | regression test + fix | No |
| No, but a Requirement covers it | The requirement is stated; no scenario pins it | **new Scenario** in this change's spec + a Ring 3 property | Yes — same change |
| No, and no requirement covers it | Genuine specification hole | **new OpenSpec change** with `## ADDED Requirements` | Yes — new change |
| The spec says the opposite | The spec is wrong | **new OpenSpec change** with `## MODIFIED Requirements` | Yes — new change |

Mapped onto the six causes (§10.4):

| Cause | Disposition | Writeback |
|---|---|---|
| 1. Code bug | fix implementation | regression test only |
| 2. Spec bug | `openspec new change` → MODIFIED Requirements | **yes**, human-authored |
| 3. Monitor bug | fix the `SpanContract` | none (the spec's EARS wording may need work) |
| 4. Instrumentation bug | fix event names/attributes to match the spec | none — the spec was the contract |
| 5. Infra artifact | nothing (must not occur in CI) | none |
| 6. Legitimate new behaviour | `openspec new change` → ADDED Requirements | **yes**, human-authored |

**Only causes 2 and 6 produce spec changes, and both go through `openspec new change` —
never through editing an archived spec.** OpenSpec specs are delta specs; retroactively
editing one destroys the audit trail of what was agreed when.

### 7.2 The direction-of-truth rule

> **Never write observed behaviour into a spec as a requirement.**
> A Ring 9 violation is evidence of a *question*, not an answer.

The failure mode is easy to fall into and nearly undetectable afterwards. The gate fires
because the agent answered without calling the retrieval tool. The tempting fix is a
scenario describing what happened — "when the answer is cached, retrieval is skipped." If
that behaviour is a bug, it has just been promoted to a requirement, and every subsequent
ring will faithfully protect it. Ring 3 will generate against it. Ring 5 will kill mutants
that break it. Ring 8 will check the implementation complies with it. **The whole stack
becomes a bug-preservation machine, and every ring will report green.**

The correct output of an unclassified violation is a **finding**, which a human converts
into either a fix or a proposal.

### 7.3 What makes the rule stick under v13 — three mechanisms, not one paragraph

This is the substantive upgrade over v3, whose entire enforcement was a `NEVER` sentence
inside Step 8's prose. v13's changelog records that Step 13 "is already at maximum prose
intensity and the violations happened around it, not in defiance of it."

| Mechanism | Tier | What it refuses / reports |
|---|---|---|
| **`hooks/gate.sh --event tool-call`** — a Ring 9 writeback lock | pre-execution (universal, blocking) | An edit that adds a `#### Scenario:` to a spec while an *open* finding exists whose disposition is unclassified. Same bounded-refusal discipline as the oracle lock: fail open when state is unavailable, one refusal per turn, escape hatch honoured, and change-artifact edits that *close* the finding are always allowed. |
| **`scanner/ring9-findings-check.sh`** (new; or a fourth pass in `registry-check.sh`) | CI, blocking | Every file in `openspec/ring9-findings/` with `Status: open` has ≥1 unchecked disposition item **and** a linked change or test path. A finding with no disposition, or a disposition naming no artifact, fails CI. |
| **spec-lint CONTEXT block** | report, machine-attested | States Ring 9's applicability as a repository fact — telemetry stack present/absent, findings directory present/absent — including the negative case. "N/A" inferred from an assumption is exactly how the altitude rule once got skipped (v11). |

The first is what the v3 design lacked and what v13's architecture makes available. Without
it, §7.2 is an honour system, and the risk table rates that **High**.

### 7.4 Findings as first-class artifacts

`openspec/ring9-findings/` — one markdown file per unresolved violation:

```markdown
# F-2026-0822 — final answer emitted without a retrieval call

**Status**: open | classified | resolved
**Detected**: ring9-ci · ReactAgentTraceGateSuite
**Provenance**: react-agent-tracing — Temporal: EARS-1 (Previously(event "tool.retrieve"))
**Confidence**: Confirmed
**Fixture**: fixtures/traces/F-2026-0822.json
**Baseline**: <sha>

## Observed
ReactAgent emitted MessageOutput with no preceding ToolCallCompleted for
`retrieve` in the same RunPath. Reproducible when the memory hook pre-populates
the conversation.

## Classification
Cause 6 — legitimate new behaviour. The memory pre-population path was added by
`add-memory-orchestration-hook` and the tracing spec predates it.

## Disposition
- [ ] openspec change: `react-agent-memory-shortcut`
- [ ] MODIFIED Requirement: react-agent-tracing — "shall have called retrieve" →
      "shall have called retrieve, or a memory hit shall be present in the run"
- [ ] Ring 3 generator widened: genRunScenario gains a memory-hit branch
- [ ] Ring 9 contract updated: Previously(anyOf(toolRetrieve, memoryHit))
- [ ] Ring 5 mutation delta recorded (§8.5)
```

Three properties make the ceremony worth it:

- **A finding cannot be silently dropped** — §7.3's CI check.
- **The fixture is the regression test.** `fixtures/traces/F-2026-0822.json` replays
  through `TraceGate` via the fixture adapter (§3.2) without the original scenario being
  reconstructible. It is also how a production finding enters CI.
- **It survives the human gate.** Step 13 already stops for review; a finding gives that
  review something concrete to decide rather than a diff to skim.

### 7.5 Production findings enter as fixtures, not as tests

```
production violation
   → capture the trace (an OTLP span batch, or a serialized event list)
   → openspec/ring9-findings/F-<id>.md + fixtures/traces/F-<id>.json
   → human classification (six causes, §10.4)
   → cause 1:        replay the fixture through TraceGate — it MUST fail. Fix. It passes.
   → cause 2 or 6:   openspec new change
   → cause 3, 4, 5:  fix the monitor / the instrumentation, close the finding
```

**A production finding that cannot be made to fail in CI is not yet understood**, and no
fix may be attempted until it can be. That single constraint eliminates most of the noise
problem in §10.4: you cannot act on a violation you cannot reproduce, which is exactly the
right filter for the thousands of cause-4 and cause-5 findings a new monitor produces in
its first week.

---

## 8. Generating test artifacts from Ring 9 results

"Can we generate tests from Ring 9 findings?" — yes for two of three artifact classes, and
the third must never be generated. The distinction is what keeps the loop safe.

### 8.1 Three classes, three answers

| Artifact | Generatable? | Derived from | Risk |
|---|---|---|---|
| **Regression test** pinned to a counterexample | **Yes — mechanical** | the observed value | Low. It asserts a fact that was true |
| **Widened generator** | **Yes — mechanically derivable** | the *constraint*, not the observation | Low — §8.2 |
| **New property** (a new invariant) | **No — never** | would be the observation | High. A property is a specification claim |

The third row is the same direction-of-truth violation as auto-authoring a scenario. A
property is a universally quantified claim about all inputs; inferring one from a single
observation is over-fitting by construction. Worse, it will *pass* — so nothing downstream
contradicts it.

> **Rule:** Ring 9 may generate *inputs* and *fixtures*. Only a human writes *properties*.

This is not merely convention under v13: a property added to a spec's `## Properties
(Ring 3)` section is part of the **approved test oracle**, and Step 2's ORACLE FAITHFULNESS
rule already forbids changing the oracle without human re-approval. Ring 9 auto-authoring a
property would be oracle tampering by a different route.

### 8.2 Widening is derived from the constraint, not from the violation

This is the observation that makes the loop deterministic and removes the LLM from the
critical path entirely.

A violation tells you **which** constraint was crossed. It does not need to tell you how to
widen the generator, because **the complement of a constraint is computable from the
constraint alone** — and the constraint is already in the host's IDL or refinement layer
(Smithy `@length`/`@pattern`/`@range`/`@enum`; Iron's `Length`/`Match`/`Positive`).

Two things follow, both improvements over the concept note:

- **No LLM.** Generator widening is a total function over the `Constraint` ADT. The output
  is identical every run, diffable, reviewable in a PR.
- **Construct, don't filter.** Build values in the complement directly rather than
  filtering a narrow predicate. A filtered generator that discards too often silently stops
  testing anything — in ScalaCheck it gives up after a retry budget; in Hedgehog a
  discard-heavy generator fails the property with a discard-limit report. Where filtering is
  unavoidable (`Pattern`), bound it explicitly.

### 8.3 ScalaCheck and Hedgehog — the comparison, and why the choice is detected

Both frameworks appear in this document because the two reference hosts differ:
loyalty-engine uses ScalaCheck (`project/Dependencies.scala` — `scalacheck-1-17`,
`scalacheck-toolbox-datetime`), adk4s uses Hedgehog 0.13.1
(`capability-profile.md` Testing table). **The choice is not made by this design — it is
read from `openspec/capability-profile.md`.** Writing ScalaCheck into an adk4s spec is a
capability-profile violation, and `config.yaml` already carries the rule explicitly
(`rules.specs`: *"Properties must use Hedgehog (not ScalaCheck — not in the stack)"*).

#### The same widening function, both frameworks

```scala
// ── ScalaCheck ────────────────────────────────────────────────────────────
object Widen:
  def complement(c: Constraint): Gen[String] = c match
    case Constraint.Length(min, max) =>
      Gen.oneOf(
        List(Gen.const("")) ++
        min.map(m => Gen.choose(0, m - 1).flatMap(Gen.stringOfN(_, Gen.alphaNumChar))) ++
        max.map(m => Gen.choose(m + 1, m + 8).flatMap(Gen.stringOfN(_, Gen.alphaNumChar)))
      ).flatten
    case Constraint.OneOf(values) =>
      Gen.identifier.retryUntil(!values.contains(_), 100)      // bounded retries
    case Constraint.Pattern(re) =>
      val compiled = re.r
      Gen.asciiPrintableStr.retryUntil(s => !compiled.matches(s), 100)
    case Constraint.Range(lo, hi) => …

// ── Hedgehog ──────────────────────────────────────────────────────────────
object Widen:
  def complement(c: Constraint): Gen[String] = c match
    case Constraint.Length(min, max) =>
      Gen.choice1(
        Gen.constant(""),
        min.fold(Gen.constant(""))(m => Gen.string(Gen.alphaNum, Range.linear(0, m - 1))),
        max.fold(Gen.constant(""))(m => Gen.string(Gen.alphaNum, Range.linear(m + 1, m + 8)))
      )
    case Constraint.OneOf(values) =>
      Gen.string(Gen.alpha, Range.linear(1, 12)).ensure(!values.contains(_))
    case Constraint.Pattern(re) =>
      val compiled = re.r
      Gen.string(Gen.ascii, Range.linear(0, 24)).filter(s => !compiled.matches(s))
    case Constraint.Range(lo, hi) => …
```

Note the Hedgehog `Range.linear(m + 1, m + 8)` in the `max` branch: `Range` is not just a
bound, it is the *shrink target*, so an over-long string shrinks toward `m + 1` — the
boundary — rather than toward the empty string. That is free minimisation of exactly the
kind §8.4 needs.

#### Where the two genuinely differ, for Ring 9's purposes

| Concern | ScalaCheck | Hedgehog | Consequence for Ring 9 |
|---|---|---|---|
| **Shrinking** | Separate `Shrink[A]` typeclass, decoupled from the generator. Shrunk values can violate the generator's invariants; `forAllNoShrink` is the common escape hatch | **Integrated** — a `Gen` carries its shrink tree, so every shrunk value is one the generator could have produced | Decisive. §8.4's "minimise the counterexample" step is a manual `Shrink.shrink` loop in ScalaCheck and free in Hedgehog. A shrunk trace that the generator could not have produced is a wild-goose regression test |
| **Sizing** | Implicit `Gen.size` parameter, largely invisible | Explicit `Range` at every collection/numeric site | Ring 9 scenarios are op sequences; explicit `Range.linear(1, 12)` documents the operation-space bound in the code, which §8.6's exit criterion needs |
| **Coverage** | `Prop.classify` / `collect` — **reporting only** | **`cover(pct, label, pred)` — an assertion.** An unmet percentage FAILS the property | Decisive. It turns "did the widened generator actually reach the boundary?" from a report someone must read into a test failure. This addresses the cosmetic-widening risk *before* Ring 5 even runs |
| **Discards** | `suchThat` / `retryUntil` with a retry budget; exhaustion surfaces as a give-up | `.ensure` (discard immediately) and `.filter` (bounded, then discard the generator) with a discard limit in the report | Similar hazard, different symptom. Both punish filtering — construct instead (§8.2) |
| **Typeclass surface** | `Arbitrary[A]` implicit resolution | **No `Arbitrary`** — generators are ordinary values passed explicitly | Hedgehog's explicitness suits generated code: `Widen.complement` returns a value, and there is no implicit scope to pollute or accidentally shadow |
| **Seeds** | `Test.Parameters.withInitialSeed` | `Seed.fromLong(n)` | Both satisfy Step 6's "RECORD THE SEED" rule. The capability profile records the host's mechanism |
| **Stateful testing** | `org.scalacheck.commands.Commands` — a formal state-machine API | none | Neither is used here: `Commands` is synchronous-oriented and fights `IO`. A hand-rolled `Gen[List[Op]]` + fold (§8.6) is simpler in both, stays in cats-effect, and gets shrinking for free |
| **Effectful properties** | `IO` must be run inside the property body (`unsafeRunSync`) | same | A wash. Both need `unsafeRunSync` at the boundary, or the gate's `List[Violation]` computed first and asserted purely |

**Recommendation.** For a *new* Ring 9 implementation with a free choice, Hedgehog wins on
the two axes that matter most here — integrated shrinking (the counterexample you get is
one the generator could produce) and `cover` as an assertion (the widening must demonstrably
reach the boundary). For an *existing* ScalaCheck host, do not migrate: the delta is not
worth a test-suite rewrite, and §8.5's mutation-delta gate provides the widening-quality
signal that `cover` would otherwise give. **In both cases the framework is read from the
capability profile, never chosen in the spec.**

### 8.4 Minimise before pinning a regression test

A raw counterexample from a trace carries incidental structure — a full identifier, a real
timestamp, a 40-element input. Pinning it verbatim produces a test that passes for reasons
nobody understands and breaks when unrelated fixtures change.

- **Hedgehog**: minimisation already happened. The failure report's value *is* the minimal
  one, and it respects the generator's invariants. Pin it.
- **ScalaCheck**: run the observed value back through the shrinker before pinning, and
  **re-validate** each candidate against the generator's own predicate — decoupled shrinking
  will happily hand you a value the generator could never produce:

  ```scala
  def minimise[A: Shrink](observed: A)(fails: A => Boolean, valid: A => Boolean): A =
    Shrink.shrink(observed).filter(a => valid(a) && fails(a)).headOption match
      case Some(smaller) => minimise(smaller)(fails, valid)
      case None          => observed
  ```

The finding file records both: `Observed:` for the trace, `Minimal:` for the pinned test.
The minimal case goes in the assertion; the observed case goes in `fixtures/traces/`.

### 8.5 Ring 5 (mutation) is the objective acceptance gate for any generated artifact

The problem with generated tests is that they look like coverage. There is already a tool in
the pipeline that measures whether a test does anything.

> **A generated test artifact is accepted only if it kills at least one mutant that
> survived before it was added.**

Hard, objective, automatable — no judgement call, no LLM review. It sits in **STEP 9**
(Ring 5), with one v13-specific wrinkle:

```
STEP 9 addendum — generated-artifact acceptance
For each generator widened or regression test added by a Ring 9 writeback:
  0. RETARGET stryker4s.conf to this spec's changed files. The conf ships a FIXED
     mutate list (capability-profile.md:83); leaving it lets the change pass Ring 5
     without being mutated at all.
  1. Record the surviving-mutant set before the addition.
  2. Re-run the mutation tool.
  3. If the surviving set is unchanged, the artifact is COSMETIC. Delete it and
     record "no mutation delta" in the finding's disposition.
```

A widened generator that kills no new mutants means the widening did not reach new
behaviour — the boundary was already covered, or the implementation branches identically on
both sides of it. Either way it is a finding worth recording, not a test worth keeping.

In a Hedgehog host, `cover` catches most cosmetic widenings earlier and more cheaply; the
mutation delta remains the acceptance gate.

### 8.6 The bigger prize: property testing *over* the trace gate

Everything above generates artifacts *from* Ring 9 output. The more valuable direction is
the opposite: **use the property framework to drive Ring 9**, which turns the gate from "one
execution" into "many executions" and answers the structural objection that Ring 9 only ever
sees a single trace.

A `SpanContract` is a predicate on a `TraceEvent`. A `TemporalProperty` is a predicate on an
event list. Both are exactly the shape a property framework wants:

```scala
enum Op:
  case Ask(prompt: String)
  case ToolReturns(name: String, out: JsonValue)
  case ToolFails(name: String)
  case Interrupt
  case Resume(data: JsonValue)

final case class Scenario(ops: List[Op])

// Hedgehog
val genScenario: Gen[Scenario] =
  genOp.list(Range.linear(1, 12)).map(Scenario.apply)

// spec: react-agent-tracing — Temporal: EARS-1, EARS-2
property("no operation sequence violates the agent tracing contracts"):
  for s <- genScenario.forAll
        .cover(20, "has-interrupt", (s: Scenario) => s.ops.contains(Op.Interrupt))
        .cover(20, "has-tool-failure", (s: Scenario) => s.ops.exists { case Op.ToolFails(_) => true; case _ => false })
  yield
    val violations = TraceGate.check(source, Ring9Contracts.all, Ring9Temporal.all)(run(s)).unsafeRunSync()
    if violations.isEmpty then Result.success
    else Result.failure.log(violations.map(_.detail).mkString("; "))
```

Why this is the best idea in the document:

- **It generalises Ring 9 from one trace to a distribution over traces.** The strongest
  objection to Ring 9 is "it checks one execution, not all executions." This does not make
  it exhaustive, but it moves it from *one* to *hundreds* — precisely what Ring 3 did for
  values.
- **Shrinking hands you a minimal violating operation sequence.** This is the payoff.
  Ordering bugs are the hardest class to debug from a production trace, because the trace
  contains everything that happened. The framework hands you *the shortest sequence that
  breaks the property* — e.g. `List(Interrupt, Resume(d), Ask(p))`. That alone is worth the
  exercise.
- **`cover` makes the operation space auditable.** A scenario generator that never produces
  an interrupt is not exploring the interesting half of adk4s; `cover(20, "has-interrupt", …)`
  fails the property instead of quietly passing.
- **It composes with fault injection** rather than replacing it. Injected faults prove the
  gate *can* fail; generated scenarios explore *where* it fails.
- **It reuses existing Ring 3 generators.** `genOp` is a `Gen.choice1` over generators the
  concept inventory already lists. No new generator infrastructure.

**Exit criterion:** the generated-scenario property finds at least one ordering violation
the hand-written scenarios did not. If nothing surfaces over a few thousand scenarios,
either the contracts are too weak or the operation space is too narrow — both are findings
worth having.

### 8.7 Production traces as generator *distributions*

Cheap and non-obvious. Ring 3 generators are typically uniform over their domain; real
traffic is not. Production attributes give you the real histogram, and feeding it back as
frequency weights costs almost nothing:

```scala
// ScalaCheck                         // Hedgehog
Gen.frequency(                        Gen.frequency1(
  (60, Gen.choose(1, 5)),               60 -> Gen.int(Range.linear(1, 5)),
  (30, Gen.choose(6, 15)),              30 -> Gen.int(Range.linear(6, 15)),
  ( 9, Gen.choose(16, 40)),              9 -> Gen.int(Range.linear(16, 40)),
  ( 1, Gen.choose(41, 200)))             1 -> Gen.int(Range.linear(41, 200)))
```

This does not change what is *provable*; it changes where the effort goes, and it keeps the
rare-but-real tail in the generator instead of leaving it to be discovered in production.
The direction-of-truth rule still applies: **observed frequencies may reweight a generator;
they may never narrow its domain.** A value never observed is not thereby impossible.

Under v13 this reweighting is a change to the approved oracle's generators, so it goes
through the same re-approval as any other generator edit (Step 2 GENERATOR FAITHFULNESS).
Narrowing a domain by reweighting to zero is oracle tampering.

---

## 9. Prior art: what not to build

| Capability | Existing tool | Decision |
|---|---|---|
| Validate live telemetry against a schema registry | **OTel Weaver `registry live-check`** — checks emitted telemetry against a semconv registry + policies, emits findings, exits non-zero, reports coverage | **Adopt for production (Phase P).** Do not rebuild. Generating a semconv registry from the host's IDL models is a much smaller codegen job than generating Scala validators |
| Declarative span predicates in the collector | **OTTL** / `transformprocessor` / `filterprocessor` | **Adopt as the production baseline.** Scalar constraints (range, pattern, presence) need zero Scala |
| Trace-based assertions in CI | **Tracetest** | **Do not adopt.** Requires a running collector and a separate assertion language. In-memory testkit + the detected test framework stays inside the build and inside the Ring 3 discipline |
| Verified monitoring | **VeriMon** (Isabelle-verified MFOTL monitor) | **Do not adopt; borrow the idea.** Mirror `Ring9Evaluator` into the Stainless leaf instead (§4.4) |
| Data-parameterised monitor scaling | **DejaVu** (BDD-indexed first-order past-time LTL); stream slicing | **Defer to Phase P.** Not needed at CI cardinality |
| Scala monitoring DSL | **Daut** / TraceContract | **Do not adopt.** Our properties are past-time over a bounded event set, which is a fold. Adding a dependency to express a fold is not justified — and under v13 every new dependency is an unreviewed behaviour source that Step 12's BUILD-DEPENDENCY DELTA will surface. Revisit only if Phase P needs true streaming monitors |

Dropping Daut remains the largest departure from the concept note, and the justification is
unchanged: past-time over a complete trace is a fold with a fact set — twenty lines of pure
Scala 3 that Stainless can verify. Daut's value is in long-running future-time monitors over
unbounded streams, which is Phase P's problem, not Phase 1's.

**Re-establishment note (per the invariant).** Every tool row above is a claim about an
external project's capabilities as understood on **2026-08-22**, established by reading
documentation, not by running the tool in this repository. Before Phase P relies on any of
them, re-establish the row with a dated, executed check — a recorded limitation is
re-established before it is relied upon, and an external tool's feature set is exactly the
kind of fact that drifts silently.

---

## 10. Ring 9-PROD: staged, with the hard parts named

Not in scope for the first change. Documented so the CI work does not paint us into a corner.

### 10.1 Sampling

A contract processor placed in an exporting processor chain sees the same sampled stream the
exporter does. Consequences:

- No production monitor can support a "never" claim. At 1% sampling a violation occurring
  once per thousand requests is observed with probability ~0.001 per occurrence.
- **The contract processor must run before the sampling gate** — it must not replicate an
  `isSampled` check. Validation is cheap and should see everything.
- Anything that becomes compliance-relevant needs a separate, unsampled audit stream.
  Compliance evidence cannot ride on a cost-optimised, lossy telemetry path. **This is a
  hard constraint, not a preference**, and it must be settled before any Ring 9 output is
  described as an audit trail.

### 10.2 Out-of-order and incomplete traces

In production, events arrive batched and late, clocks across nodes are not synchronised, and
batch processors typically use a **dropping** queue — under backpressure events are silently
discarded. Therefore:

- Sort-by-timestamp is unsound. Prefer the parent/child partial order where the property can
  be expressed on trace structure.
- Every temporal property needs a bounded-lateness window, and violations carry
  `Confidence.PendingEvidence` until the window closes.
- A dropped event is indistinguishable from an absent event. Any `Previously(…)` property
  can produce false violations under load — which is exactly when it will fire.

`Confidence` is not decoration: under v13, a `PendingEvidence` violation must not produce a
ledger row, because a row is a claim of obtained evidence. **Only `Confirmed` violations —
and only green `Confirmed` runs — are recordable.**

### 10.3 Failure semantics

To be decided before Phase P, not during it:

- Contract processor throws or lags → fail open (drop validation, keep exporting) or fail
  closed (backpressure the app)? Default **fail open**, with a counter, because telemetry
  must never take down the service.
- A gap in Ring 9 evidence is itself a finding if the output is used for audit.

### 10.4 Violation triage

A violation has at least six causes, and only the first should reach a code fix:

1. Code bug  2. Spec bug  3. Monitor bug  4. Instrumentation bug (the attribute does not
mean what the contract thinks)  5. Infrastructure artifact (clock skew, dropped event,
sampling)  6. Legitimate new behaviour.

§7.1 maps each cause to its disposition and states which two may touch a spec. §7.5 defines
how a production finding enters CI as a replayable fixture — and why a finding that cannot
be made to fail in CI must not be acted on at all.

A new monitor on a live service typically fires thousands of times in week one, nearly all
of it (2), (4) and (5). **No automated violation → LLM → fix path until a human triage stage
exists with a measured false-positive rate.**

---

## 11. Changes to the verified-scala3 schema (v13 → v14)

Every item below was verified against the schema in this repo on **2026-08-22**. Several are
worth doing regardless of whether Ring 9 proceeds.

### 11.1 `templates/spec.md` — the Ring 9 sketch still teaches the bug

`templates/spec.md:260` at v13, unchanged since v3:

```
**Monitor sketch**:
always {
  case [TriggerEvent](params) =>
    hot { case [ExpectedResponse](params) => ok }
}
```

`hot` is a *future* obligation. Every EARS pattern of the form "when X, the system shall
have already Y" is mis-expressed by this template. Replacement:

````diff
 ### Temporal: [Property Name]

 **EARS**: "When [trigger], the system shall [response]"
 **Trigger event**: [event name]
 **Response event**: [event name]
+**Scope**: WithinTrace | WithinSession(<attribute>) | WithinWindow(<attribute>, <duration>)
+**Direction**: past | future
+
+<!-- past  = the trigger requires prior evidence. Checkable in CI and in production.
+     future = the trigger creates an obligation on a later event. Checkable in CI ONLY:
+              in production you cannot distinguish "not yet" from "never". -->

-**Monitor sketch**:
-always { case [TriggerEvent](params) => hot { case [ExpectedResponse](params) => ok } }
+**Contract sketch**:
+TemporalProperty(
+  id        = "EARS-<n>",              // the join key: spec ↔ contract ↔ finding ↔ ledger
+  scope     = Scope.WithinTrace,
+  direction = Direction.Past,
+  trigger   = event("[trigger.name]"),
+  requires  = PastCondition.Previously(event("[prior.event.name]"))
+)
+
+**Redundancy statement**: this property is NOT dischargeable by Ring 0 (a refined type on
+a parsed value), Ring 3 (an input→output invariant), Ring 4 (a round-trip), Ring 6 (a law
+about a pure algorithm), or Ring 8 (spec-compliance readable from the diff), because:
+<one sentence>. If it is, delete it. (See RING9-IMPLEMENTATION.md §6.1.)
````

The `id` field is new relative to v3 and carries real weight: it is the single string that
joins the spec heading, the Proof-Obligations `Temporal: <id>` Source, the contract value,
the finding file's `Provenance`, and the ledger row's `obligation`.

### 11.2 `scanner/spec-lint.sh` — extend F5, add two checks

F5 already requires `**Trigger event**` and `**Response event**` (`spec-lint.sh:26`, `:314`).
Three additions, all in the same awk pass:

| Check | Level | Rule |
|---|---|---|
| **F5 (extended)** | FAIL | a `### Temporal:` block also declares `**Scope**` and `**Direction**` |
| **F11** | FAIL | a `### Temporal:` block declares a `**Redundancy statement**` — the mechanical half of §6.1's gate. Whether the statement is *true* stays a human judgement; whether it was *made* should never have been |
| **W8** | WARN | `**Direction**: future` on a property whose spec also targets production monitoring — future-time is CI-only (§5) |

Plus one line in the CONTEXT block, which is where applicability becomes a machine fact:

```
Ring 9 applicability: telemetry stack = <present|absent> (capability-profile.md);
findings dir openspec/ring9-findings/ = <present|absent>; temporal blocks in this
change = <n>
```

That line is what stops the next reviewer from marking Ring 9 "N/A" without looking — the
v11 defect, in a new location.

Note what needs **no** change: `Temporal: <n>` is already a typed Source kind accepted by
F6/F8 (`spec-lint.sh:460`), and F9's artifact resolution already covers a Ring 9 gate suite.
The obligation chain works for Ring 9 today.

### 11.3 `schema.yaml` — STEP 11 rewrite

Current STEP 11 (line 1476) is four bullets naming no artifact, no evaluator, and no ledger
row. Replacement:

```
═══════════════════════════════════════════════════════════════════════════
STEP 11 — RING 9: TELEMETRY / RUNTIME VERIFICATION (if applicable)
═══════════════════════════════════════════════════════════════════════════
APPLICABILITY IS A FACT: read it from spec-lint's CONTEXT block, never infer it.
Applies only when the spec's "Temporal Properties" section is NON-EMPTY. The
trace SOURCE is whatever openspec/capability-profile.md records — a tracing
library, an in-process event stream, or a captured fixture. "No telemetry
library" is not "Ring 9 unavailable" when the host already emits a structured,
correlated event stream; if there is genuinely no event source, skip with a
STATED correctness impact naming what goes unchecked.

1. Instrument the new code paths with the EXACT event names and attribute keys
   named in the spec's Temporal Properties section. The spec is the contract.
2. Translate each Temporal entry into a SpanContract / TemporalProperty value,
   carrying its `id` as provenance.
3. REDUNDANCY GATE: for each contract, confirm the spec's Redundancy statement
   (F11) — a contract dischargeable by Rings 0/3/4/6/8 is DELETED, and the
   deletion is recorded in the Proof-Obligations table as an explicit "—" row.
4. Write a TraceGate suite exercising the scenario and asserting ZERO
   violations. Cite the spec:
     // spec: <spec-name> — Temporal: <id>: <property name>
5. FAULT INJECTION (once per change, not per property): break one instrumented
   attribute or remove one required prior event and confirm the gate FAILS.
   A gate never observed failing is not known to be a gate.
6. RUN IT, then RECORD IT:
     scanner/ledger.sh append --ring R9 --obligation "Temporal: <id>" \
       --artifact <suite path> --command <the command> --exit <code> \
       --baseline <sha>
   A Ring 9 with no R9 row is NOT a passing ring. It is an unrun ring.
7. WRITEBACK — for each violation surviving this step, apply the triage:
   - covered by an existing Scenario → fix the implementation, add a
                                       regression test
   - covered by a Requirement only   → add a Scenario to this spec, plus a
                                       Ring 3 property, plus a cross-reference
                                       row
   - not covered at all              → write openspec/ring9-findings/F-<id>.md
                                       with Status: open, and STOP. A
                                       specification hole is a human decision,
                                       not an apply-phase one.
   NEVER author a Scenario from observed behaviour. A violation is evidence of
   a question, not an answer: writing observed behaviour into a spec promotes a
   possible bug to a requirement that every other ring will then protect.
8. Any generator widened or regression test added here is subject to STEP 9's
   mutation-delta acceptance. Cosmetic artifacts are deleted, not kept.

If a contract is violated, fix the INSTRUMENTATION or the IMPLEMENTATION —
never the contract. The contract is the spec.
```

Step 13's checkpoint gains two lines:

```
**Ring 9 findings opened:** <list or "none">
**Ring 9 findings resolved:** <list or "none">
```

### 11.4 `schema.yaml` — narrow the Ring 9 applicability rule

Line 156 currently reads: *"Ring 9 (telemetry) applies when the change affects API
operations or event sequences AND the telemetry stack is present per
openspec/capability-profile.md."*

Half the v3 doc's §7.4 correction already landed — the stack conjunct is there. The
remaining half is that "affects API operations" is still too broad and is how a spec ends up
with a temporal section nothing checks:

> Ring 9 applies when the change (a) crosses a boundary the type system cannot reach —
> external client, message bus, warehouse read, model output, runtime-interpreted rules —
> **or** (b) asserts an ORDERING between operations that no input→output property can
> express; **and** an event source exists per the capability profile. Ring 9 does *not*
> apply merely because an API operation exists.

Also widen the stack conjunct's wording from "telemetry stack" to "event source", so a host
with a structured in-process event stream is not misread as ineligible (§3.2).

### 11.5 The ledger, chain-state, and checkpoint need no changes

`R9` is already in the closed ring domain (`ledger-record-contract.jq:34`) and in
`KNOWN_RINGS` (`checkpoint.sh:61`). Chain-state derives from ledger rows generically.
**Nothing in the evidence substratum changes for Ring 9** — a good sign about the
substratum's design, and worth saying out loud in the proposal, because it means the
expensive half of this work is already paid for.

### 11.6 A findings check, with the discipline the shell tree now requires

New: `scanner/ring9-findings-check.sh` (or a fourth pass in `registry-check.sh` — decide at
design time; a separate script is easier to test and to skip when the directory is absent).

Two rules:

1. Every file in `openspec/ring9-findings/` with `Status: open` has ≥1 unchecked disposition
   item **and** a linked change path or test path.
2. Every `### Temporal:` block in an active change spec has a matching
   `// spec: <spec> — Temporal: <id>` back-reference in a tracked test file. This is what
   makes §11.1's template correction stick.

Under v13 this is no longer "dependency-free bash + git grep". The prerequisite set is
declared (bash, git, jq at runtime; shellcheck, bats, shfmt in CI), so the script:

- may use `jq`;
- MUST be `shellcheck` clean (0.11.0 detected) with reasoned suppressions;
- MUST be formatted `shfmt -i 2 -ci` (the detected convention);
- MUST ship a `.bats` suite alongside the existing 17 (`tests/*.bats`);
- SHOULD use the `*_OVERRIDE` env seam convention the other scanners use, so its bats suite
  can substitute fixtures.

### 11.7 `hooks/gate.sh` — the writeback lock

§7.3's pre-execution refusal. Scope it narrowly, following `oracle-ordering-lock`'s shape
(`gate.sh:346`):

- **Event**: `tool-call`. **Refuses**: a write/edit adding a `#### Scenario:` heading to a
  file under `openspec/changes/*/specs/**` while an *open, unclassified* Ring 9 finding
  exists for that spec.
- **Always allowed**: edits to the finding file itself, to tests, to workflow tooling, and
  any spec edit once the finding's Classification block names a cause.
- **Bounded refusal**: fail open when `STATE_DIR` is unavailable, one refusal per turn,
  escape hatch honoured — identical discipline to the existing locks, and a bats suite
  proving each clause.

This is the mechanism the v3 design could not have had, and it is the difference between a
rule and a wish.

### 11.8 What the v3 doc's §7.1 asked for, and why it is now obsolete

§7.1 patched stale library versions in `openspec/config.yaml` — otel4s, Iron, cats-effect,
Scala. **That entire class of defect was structurally removed at schema v6**, when the
capability profile became a project-scoped living document that each change verifies and
refreshes. `config.yaml` is now a thin pointer that says so explicitly: *"If this block
disagrees with [capability-profile.md], the profile wins."*

The v3 doc's finding was real and its remedy has been superseded by a better one. Nothing to
do — but worth recording, because "patch the version list" is exactly the kind of task an
agent re-derives from a stale document and then performs on a file that is no longer the
authority.

---

## 12. Implementation plan, expressed as an OpenSpec change

The v3 doc's plan was five engineering phases. Under v13 the plan IS a change: proposal →
capability-check → inventory-check → specs → spec-lint → design → implementation-order →
tasks, then the apply loop per spec with a human gate at each checkpoint. Phases map to
specs; exit criteria map to `Criterion:` / `MUST-CONFIRM:` proof obligations.

### Change: `add-ring9-trace-gate`

| # | Spec | Content | Applicable rings | Gate tier |
|---|---|---|---|---|
| 0 | *(schema)* `ring9-spec-template` | §11.1 template fix + §11.2 spec-lint F5/F11/W8 + CONTEXT line | R0¹ R1 R3 R8 | combined (simple/low) |
| 1 | `ring9-core` | `TraceEvent`, `SpanContract`, `Constraint`, `Violation`, `Provenance`, `Ring9Evaluator` | R0 R1 R2 R3 R5 R8 | two gates |
| 2 | `ring9-trace-gate` | `TraceSource`, one adapter, `TraceGate`, first gate suite, **first `R9` ledger row** | R0 R1 R2 R3 R8 **R9** | two gates |
| 3 | `ring9-temporal` | `TemporalProperty`, `Scope`, `Direction`, `PastCondition`, `TemporalEvaluator`, two real past-time properties, fault injection | R0 R1 R2 R3 R5 R8 R9 | two gates |
| 4 | `ring9-findings` | `openspec/ring9-findings/` + template, the check script (§11.6) + bats, the gate.sh writeback lock (§11.7) + bats | R0¹ R1 R3 R8 | two gates |
| 5 | `ring9-scenario-properties` | `Op`, `genScenario`, the property over the trace gate (§8.6) | R0 R1 R3 R5 R8 R9 | two gates |
| 6 | `ring9-evaluator-mirror` | PureScala mirror of `Ring9Evaluator` in the `verified` leaf + bridge property test (§4.4) | R0 R1 R3 **R6** R8 | two gates |
| 7 | `ring9-spec-generation` | parser for `## Temporal Properties (Ring 9)` → contract values; build-time source generator | R0 R1 R3 R5 R8 R9 | two gates |
| P | *(not scoped)* | production path | — | — |

¹ shell-only specs: Ring 0's analogue is "genuinely executable" (`shellcheck` + `bash -n`),
Ring 3's is bats, and Ring 5 does not apply (no mutation tooling for bash — a recorded
capability-profile fact, not an assumption).

### Sequencing rationale

Spec 0 first and separately: it is half a day, it is pure win, and it stops new specs being
written against a template that teaches a future-time operator. It should ship even if
everything below it is deferred.

Spec 2 before spec 3: the first `R9` ledger row is the single most valuable milestone in the
change, because it converts Ring 9 from a documented ring into an exercised one. Get there
on the simplest possible contract.

Spec 4 before spec 5: the writeback lock must exist before generated scenarios start finding
things, or the first interesting violation gets triaged by an agent under exactly the
conditions §7.2 warns about.

Spec 6 optional but cheap, and it is the answer to "is your compliance monitor correct?"

### Exit criteria, as proof obligations

| Spec | Criterion (goes in the spec's Proof-Obligations table as `Criterion: <name>`) |
|---|---|
| 0 | A spec containing a `### Temporal:` block with no `**Scope**`/`**Direction**`/`**Redundancy statement**` FAILS `spec-lint.sh` |
| 2 | Deleting a required attribute from the instrumented path FAILS the gate suite with a violation naming the attribute **and** the spec |
| 2 | `checkpoint.sh report` renders `R9` from the appended row, with no hand-editing |
| 3 | Both injected faults are detected; a boundary contract fires against a replayed fixture of real captured data. If the boundary contract never fires against real data, the boundary was the wrong choice — pick another before continuing |
| 4 | A deliberate specification hole: add a code path the spec does not describe and instrument it. The gate MUST fire, the apply phase MUST refuse to auto-author a scenario (gate.sh returns the refusal), and the findings check MUST fail until a finding file exists with a disposition |
| 5 | `genScenario` finds ≥1 ordering violation the hand-written scenarios did not, **or** the null result is recorded as a finding about contract strength / operation-space breadth |
| 6 | The bridge property test binds the mirror to the shipped evaluator; the scope note names every law delegated back to Ring 3 (spec-lint W6 silent) |
| 7 | Adding a `### Temporal:` entry to a spec and running the build FAILS until the instrumentation exists. **The spec becomes executable.** |

### Gate on the whole thing

**If the CI gate catches nothing over a quarter of real changes, do not build Phase P** —
and consider whether specs 5–7 are worth their maintenance. Ring 9's value is empirical, and
it is the only ring in this workflow whose value has never been measured.

---

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Contracts are tautological restatements of type-level constraints | **High** — this is what the concept note proposed | §6.1 redundancy table; §11.1 mandatory Redundancy statement; §11.2 F11 makes its *presence* mechanical; reject in review |
| **The writeback loop ratifies a bug as a requirement** | **High if unguarded** | §7.2 direction-of-truth; §11.7 pre-execution refusal (a mechanism, not a paragraph); spec 4's exit criterion tests exactly this |
| Ring 9 reports green having never run | **High** — this is the defect class v12 exists to remove | §6.4: passing = an `R9` ledger row at this baseline; `checkpoint.sh report` output pasted verbatim; Step 11 clause 6 says an unrecorded ring is an unrun ring |
| **Ring 9 is built where nothing needs it** | **Medium-High** | §2.2's number. adk4s has zero temporal properties and no telemetry stack; the honest first question is whether a real host needs this now, and spec 0 is valuable regardless of the answer |
| A gate is never observed failing | Medium | Step 11 clause 5 makes fault injection mandatory once per change, not optional |
| `ring9-findings/` becomes a folder nobody reads | Medium | §11.6's CI check fails on any `Status: open` finding without a disposition and a linked artifact |
| Generated test artifacts look like coverage but assert nothing | Medium | §8.5 mutation-delta acceptance — and in a Hedgehog host, `cover` fails the property earlier and more cheaply |
| A widened generator filters on a narrow predicate and silently stops generating | Medium | §8.2 construct-don't-filter; bound retries where filtering is unavoidable; `cover` asserts the boundary was reached |
| A ScalaCheck-shrunk counterexample violates its own generator's invariants | Medium (ScalaCheck only) | §8.4's `valid` predicate in the minimisation loop; non-issue in Hedgehog |
| Trace gate becomes flaky (timing-dependent assertions) | Medium | Contracts assert on event *shape and order*, never on duration. **Ban duration predicates in Ring 9-CI.** Step 6's FLAKE DISCIPLINE (N=20, any intermittent failure is a Ring 3 failure) applies to gate suites too |
| Generated `Ring9Contracts` drifts from specs | Medium | Spec 7's source generator, not a committed file; §11.6's back-reference lint until then |
| `TracesTestkit` / adapter API differs from the assumed shape | Medium | **Verify in the first hour of spec 2.** Fallback: a hand-rolled in-memory exporter + simple processor (~30 lines), or the in-process event adapter, which needs no telemetry dependency at all |
| A new dependency enters unreviewed | Low | Step 12's BUILD-DEPENDENCY DELTA catches it; the in-process adapter needs none |
| Effort displaces Ring 3/5 work | Medium | Spec 0 is half a day and is pure win. Spec 2 has a hard exit criterion; stop there if it is not met |

---

## 14. Open questions for the human gate

1. **Does a host need this now?** adk4s has zero temporal properties, no telemetry library,
   and 302 evidence rows with no `R9` among them. loyalty-engine has otel4s and 21 temporal
   sections but is still on schema v3. Building Ring 9 in adk4s means dogfooding it where it
   has no pull; building it in loyalty-engine means upgrading that repo's schema first.
   **Recommendation:** ship spec 0 (template + lint) into the schema now regardless; make
   specs 1–3 contingent on a host with a real ordering property to check.
2. **Compliance.** Is any Ring 9 output intended as regulatory evidence? If yes, §10.1's
   unsampled audit stream is a prerequisite, not a Phase-P nicety, and the answer changes the
   architecture.
3. **The 21 existing temporal sections in loyalty-engine.** Convert as a batch when that repo
   upgrades, or leave them and apply the corrected template only to new specs? Spec 7's
   parser should report unconvertible entries rather than guessing at them.
4. **Weaver.** Worth generating a semconv registry from the host's IDL models in Phase P, or
   is a policy language the team does not want to own?

---

## Appendix A — anchors verified in this repo

Verified 2026-08-22 against `adk4s@e45c7fd`, branch `probatio/porting`.

| Claim | Anchor |
|---|---|
| `R9` is in the ledger's closed ring domain | `openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq:34` |
| `R9` is in checkpoint's `KNOWN_RINGS` | `openspec/schemas/verified-scala3/scanner/checkpoint.sh:61` |
| Spec template has a Ring 9 section | `openspec/schemas/verified-scala3/templates/spec.md:240` |
| The template still teaches the future-time bug | `openspec/schemas/verified-scala3/templates/spec.md:260` |
| spec-lint F5 checks temporal blocks | `openspec/schemas/verified-scala3/scanner/spec-lint.sh:26`, `:314` |
| `Temporal:` is an accepted typed obligation Source | `openspec/schemas/verified-scala3/scanner/spec-lint.sh:460`; `schema.yaml:510` |
| "runtime monitor (Ring 9)" is a listed mechanism | `openspec/schemas/verified-scala3/templates/spec.md:310` |
| Apply STEP 11 is Ring 9 | `openspec/schemas/verified-scala3/schema.yaml:1476` |
| Ring 9 applicability rule | `openspec/schemas/verified-scala3/schema.yaml:156` |
| Ring 8 runs before Rings 5/6/7 | `openspec/schemas/verified-scala3/schema.yaml:1359`, `:1365` |
| Pre-execution gate tier (`tool-call`) exists | `openspec/schemas/verified-scala3/hooks/gate.sh:346` |
| Verified-mirror pattern is the Ring 6 procedure | `openspec/schemas/verified-scala3/schema.yaml:1438` (STEP 10(b)); `templates/verified-mirror.md` |
| adk4s telemetry stack: none | `openspec/capability-profile.md:64` |
| adk4s property framework: Hedgehog 0.13.1, not ScalaCheck | `openspec/capability-profile.md` (Testing); `openspec/config.yaml` `rules.specs` |
| Stainless frontend pinned to 3.7.2; `verified` leaf; bridge-test precedent | `openspec/capability-profile.md:84` |
| stryker4s.conf has a FIXED mutate list — must be retargeted | `openspec/capability-profile.md:83` |
| shellcheck 0.11.0 / bats 1.14.0 / shfmt 3.13.1 detected; 17 bats suites | `openspec/capability-profile.md` (Shell tooling) |
| 302 ledger rows, zero `R9`, zero `R7` | `jq -r .ring` over every `evidence-ledger.jsonl` |
| Zero `### Temporal:` entries in any adk4s spec | `grep -rn "^### Temporal:" openspec/specs openspec/changes` |
| The one spec with the section declares Ring 9 not checked | `openspec/specs/cross-run-memory-example/spec.md:334` |
| loyalty-engine has otel4s + ScalaCheck, schema v3 | `/home/gruggiero/git/poste/loyalty-engine/project/Dependencies.scala:202-215`, `:81`; `openspec/schemas/verified-scala3/schema.yaml:2` |
| adk4s event stream is trace-shaped | `org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, RunPath, RunStep}`; concept `openspec/concepts/agent-event-stream.md` |
| Hedgehog API used above | `hedgehog-core_3-0.13.1-sources.jar`: `Gen.scala:98` `choice1`, `:114` `frequency1`, `core/GenT.scala:91` `ensure`, `:100` `filter`, `Property.scala:19` `cover`, `core/Seed.scala:51` `fromLong` |

## Appendix B — ring quick reference (v13)

```
R0 compile      R1 lint         R2 architecture   R3 test oracle (MANDATORY)
R4 wire/persist R5 mutation     R6 formal         R7 model checking
R8 adversarial review (MANDATORY, runs BEFORE 5/6/7)      R9 telemetry
```

Apply-loop order: Step 3 → R0, 4 → R1, 5 → R2, 6 → R3, 7 → R4, **8 → R8**, 9 → R5,
10 → R6/R7, **11 → R9**, 12 → concept delta, 13 → checkpoint.

## Appendix C — references

- otel4s: <https://typelevel.org/otel4s/> · otel4s testkit — testing traces:
  <https://typelevel.org/otel4s/how-to-testkit/test-traces-emitted-by-your-code.html>
- Hedgehog for Scala: <https://hedgehogqa.github.io/scala-hedgehog/> — integrated shrinking,
  explicit `Range`, `cover` as an assertion
- ScalaCheck user guide: <https://github.com/typelevel/scalacheck/blob/main/doc/UserGuide.md>
  — `Shrink` as a separate typeclass, `classify`/`collect` as reporting
- OpenTelemetry Weaver: <https://github.com/open-telemetry/weaver> · live-check findings ·
  OTTL: <https://opentelemetry.io/docs/collector/transforming-telemetry/>
- Tracetest: <https://tracetest.io/>
- Aichernig & Havelund, "Correct-ish by Design: From Upfront Verification to Continuous
  Monitoring of LLM Generated Code", AISoLA 2024
- Havelund, Omer & Peled, "DSLs for Runtime Verification", RV 2025 — §5 on generating
  monitors from natural-language requirements
- Barringer & Havelund, "TraceContract: A Scala DSL for Trace Analysis", FM 2011 ·
  Daut: <https://github.com/havelund/daut>
- Schneider, Basin, Krstić & Traytel, "A Formally Verified Monitor for Metric First-Order
  Temporal Logic", RV 2019 (VeriMon)
- Shafiei, Havelund & Mehlitz, "Concurrent runtime verification of data rich events",
  STTT 2023
- Meng & Jackson, "What You See Is What It Does" — the concept-registry framing the schema
  adopts
