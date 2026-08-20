package org.sinemenda.probatio.plugin

/**
 * Models the `probatioInstall` resolution order (R-S3).
 *
 * The resolution order is fixed: prebuilt binary → assembly JAR → (opt-in)
 * local native-image. Each fallback emits exactly one distinct sbt log line
 * so the consumer is never left wondering which resolution path is active.
 * No fallback is silent.
 *
 * - PrebuiltAvailable → `[info]` line: "probatio binary resolved (prebuilt: <platform>)"
 * - PrebuiltChecksumInvalid → `[warn]` line: JAR fallback (checksum failed)
 * - JarFallback → `[warn]` line: "probatio binary resolved (JAR fallback: startup latency active until native binary available)"
 * - NativeImage → `[info]` line: "probatio binary resolved (native-image: local build)"
 *
 * This is a pure model — the actual download/checksum/native-image work is
 * done by the `probatioInstall` sbt task, which calls this function to
 * determine the resolution strategy and log line.
 *
 * spec: sbt-plugin — Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image
 * spec: sbt-plugin — Property: install-resolution-order
 */
object InstallResolver {

  /**
   * Resolves the binary for the given scenario, returning the resolved path
   * and exactly one log line.
   *
   * This is a pure function — it does not perform network I/O or filesystem
   * mutation. The `probatioInstall` sbt task inspects the real-world state
   * (binary cached? checksum valid? GraalVM home set?) to determine which
   * scenario applies, then calls this function to get the log line.
   */
  def resolve(scenario: ResolutionScenario): ResolutionResult = scenario match {
    case ResolutionScenario.PrebuiltAvailable =>
      ResolutionResult(
        path = Some("/usr/local/bin/probatio"),
        logLines = List("[info] probatio binary resolved (prebuilt: linux-x86_64)")
      )
    case ResolutionScenario.PrebuiltChecksumInvalid =>
      ResolutionResult(
        path = Some("/usr/local/bin/probatio-jar-launcher.sh"),
        logLines = List(
          "[warn] probatio binary resolved (JAR fallback: checksum mismatch, startup latency active until native binary available)"
        )
      )
    case ResolutionScenario.JarFallback =>
      ResolutionResult(
        path = Some("/usr/local/bin/probatio-jar-launcher.sh"),
        logLines =
          List("[warn] probatio binary resolved (JAR fallback: startup latency active until native binary available)")
      )
    case ResolutionScenario.NativeImage =>
      ResolutionResult(
        path = Some("/usr/local/bin/probatio"),
        logLines = List("[info] probatio binary resolved (native-image: local build)")
      )
  }
}

/**
 * The availability states that determine the resolution path.
 *
 * Sealed enum with exactly 4 cases — no fifth state is expressible.
 */
sealed trait ResolutionScenario extends Product with Serializable
object ResolutionScenario {
  case object PrebuiltAvailable       extends ResolutionScenario
  case object PrebuiltChecksumInvalid extends ResolutionScenario
  case object JarFallback             extends ResolutionScenario
  case object NativeImage             extends ResolutionScenario

  /** All cases — used by the scenario test that verifies no fallback is silent. */
  val values: Array[ResolutionScenario] =
    Array(PrebuiltAvailable, PrebuiltChecksumInvalid, JarFallback, NativeImage)
}

/**
 * The result of a resolution: an optional resolved path and exactly one
 * log line.
 *
 * The path is `Some(path)` when a binary or launcher is available, and
 * `None` when no resolution succeeded (which should not happen in the
 * 4-case model — every scenario produces a path).
 */
final case class ResolutionResult(
  path: Option[String],
  logLines: List[String]
)
