# Spec: Gate Checkpoint Lock

<!-- DELTA spec for the `gate-checkpoint-lock` capability of the
     port-scanner-to-probatio change. Covers R-GK1…R-GK3: the ported
     `gate` subcommand's decision logic SHALL require a checkpoint
     presentation marker in addition to `verified` phase for the
     predecessor check and the grant waiver, and SHALL distinguish
     "not checkpointed" from "not verified" in the block reason.

     This spec closes a gap discovered during the port itself: the
     predecessor `gate.sh` treated `verified` phase as sufficient for
     proceeding to the next spec, but `verified` only means tests ran
     (RED+GREEN ledger rows exist). Three specs advanced to `verified`
     and the next spec started without `checkpoint.sh` ever being run,
     so the chain-state discharge check never fired and ledger rows
     with the wrong obligation text went undetected. The bash `gate.sh`
     was fixed in place; this spec binds the Scala port to the same
     behavior so the port does not regress to the old, weaker gate.

     ALTITUDE: requirements and scenarios use behavioral vocabulary. Code
     identifiers — `GateDecision`, `PredecessorCheck`, `GrantWaiver`,
     `GateEvent`, `Phase`, `PresentationMarker` — live in Implementation
     Anchors and the Concepts Introduced table. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | This spec introduces the gate's decision logic as pure functions; no behavioral concept's actions, state, or synchronizations are changed | — |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Outcome[A]` | sealed enum | `org.sinemenda.probatio.core` |
| `LedgerRecord` | immutable case class | `org.sinemenda.probatio.core` |

The gate's decision logic consumes `Outcome` (the gate subcommand returns
`Outcome[Int]` mapping to exit 0/1/2) and reads ledger records to determine
spec phase. No adk4s types are reused (R-ARCH1).

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `GateEvent` | sealed enum | The hook events the gate handles: `SessionStart`, `PromptSubmit`, `PostEdit`, `ToolCall`, `Completion`. Each has a distinct blocking policy (the blocking asymmetry, §4.5) |
| `GateDecision` | sealed enum | The gate's decision: `Allow` or `Block(reason)`. A pure function of (event, changed files, prior spec states, grant tokens, oracle state). Maps to `Outcome[Int]` at the CLI boundary — `Allow` → exit 0, `Block` → exit 1 with the reason in the payload |
| `SpecPhase` | sealed enum | The phase of a spec in implementation order: `Oracle`, `Implementation`, `Verified`. Derived from ledger rows (RED+GREEN at an ancestor baseline → `Verified`; GREEN only → `Implementation`; RED only or none → `Oracle`) |
| `PredecessorCheck` | pure function | `(prior specs: List[(SpecName, SpecPhase, hasPresentation)], escapeHatch: Boolean) → Either[BlockReason, Unit]`. Blocks if any prior spec is not `Verified` OR is `Verified` without a presentation marker. The escape hatch bypasses both checks |
| `GrantWaiver` | pure function | `(prior specs: List[(SpecName, SpecPhase, hasPresentation, hasGrant)], escapeHatch: Boolean) → Either[BlockReason, Unit]`. Waives the human-grant lock if every prior spec is `Verified` AND has a presentation marker. A current-session grant token satisfies the lock directly. The escape hatch bypasses both checks |
| `PresentationMarker` | value type | Evidence that `checkpoint.sh` ran for a spec in some session. The CLI layer reads the state directory for marker files and passes their existence as a boolean per spec; the pure decision logic does not read files |
| `BlockReason` | sealed trait | The reason a gate decision is `Block`: `PredecessorNotVerified(spec, phase)`, `PredecessorNotCheckpointed(spec)`, `OracleOrderingViolation`, `GrantRequired(spec)`. Each renders to a distinct human-readable string in the payload |

## ADDED Requirements

### Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase

The ported `gate` subcommand's predecessor check SHALL refuse a
production-source edit if any prior spec in implementation order is not
both `Verified` (RED+GREEN ledger rows exist at an ancestor baseline) AND
checkpointed (a presentation marker exists for that spec). A spec that is
`Verified` but has no presentation marker SHALL be reported as
`PredecessorNotCheckpointed(spec)` in the block reason, distinct from a
spec that is `Oracle` or `Implementation` phase
(`PredecessorNotVerified(spec, phase)`). The escape hatch
(`VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1`, migrated to
`PROBATIO_HOOKS` per schema-policy) SHALL remain functional and SHALL
bypass both the phase check and the presentation check.

**Given** a prior spec with phase `Verified` (RED+GREEN ledger rows
exist) but no presentation marker
**When** the gate evaluates a production-source edit for the next spec
**Then** the gate decision is `Block(PredecessorNotCheckpointed(spec))`
and the payload names the prior spec and instructs the agent to run
`checkpoint`

**Rationale**: `Verified` phase means tests ran (a RED run and a GREEN
run were recorded in the ledger). It does NOT mean the chain-state
discharge check ran — that check only fires when `checkpoint` is invoked,
which writes a presentation marker. Three specs in this very port
advanced to `Verified` and the next spec started, but `checkpoint` was
never run, so ledger rows with the wrong obligation text went
undetected. The chain-state check that would have caught the mismatch
never fired because nothing triggered it. Requiring a presentation
marker in addition to `Verified` phase closes this gap: the gate demands
proof that the chain-state check ran, not just that tests ran. The bash
`gate.sh` was fixed in place; this spec binds the Scala port to the same
behavior so the port does not regress to the old, weaker gate.

#### Scenario: verified predecessor with no presentation is blocked

**Given** spec N has phase `Verified` (RED+GREEN ledger rows at an
ancestor baseline) and no presentation marker exists for spec N
**When** the gate evaluates a production-source edit for spec N+1
**Then** the gate decision is `Block(PredecessorNotCheckpointed("specN"))`
and the payload contains the string `"not checkpointed"` and the name
of spec N

#### Scenario: verified predecessor with a presentation is allowed

**Given** spec N has phase `Verified` and a presentation marker exists
for spec N (from any session)
**When** the gate evaluates a production-source edit for spec N+1
**Then** the gate decision is `Allow` (the predecessor check passes)

#### Scenario: non-verified predecessor is blocked regardless of presentation

**Given** spec N has phase `Implementation` (GREEN run exists but no
RED run) and a presentation marker exists for spec N
**When** the gate evaluates a production-source edit for spec N+1
**Then** the gate decision is
`Block(PredecessorNotVerified("specN", Implementation))` — the
presentation marker is irrelevant, the phase must be `Verified` first

#### Scenario: escape hatch bypasses both checks

**Given** spec N has phase `Verified` but no presentation marker, and
the escape hatch is set
**When** the gate evaluates a production-source edit for spec N+1
**Then** the gate decision is `Allow` — the escape hatch bypasses both
the phase check and the presentation check

#### Scenario: no state directory means fail open

**Given** the gate state directory cannot be resolved (no `.git`
directory, fresh clone with no commits)
**When** the gate evaluates a production-source edit for any spec
**Then** the gate decision is `Allow` — the predecessor check fails
open when the state directory is unavailable, matching the existing
fail-open discipline for the phase check

### Requirement: The grant waiver requires a checkpoint presentation marker

The ported `gate` subcommand's grant waiver SHALL require a presentation
marker in addition to `Verified` phase. When a prior spec has a
presentation marker from a prior session but no grant token in the
current session, and the spec's phase is `Verified`, the grant SHALL be
waived (the spec was checkpointed and the chain-state check ran in a
prior session). When a prior spec has phase `Verified` but NO
presentation marker from any session, the grant SHALL NOT be waived —
the agent must obtain an explicit human grant (a user prompt after the
checkpoint) before proceeding, even though the phase is `Verified`.

**Given** a prior spec with phase `Verified` and no presentation marker
from any session
**When** the gate evaluates a non-production write targeting the next
spec's files (implementation-progress, spec directory)
**Then** the grant is not waived and the gate decision is
`Block(GrantRequired(spec))`, requiring an explicit human grant

**Rationale**: The grant waiver's original comment said "approved in a
prior session" — but `Verified` phase only means tests ran, not that
the checkpoint was presented and the chain-state check ran. Waiving the
grant on `Verified` alone allowed an agent to skip the checkpoint
entirely and proceed to the next spec with no human review and no
chain-state discharge check. Requiring a presentation marker ensures
the grant is waived only when the checkpoint was actually run in some
session, which is the minimum evidence that the chain-state check fired.

#### Scenario: verified with presentation waives grant

**Given** spec N has phase `Verified` and a presentation marker exists
from a prior session (but no grant token in the current session)
**When** the gate evaluates a non-production write targeting spec N+1
**Then** the grant is waived and the gate decision is `Allow` (the spec
was checkpointed in a prior session)

#### Scenario: verified without presentation does not waive grant

**Given** spec N has phase `Verified` but no presentation marker from
any session
**When** the gate evaluates a non-production write targeting spec N+1
**Then** the grant is not waived and the gate decision is
`Block(GrantRequired("specN"))`

#### Scenario: verified with grant from current session is allowed

**Given** spec N has phase `Verified` and a grant token exists in the
current session (written by a user prompt after a checkpoint)
**When** the gate evaluates a non-production write targeting spec N+1
**Then** the gate decision is `Allow` (the grant is satisfied directly,
no waiver needed)

### Requirement: The block reason distinguishes not-checkpointed from not-verified

The ported `gate` subcommand's block reason SHALL distinguish a
predecessor that is `Verified` but not checkpointed from a predecessor
that is not `Verified`. The not-checkpointed case SHALL be
`PredecessorNotCheckpointed(spec)` and SHALL name the missing checkpoint
explicitly in the rendered payload. The not-verified case SHALL be
`PredecessorNotVerified(spec, phase)` and SHALL retain the phase name in
the rendered payload. Both cases SHALL name the escape hatch in the
rendered payload.

**Given** a predecessor spec that is `Verified` but not checkpointed
**When** the gate blocks a production edit for the next spec
**Then** the block reason is `PredecessorNotCheckpointed(spec)` and the
rendered payload contains the string `"not checkpointed"` and the
instruction to run `checkpoint`

**Given** a predecessor spec that is in `Oracle` or `Implementation`
phase
**When** the gate blocks a production edit for the next spec
**Then** the block reason is `PredecessorNotVerified(spec, phase)` and
the rendered payload contains the phase name and does NOT contain the
string `"not checkpointed"`

**Rationale**: An agent receiving a block reason needs to know what
action to take. "Not verified" means "run the tests" (record RED and
GREEN ledger rows). "Not checkpointed" means "run `checkpoint`" (trigger
the chain-state discharge check). Conflating the two would send the
agent to re-run tests when the real problem is a missing checkpoint, or
vice versa. The distinct `BlockReason` variant ensures the agent takes
the correct remediation action, and the typed sum prevents the renderer
from conflating them by construction.

#### Scenario: not-checkpointed reason names checkpoint

**Given** a predecessor spec that is `Verified` but has no presentation
marker
**When** the gate blocks a production edit
**Then** the block reason is `PredecessorNotCheckpointed(spec)` and the
rendered payload contains `"checkpoint"` and `"not checkpointed"`

#### Scenario: not-verified reason does not name checkpoint

**Given** a predecessor spec that is in `Oracle` phase
**When** the gate blocks a production edit
**Then** the block reason is `PredecessorNotVerified(spec, Oracle)` and
the rendered payload contains `"Oracle"` and does NOT contain
`"not checkpointed"`

#### Scenario: both reasons name the escape hatch

**Given** any predecessor spec that blocks the next spec's production
edit
**When** the block reason is rendered to the payload
**Then** the payload contains the escape hatch variable name

## Properties (Ring 3)

Properties use Hedgehog (the detected property framework per
`openspec/capability-profile.md` — NOT ScalaCheck). These properties
test the gate's predecessor-check and grant-waiver logic as pure
functions over generated state configurations, binding the shipped
Scala logic to its behavioral contract.

### Property: predecessor-check-requires-presentation

**Invariant**: The predecessor check's decision is a pure function of
(prior-spec-phase, prior-spec-has-presentation, escape-hatch). A spec
is blocked if and only if: phase is not `Verified`, OR phase is
`Verified` and no presentation marker exists. The escape hatch bypasses
both conditions.

**Generator strategy**: `genPredecessorState` — constructive over
phase (`Oracle`, `Implementation`, `Verified`) × presentation-exists
(true, false) × escape-hatch (set, unset). Edge cases: `Verified` with
no presentation (the critical case); `Verified` with presentation
(allowed); `Implementation` with presentation (blocked — phase first);
escape hatch set with `Verified` and no presentation (allowed).

```
forAll { (state: PredecessorState) =>
  val decision: Either[BlockReason, Unit] = predecessorCheck(state)
  state.escapeHatch match
    case Some(_) => decision.isRight
    case None =>
      (state.phase != Verified || !state.hasPresentation) == decision.isLeft
}
```

### Property: grant-waiver-requires-presentation

**Invariant**: The grant waiver's decision is a pure function of
(prior-spec-phase, prior-spec-has-presentation, prior-spec-has-grant,
escape-hatch). The grant is waived if and only if: phase is `Verified`
AND a presentation marker exists. If a grant token exists, the write is
allowed directly (no waiver needed). If neither a grant nor a waiver
applies, the write is blocked. The escape hatch bypasses both checks.

**Generator strategy**: `genGrantState` — constructive over phase ×
presentation-exists × grant-exists × escape-hatch. Edge cases:
`Verified` with presentation, no grant (waived); `Verified` without
presentation, no grant (blocked — the fix); `Verified` with grant
(allowed directly); `Implementation` with presentation and grant
(blocked — phase first); escape hatch set (always allowed).

```
forAll { (state: GrantState) =>
  val decision: Either[BlockReason, Unit] = grantWaiver(state)
  state.escapeHatch match
    case Some(_) => decision.isRight
    case None =>
      state.grantExists match
        case true => decision.isRight
        case false =>
          (state.phase == Verified && state.hasPresentation) == decision.isRight
}
```

### Property: block-reason-distinguishes-not-checkpointed

**Invariant**: The block reason is a pure function of
(prior-spec-phase, prior-spec-has-presentation). When phase is
`Verified` and no presentation exists, the reason is
`PredecessorNotCheckpointed(spec)` and the rendered string contains
`"not checkpointed"` and `"checkpoint"`. When phase is not `Verified`,
the reason is `PredecessorNotVerified(spec, phase)` and the rendered
string does NOT contain `"not checkpointed"`. All rendered block
reasons contain the escape hatch variable name.

**Generator strategy**: `genBlockReasonState` — constructive over
phase × presentation-exists, filtered to blocking configurations only
(phase != `Verified` OR no presentation). Edge cases: `Verified`
without presentation (must be `PredecessorNotCheckpointed`); `Oracle`
with no presentation (must be `PredecessorNotVerified`).

```
forAll { (state: BlockReasonState) =>
  val reason: BlockReason = blockReason(state)
  (state.phase, state.hasPresentation) match
    case (Verified, false) =>
      reason.isInstanceOf[PredecessorNotCheckpointed] &&
      reason.render.contains("not checkpointed") &&
      reason.render.contains("checkpoint") &&
      reason.render.contains("PROBATIO_HOOKS")
    case (phase, _) if phase != Verified =>
      reason.isInstanceOf[PredecessorNotVerified] &&
      !reason.render.contains("not checkpointed") &&
      reason.render.contains("PROBATIO_HOOKS")
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `GateEvent` with a sixth case | The gate handles exactly five hook events (§4.5 blocking asymmetry); a sixth case is the silent fallback that could carry an unintended blocking policy | `assertDoesNotCompile("val e: GateEvent = new GateEvent { ... }")` — the enum is sealed |
| `BlockReason` with a fifth variant | The block reasons are a closed set (PredecessorNotVerified, PredecessorNotCheckpointed, OracleOrderingViolation, GrantRequired); a fifth variant would mean the gate and the block-reason renderer disagree | `assertDoesNotCompile("val r: BlockReason = new BlockReason { ... }")` — the trait is sealed |
| `SpecPhase` with a fourth case | The phase domain is exactly three values (Oracle, Implementation, Verified); a fourth case would admit a phase the predecessor check and grant waiver do not handle | `assertDoesNotCompile("val p: SpecPhase = new SpecPhase { ... }")` — the enum is sealed |
| `case _` in a `GateDecision` match | A catch-all in a GateDecision match defeats exhaustiveness checking; a future variant would be silently absorbed, and a `Block` could be misclassified as `Allow` | code-review gate (Scalafix DisableSyntax `case _` in pattern matches on sealed types) + WartRemover |
| `case _` in a `BlockReason` match (in the renderer) | A catch-all in the block-reason renderer would conflate `PredecessorNotCheckpointed` with `PredecessorNotVerified` — the exact defect this spec exists to prevent | code-review gate (Scalafix DisableSyntax `case _` in pattern matches on sealed types) + WartRemover; exhaustive match with `-Wconf:cat=pattern-match-exhaustivity:e` |
| File I/O (`scala.io.Source`, `java.nio.file`, `java.io.File`) in `PredecessorCheck` or `GrantWaiver` | R-ARCH1/R-C3: the pure decision functions take booleans (hasPresentation, hasGrant) as inputs; the CLI layer reads state files and passes the results. File I/O in the pure functions would make them non-deterministic and untestable as pure functions | code-review gate + Scalafix rule banning `scala.io.Source`/`java.nio.file`/`java.io.File` in `PredecessorCheck.scala` and `GrantWaiver.scala` |
| `System.getenv` in `PredecessorCheck` or `GrantWaiver` | The escape hatch is passed as a `Boolean` parameter; reading the env var inside the pure function would couple the decision to the process environment and break referential transparency | code-review gate + Scalafix rule banning `System.getenv` in `PredecessorCheck.scala` and `GrantWaiver.scala` |
| `asInstanceOf` in gate decision code | Project rule: NEVER use `asInstanceOf`; use pattern matching | WartRemover `AsInstanceOf` wart (scoped to probatio) |
| `cats` or `cats-effect` import in gate decision code | R-X3: cats/cats-effect are explicitly excluded from probatio; the gate's decision logic uses stdlib `Either`/`List`, not `cats.data.EitherT` or `cats.effect.IO` | dependency-lint rule (R-ARCH1 extension) + code-review gate |
| `Arbitrary`-based Hedgehog generators | Hedgehog does not have `Arbitrary`; this is a ScalaCheck anti-pattern | code-review gate (Hedgehog uses `Gen`, not `Arbitrary`) |
| `GateDecision` constructed without a `BlockReason` when blocking | A `Block` without a reason is a silent block — the agent receives no remediation instruction; the `Block(reason: BlockReason)` constructor requires a reason by construction | type system (case class with required field) + compile-negative: `assertDoesNotCompile("val d: GateDecision = GateDecision.Block")` — Block is not a case object |

## Formal Contracts (Ring 6)

<!-- Ring 6 applies via the verified-mirror pattern. The gate's decision logic
     — the predecessor check and the grant waiver — is a pair of pure decision
     functions over finite state. Each is expressible in PureScala at the
     abstraction level of (phase, hasPresentation, hasGrant, escapeHatch) →
     Either[BlockReason, Unit]. The mirror lives in the `verified` leaf module
     (Scala 3.7.2, Stainless), following the same pattern as ChainStateKernel
     and LedgerValidatorKernel. The mirror models the DECISION only (which
     configurations block, which allow, which block reason is selected); the
     state-directory file I/O, the session-id sanitization, and the
     bounded-refusal marker logic are NOT modeled — they are Ring 3/4
     concerns. R-X3 excludes cats from probatio; the mirror uses PureScala
     stdlib only, no cats.

     Per templates/verified-mirror.md: the mirror is a leaf pinned to the
     Stainless frontend's Scala version, depends on nothing project-local,
     `stainlessEnabled := false` by default, and the production module takes
     it as a `% Test` dependency. The bridge property test is the
     load-bearing part — it binds shipped code to the model on shared inputs. -->

### Contract: GateDecisionKernel — the predecessor check and grant waiver mirror

**Mirror name**: `GateDecisionKernel`
**Mirror location**: `verified/probatio/src/main/scala/org/sinemenda/probatio/core/GateDecisionKernel.scala`
**Abstraction**: The gate's decision logic is reduced to its decision kernel: a `SpecState` case class with `phase: SpecPhase` (sealed, three cases), `hasPresentation: Boolean`, and `hasGrant: Boolean`; an `escapeHatch: Boolean` flag; and a `specId: BigInt` for naming in the block reason. The kernel exposes two functions: `predecessorCheck(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit]` and `grantWaiver(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit]`. The `BlockReason` is a sealed abstract class with four variants mirroring the shipped enum. The state-directory file I/O, the session-id resolution, and the bounded-refusal marker are NOT modeled — they are Ring 3/4 concerns.

**Precondition** (`require`): the input list is finite (modeled as a Stainless `List`).
**Postcondition** (`ensuring`): `predecessorCheck` returns `Right(())` if and only if every spec has `phase == Verified && hasPresentation`, OR `escapeHatch` is true. `grantWaiver` returns `Right(())` if and only if every spec has `(phase == Verified && hasPresentation) || hasGrant`, OR `escapeHatch` is true. When blocking, the `BlockReason` variant is determined by the first failing spec: `PredecessorNotCheckpointed` when `phase == Verified && !hasPresentation`, `PredecessorNotVerified` when `phase != Verified`, `GrantRequired` when the grant waiver fails and the phase/presentation check passes.

```scala
object GateDecisionKernel:
  // ── Phase abstraction (three cases, sealed) ───────────────────────────
  sealed abstract class SpecPhase
  case object Oracle extends SpecPhase
  case object Implementation extends SpecPhase
  case object Verified extends SpecPhase

  // ── Block reason abstraction (four variants, sealed) ──────────────────
  sealed abstract class BlockReason
  case class PredecessorNotVerified(specId: BigInt, phase: SpecPhase) extends BlockReason
  case class PredecessorNotCheckpointed(specId: BigInt) extends BlockReason
  case class OracleOrderingViolation() extends BlockReason
  case class GrantRequired(specId: BigInt) extends BlockReason

  // ── Per-spec state ────────────────────────────────────────────────────
  final case class SpecState(
    specId: BigInt,
    phase: SpecPhase,
    hasPresentation: Boolean,
    hasGrant: Boolean
  )

  // ── Predecessor check ─────────────────────────────────────────────────
  // A spec passes the predecessor check if and only if:
  //   phase == Verified AND hasPresentation.
  // The escape hatch bypasses both conditions.
  def specPassesPredecessor(s: SpecState): Boolean =
    s.phase == Verified && s.hasPresentation

  def predecessorCheck(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit] =
    require(true) // total function — no precondition beyond finiteness
    if escapeHatch then Right(())
    else
      specs match
        case Nil() => Right(())
        case Cons(head, tail) =>
          if specPassesPredecessor(head) then predecessorCheck(tail, escapeHatch)
          else
            head.phase match
              case Verified => Left(PredecessorNotCheckpointed(head.specId))
              case _        => Left(PredecessorNotVerified(head.specId, head.phase))
  .ensuring((result: Either[BlockReason, Unit]) =>
    escapeHatch || result.isRight == specs.forall(specPassesPredecessor)
  )

  // ── Grant waiver ──────────────────────────────────────────────────────
  // A spec passes the grant waiver if and only if:
  //   (phase == Verified AND hasPresentation) OR hasGrant.
  // The escape hatch bypasses both conditions.
  def specPassesGrant(s: SpecState): Boolean =
    (s.phase == Verified && s.hasPresentation) || s.hasGrant

  def grantWaiver(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit] =
    require(true) // total function — no precondition beyond finiteness
    if escapeHatch then Right(())
    else
      specs match
        case Nil() => Right(())
        case Cons(head, tail) =>
          if specPassesGrant(head) then grantWaiver(tail, escapeHatch)
          else Left(GrantRequired(head.specId))
  .ensuring((result: Either[BlockReason, Unit]) =>
    escapeHatch || result.isRight == specs.forall(specPassesGrant)
  )

  // The law worth proving: the predecessor check blocks on the first spec
  // that is Verified without a presentation, and selects the correct
  // BlockReason variant — PredecessorNotCheckpointed (not
  // PredecessorNotVerified) for that case.
  def notCheckpointedDistinctFromNotVerified(specs: List[SpecState], escapeHatch: Boolean): Boolean = {
    predecessorCheck(specs, escapeHatch) match
      case Right(_) => true
      case Left(PredecessorNotCheckpointed(id)) =>
        // The blocking spec must be Verified with no presentation
        specs.exists(s => s.specId == id && s.phase == Verified && !s.hasPresentation)
      case Left(PredecessorNotVerified(id, phase)) =>
        // The blocking spec must NOT be Verified
        specs.exists(s => s.specId == id && s.phase == phase && phase != Verified)
      case Left(OracleOrderingViolation()) => true
      case Left(GrantRequired(_)) => true // not produced by predecessorCheck
  }.holds

  // The law worth proving: the grant waiver is stricter than the predecessor
  // check only in the hasGrant dimension — a spec that passes the
  // predecessor check also passes the grant waiver (presentation implies
  // the grant condition is met via the waiver branch).
  def grantWaiverWeakerThanPredecessor(specs: List[SpecState], escapeHatch: Boolean): Boolean = {
    val pred = predecessorCheck(specs, escapeHatch)
    val grant = grantWaiver(specs, escapeHatch)
    (pred, grant) match
      case (Right(()), Right(())) => true
      case (Right(()), Left(_))   => false // predecessor passes but grant fails — impossible
      case (Left(_), _)           => true  // predecessor fails — grant may pass or fail
  }.holds
```

**Bridge property test**: `GateDecisionModelBridgeTests` — runs the shipped `PredecessorCheck.apply` and `GrantWaiver.apply` and the `GateDecisionKernel.predecessorCheck`/`grantWaiver` on the same generated `SpecState` lists (reduced to the model's abstraction) and asserts they agree on allow/block and on the `BlockReason` variant selected.

```
property("shipped predecessor check agrees with the Stainless model") {
  for states <- genSpecStateList.forAll
  yield
    val escapeHatch = false // escape hatch tested separately
    val real  = PredecessorCheck.apply(states.map(toShipped), escapeHatch)
    val flat  = states.map(toModel)
    val model = GateDecisionKernel.predecessorCheck(flat, escapeHatch)
    (real, model) match
      case (Right(()), Right(()))   => Result.success
      case (Left(rr), Left(mr))     => Result.assert(blockReasonVariant(rr) == blockReasonVariant(mr))
      case _                        => Result.failure("real and model disagree on predecessor check")
}

property("shipped grant waiver agrees with the Stainless model") {
  for states <- genSpecStateList.forAll
  yield
    val escapeHatch = false
    val real  = GrantWaiver.apply(states.map(toShipped), escapeHatch)
    val flat  = states.map(toModel)
    val model = GateDecisionKernel.grantWaiver(flat, escapeHatch)
    (real, model) match
      case (Right(()), Right(()))   => Result.success
      case (Left(rr), Left(mr))     => Result.assert(blockReasonVariant(rr) == blockReasonVariant(mr))
      case _                        => Result.failure("real and model disagree on grant waiver")
}

property("escape hatch bypasses both checks in model and shipped") {
  for states <- genSpecStateList.forAll
  yield
    val realPred  = PredecessorCheck.apply(states.map(toShipped), true)
    val modelPred = GateDecisionKernel.predecessorCheck(states.map(toModel), true)
    val realGrant  = GrantWaiver.apply(states.map(toShipped), true)
    val modelGrant = GateDecisionKernel.grantWaiver(states.map(toModel), true)
    Result.assert(realPred.isRight && modelPred.isRight && realGrant.isRight && modelGrant.isRight)
}
```

**Scope note**: Stainless proves (1) the predecessor check's ensuring clause (blocks if and only if any spec fails the Verified+presentation condition, unless the escape hatch is set), (2) the grant waiver's ensuring clause (blocks if and only if any spec fails the Verified+presentation OR grant condition, unless the escape hatch is set), (3) `notCheckpointedDistinctFromNotVerified` (the BlockReason variant is correct: Verified-without-presentation yields `PredecessorNotCheckpointed`, not `PredecessorNotVerified`), and (4) `grantWaiverWeakerThanPredecessor` (a spec passing the predecessor check also passes the grant waiver). The state-directory file I/O, the session-id sanitization, the bounded-refusal marker, and the CLI boundary's `GateDecision → Outcome[Int]` mapping are NOT modeled — they are Ring 3/4. The `render` method's string content (the payload text naming `"not checkpointed"`, `"checkpoint"`, and `"PROBATIO_HOOKS"`) is NOT modeled — it is Ring 3 (the block-reason-distinguishes-not-checkpointed property test binds the string content).

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The predecessor check requires a checkpoint presentation marker in addition to verified phase | Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase + Scenario: verified predecessor with no presentation is blocked | Hedgehog property: predecessor-check-requires-presentation | GateDecisionSpec, probatio-core |
| The grant waiver requires a checkpoint presentation marker in addition to verified phase | Requirement: The grant waiver requires a checkpoint presentation marker + Scenario: verified without presentation does not waive grant | Hedgehog property: grant-waiver-requires-presentation | GateDecisionSpec, probatio-core |
| The block reason distinguishes not-checkpointed from not-verified | Requirement: The block reason distinguishes not-checkpointed from not-verified + Scenario: not-checkpointed reason names checkpoint | Hedgehog property: block-reason-distinguishes-not-checkpointed | GateDecisionSpec, probatio-core |
| No sixth GateEvent case is constructible | Compile-Negative: GateEvent sealed enum | compile-negative test (`assertDoesNotCompile`) | GateDecisionSpec, probatio-core |
| No fifth BlockReason variant is constructible | Compile-Negative: BlockReason sealed trait | compile-negative test (`assertDoesNotCompile`) | GateDecisionSpec, probatio-core |
| No fourth SpecPhase case is constructible | Compile-Negative: SpecPhase sealed enum | compile-negative test (`assertDoesNotCompile`) | GateDecisionSpec, probatio-core |
| No `case _` in GateDecision match | Compile-Negative: exhaustive match on sealed enum | Scalafix DisableSyntax + WartRemover + `-Wconf:cat=pattern-match-exhaustivity:e` | GateDecision.scala, probatio-core |
| No `case _` in BlockReason renderer | Compile-Negative: exhaustive match on sealed trait | Scalafix DisableSyntax + WartRemover + `-Wconf:cat=pattern-match-exhaustivity:e` | BlockReason.scala, probatio-core |
| No file I/O in PredecessorCheck/GrantWaiver | Compile-Negative: pure functions take booleans, not file paths | Scalafix rule banning `scala.io.Source`/`java.nio.file`/`java.io.File` in PredecessorCheck.scala and GrantWaiver.scala | PredecessorCheck.scala, GrantWaiver.scala, probatio-core |
| No `System.getenv` in PredecessorCheck/GrantWaiver | Compile-Negative: escape hatch passed as Boolean parameter | Scalafix rule banning `System.getenv` in PredecessorCheck.scala and GrantWaiver.scala | PredecessorCheck.scala, GrantWaiver.scala, probatio-core |
| No `asInstanceOf` in gate decision code | Compile-Negative: project rule | WartRemover `AsInstanceOf` wart (scoped to probatio) | probatio-core |
| No `cats`/`cats-effect` import in gate decision code | Compile-Negative: R-X3 exclusion | dependency-lint rule (R-ARCH1 extension) + code-review gate | build.sbt, probatio-core |
| No `Arbitrary`-based Hedgehog generators | Compile-Negative: Hedgehog uses `Gen`, not `Arbitrary` | code-review gate | GateDecisionSpec, probatio-core |
| Block requires a BlockReason | Compile-Negative: `Block(reason: BlockReason)` constructor | type system (case class with required field) + compile-negative test | GateDecision.scala, probatio-core |
| Predecessor check ensuring clause — blocks iff any spec fails Verified+presentation, unless escape hatch | Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase + Formal Contract: GateDecisionKernel | formal contract (Ring 6, Stainless) + bridge property test | GateDecisionKernel (verified mirror); GateDecisionModelBridgeTests, probatio-core test |
| Grant waiver ensuring clause — blocks iff any spec fails Verified+presentation OR grant, unless escape hatch | Requirement: The grant waiver requires a checkpoint presentation marker + Formal Contract: GateDecisionKernel | formal contract (Ring 6, Stainless) + bridge property test | GateDecisionKernel (verified mirror); GateDecisionModelBridgeTests, probatio-core test |
| NotCheckpointed distinct from NotVerified in BlockReason variant selection | Requirement: The block reason distinguishes not-checkpointed from not-verified + Formal Contract: GateDecisionKernel | formal contract (Ring 6, Stainless) + bridge property test | GateDecisionKernel (verified mirror); GateDecisionModelBridgeTests, probatio-core test |
| Grant waiver weaker than predecessor check | Requirement: The grant waiver requires a checkpoint presentation marker + Formal Contract: GateDecisionKernel | formal contract (Ring 6, Stainless) | GateDecisionKernel (verified mirror) |
| Shipped predecessor check agrees with Stainless model | Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase + Formal Contract: GateDecisionKernel | bridge property (Ring 3 + Ring 6) | GateDecisionModelBridgeTests, probatio-core test |
| Shipped grant waiver agrees with Stainless model | Requirement: The grant waiver requires a checkpoint presentation marker + Formal Contract: GateDecisionKernel | bridge property (Ring 3 + Ring 6) | GateDecisionModelBridgeTests, probatio-core test |
| Escape hatch bypasses both checks in model and shipped | Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase + Scenario: escape hatch bypasses both checks + Formal Contract: GateDecisionKernel | bridge property (Ring 3 + Ring 6) | GateDecisionModelBridgeTests, probatio-core test |

## Implementation Anchors

| Anchor | Location | Notes |
|--------|----------|-------|
| `GateEvent` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/GateEvent.scala` | Sealed enum: `SessionStart`, `PromptSubmit`, `PostEdit`, `ToolCall`, `Completion` |
| `GateDecision` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/GateDecision.scala` | Sealed enum: `Allow`, `Block(reason: BlockReason)` |
| `SpecPhase` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SpecPhase.scala` | Sealed enum: `Oracle`, `Implementation`, `Verified`; derived from ledger rows by the CLI layer |
| `BlockReason` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/BlockReason.scala` | Sealed trait: `PredecessorNotVerified(spec, phase)`, `PredecessorNotCheckpointed(spec)`, `OracleOrderingViolation`, `GrantRequired(spec)`; each has a `render` method producing the payload string |
| `PredecessorCheck` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/PredecessorCheck.scala` | Pure function: `(List[(SpecName, SpecPhase, Boolean)], Boolean) → Either[BlockReason, Unit]` |
| `GrantWaiver` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/GrantWaiver.scala` | Pure function: `(List[(SpecName, SpecPhase, Boolean, Boolean)], Boolean) → Either[BlockReason, Unit]` |
| `PresentationMarker` | `workflow/core/src/main/scala/org/sinemenda/probatio/core/PresentationMarker.scala` | Value type; the CLI layer reads the state directory for marker files and passes existence as a boolean per spec |
| `GateCmd` | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/GateCmd.scala` | The `gate` subcommand; reads state files, constructs the pure-function inputs, calls `PredecessorCheck`/`GrantWaiver`, maps `GateDecision` to `Outcome[Int]` |
| `GateDecisionSpec` | `workflow/core/src/test/scala/org/sinemenda/probatio/core/GateDecisionSpec.scala` | Hedgehog properties: predecessor-check-requires-presentation, grant-waiver-requires-presentation, block-reason-distinguishes-not-checkpointed |
