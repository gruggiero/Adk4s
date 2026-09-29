package org.sinemenda.probatio.packaging

/**
 * A recorded start-up latency measurement (R-N1).
 *
 * The delivery SHALL measure the per-turn tool's start-up latency on the
 * target platform and record the measurement before delivering against a
 * latency budget; a budget is never reported as met without a recorded
 * measurement. The measurement carries the sample count, the observed
 * median, the observed maximum, and the artifact kind measured — an
 * undersized measurement is a real, recordable observation whose
 * consequence is `BudgetVerdict.Undetermined`, not a value to reject at
 * construction.
 *
 * spec: native-gate-delivery — Concepts Introduced: LatencyMeasurement
 * spec: native-gate-delivery — Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed
 * spec: native-packaging — Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget
 */
final case class LatencyMeasurement(
  sampleCount: Int,
  medianMillis: Double,
  maxMillis: Double,
  artifactKind: LatencyMeasurement.ArtifactKind
)

object LatencyMeasurement:

  /**
   * The artifact kind a measurement was taken on. A `JarLauncher`
   * measurement is a real measurement — it is recorded with its own kind,
   * never silently relabelled as a native-image measurement.
   */
  enum ArtifactKind:
    case NativeImage
    case JarLauncher
