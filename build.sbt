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
// The following warts are permanently excluded:
//   - Any: triggered by s"..." string interpolation (known Scala 3 WartRemover
//     false positive — StringContext.s takes Any*); not fixable without abandoning
//     string interpolation entirely (919 sites)
//   - DefaultArguments: default args are a valid Scala API design feature used in
//     config case classes (47 sites across 15 files); removing them would require
//     100+ call-site changes for no behavioral benefit
ThisBuild / wartremoverErrors ++= Warts.unsafe.filterNot(w =>
  w == Wart.TripleQuestionMark ||
  w == Wart.Any ||
  w == Wart.DefaultArguments
)
ThisBuild / testFrameworks += new TestFramework("munit.Framework")

// --- Shared Scala 3 compiler options ---
lazy val scala3Options: Seq[String] = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xkind-projector:underscores",
  // Ring 0 exhaustiveness escalation (verified-scala3 schema): an
  // inexhaustive match over a sealed type must fail compilation, not warn —
  // the schema's no-catch-all rules are unenforceable otherwise.
  "-Wconf:name=PatternMatchExhaustivity:e",
  "-Wconf:name=MatchCaseUnreachable:e",
  // "-source:future"
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

// ── adk4s-memory-api — durable, recallable agent memory capability ─────────
// Effect-polymorphic interface (AgentMemory[F]) + value types + in-process
// test double (InMemoryAgentMemory) + Retriever bridge (MemoryRetriever).
// Depends on adk4s-core for Retriever/Document/RetrieverConfig.
lazy val `adk4s-memory-api` = (project in file("adk4s-memory-api"))
  .dependsOn(`adk4s-core`)
  .settings(
    name := "adk4s-memory-api",
    libraryDependencies ++= Seq(
      catsEffect,
      fs2Core
    ) ++ testDeps,
    scalacOptions ++= scala3Options
  )

// ── adk4s-memory-testkit — downstream-consumable behavioral laws ───────────
// Publishes AgentMemoryLaws in main scope so downstream backends (e.g.
// GraphStore) can depend on it as a regular libraryDependencies line.
// munit is in MAIN scope (not Test) because AgentMemoryLaws is a test-contract
// API, not a test-only utility.
lazy val `adk4s-memory-testkit` = (project in file("adk4s-memory-testkit"))
  .dependsOn(`adk4s-memory-api`)
  .settings(
    name := "adk4s-memory-testkit",
    libraryDependencies ++= Seq(
      catsEffect,
      munitMain,
      munitCatsEffect,
      hedgehogMunit
    ),
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
        w == Wart.Any ||
        w == Wart.DefaultArguments
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
