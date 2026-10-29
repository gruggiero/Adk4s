/** Centralized version constants for all dependencies and plugins.
 *
 *  Single source of truth — reference these from `Dependencies.scala`,
 *  `build.sbt`, and `project/plugins.sbt`.
 */
object Versions {

  // --- Language / runtime ---
  val Scala: String       = "3.8.4"
  val ScalaVerified: String = "3.7.2" // Stainless frontend pin (Ring 6)
  val Scala2_12: String   = "2.12.20" // sbt 1.x plugin Scala version

  // --- Core libraries ---
  val Llm4s: String          = "0.3.4"
  val CatsEffect: String     = "3.7.0"
  val Fs2: String            = "3.13.0"
  val TypesafeConfig: String = "1.4.9"
  val Workflows4s: String    = "0.6.2"
  val Smithy4s: String       = "0.18.55"
  val Upickle: String        = "4.4.3" // MUST match llm4s 0.3.4 transitive
  val Iron: String           = "3.3.2"
  val Logback: String        = "1.5.34"

  // --- probatio tooling (R-X3 allowed-dependency set) ---
  // os-lib + mainargs are com.lihaoyi libs used by the probatio CLI port.
  // Both are GraalVM-native-image-safe (no reflection). scalameta is
  // spike-gated (V1) and added when the concept-scanner port is scheduled.
  val OsLib: String          = "0.11.8"   // released 2026-01-26
  val Mainargs: String       = "0.7.8"    // released 2025-12-26

  // --- Testing ---
  val Munit: String            = "1.3.3"
  val MunitCatsEffect: String  = "2.2.0"
  val Hedgehog: String         = "0.13.1"

  // --- SBT plugins ---
  val SbtScalafix: String      = "0.14.7"
  val SbtScalafmt: String      = "2.6.1"
  val SbtScoverage: String     = "2.4.4"
  val SbtAssembly: String      = "2.3.1"
  val SbtWartremover: String   = "3.5.8"
  val SbtStryker4s: String     = "1.1.1"
  val SbtNativeImage: String   = "0.4.0"
}
