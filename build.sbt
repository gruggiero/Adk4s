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
    ) ++ fs2 ++ upickle ++ testDeps,
    scalacOptions ++= scala3Options
  )

// ── adk4s-harness-api — agent middleware + harness state (Phase 0) ──────────
// AgentMiddleware[F[_]] (four-hook trait), HarnessState (typed heterogeneous
// map), StateCell[A] (visibility + merge + ReadWriter codec), MiddlewareStack[F]
// (monoid with validated construction), ModelRequest/ModelResponse, ToolStep,
// PromptSection/SystemPrompt, StackError, StateDecodeError.
// Depends on adk4s-core for InvokableTool, ToolInput/ToolOutput, JsonValue/
// JsonValueCodec, AdkError. Depends on `verified` at Test scope for the Ring 6
// bridge (TASTy is backward compatible: 3.8.4 reads 3.7.2).
// MUST NOT depend on workflows4s, llm4s LLM client, adk4s-orchestration,
// fs2-io, or logback (Ring 2 purity rule).
lazy val `adk4s-harness-api` = (project in file("adk4s-harness-api"))
  .dependsOn(
    `adk4s-core`,
    `verified` % Test
  )
  .settings(
    name := "adk4s-harness-api",
    libraryDependencies ++= Seq(catsEffect) ++ upickle ++ testDeps,
    scalacOptions ++= scala3Options
  )

