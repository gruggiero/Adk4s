import Dependencies._
import wartremover.WartRemover

ThisBuild / scalaVersion := Versions.Scala
ThisBuild / organization := "org.adk4s"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions += "-Wconf:src=target/.*:s"

// --- scalafix ---
ThisBuild / scalafixDependencies += scalafixRules
ThisBuild / scalafixOnCompile := false  // Disabled for now
Test / scalafix / unmanagedSources := Seq.empty

// --- semanticdb (required for scalafix semantic rules) ---
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// --- WartRemover (Ring 1) ---
// Warts.unsafe minus TripleQuestionMark (??? allowed for stubs).
// The following warts are temporarily excluded — the codebase predates WartRemover
// and needs targeted refactoring to pass them. Re-enable each as code is cleaned up:
//   - Any: triggered by s"..." string interpolation (known Scala 3 WartRemover
//     false positive); fix by switching to + concatenation per accordant4s pattern
//   - DefaultArguments: many case classes use default args; fix by making defaults
//     explicit or using factory methods
//   - IterableOps: .init/.last used in a few places; fix by using dropRight(1)/lastOption
//   - AsInstanceOf: Tool.scala, ToolInfer.scala, ToolSchema.scala use asInstanceOf
//     for runtime type dispatch on erased types; fix with type-safe approach
//   - Throw: Tool.scala uses throw for error cases; fix by returning F[Either[...]]
//   - Var: JsonFixMiddleware.scala uses var for mutable parsing state; fix with
//     recursive or fold-based approach
//   - OptionPartial: .get on Option in test-models; fix with .getOrElse or pattern match
ThisBuild / wartremoverErrors ++= Warts.unsafe.filterNot(w =>
  w == Wart.TripleQuestionMark ||
  w == Wart.Any ||
  w == Wart.DefaultArguments ||
  w == Wart.IterableOps ||
  w == Wart.AsInstanceOf ||
  w == Wart.Throw ||
  w == Wart.Var ||
  w == Wart.OptionPartial ||
  w == Wart.StringPlusAny
)
ThisBuild / testFrameworks += new TestFramework("munit.Framework")

// --- Shared Scala 3 compiler options ---
lazy val scala3Options: Seq[String] = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xkind-projector:underscores",
  "-source:future"
)

// ---------------------------------------------------------------------------
// Modules
// ---------------------------------------------------------------------------

lazy val `structured-llm` = (project in file("structured-llm"))
  .settings(
    name := "structured-llm",
    libraryDependencies ++= Seq(
      llm4s,
      catsEffect,
      fs2Core,
      typesafeConfig,
      workflows4sCore
    ) ++ smithy4s ++ testDeps,
    scalacOptions ++= scala3Options
  )

lazy val `structured-llm-test-models` = (project in file("structured-llm-test-models"))
  .dependsOn(`structured-llm` % "compile->compile")
  .settings(
    name := "structured-llm-test-models",
    libraryDependencies ++= Seq(typesafeConfig) ++ testDeps,
    // Disable scalafix for this test/example module
    Compile / scalafix / unmanagedSources := Seq.empty
  )
  .enablePlugins(Smithy4sCodegenPlugin)

lazy val `adk4s-core` = (project in file("adk4s-core"))
  .dependsOn(`structured-llm`)
  .settings(
    name := "adk4s-core",
    libraryDependencies ++= Seq(
      llm4s,
      catsEffect
    ) ++ fs2 ++ testDeps,
    scalacOptions ++= scala3Options
  )

lazy val `adk4s-orchestration` = (project in file("adk4s-orchestration"))
  .dependsOn(
    `adk4s-core`,
    `structured-llm`
  )
  .settings(
    name := "adk4s-orchestration",
    libraryDependencies ++= Seq(
      catsEffect,
      workflows4sCore
    ) ++ fs2 ++ testDeps,
    scalacOptions ++= scala3Options
  )

lazy val `adk4s-examples` = (project in file("adk4s-examples"))
  .dependsOn(
    `adk4s-core`,
    `adk4s-orchestration`,
    `structured-llm`,
    `structured-llm-test-models`
  )
  .settings(
    name := "adk4s-examples",
    // Examples are application-edge code — same relaxed wart set as ThisBuild.
    wartremoverErrors := Warts.unsafe
      .filterNot(w =>
        w == Wart.TripleQuestionMark ||
        w == Wart.AsInstanceOf ||
        w == Wart.Any ||
        w == Wart.DefaultArguments ||
        w == Wart.IterableOps ||
        w == Wart.Throw ||
        w == Wart.Var ||
        w == Wart.OptionPartial ||
        w == Wart.StringPlusAny
      ),
    libraryDependencies ++= Seq(
      llm4s,
      catsEffect,
      workflows4sCore,
      workflows4sBpmn,
      logback
    ) ++ fs2 ++ testDeps,
    scalacOptions ++= scala3Options
  )

// ── Ring 6 — Stainless formal verification ────────────────────────────────
// A dedicated LEAF module pinned to Scala 3.7.2 (the version Stainless's bundled
// frontend supports) with strict flags relaxed, so the rest of the build can
// stay on 3.8.4. It depends on nothing project-local (TASTy is only
// backward-compatible). Contains pure-model mirrors of algorithms to verify.
// Not aggregated by root, so normal builds skip Stainless.
// Run Ring 6 with: sbt -J-Xmx6g ring6
lazy val `verified` = (project in file("verified"))
  .enablePlugins(StainlessPlugin)
  .settings(
    name         := "adk4s-verified",
    scalaVersion := Versions.ScalaVerified,
    // Stainless injects library sources that emit warnings we don't own.
    scalacOptions := Seq(
      "-deprecation",
      "-feature",
      "-Wconf:src=.*stainless-library.*:silent"
    ),
    wartremoverErrors := Seq.empty,
    semanticdbEnabled := false,
    // Default OFF: verified/compile is a plain, fast compile of the model.
    // Ring 6 turns verification ON explicitly via the `ring6` alias below.
    stainlessEnabled := false,
    publish / skip   := true
  )

// Ring 6 — run Stainless verification (needs a big heap; z3 single-threaded).
addCommandAlias("ring6", "; set verified / stainlessEnabled := true ; verified / compile")
