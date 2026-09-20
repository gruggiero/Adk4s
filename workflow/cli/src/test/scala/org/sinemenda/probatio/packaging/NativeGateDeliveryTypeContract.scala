package org.sinemenda.probatio.packaging

import org.sinemenda.probatio.cli.ProbatioCliSuite

/**
 * Typed contract for spec: native-gate-delivery (Step 1)
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references and asserts the
 * compile-negative obligations. Zero runtime cost; any later signature
 * drift breaks `probatio-cli/Test/compile`.
 *
 * spec: native-gate-delivery — Step 1: typed contract
 * spec: native-gate-delivery — Concepts Introduced: LatencyMeasurement
 * spec: native-gate-delivery — Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed
 */
final class NativeGateDeliveryTypeContract extends ProbatioCliSuite:

  // ── Signature pins (eta-expanded against the real implementation) ───────
  // These pins make "signatures stay as approved" compiler-checked.

  // BudgetVerdict.evaluate: (Option[LatencyMeasurement], LatencyBudget) => BudgetVerdict
  val evaluateSig: (Option[LatencyMeasurement], LatencyBudget) => BudgetVerdict =
    BudgetVerdict.evaluate

  // ── LatencyMeasurement — Concepts Introduced ────────────────────────────
  // spec: native-gate-delivery — Concepts Introduced: LatencyMeasurement
  test("LatencyMeasurement carries sample count, median, max, and artifact kind"):
    val m: LatencyMeasurement = LatencyMeasurement(
      sampleCount  = 100,
      medianMillis = 12.5,
      maxMillis    = 40.0,
      artifactKind = LatencyMeasurement.ArtifactKind.NativeImage
    )
    val n: Int                              = m.sampleCount
    val med: Double                         = m.medianMillis
    val mx: Double                          = m.maxMillis
    val k: LatencyMeasurement.ArtifactKind  = m.artifactKind
    assertEquals(n, 100)
    assertEquals(med, 12.5)
    assertEquals(mx, 40.0)
    assertEquals(k, LatencyMeasurement.ArtifactKind.NativeImage)

  test("LatencyMeasurement.ArtifactKind names the launcher distinctly from the native image"):
    val launcher: LatencyMeasurement.ArtifactKind = LatencyMeasurement.ArtifactKind.JarLauncher
    val native: LatencyMeasurement.ArtifactKind   = LatencyMeasurement.ArtifactKind.NativeImage
    assertNotEquals(launcher, native)

  // ── LatencyBudget ───────────────────────────────────────────────────────
  test("LatencyBudget carries the median threshold and the minimum sample count"):
    val b: LatencyBudget = LatencyBudget(medianMillis = 150.0, minSamples = 100)
    val threshold: Double = b.medianMillis
    val samples: Int      = b.minSamples
    assertEquals(threshold, 150.0)
    assertEquals(samples, 100)
    assertEquals(LatencyBudget.perTurn, b)

  // ── BudgetVerdict — the verdict that cannot exist without a measurement ─
  test("BudgetVerdict: Met and Exceeded carry the measurement; Undetermined carries a structural reason"):
    val m: LatencyMeasurement =
      LatencyMeasurement(100, 12.5, 40.0, LatencyMeasurement.ArtifactKind.NativeImage)
    val b: LatencyBudget = LatencyBudget.perTurn

    val met: BudgetVerdict          = BudgetVerdict.Met(m, b)
    val exceeded: BudgetVerdict     = BudgetVerdict.Exceeded(m, b)
    val undetermined: BudgetVerdict =
      BudgetVerdict.Undetermined(BudgetVerdict.UndeterminedReason.NoMeasurement)
    val insufficient: BudgetVerdict = BudgetVerdict.Undetermined(
      BudgetVerdict.UndeterminedReason.InsufficientSamples(3, 100)
    )

    def name(v: BudgetVerdict): String = v match
      case BudgetVerdict.Met(_, _)        => "met"
      case BudgetVerdict.Exceeded(_, _)   => "exceeded"
      case BudgetVerdict.Undetermined(_)  => "undetermined"

    assertEquals(name(met), "met")
    assertEquals(name(exceeded), "exceeded")
    assertEquals(name(undetermined), "undetermined")
    assertEquals(name(insufficient), "undetermined")

  // ── Compile-negative: a budget cannot be reported as met without a
  //    recorded measurement ────────────────────────────────────────────────
  // spec: native-gate-delivery — Compile-Negative: A budget cannot be reported as met without a recorded measurement
  test("BudgetVerdict.evaluate cannot be called without a measurement slot"):
    val err: String = compileErrors("BudgetVerdict.evaluate(LatencyBudget.perTurn)")
    assert(err.nonEmpty, "evaluate(budget) should not compile — the measurement slot is mandatory")

  test("BudgetVerdict.evaluate cannot take an assumed median in place of a measurement"):
    val err: String = compileErrors("BudgetVerdict.evaluate(42.0, LatencyBudget.perTurn)")
    assert(err.nonEmpty, "evaluate(raw-number, budget) should not compile — a bare median is not a measurement")

  test("BudgetVerdict.Met cannot be constructed without a measurement"):
    val err: String = compileErrors("BudgetVerdict.Met(LatencyBudget.perTurn)")
    assert(err.nonEmpty, "Met(budget) should not compile — Met requires the measurement that produced it")