// ── adk4s-harness-testkit — downstream-consumable middleware laws ───────────
// Publishes `AgentMiddlewareLaws` (L0–L10), `SemilatticeLaws` (L11),
// `DeterministicChatModel` (test double), and Hedgehog `Generators` in MAIN
// scope so a downstream middleware author can add
// `libraryDependencies += "org.adk4s" %% "adk4s-harness-testkit" % version`
// and import the laws directly — the `adk4s-memory-testkit` precedent.
// munit + hedgehog-munit are in MAIN scope (not % Test) because the laws are
// a downstream-consumable main API. Depends on `adk4s-harness-api` for
// `AgentMiddleware`/`MiddlewareStack`/`HarnessState`/`StateCell`/`ModelStep`.
// Depends on `verified` at Test scope for the Ring 6 bridge
// (`SemilatticeModelBridgeSpec`; TASTy backward compatible: 3.8.4 reads 3.7.2).
// MUST NOT depend on workflows4s, llm4s LLM client, adk4s-orchestration,
// fs2-io, or logback (Ring 2 purity rule — same boundary as harness-api).
lazy val `adk4s-harness-testkit` = (project in file("adk4s-harness-testkit"))
  .dependsOn(
    `adk4s-harness-api`,
    `verified` % Test
  )
  .settings(
    name := "adk4s-harness-testkit",
    libraryDependencies ++= Seq(
      catsEffect,
      munitMain,
      munitCatsEffect,
      hedgehogMunitMain,
      catsEffectTestkitMain
    ) ++ testDeps :+ catsEffectTestkit,
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

// ── adk4s-optimize — optimizable predictor surface (DSPy port Phase 0) ─────
// Erased `Optimizable[P]` typeclass with `Mirror`-based derivation, pure
// `PredictorState`/`PredictorPath`/`Demo` data types, `OptimizeError` ADT,
// `Predict0[F, I, O]` placeholder, and `OptimizerLaws` testkit (main scope).
// Depends on structured-llm for the placeholder predictor's
// `StructuredLLM.completeTemplate` wrapper. Depends on `verified` at Test
// scope for the Ring 6 bridge (TASTy is backward compatible: 3.8.4 reads
// 3.7.2). MUST NOT depend on adk4s-core, adk4s-orchestration, workflows4s,
// or the llm4s LLM client directly (Ring 2 purity rule).
lazy val `adk4s-optimize` = (project in file("adk4s-optimize"))
  .dependsOn(
    `structured-llm`,
    `verified` % Test
  )
  .settings(
    name := "adk4s-optimize",
    libraryDependencies ++= Seq(
      catsEffect,
      fs2Core,
      munitMain,
      munitCatsEffect,
      hedgehogMunit
    ) ++ testDeps,
    scalacOptions ++= scala3Options
  )

// ── adk4s-eval — eval harness (DSPy port Phase 1) ──────────────────────────
// Parallel evaluation harness: run a program over a labeled dataset, score
// each result with a Metric, aggregate into a mean score with per-example
// rows. Includes Example/Score/Metric/Trace data types, Evaluate harness
// with failure-score substitution + maxErrors cancellation, EvaluationResult
// with JSON/CSV export, Dataset JSONL reader, built-in string metrics, and
// LLM judges (SemanticF1, CompleteAndGrounded).
// Depends on structured-llm for judges (StructuredLLM, Schema, Prompt,
// Constraint). MUST NOT depend on adk4s-core, adk4s-orchestration,
// workflows4s, llm4s LLM client, or adk4s-optimize (Ring 2 purity rule).
lazy val `adk4s-eval` = (project in file("adk4s-eval"))
  .dependsOn(`structured-llm`)
  .settings(
    name := "adk4s-eval",
    libraryDependencies ++= Seq(
      catsEffect,
      fs2Core
    ) ++ testDeps :+ catsEffectTestkit,
    scalacOptions ++= scala3Options
  )

lazy val `adk4s-orchestration` = (project in file("adk4s-orchestration"))
  .dependsOn(
    `adk4s-core`,
    `structured-llm`,
    `adk4s-memory-api`,
    `adk4s-harness-api`,
    `adk4s-harness-testkit` % Test
  )
  .settings(
    name := "adk4s-orchestration",
    libraryDependencies ++= Seq(
      catsEffect,
      workflows4sCore
    ) ++ fs2 ++ testDeps :+ catsEffectTestkit,
    scalacOptions ++= scala3Options
  )

lazy val `adk4s-examples` = (project in file("adk4s-examples"))
  .dependsOn(
    `adk4s-core`,
    `adk4s-orchestration`,
    `structured-llm`,
    `structured-llm-test-models`,
    `adk4s-eval`,
    `adk4s-memory-testkit` % Test
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
    // The sbt-wartremover AutoPlugin unconditionally adds the wartremover
    // compiler plugin to every project's libraryDependencies. WartRemover
    // 3.6.1 is not published for Scala 3.7.2 (the verified module's Scala
    // version for Stainless), which breaks dependency resolution. Since
    // wartremoverErrors is empty above, the plugin does nothing here anyway —
    // filter it out of libraryDependencies so the jar is never resolved.
    // scalacOptions is already a full override (no -Xplugin: survives), so no
    // further cleanup is needed on the scalacOptions side.
    libraryDependencies ~= (_.filterNot(_.organization == "org.wartremover")),
    semanticdbEnabled := false,
    // Default OFF: verified/compile is a plain, fast compile of the model.
    // Ring 6 turns verification ON explicitly via the `ring6` alias below.
    stainlessEnabled := false,
    publish / skip   := true,
    // Z3 native interface: Stainless 0.9.9.3 uses the ScalaZ3 wrapper (not
    // com.microsoft.z3 directly). The ScalaZ3 jar is not bundled with the
    // Stainless plugin for Scala 3, so the native Z3 interface is unavailable.
    // Ring 6 falls back to smt-z3 (Z3 via SMT-LIB subprocess), which is slower
    // but functional. To enable the native interface, build ScalaZ3 from
    // source for Scala 3.7.2 and add the jar to unmanagedClasspath.
    // See: https://github.com/epfl-lara/ScalaZ3
  )

// Ring 6 — run Stainless verification (needs a big heap; z3 single-threaded).
// The smt-z3 fallback solver requires the z3 binary in PATH:
//   PATH=/home/gruggiero/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/bin:$PATH \
//   sbt -J-Xmx6g ring6
addCommandAlias(
  "ring6",
  "; set verified / stainlessEnabled := true ; verified / compile"
)
