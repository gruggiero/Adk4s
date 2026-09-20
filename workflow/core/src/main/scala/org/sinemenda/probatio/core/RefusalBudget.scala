package org.sinemenda.probatio.core

/**
 * The per-turn refusal budget (spec 8). At most one refusal per turn —
 * the field is a count of issued refusals, not a flag a caller could
 * reset, and the private constructor makes a budget above `PerTurn`
 * unrepresentable.
 *
 * The gate materialises a budget from the session's refusal marker:
 * `fromMarker(true)` is an already-spent budget (the refusal was issued
 * in an earlier invocation of the same turn). When the state directory
 * is absent the gate cannot establish whether the budget was spent, so
 * the blocking path fails open — a stated reason, not a block.
 *
 * spec: gate-event-completeness — Concepts Introduced (new): RefusalBudget
 * spec: gate-event-completeness — Requirement: At most one refusal is issued per turn
 * spec: gate-event-completeness — Property: refusal-budget-is-bounded-and-nonzero
 */
final case class RefusalBudget private (issued: Int)

object RefusalBudget:

  /** The per-turn refusal allowance. */
  val PerTurn: Int = 1

  /** A full budget — no refusal issued this turn. */
  val full: RefusalBudget = RefusalBudget(0)

  /**
   * Restore the budget from the refusal marker's presence. The marker
   * names a refusal already issued this turn; a present marker is a
   * spent budget.
   */
  def fromMarker(alreadyRefused: Boolean): RefusalBudget =
    if alreadyRefused then RefusalBudget(PerTurn) else full

  extension (b: RefusalBudget)
    /** True once the turn's one refusal has been issued. */
    def exhausted: Boolean = b.issued >= PerTurn

    /**
     * Issue the refusal, consuming the budget. `None` when the budget
     * is spent — the second refusal in a turn is unrepresentable.
     */
    def issue: Option[RefusalBudget] =
      if b.exhausted then None else Some(RefusalBudget(b.issued + 1))

  /**
   * The kernel-mirrored fold: the refusal decisions over a turn's
   * blockable actions. Exactly one refusal is produced, at the first
   * blockable action; none when nothing is blockable.
   *
   * spec: gate-event-completeness — Contract: refusalBudget
   */
  def apply(blockable: List[Boolean]): List[Boolean] =
    val first: Int = blockable.indexOf(true)
    blockable.zipWithIndex.map((b: Boolean, i: Int) => b && i == first)

end RefusalBudget
