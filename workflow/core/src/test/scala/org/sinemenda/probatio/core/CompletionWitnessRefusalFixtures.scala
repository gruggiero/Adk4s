package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range

/**
 * Constructive generators for the completion-witness-refusal oracle
 * (spec 3 of `repair-probatio-cutover`, Step 2).
 *
 * `genEvidenceRecord` is the spec's declared generator: lists of 0–8
 * rows, each drawn as (result ∈ {green, red}, corroborated ∈ {yes, no},
 * baseline ∈ {current, other}) from closed sets — never filtered. The
 * corroboration outcome is realised by construction: a corroborated
 * claim emits an agreeing ambient witness (or is self-observed via a
 * digest row); an uncorroborated claim emits nothing (testimony) or a
 * disagreeing ambient row (contradicted — an observation that does not
 * corroborate). Rows are keyed on a per-index command so no row's
 * witness can corroborate another row's claim.
 *
 * The `staleBaseline` arm is the declared divergence: an uncorroborated
 * green claim at a non-current baseline is a refusal warrant for the
 * predecessor (unfiltered reconcile) but NOT for the ported
 * current-baseline scope — the generator draws it deliberately and the
 * cover labels make its frequency visible.
 *
 * spec: completion-witness-refusal — Property: refusal-iff-an-uncorroborated-green-result-exists
 * spec: completion-witness-refusal — Property: refusal-budget-is-bounded-and-nonzero
 */
object CompletionWitnessRefusalFixtures:

  /** The gate-resolved baseline token — the "current" arm of the draw. */
  val currentBaseline: String = "cur-sha1"

  /** A non-current baseline token — the declared-divergence arm. */
  val staleBaseline: String = "stale-00"

  /**
   * One generated ledger row's plan — the three facts the refusal
   * predicate reads plus the realising flavour:
   *
   *  - `isGreen` — the row's recorded exit is 0.
   *  - `corroborated` — an independent observation corroborates it
   *    (or it needs none: a red row is exempt whatever this flag says).
   *  - `atBaseline` — the row's baseline is the gate's current baseline.
   *  - `contradicted` — the uncorroborated flavour: ambient observers
   *    at the key recorded a different exit.
   *  - `selfObserved` — the corroborated flavour: the row carries its
   *    own digest (a `ledger run` record).
   */
  final case class RowPlan(
    isGreen: Boolean,
    corroborated: Boolean,
    atBaseline: Boolean,
    contradicted: Boolean,
    selfObserved: Boolean
  )

  /** The claim command identifying the i-th row's key. */
  def rowCommand(i: Int): String = s"cmd-$i"

  /**
   * The records a plan emits for `change` — the claim row plus the
   * ambient/digest rows that realise its corroboration outcome, in
   * ledger order (claim first, witnesses after — the predecessor's
   * ambient rows land after the claim they observe).
   */
  def recordsFor(plan: RowPlan, change: String, i: Int): List[ValidatedRecord] =
    val key: ReconcileFixtures.ClaimKey = ReconcileFixtures.ClaimKey(
      spec = "sp",
      ring = Ring.R3,
      baseline = if plan.atBaseline then currentBaseline else staleBaseline,
      command = rowCommand(i)
    )
    if !plan.isGreen then
      // A red row discharges nothing — it is exempt whatever the
      // corroborated flag says; the flag is inert for non-green rows.
      List(ReconcileFixtures.record(key, change, exit = 1, ReconcileFixtures.RecKind.Written))
    else if plan.corroborated && plan.selfObserved then
      List(ReconcileFixtures.record(key, change, exit = 0, ReconcileFixtures.RecKind.DigestRow))
    else if plan.corroborated then
      List(
        ReconcileFixtures.record(key, change, exit = 0, ReconcileFixtures.RecKind.Written),
        ReconcileFixtures.record(key, change, exit = 0, ReconcileFixtures.RecKind.AmbientRow)
      )
    else if plan.contradicted then
      List(
        ReconcileFixtures.record(key, change, exit = 0, ReconcileFixtures.RecKind.Written),
        ReconcileFixtures.record(key, change, exit = 1, ReconcileFixtures.RecKind.AmbientRow)
      )
    else List(ReconcileFixtures.record(key, change, exit = 0, ReconcileFixtures.RecKind.Written))

  /** The per-row draw, skewed so refusal warrants are common. */
  val genRowPlan: Gen[RowPlan] =
    for
      isGreen      <- Gen.frequency1(7 -> Gen.constant(true), 3 -> Gen.constant(false))
      corroborated <- Gen.element1(true, false)
      atBaseline   <- Gen.frequency1(3 -> Gen.constant(true), 2 -> Gen.constant(false))
      contradicted <- Gen.element1(true, false)
      selfObserved <- Gen.element1(true, false)
    yield RowPlan(
      isGreen,
      corroborated,
      atBaseline,
      contradicted && isGreen && !corroborated,
      selfObserved && isGreen && corroborated
    )

  /**
   * `genEvidenceRecord` — the spec's generator: a change name, the
   * realised record list (0–8 drawn rows plus their witness rows), and
   * the row plans carrying the model truth the property asserts
   * against. The empty record is a 1-in-9 edge the Range reaches
   * naturally.
   */
  val genEvidenceRecord: Gen[(String, List[ValidatedRecord], List[RowPlan])] =
    for
      change <- Gen.string(Gen.alpha, Range.linear(3, 8)).map("chg-" + _)
      plans  <- genRowPlan.list(Range.linear(0, 8))
    yield
      val records: List[ValidatedRecord] =
        plans.zipWithIndex.flatMap { case (p: RowPlan, i: Int) => recordsFor(p, change, i) }
      (change, records, plans)

  /** A stated could-not-determine reason — non-empty by construction. */
  val genReason: Gen[UndeterminedReason] =
    Gen
      .string(Gen.alphaNum, Range.linear(1, 40))
      .map((s: String) => UndeterminedReason.stated(s"unreadable input: $s"))

  /** The per-turn refusal budget — fresh or already spent. */
  val genBudget: Gen[RefusalBudget] =
    Gen.element1(RefusalBudget.full, RefusalBudget.fromMarker(alreadyRefused = true))

  /** A claim verdict payload for the attempt-sequence generator. */
  val genClaim: Gen[ClaimVerdict] =
    for
      spec <- Gen.string(Gen.alphaNum, Range.linear(1, 6)).map("sp-" + _)
      obl  <- Gen.string(Gen.alphaNum, Range.linear(1, 8)).map("obl-" + _)
      cmd  <- Gen.element1("sbt test", "make check", "./run.sh")
    yield ClaimVerdict(
      spec = spec,
      ring = Ring.R3,
      obligation = obl,
      command = cmd,
      baseline = currentBaseline,
      verdict = "testimony",
      observed = List.empty[Int]
    )

  /**
   * `genAttemptSequence` — the spec's generator: sequences of 1–6
   * completion attempts within one turn, each attempt's evidence state
   * drawn as a verdict (witnessed / unwitnessed / undeterminable). The
   * turn identity and its budget are threaded through the fold — the
   * injected seam the spec requires, with no wall-clock involved.
   */
  val genAttemptSequence: Gen[List[WitnessVerdict]] =
    Gen
      .frequency1(
        40 -> Gen.constant(WitnessVerdict.Witnessed),
        35 -> genClaim.map((c: ClaimVerdict) => WitnessVerdict.Unwitnessed(c)),
        25 -> genReason.map((r: UndeterminedReason) => WitnessVerdict.Undeterminable(r))
      )
      .list(Range.linear(1, 6))
