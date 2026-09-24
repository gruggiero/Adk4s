package org.sinemenda.probatio.core

/**
 * Typed contract for ledger/checkpoint parity (spec 7, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `LedgerRecord` joins `LedgerRecordOptional` as a REQUIRED field — a
 *    record that cannot state what it observed is unrepresentable, and the
 *    single total `ReadWriter` emits every present optional field. Reading
 *    routes through the total validator, so a written row reads back with
 *    every field intact.
 *  - `ValidatedRecord` is a one-field wrapper; `provenance` is a DERIVED
 *    view of `record.optional` — never a second source of truth.
 *  - `ReplayVerdict` is a sealed three-case ADT — `Matches`, `Diverges`,
 *    `Unreplayable`. There is no fourth "skipped" case: every record
 *    receives exactly one verdict, so an unexamined record can never pass
 *    as verified. Judgment rings (`R8`, `Manual`) are unreplayable by the
 *    record tool's own rule — R2 is NOT unreplayable (the predecessor
 *    replays R2 rows like any runnable ring).
 *  - `RingStatus` carries the predecessor's five checkpoint tokens
 *    (`green` / `failed` / `unevidenced` / `same-session` /
 *    `unverified-session`). `Unevidenced` is a finding, not a skipped
 *    measurement.
 *  - `RingEvidence` binds one ring to its status, the ledger record the
 *    verdict traces to (`None` when unevidenced), and an optional note.
 *    The producing session lives on the record itself
 *    (`record.optional.session`) — evidence never invents a session.
 *  - `CheckpointReport`'s raw constructor is private; `of` derives
 *    `unresolvedCount` (read off the supplied verdict's own `unresolved`
 *    member) and `markerWritten` (all-requested-green AND nothing
 *    unresolved) — a report claiming the marker may be written while a
 *    requested ring is unevidenced is unrepresentable.
 *  - `CheckpointEngine.report` consumes the supplied correctness verdict
 *    as opaque `ujson.Value` — the engine READS the verdict's own fields;
 *    it never recomputes correctness (no reference to `ChainState`).
 *  - `CheckpointEngine.markerDecision` mirrors
 *    `LedgerValidatorKernel.markerDecision` — the verified kernel proves
 *    the structural-recursion formulation equals the `forall`
 *    formulation; the shipped engine uses the `forall` form.
 *  - `SessionId` is an opaque String — no public `apply`; the only
 *    constructions are `fromRaw`/`resolve`. `encoded` is the lossless
 *    filename-safe transform (injective on distinct raw identities).
 *
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): RingEvidence
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): CheckpointReport
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): ReplayVerdict
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): CheckpointEngine (implementation anchor)
 * spec: ledger-checkpoint-parity — Formal Contract: markerDecision
 */
