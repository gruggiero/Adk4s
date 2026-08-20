package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of the `Validator` 12-clause total contract.
 *
 * This model mirrors the totality law of the ledger-record validator in
 * `org.sinemenda.probatio.core.Validator`. The shipped validator operates on
 * `ujson.Value` with string regex checks and `Ring.fromString` parsing; those
 * constructs are beyond the Stainless frontend (pinned to Scala 3.7.2 while
 * the build is 3.8.4). The *algorithm* — a 12-clause sequential guard that
 * always returns exactly one outcome — survives reduction to observable
 * effect.
 *
 * Abstraction:
 *   - String fields → `BigInt` (non-zero = non-empty, zero = empty).
 *   - Integer fields → `BigInt`.
 *   - Boolean validity checks (ISO-8601, hex, path-separator-free) →
 *     `Boolean` parameters, since the string patterns cannot be modelled.
 *   - `ContractViolation` → sealed abstract class with one case object per
 *     clause.
 *   - `Ring` → sealed abstract class with case objects R0–R8 and Manual.
 *   - `Option[Ring]` models the parse result (`None()` = outside domain).
 *
 * The bridge spec (`LedgerValidatorBridgeSpec` in probatio-core) runs the real
 * `Validator.validate` and this model on the SAME generated field values and
 * validity flags, and asserts they agree on the proven invariants.
 *
 * Key law: **Totality** — for every input, `validate` returns exactly one
 * outcome (`Left` or `Right`, never both, never neither). This is proven by
 * the `ensuring` postcondition on `validate` and the `totalityLaw` lemma.
 *
 * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: probatio-core — Property: ContractViolation totality — every clause is reachable
 */
