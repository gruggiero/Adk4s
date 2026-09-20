package org.sinemenda.probatio.packaging

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.cli.ProbatioCliSuite

/**
 * Test oracle for spec: native-gate-delivery — the measurement half.
 *
 * Covers the latency-budget requirements: a budget is reported met only
 * against a recorded, adequately-sampled measurement; an absent or
 * undersized measurement is reported undetermined; and a measured median
 * above budget blocks the delivery.
 *
 * The resolution, shim, task, and release halves of this spec live in
 * `NativePackagingSpec` (resolution + release properties, extended) and in
 * the sbt-probatio suites (`ShimGeneratorSpec`, `HookCutoverShimSpec`,
 * `ExitCodeMappingSpec`, `PluginSourceLintSpec`), which own the plugin-side
 * artifacts the proof-obligations table names.
 *
 * spec: native-gate-delivery — Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed
 * spec: native-gate-delivery — Property: budget-verdict-requires-a-measurement
 * spec: native-gate-delivery — Compile-Negative Obligations
 */
final class NativeGateDeliverySpec extends ProbatioCliSuite:

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  // ──────────────────────────────────────────────────────────────────────
  // Scenarios — Requirement: The per-turn tool's start-up latency is
  // measured and recorded, never assumed
  // ──────────────────────────────────────────────────────────────────────

  // spec: native-gate-delivery — Scenario: Happy path — a measured median within budget is recorded and the delivery proceeds
  test("a measured median within budget is Met and carries the measurement"):
    val m: LatencyMeasurement = LatencyMeasurement(
      sampleCount  = 100,
      medianMillis = 42.0,
      maxMillis    = 90.0,
      artifactKind = LatencyMeasurement.ArtifactKind.NativeImage
    )
    BudgetVerdict.evaluate(Some(m), LatencyBudget.perTurn) match
      case BudgetVerdict.Met(recorded, budget) =>
        assertEquals(recorded, m, "the verdict must record the measurement it is based on")
        assertEquals(budget, LatencyBudget.perTurn)
      case other =>
        fail(s"expected Met, got $other")

  // spec: native-gate-delivery — Scenario: Adversarial — an unmeasured tool is not reported as meeting the budget
  test("an unmeasured tool reports the budget undetermined, not met"):
    BudgetVerdict.evaluate(None, LatencyBudget.perTurn) match
      case BudgetVerdict.Undetermined(BudgetVerdict.UndeterminedReason.NoMeasurement) =>
        ()
      case BudgetVerdict.Met(_, _) =>
        fail("an unmeasured tool must never report Met — an assumed budget is the defect")
      case other =>
        fail(s"expected Undetermined(NoMeasurement), got $other")

  // spec: native-gate-delivery — Scenario: Adversarial — a measured median above budget blocks the delivery
  test("a measured median above budget blocks the delivery and records the measurement as the reason"):
    val m: LatencyMeasurement = LatencyMeasurement(
      sampleCount  = 120,
      medianMillis = 250.0,
      maxMillis    = 400.0,
      artifactKind = LatencyMeasurement.ArtifactKind.NativeImage
    )
    BudgetVerdict.evaluate(Some(m), LatencyBudget.perTurn) match
      case BudgetVerdict.Exceeded(recorded, _) =>
        assertEquals(recorded, m, "the block must record the measurement as the reason")
      case BudgetVerdict.Met(_, _) =>
        fail("an over-budget median must never report Met")
      case other =>
        fail(s"expected Exceeded, got $other")

  // spec: native-gate-delivery — Scenario: Adversarial — a measured median above budget blocks the delivery
  // The budget is 'warm p50 BELOW 150 ms' — a median equal to the budget
  // is not below it, so the boundary value itself is Exceeded.
  test("a median equal to the budget is Exceeded — the budget is strict"):
    val m: LatencyMeasurement = LatencyMeasurement(
      sampleCount  = 100,
      medianMillis = LatencyBudget.perTurn.medianMillis,
      maxMillis    = 200.0,
      artifactKind = LatencyMeasurement.ArtifactKind.NativeImage
    )
    BudgetVerdict.evaluate(Some(m), LatencyBudget.perTurn) match
      case BudgetVerdict.Exceeded(_, _) => ()
      case other =>
        fail(s"a median equal to the budget is not 'below' it — expected Exceeded, got $other")

  // spec: native-gate-delivery — Scenario: Edge case — a measurement with too few samples is not a median
  test("a measurement with too few samples is undetermined, naming the sample count"):
    val m: LatencyMeasurement = LatencyMeasurement(
      sampleCount  = 12,
      medianMillis = 42.0,
      maxMillis    = 60.0,
      artifactKind = LatencyMeasurement.ArtifactKind.NativeImage
    )
    BudgetVerdict.evaluate(Some(m), LatencyBudget.perTurn) match
      case BudgetVerdict.Undetermined(
            BudgetVerdict.UndeterminedReason.InsufficientSamples(observed, required)
          ) =>
        assertEquals(observed, 12, "the undetermined verdict must name the observed sample count")
        assertEquals(required, LatencyBudget.perTurn.minSamples)
      case BudgetVerdict.Met(_, _) =>
        fail("an undersized measurement must never report Met")
      case other =>
        fail(s"expected Undetermined(InsufficientSamples), got $other")

  // ──────────────────────────────────────────────────────────────────────
  // Property: budget-verdict-requires-a-measurement
  // spec: native-gate-delivery — Property: budget-verdict-requires-a-measurement
  //
  // A budget is reported met only when a measurement with at least the
  // minimum sample count exists and its median is below the budget.
  // ──────────────────────────────────────────────────────────────────────

  /** spec: native-gate-delivery — Generator: genLatencyMeasurement. */
  private def genLatencyMeasurement: Gen[Option[LatencyMeasurement]] =
    val measurement: Gen[LatencyMeasurement] =
      for
        sampleCount <- Gen.int(Range.linear(0, 200))
        median      <- Gen.frequency1(
                         1 -> Gen.int(Range.linear(0, 149)).map((n: Int) => n.toDouble),
                         1 -> Gen.int(Range.linear(150, 400)).map((n: Int) => n.toDouble)
                       )
        maxExtra    <- Gen.int(Range.linear(0, 200)).map((n: Int) => n.toDouble)
        kind        <- Gen.element1(
                         LatencyMeasurement.ArtifactKind.NativeImage,
                         LatencyMeasurement.ArtifactKind.JarLauncher
                       )
      yield LatencyMeasurement(sampleCount, median, median + maxExtra, kind)
    for
      present <- Gen.frequency1(13 -> Gen.constant(true), 7 -> Gen.constant(false))
      m       <- measurement
    yield if present then Some(m) else None

  property("budget-verdict-requires-a-measurement", coverConfig):
    for
      mOpt <- genLatencyMeasurement.forAll
                .cover(25, "absent", (o: Option[LatencyMeasurement]) => o.isEmpty)
                .cover(
                  20,
                  "too-few-samples",
                  (o: Option[LatencyMeasurement]) =>
                    o.exists(_.sampleCount < LatencyBudget.perTurn.minSamples)
                )
                .cover(
                  20,
                  "median-under",
                  (o: Option[LatencyMeasurement]) =>
                    o.exists(_.medianMillis < LatencyBudget.perTurn.medianMillis)
                )
                .cover(
                  20,
                  "median-over",
                  (o: Option[LatencyMeasurement]) =>
                    o.exists(_.medianMillis >= LatencyBudget.perTurn.medianMillis)
                )
    yield
      val verdict: BudgetVerdict = BudgetVerdict.evaluate(mOpt, LatencyBudget.perTurn)
      val reportedMet: Boolean = verdict match
        case BudgetVerdict.Met(_, _) => true
        case _                     => false
      val shouldBeMet: Boolean = mOpt.exists((m: LatencyMeasurement) =>
        m.sampleCount >= LatencyBudget.perTurn.minSamples &&
          m.medianMillis < LatencyBudget.perTurn.medianMillis
      )
      Result
        .assert(reportedMet == shouldBeMet)
        .log(s"verdict $verdict disagrees with the budget rule for measurement $mOpt")

  // ──────────────────────────────────────────────────────────────────────
  // Compile-Negative: a budget verdict function that does not take a
  // measurement — an assumed budget is the defect
  // spec: native-gate-delivery — Compile-Negative Obligations
  // ──────────────────────────────────────────────────────────────────────

  test("compile-negative: the verdict function cannot be called without a measurement"):
    val err: String = compileErrors("BudgetVerdict.evaluate(LatencyBudget.perTurn)")
    assert(
      err.nonEmpty,
      "evaluate(budget) should not compile — the verdict cannot be computed without evidence"
    )

  test("compile-negative: a Met verdict cannot be constructed without a measurement"):
    val err: String = compileErrors("BudgetVerdict.Met(LatencyBudget.perTurn)")
    assert(
      err.nonEmpty,
      "Met(budget) should not compile — a Met verdict carries the measurement that produced it"
    )