final class LedgerCheckpointParityTypeContract extends ProbatioSuite:

  // ── RingStatus — the predecessor's five per-ring tokens ─────────────
  val ringStatusGreenSig: RingStatus       = RingStatus.Green
  val ringStatusFailedSig: RingStatus      = RingStatus.Failed
  val ringStatusUnevidencedSig: RingStatus = RingStatus.Unevidenced
  val ringStatusSameSessionSig: RingStatus = RingStatus.SameSession
  val ringStatusUnverifiedSig: RingStatus  = RingStatus.UnverifiedSession

  val ringStatusTokenSig: RingStatus => String =
    RingStatus.token

  // ── RingEvidence — one ring's evidence, traced to a record ──────────
  // The raw constructor is private: the only routes are `unevidenced`
  // (no outcome, no record) and `evidenced` (a REQUIRED record — an
  // outcome-bearing entry without evidence is unconstructible).
  val ringEvidenceUnevidencedSig: Ring => RingEvidence =
    RingEvidence.unevidenced

  val ringEvidenceEvidencedSig: (
    Ring,
    RingStatus,
    LedgerRecord,
    Option[String]
  ) => RingEvidence = RingEvidence.evidenced

  val ringEvidenceFieldsSig: RingEvidence => (
    Ring,
    RingStatus,
    Option[LedgerRecord],
    Option[String]
  ) =
    (e: RingEvidence) => (e.ring, e.status, e.record, e.note)

  // ── ReplayVerdict — exactly three verdicts, no "skipped" ────────────
  val verdictMatchesSig: ReplayVerdict      = ReplayVerdict.Matches
  val verdictDivergesSig: ReplayVerdict     = ReplayVerdict.Diverges
  val verdictUnreplayableSig: ReplayVerdict = ReplayVerdict.Unreplayable

  val unreplayableRingsSig: Set[Ring] =
    ReplayVerdict.unreplayableRings

  val classifySig: (Ring, Option[Int], BigInt) => ReplayVerdict =
    ReplayVerdict.classify

  // ── CheckpointReport — private constructor, single smart route ──────
  val reportOfSig: (
    String,
    String,
    String,
    List[RingEvidence],
    ujson.Value
  ) => CheckpointReport = CheckpointReport.of

  val reportFieldsSig: CheckpointReport => (
    String,
    String,
    String,
    List[RingEvidence],
    ujson.Value,
    Int,
    Boolean
  ) =
    (r: CheckpointReport) => (r.change, r.spec, r.baseline, r.rings, r.chainState, r.unresolvedCount, r.markerWritten)

  // ── CheckpointEngine — pure report generation ───────────────────────
  // `records` arrives ALREADY filtered by the record tool's own read
  // path; `chainState` is the supplied verdict as opaque JSON; the engine
  // never recomputes it.
  val engineReportSig: (
    String,
    String,
    String,
    List[Ring],
    List[LedgerRecord],
    ujson.Value,
    Option[SessionId]
  ) => CheckpointReport = CheckpointEngine.report

  val engineClassifySig: (Ring, Option[LedgerRecord], Option[SessionId]) => RingEvidence =
    CheckpointEngine.classify

  val engineMarkerDecisionSig: (List[Ring], List[Ring], Int) => Boolean =
    CheckpointEngine.markerDecision

  val unresolvedCountSig: ujson.Value => Int =
    CheckpointEngine.unresolvedCountOf

  val specBaselineSig: (String, String) => Option[String] =
    CheckpointEngine.specBaseline

  val regenerateTasksSig: (String, String) => String =
    CheckpointEngine.regenerateTasks

  // ── LedgerRecord — joined optional group, total encoder ─────────────
  val recordOptionalSig: LedgerRecord => LedgerRecordOptional =
    (r: LedgerRecord) => r.optional

  val recordFromSig: ujson.Value => Either[ContractViolation, LedgerRecord] =
    LedgerRecord.from

  val recordWriterSig: upickle.default.ReadWriter[LedgerRecord] =
    summon[upickle.default.ReadWriter[LedgerRecord]]

  val optionalEmptySig: LedgerRecordOptional = LedgerRecordOptional.empty

  val optionalExtractSig: Map[String, ujson.Value] => Either[ContractViolation, LedgerRecordOptional] =
    LedgerRecordOptional.extract

  // ── SessionId — opaque, lossless encoding ───────────────────────────
  val sessionFromRawSig: String => SessionId =
    SessionId.fromRaw

  val sessionResolveSig: (Option[String], Option[String], Option[String], Long) => SessionId =
    SessionId.resolve

  val sessionRawSig: SessionId => String =
    (s: SessionId) => s.raw

  val sessionEncodedSig: SessionId => String =
    (s: SessionId) => s.encoded

  // ── ValidatedRecord — one-field wrapper, derived provenance ─────────
  val validatedRecordApplySig: LedgerRecord => ValidatedRecord =
    ValidatedRecord.apply

  val validatedProvenanceSig: ValidatedRecord => LedgerRecordOptional =
    (v: ValidatedRecord) => v.provenance

  // ── Verified kernel mirror — markerDecision proof obligation ────────
  val kernelMarkerDecisionSig: (
    stainless.collection.List[BigInt],
    stainless.collection.List[BigInt],
    BigInt
  ) => Boolean =
    org.sinemenda.probatio.verified.LedgerValidatorKernel.markerDecision

  val kernelAllEvidencedSig: (
    stainless.collection.List[BigInt],
    stainless.collection.List[BigInt]
  ) => Boolean =
    org.sinemenda.probatio.verified.LedgerValidatorKernel.allEvidenced

  // ── The pinned surface evaluates ────────────────────────────────────

  private val sampleRecord: LedgerRecord =
    LedgerRecord
      .from(
        ujson.Obj(
          "v"          -> ujson.Num(1),
          "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
          "change"     -> ujson.Str("c"),
          "spec"       -> ujson.Str("s"),
          "ring"       -> ujson.Str("R1"),
          "obligation" -> ujson.Str("o"),
          "artifact"   -> ujson.Str("a"),
          "command"    -> ujson.Str("true"),
          "exit"       -> ujson.Num(0),
          "baseline"   -> ujson.Str("abc1234")
        )
      )
      .getOrElse(fail("a valid record must construct"))

  test("the record encoder round-trips every present optional field"):
    val withOptional: LedgerRecord = sampleRecord.copy(
      optional = LedgerRecordOptional(
        sha256 = Some("a" * 64),
        digest = Some("b" * 64),
        wallTime = Some(12),
        source = Some("ambient"),
        session = Some("sess-1")
      )
    )
    val written: String       = upickle.default.write(withOptional)
    val readBack: ujson.Value = ujson.read(written)
    val reparsed: LedgerRecord =
      LedgerRecord.from(readBack).getOrElse(fail("a written record must re-validate"))
    assertEquals(reparsed.optional, withOptional.optional)

  test("ReplayVerdict.classify covers every ring with exactly one verdict"):
    assertEquals(
      ReplayVerdict.classify(Ring.R1, Some(0), 0),
      ReplayVerdict.Matches
    )
    assertEquals(
      ReplayVerdict.classify(Ring.R1, Some(1), 0),
      ReplayVerdict.Diverges
    )
    assertEquals(
      ReplayVerdict.classify(Ring.R1, None, 0),
      ReplayVerdict.Unreplayable
    )
    assertEquals(
      ReplayVerdict.classify(Ring.R8, Some(0), 0),
      ReplayVerdict.Unreplayable,
      "judgment rings are unreplayable even when the exit would match"
    )
    assertEquals(
      ReplayVerdict.classify(Ring.Manual, Some(0), 0),
      ReplayVerdict.Unreplayable
    )
    assertEquals(
      ReplayVerdict.classify(Ring.R2, Some(0), 0),
      ReplayVerdict.Matches,
      "R2 is replayed like any runnable ring — not a judgment ring for verify"
    )

  test("CheckpointEngine.markerDecision mirrors the kernel rule"):
    assert(
      CheckpointEngine.markerDecision(
        List(Ring.R0, Ring.R1),
        List(Ring.R0, Ring.R1),
        0
      )
    )
    assert(
      !CheckpointEngine.markerDecision(
        List(Ring.R0, Ring.R1),
        List(Ring.R0),
        0
      ),
      "an unevidenced requested ring forbids the marker"
    )
    assert(
      !CheckpointEngine.markerDecision(
        List(Ring.R0),
        List(Ring.R0),
        1
      ),
      "an unresolved requirement forbids the marker"
    )

  test("SessionId encoding is lossless and injective"):
    val a: SessionId = SessionId.fromRaw("session/a+b=c")
    val b: SessionId = SessionId.fromRaw("session/b")
    assertEquals(a.raw, "session/a+b=c")
    assertNotEquals(a.encoded, b.encoded, "distinct identities must not collide")
    assert(a.encoded.forall((c: Char) => c.isLetterOrDigit || c == '-' || c == '_' || c == '.'))

  // ── Compile-Negative (spec 8): a checkpoint entry asserting an
  //    outcome with no evidence ─────────────────────────────────────────
  // spec: ledger-checkpoint-cutover — Compile-Negative: A checkpoint entry asserting an outcome with no evidence
  // The raw constructor is private (RingEvidence is a final class — `copy`
  // cannot be suppressed on a case class), so an outcome-bearing entry
  // without its record is unconstructible: the only paths are
  // `unevidenced` (no outcome) and `evidenced` (a required record).
  test("compile-negative: an outcome-bearing RingEvidence without its record is unconstructible"):
    val err: String = compileErrors("RingEvidence(Ring.R0, RingStatus.Green, None, None)")
    assert(
      err.nonEmpty,
      "RingEvidence(R0, Green, None, None) should not compile — an outcome-bearing entry requires its evidence"
    )
    val err2: String = compileErrors("RingEvidence.unevidenced(Ring.R0).copy(status = RingStatus.Green)")
    assert(
      err2.nonEmpty,
      "RingEvidence is a final class — there is no public `copy` to strip the record off an entry"
    )
