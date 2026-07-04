import sbt._

/** Centralized dependency definitions.
 *
 *  Each `val` is a ready-to-use `ModuleID` (or `Seq[ModuleID]`).
 *  Reference them in `build.sbt` via `libraryDependencies ++= Dependencies.catsEffect` etc.
 *
 *  Test-scoped dependencies are pre-configured with `% Test`.
 */
object Dependencies {

  // --- Core libraries ---

  val llm4s: ModuleID =
    "org.llm4s" %% "core" % Versions.Llm4s

  val catsEffect: ModuleID =
    "org.typelevel" %% "cats-effect" % Versions.CatsEffect

  val fs2: Seq[ModuleID] = Seq(
    "co.fs2" %% "fs2-core" % Versions.Fs2,
    "co.fs2" %% "fs2-io" % Versions.Fs2
  )

  val fs2Core: ModuleID =
    "co.fs2" %% "fs2-core" % Versions.Fs2

  val typesafeConfig: ModuleID =
    "com.typesafe" % "config" % Versions.TypesafeConfig

  val workflows4sCore: ModuleID =
    "org.business4s" %% "workflows4s-core" % Versions.Workflows4s

  val workflows4sBpmn: ModuleID =
    "org.business4s" %% "workflows4s-bpmn" % Versions.Workflows4s

  val smithy4s: Seq[ModuleID] = Seq(
    "com.disneystreaming.smithy4s" %% "smithy4s-core" % Versions.Smithy4s,
    "com.disneystreaming.smithy4s" %% "smithy4s-json" % Versions.Smithy4s
  )

  val logback: ModuleID =
    "ch.qos.logback" % "logback-classic" % Versions.Logback

  // --- Property testing: Hedgehog ---
  // Hedgehog provides integrated shrinking with no Arbitrary typeclass.
  // hedgehog-core carries the Gen/Property API.
  // hedgehog-munit provides HedgehogSuite (runs through munit's framework) and
  // pulls in hedgehog-core + hedgehog-runner transitively at Test scope.
  val hedgehogCore: ModuleID =
    "qa.hedgehog" %% "hedgehog-core" % Versions.Hedgehog

  val hedgehogMunit: ModuleID =
    "qa.hedgehog" %% "hedgehog-munit" % Versions.Hedgehog % Test

  // --- Testing ---

  val munit: ModuleID =
    "org.scalameta" %% "munit" % Versions.Munit % Test

  /** munit in MAIN scope — used by adk4s-memory-testkit, which publishes
    * AgentMemoryLaws as a downstream-consumable main-scoped API. */
  val munitMain: ModuleID =
    "org.scalameta" %% "munit" % Versions.Munit

  val munitCatsEffect: ModuleID =
    "org.typelevel" %% "munit-cats-effect" % Versions.MunitCatsEffect % Test

  /** Common test dependencies shared across all modules.
   *  Includes munit + munit-cats-effect + hedgehog-munit. */
  val testDeps: Seq[ModuleID] = Seq(munit, munitCatsEffect, hedgehogMunit)

  // --- Plugin-level dependencies (used in plugins.sbt) ---

  val sbtScalafix: ModuleID =
    "ch.epfl.scala" % "sbt-scalafix" % Versions.SbtScalafix

  val sbtScalafmt: ModuleID =
    "org.scalameta" % "sbt-scalafmt" % Versions.SbtScalafmt

  val sbtScoverage: ModuleID =
    "org.scoverage" % "sbt-scoverage" % Versions.SbtScoverage

  val sbtAssembly: ModuleID =
    "com.eed3si9n" % "sbt-assembly" % Versions.SbtAssembly

  val smithy4sCodegen: ModuleID =
    "com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % Versions.Smithy4s

  val scalafixRules: ModuleID =
    "ch.epfl.scala" %% "scalafix-rules" % Versions.ScalafixRules

  val sbtWartremover: ModuleID =
    "org.wartremover" % "sbt-wartremover" % Versions.SbtWartremover

  val sbtStryker4s: ModuleID =
    "io.stryker-mutator" % "sbt-stryker4s" % Versions.SbtStryker4s
}
