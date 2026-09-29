package org.sinemenda.probatio.core

/**
 * The three-way replay verdict for one ledger record (spec 7).
 *
 * Every record receives exactly one verdict — there is no "skipped"
 * fourth case that would let an unexamined record pass as verified:
 *   - `Matches`      — the command replayed and its observed exit agrees
 *                      with the recorded exit.
 *   - `Diverges`     — the command replayed and its observed exit
 *                      disagrees with the recorded exit.
 *   - `Unreplayable` — the record cannot be replayed: its ring's
 *                      discharge is a human judgment (manual/R8), or the
 *                      recorded command does not parse as shell.
 *
 * spec: ledger-checkpoint-parity — Requirement: Replay SHALL assign exactly one verdict to every record
 * spec: ledger-checkpoint-parity — Compile-Negative: A match on ReplayVerdict that omits a case
 */
enum ReplayVerdict:
  case Matches, Diverges, Unreplayable

object ReplayVerdict:

  /**
   * The predecessor's non-runnable ring set for `verify`: `manual` and
   * `R8` — the rings whose discharge is a human judgment. R2 is NOT in
   * this set (the predecessor replays R2 rows like any runnable ring);
   * this is `verify`'s own rule, not `ReconcileEngine.judgmentRings`.
   */
  val unreplayableRings: Set[Ring] = Set(Ring.R8, Ring.Manual)

  /**
   * Classify one record's replay outcome.
   *
   * `replayedExit` is `None` when the command could not be replayed at
   * all (it did not parse as shell) and `Some(observed)` when the replay
   * ran. Judgment-ring records are `Unreplayable` regardless — they are
   * evidence of a human decision, not of a runnable command.
   *
   * spec: ledger-checkpoint-parity — Scenario: a record on a judgment ring is unreplayable, not failing
   */
  def classify(ring: Ring, replayedExit: Option[Int], recordedExit: BigInt): ReplayVerdict =
    if unreplayableRings.contains(ring) then ReplayVerdict.Unreplayable
    else byExit(replayedExit, recordedExit)

  /**
   * Classify by the row's raw ring name — the shipped verify path reads
   * `ring` as a string. A name outside the closed domain is not a
   * judgment ring (never silently `Unreplayable`): it is replayed and
   * judged by exit like any runnable row.
   */
  def classifyName(ring: String, replayedExit: Option[Int], recordedExit: BigInt): ReplayVerdict =
    if Ring.fromString(ring).exists(unreplayableRings.contains) then ReplayVerdict.Unreplayable
    else byExit(replayedExit, recordedExit)

  /** The exit comparison shared by both classifiers. */
  private def byExit(replayedExit: Option[Int], recordedExit: BigInt): ReplayVerdict =
    replayedExit match
      case None => ReplayVerdict.Unreplayable
      case Some(observed) =>
        if observed == recordedExit then ReplayVerdict.Matches else ReplayVerdict.Diverges

end ReplayVerdict
