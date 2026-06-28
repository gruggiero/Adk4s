/** Centralized version constants for all dependencies and plugins.
 *
 *  Single source of truth — reference these from `Dependencies.scala`,
 *  `build.sbt`, and `project/plugins.sbt`.
 */
object Versions {

  // --- Language / runtime ---
  val Scala: String       = "3.8.4"
  val ScalaVerified: String = "3.7.2" // Stainless frontend pin (Ring 6)

  // --- Core libraries ---
  val Llm4s: String          = "0.3.4"
  val CatsEffect: String     = "3.7.0"
  val Fs2: String            = "3.13.0"
  val TypesafeConfig: String = "1.4.9"
  val Workflows4s: String    = "0.6.2"
  val Smithy4s: String       = "0.18.55"
  val Logback: String        = "1.5.34"

  // --- Testing ---
  val Munit: String            = "1.3.3"
  val MunitCatsEffect: String  = "2.2.0"
  val Hedgehog: String         = "0.13.1"

  // --- SBT plugins ---
  val SbtScalafix: String      = "0.14.7"
  val SbtScalafmt: String      = "2.6.1"
  val SbtScoverage: String     = "2.4.4"
  val SbtAssembly: String      = "2.3.1"
  val ScalafixRules: String    = "0.14.7"
  val SbtWartremover: String   = "3.5.8"
  val SbtStryker4s: String     = "0.21.0"
}
