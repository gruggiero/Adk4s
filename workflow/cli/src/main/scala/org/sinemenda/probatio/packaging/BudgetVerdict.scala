package org.sinemenda.probatio.packaging

/**
 * The verdict of evaluating a recorded latency measurement against a
 * budget (R-N1).
 *
 * `Met` and `Exceeded` each carry the `LatencyMeasurement` that produced
 * them — a budget verdict cannot exist without the measurement as
 * evidence. `Undetermined` carries a structural reason: either there was
 * no measurement at all, or the sample count was below what the
 * measurement procedure requires. An undetermined budget is reported as
 * undetermined, never as met.
 *
 * spec: native-gate-delivery — Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed
 * spec: native-gate-delivery — Property: budget-verdict-requires-a-measurement
 */
enum BudgetVerdict:
  case Met(measurement: LatencyMeasurement, budget: LatencyBudget)
  case Exceeded(measurement: LatencyMeasurement, budget: LatencyBudget)
  case Undetermined(reason: BudgetVerdict.UndeterminedReason)

object BudgetVerdict:

  /**
   * Why a budget verdict could not be determined — structural, not a
   * string. `InsufficientSamples` names both the observed and the required
   * sample count.
   */
  enum UndeterminedReason:
    case NoMeasurement
    case InsufficientSamples(observed: Int, required: Int)

  /**
   * Evaluates a recorded measurement against a latency budget.
   *
   * The measurement argument is mandatory — it is `Option` because the
   * absent case is a representable input whose verdict is `Undetermined`,
   * but no overload exists that computes a verdict without being handed
   * the measurement slot. An assumed budget is the defect this signature
   * makes unconstructible.
   *
   * Verdict rules:
   *  - `None` → `Undetermined(NoMeasurement)`
   *  - `Some(m)` with `m.sampleCount < budget.minSamples` →
   *    `Undetermined(InsufficientSamples(m.sampleCount, budget.minSamples))`
   *  - `Some(m)` with enough samples and `m.medianMillis < budget.medianMillis`
   *    → `Met(m, budget)`
   *  - `Some(m)` with enough samples and `m.medianMillis >= budget.medianMillis`
   *    → `Exceeded(m, budget)`
   */
  def evaluate(
    measurement: Option[LatencyMeasurement],
    budget: LatencyBudget
  ): BudgetVerdict =
    measurement match
      case None =>
        Undetermined(UndeterminedReason.NoMeasurement)
      case Some(m) if m.sampleCount < budget.minSamples =>
        Undetermined(
          UndeterminedReason.InsufficientSamples(
            observed = m.sampleCount,
            required = budget.minSamples
          )
        )
      case Some(m) if m.medianMillis < budget.medianMillis =>
        Met(m, budget)
      case Some(m) =>
        Exceeded(m, budget)

/**
 * A latency budget: the median threshold and the minimum sample count the
 * measurement procedure requires. Both travel together — a threshold
 * without its sample floor is half a budget.
 */
final case class LatencyBudget(medianMillis: Double, minSamples: Int)

object LatencyBudget:

  /**
   * The per-turn warm-start budget from native-packaging R-N1: warm p50
   * below 150 ms over a 100-run hyperfine sample on linux-x86_64.
   *
   * spec: native-packaging — Scenario: warm-start latency meets budget
   */
  val perTurn: LatencyBudget = LatencyBudget(medianMillis = 150.0, minSamples = 100)
