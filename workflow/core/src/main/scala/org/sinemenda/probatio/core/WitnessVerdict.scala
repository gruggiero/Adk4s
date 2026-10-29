package org.sinemenda.probatio.core

/**
 * The completion tier's corroboration verdict over a change's evidence
 * record — whether every green claim at the current baseline carries an
 * independent observation.
 *
 * Three closed variants, and the third is load-bearing: `Witnessed`
 * means "checked, corroborated"; `Unwitnessed` means "checked, a green
 * claim names no corroborating observation" — and it carries the
 * offending `ClaimVerdict`, so a refusal can always name what it
 * refuses over. `Undeterminable` means "the check could not be
 * performed" — the record, the baseline, or the state area could not
 * be read — and it carries a stated `UndeterminedReason`, never a bare
 * marker. Collapsing `Undeterminable` into either verdict arm would
 * turn an environment fault into a claim: a refusal nobody earned or an
 * allow nobody verified.
 *
 * The verdict says nothing about the refusal budget — bounding the
 * decision to one refusal per turn is `CompletionDecision`'s job.
 *
 * spec: completion-witness-refusal — Concepts Introduced (new): WitnessVerdict
 * spec: completion-witness-refusal — Compile-Negative: A witness verdict with only two variants
 */
enum WitnessVerdict:

  /** Every green claim at the current baseline is corroborated — or none exist. */
  case Witnessed

  /**
   * A green claim at the current baseline has no corroborating
   * observation. `row` is the offending claim — the refusal names it.
   */
  case Unwitnessed(row: ClaimVerdict)

  /**
   * The corroboration check could not be performed — the record, the
   * baseline, or the per-session state area could not be read. `reason`
   * names the unreadable input; the verdict is neither witnessed nor a
   * refusal.
   */
  case Undeterminable(reason: UndeterminedReason)

object WitnessVerdict:

  extension (v: WitnessVerdict)
    /** The offending claim, when the verdict carries one. */
    def namedRow: Option[ClaimVerdict] = v match
      case Unwitnessed(row)  => Some(row)
      case Witnessed         => None
      case Undeterminable(_) => None

/**
 * The completion tier's decision — the corroboration verdict bounded by
 * the per-turn refusal budget and mapped to the tier's three
 * continuations.
 *
 * `Allow` is the clean proceed. `AllowUndetermined` is the fail-open
 * proceed: the check could not run, so the tier allows and STATES the
 * reason — an unreadable input must never be read as corroboration.
 * `Refuse` is the promoted `Unwitnessed` verdict — it keeps the
 * offending row so the refusal output can name it.
 *
 * spec: completion-witness-refusal — Contract: decideCompletion
 */
enum CompletionDecision:

  /** Every check that ran returned witnessed — proceed. */
  case Allow

  /**
   * The corroboration check could not be performed; the tier proceeds
   * and states `reason` — naming the unreadable input.
   */
  case AllowUndetermined(reason: UndeterminedReason)

  /**
   * The turn is refused — `unwitnessed` carries the offending claim so
   * the refusal names what it refuses over.
   */
  case Refuse(unwitnessed: WitnessVerdict.Unwitnessed)

object CompletionDecision:

  extension (d: CompletionDecision)
    /** True when the decision is a refusal. */
    def isRefusal: Boolean = d match
      case Refuse(_)            => true
      case Allow                => false
      case AllowUndetermined(_) => false

    /** The row the refusal is justified by — defined iff a refusal. */
    def namedRow: Option[ClaimVerdict] = d match
      case Refuse(unwitnessed)  => Some(unwitnessed.row)
      case Allow                => None
      case AllowUndetermined(_) => None