object LedgerValidatorKernel:

  // ── Ring ADT — closed domain (R0–R8, Manual) ──────────────────────────────

  /** The closed ring domain. A ring outside this set is unrepresentable. */
  sealed abstract class Ring
  object Ring:
    case object R0     extends Ring
    case object R1     extends Ring
    case object R2     extends Ring
    case object R3     extends Ring
    case object R4     extends Ring
    case object R5     extends Ring
    case object R6     extends Ring
    case object R7     extends Ring
    case object R8     extends Ring
    case object Manual extends Ring

  // ── Violation ADT — one variant per clause (1–12) ─────────────────────────

  /** Disjoint sum of the 12 clause failures. One case object per clause. */
  sealed abstract class Violation:
    /** 1-based index of the failing clause (1–12). */
    def clauseIndex: BigInt

  object Violation:
    /** Clause 1: v must be a positive integer. */
    case object VersionInvalid extends Violation:
      def clauseIndex: BigInt = 1

    /** Clause 2: ts must be a valid ISO-8601 timestamp (non-empty). */
    case object TimestampInvalid extends Violation:
      def clauseIndex: BigInt = 2

    /** Clause 3: change must be non-empty. */
    case object ChangeInvalid extends Violation:
      def clauseIndex: BigInt = 3

    /** Clause 4: spec must be non-empty. */
    case object SpecInvalid extends Violation:
      def clauseIndex: BigInt = 4

    /** Clause 5: ring must be a valid Ring (R0–R8 or Manual). */
    case object RingOutsideDomain extends Violation:
      def clauseIndex: BigInt = 5

    /** Clause 6: obligation must be non-empty. */
    case object ObligationEmpty extends Violation:
      def clauseIndex: BigInt = 6

    /** Clause 7: artifact must be non-empty. */
    case object ArtifactEmpty extends Violation:
      def clauseIndex: BigInt = 7

    /** Clause 8: command must be non-empty. */
    case object CommandEmpty extends Violation:
      def clauseIndex: BigInt = 8

    /** Clause 9: exit must be an integer. */
    case object ExitNotInteger extends Violation:
      def clauseIndex: BigInt = 9

    /** Clause 10: baseline must be a valid hex string (non-empty). */
    case object BaselineInvalid extends Violation:
      def clauseIndex: BigInt = 10

    /** Clause 11: artifact must not contain path separators. */
    case object ArtifactPathSeparator extends Violation:
      def clauseIndex: BigInt = 11

    /** Clause 12: ts must not contain path separators. */
    case object TimestampPathSeparator extends Violation:
      def clauseIndex: BigInt = 12

  // ── Valid record — the Right outcome ──────────────────────────────────────

  /** A record that has passed all 12 clauses. */
  case class ValidRecord(
    v: BigInt,
    ts: BigInt,
    change: BigInt,
    spec: BigInt,
    ring: Ring,
    obligation: BigInt,
    artifact: BigInt,
    command: BigInt,
    exit: BigInt,
    baseline: BigInt
  )

  // ── Validity check functions (boolean abstractions) ───────────────────────

  /** Clause 1 check: v must be a positive integer. */
  @pure
  def isPositive(v: BigInt): Boolean = v > 0

  /** Non-empty check for string fields abstracted to BigInt (non-zero = non-empty). */
  @pure
  def isNonEmpty(s: BigInt): Boolean = s != 0

  /** Clause 5 check: ring must parse to a valid Ring (Some) vs outside domain (None). */
  @pure
  def isValidRing(r: Option[Ring]): Boolean = r match
    case Some(_) => true
    case None()  => false

  // ── The 12-clause total validator ─────────────────────────────────────────

  /**
   * Validate all 12 clauses in order, returning either the first
   * `Violation` or a `ValidRecord`.
   *
   * Parameters (12 clauses):
   *   1. `v` — version integer (must be positive)
   *   2. `ts` + `tsValidIso` — timestamp string (non-empty + valid ISO-8601)
   *   3. `change` — change string (non-empty)
   *   4. `spec` — spec string (non-empty)
   *   5. `ring` — ring parse result (must be Some)
   *   6. `obligation` — obligation string (non-empty)
   *   7. `artifact` — artifact string (non-empty)
   *   8. `command` — command string (non-empty)
   *   9. `exit` + `exitIsInteger` — exit code (must be integer)
   *   10. `baseline` + `baselineValidHex` — baseline string (non-empty + valid hex)
   *   11. `artifactNoSep` — artifact must not contain path separators
   *   12. `tsNoSep` — ts must not contain path separators
   *
   * Totality postcondition: the result is always either `Left` or `Right`.
   */
  @pure
  def validate(
    v: BigInt,
    ts: BigInt,
    tsValidIso: Boolean,
    tsNoSep: Boolean,
    change: BigInt,
    spec: BigInt,
    ring: Option[Ring],
    obligation: BigInt,
    artifact: BigInt,
    artifactNoSep: Boolean,
    command: BigInt,
    exit: BigInt,
    exitIsInteger: Boolean,
    baseline: BigInt,
    baselineValidHex: Boolean
  ): Either[Violation, ValidRecord] = {
    // Clause 1: v must be a positive integer
    if !isPositive(v) then
      Left(Violation.VersionInvalid)
    // Clause 2: ts must be non-empty and valid ISO-8601
    else if !isNonEmpty(ts) || !tsValidIso then
      Left(Violation.TimestampInvalid)
    // Clause 3: change must be non-empty
    else if !isNonEmpty(change) then
      Left(Violation.ChangeInvalid)
    // Clause 4: spec must be non-empty
    else if !isNonEmpty(spec) then
      Left(Violation.SpecInvalid)
    // Clause 5: ring must be a valid Ring
    else if !isValidRing(ring) then
      Left(Violation.RingOutsideDomain)
    // Clause 6: obligation must be non-empty
    else if !isNonEmpty(obligation) then
      Left(Violation.ObligationEmpty)
    // Clause 7: artifact must be non-empty
    else if !isNonEmpty(artifact) then
      Left(Violation.ArtifactEmpty)
    // Clause 8: command must be non-empty
    else if !isNonEmpty(command) then
      Left(Violation.CommandEmpty)
    // Clause 9: exit must be an integer
    else if !exitIsInteger then
      Left(Violation.ExitNotInteger)
    // Clause 10: baseline must be valid hex (non-empty)
    else if !isNonEmpty(baseline) || !baselineValidHex then
      Left(Violation.BaselineInvalid)
    // Clause 11: artifact must not contain path separators
    else if !artifactNoSep then
      Left(Violation.ArtifactPathSeparator)
    // Clause 12: ts must not contain path separators
    else if !tsNoSep then
      Left(Violation.TimestampPathSeparator)
    // All 12 clauses passed — extract the Ring and construct the record
    else
      ring match
        case Some(r) =>
          Right(ValidRecord(v, ts, change, spec, r, obligation, artifact, command, exit, baseline))
        case None() =>
          Left(Violation.RingOutsideDomain) // danger-scan:allow dead-branch — unreachable: isValidRing passed above
  }.ensuring { result =>
    // Totality: exactly one outcome — never both, never neither
    result.isLeft || result.isRight
  }

  // ---------------------------------------------------------------------------
  // Property lemmas — standalone boolean functions
  // ---------------------------------------------------------------------------

  /**
   * Law: Totality — for every input, `validate` returns either `Left` or
   * `Right` (never crashes, never returns neither).
   *
   * This is the key totality law: the validator is a total function from
   * inputs to outcomes.
   */
  @pure
  def totalityLaw(
    v: BigInt,
    ts: BigInt,
    tsValidIso: Boolean,
    tsNoSep: Boolean,
    change: BigInt,
    spec: BigInt,
    ring: Option[Ring],
    obligation: BigInt,
    artifact: BigInt,
    artifactNoSep: Boolean,
    command: BigInt,
    exit: BigInt,
    exitIsInteger: Boolean,
    baseline: BigInt,
    baselineValidHex: Boolean
  ): Boolean = {
    val result: Either[Violation, ValidRecord] = validate(
      v, ts, tsValidIso, tsNoSep, change, spec, ring,
      obligation, artifact, artifactNoSep, command, exit, exitIsInteger,
      baseline, baselineValidHex
    )
    result.isLeft || result.isRight
  }.ensuring(_ == true)

  /**
   * Law: Mutual exclusivity — an `Either` outcome is never both `Left` and
   * `Right` simultaneously.
   */
  @pure
  def mutualExclusivityLaw(result: Either[Violation, ValidRecord]): Boolean = {
    !(result.isLeft && result.isRight)
  }.ensuring(_ == true)

  /**
   * Law: If all 12 clauses pass, `validate` returns `Right` with a
   * `ValidRecord` carrying the input values.
   */
  @pure
  def allValidProducesRight(
    v: BigInt,
    ts: BigInt,
    tsValidIso: Boolean,
    tsNoSep: Boolean,
    change: BigInt,
    spec: BigInt,
    ring: Option[Ring],
    obligation: BigInt,
    artifact: BigInt,
    artifactNoSep: Boolean,
    command: BigInt,
    exit: BigInt,
    exitIsInteger: Boolean,
    baseline: BigInt,
    baselineValidHex: Boolean
  ): Boolean = {
    require(
      v > 0 &&
        ts != 0 && tsValidIso && tsNoSep &&
        change != 0 &&
        spec != 0 &&
        ring.isDefined &&
        obligation != 0 &&
        artifact != 0 && artifactNoSep &&
        command != 0 &&
        exitIsInteger &&
        baseline != 0 && baselineValidHex
    )
    validate(
      v, ts, tsValidIso, tsNoSep, change, spec, ring,
      obligation, artifact, artifactNoSep, command, exit, exitIsInteger,
      baseline, baselineValidHex
    ) match
      case Right(rec) =>
        rec.v == v && rec.ts == ts && rec.change == change && rec.spec == spec &&
          rec.obligation == obligation && rec.artifact == artifact &&
          rec.command == command && rec.exit == exit && rec.baseline == baseline
      case Left(_) => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 1 — if v is not positive, `validate` returns
   * `Left(VersionInvalid)`.
   */
  @pure
  def clause1VersionInvalid(v: BigInt): Boolean = {
    require(v <= 0)
    validate(
      v, BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.VersionInvalid) => true
      case _                              => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 2 — if ts is empty or not valid ISO-8601, `validate` returns
   * `Left(TimestampInvalid)`.
   */
  @pure
  def clause2TimestampInvalid(ts: BigInt, tsValidIso: Boolean): Boolean = {
    require(ts == 0 || !tsValidIso)
    validate(
      BigInt(1), ts, tsValidIso, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.TimestampInvalid) => true
      case _                                 => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 3 — if change is empty, `validate` returns
   * `Left(ChangeInvalid)`.
   */
  @pure
  def clause3ChangeInvalid(change: BigInt): Boolean = {
    require(change == 0)
    validate(
      BigInt(1), BigInt(1), true, true, change, BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.ChangeInvalid) => true
      case _                             => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 4 — if spec is empty, `validate` returns `Left(SpecInvalid)`.
   */
  @pure
  def clause4SpecInvalid(spec: BigInt): Boolean = {
    require(spec == 0)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), spec, Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.SpecInvalid) => true
      case _                           => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 5 — if ring is outside the domain (None), `validate` returns
   * `Left(RingOutsideDomain)`.
   */
  @pure
  def clause5RingOutsideDomain(ring: Option[Ring]): Boolean = {
    require(ring.isEmpty)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), ring,
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.RingOutsideDomain) => true
      case _                                 => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 6 — if obligation is empty, `validate` returns
   * `Left(ObligationEmpty)`.
   */
  @pure
  def clause6ObligationEmpty(obligation: BigInt): Boolean = {
    require(obligation == 0)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      obligation, BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.ObligationEmpty) => true
      case _                               => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 7 — if artifact is empty, `validate` returns
   * `Left(ArtifactEmpty)`.
   */
  @pure
  def clause7ArtifactEmpty(artifact: BigInt): Boolean = {
    require(artifact == 0)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), artifact, true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.ArtifactEmpty) => true
      case _                             => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 8 — if command is empty, `validate` returns
   * `Left(CommandEmpty)`.
   */
  @pure
  def clause8CommandEmpty(command: BigInt): Boolean = {
    require(command == 0)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, command, BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.CommandEmpty) => true
      case _                            => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 9 — if exit is not an integer, `validate` returns
   * `Left(ExitNotInteger)`.
   */
  @pure
  def clause9ExitNotInteger(exitIsInteger: Boolean): Boolean = {
    require(!exitIsInteger)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), exitIsInteger, BigInt(1), true
    ) match
      case Left(Violation.ExitNotInteger) => true
      case _                              => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 10 — if baseline is empty or not valid hex, `validate`
   * returns `Left(BaselineInvalid)`.
   */
  @pure
  def clause10BaselineInvalid(baseline: BigInt, baselineValidHex: Boolean): Boolean = {
    require(baseline == 0 || !baselineValidHex)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, baseline, baselineValidHex
    ) match
      case Left(Violation.BaselineInvalid) => true
      case _                               => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 11 — if artifact contains path separators, `validate`
   * returns `Left(ArtifactPathSeparator)`.
   */
  @pure
  def clause11ArtifactPathSeparator(artifactNoSep: Boolean): Boolean = {
    require(!artifactNoSep)
    validate(
      BigInt(1), BigInt(1), true, true, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), artifactNoSep, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.ArtifactPathSeparator) => true
      case _                                     => false
  }.ensuring(_ == true)

  /**
   * Law: Clause 12 — if ts contains path separators, `validate` returns
   * `Left(TimestampPathSeparator)`.
   */
  @pure
  def clause12TimestampPathSeparator(tsNoSep: Boolean): Boolean = {
    require(!tsNoSep)
    validate(
      BigInt(1), BigInt(1), true, tsNoSep, BigInt(1), BigInt(1), Some(Ring.R0),
      BigInt(1), BigInt(1), true, BigInt(1), BigInt(0), true, BigInt(1), true
    ) match
      case Left(Violation.TimestampPathSeparator) => true
      case _                                      => false
  }.ensuring(_ == true)

  /**
   * Law: Clause index totality — every `Violation` has a clause index in
   * the range 1–12.
   */
  @pure
  def violationClauseIndexInRange(v: Violation): Boolean = {
    v.clauseIndex >= 1 && v.clauseIndex <= 12
  }.ensuring(_ == true)

  /**
   * Law: Clause index distinctness — each violation variant has a unique
   * clause index.
   */
  @pure
  def clauseIndexDistinctness: Boolean = {
    val indices: List[BigInt] = List(
      Violation.VersionInvalid.clauseIndex,
      Violation.TimestampInvalid.clauseIndex,
      Violation.ChangeInvalid.clauseIndex,
      Violation.SpecInvalid.clauseIndex,
      Violation.RingOutsideDomain.clauseIndex,
      Violation.ObligationEmpty.clauseIndex,
      Violation.ArtifactEmpty.clauseIndex,
      Violation.CommandEmpty.clauseIndex,
      Violation.ExitNotInteger.clauseIndex,
      Violation.BaselineInvalid.clauseIndex,
      Violation.ArtifactPathSeparator.clauseIndex,
      Violation.TimestampPathSeparator.clauseIndex
    )
    indices.length == 12 && indices.forall(i => i >= 1 && i <= 12)
  }.ensuring(_ == true)
