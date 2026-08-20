import Dependencies._
import wartremover.WartRemover
import sbt.dsl.LinterLevel.Ignore

ThisBuild / scalaVersion := Versions.Scala
ThisBuild / organization := "org.adk4s"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions += "-Wconf:src=target/.*:s"

// iron-upickle 3.3.2 transitively depends on upickle 3.1.3, but the project
// uses upickle 4.4.3. The iron-upickle ReadWriter bridge uses only stable
// upickle APIs (upickle.default.ReadWriter) that are compatible across both
// major versions. Tell sbt to treat the eviction as compatible.
ThisBuild / libraryDependencySchemes ++= Seq(
  "com.lihaoyi" %% "upickle" % "always"
)

// --- scalafix ---
// Note: built-in rules (DisableSyntax, RemoveUnused, OrganizeImports) are
// already included transitively in scalafix-cli, which fetchAndClassloadInstance
// fetches automatically. Do NOT add scalafix-rules as a scalafixDependency —
// scalafix 0.14.x publishes it with CrossVersion.full only, and %% (binary)
// resolution fails. scalafixDependencies is for CUSTOM external rules only.
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
  // Required by scalafix RemoveUnused rule and OrganizeImports.removeUnused
  "-Wunused:all",
  // Ring 0 exhaustiveness escalation (verified-scala3 schema): an
  // inexhaustive match over a sealed type must fail compilation, not warn —
  // the schema's no-catch-all rules are unenforceable otherwise.
  "-Wconf:name=PatternMatchExhaustivity:e",
  "-Wconf:name=MatchCaseUnreachable:e",
  // "-source:future"
)

// --- probatio strict compiler flags (R-CS1–R-CS5) ---
// Scoped to probatio-core/probatio-cli ONLY (R-CS5). NOT applied repo-wide.
// These flags make the silent-fallback defect class a compile error:
//   R-CS1: -Werror (master switch — every other strict flag is load-bearing)
//   R-CS2: deprecation + feature escalation (forbids deprecated-alias paths)
//   R-CS3: -Wvalue:discard (discarded validator result = compile error)
//   R-CS4: -Ysafe-init (unsafe init order = compile error)
//   R-CS5: scoping (this val is only appended to probatio subprojects)
lazy val probatioScalacOptions: Seq[String] = Seq(
  "-Werror",
  "-Wconf:cat=deprecation:e",
  "-Wconf:cat=feature:e",
  "-Wsafe-init"
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
    ) ++ smithy4s ++ iron ++ testDeps,
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
    ) ++ fs2 ++ upickle ++ iron ++ testDeps,
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
    libraryDependencies ++= Seq(catsEffect) ++ upickle ++ iron ++ Seq(ironUpickle) ++ testDeps,
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

// ── adk4s-record — deterministic call recording + content-hash caching ────
// Recording middleware over a pluggable Recorder[F] sink, with canonical
// content-hash keys (CallKey), three reference backends (noop, inMemory,
// file), and RecorderLaws (RL0–RL12) in main scope.
// Depends on adk4s-core for ChatModel, Embedder, ToolMiddleware, JsonValue,
// AdkError, Iron refined types (NodeKey precedent). Depends on `verified` at
// Test scope for the Ring 6 bridge (NormalizationModel, RecorderCoherenceModel;
// TASTy backward compatible: 3.8.4 reads 3.7.2).
// MUST NOT depend on workflows4s, adk4s-orchestration, adk4s-optimize,
// adk4s-eval, or logback (Ring 2 purity rule). fs2-io is a module-level
// dependency but confined to org.adk4s.record.file by convention + import
// audit scenario; canonicalization (org.adk4s.record.canonical) imports
// neither fs2 nor cats.effect (AR-REC-1, AR-REC-2).
// munit + hedgehog-munit are in MAIN scope (not % Test) because RecorderLaws
// is a downstream-consumable main API — the adk4s-harness-testkit precedent.
lazy val `adk4s-record` = (project in file("adk4s-record"))
  .dependsOn(
    `adk4s-core`,
    `verified` % Test,
    `adk4s-harness-testkit` % Test
  )
  .settings(
    name := "adk4s-record",
    libraryDependencies ++= Seq(
      catsEffect,
      munitMain,
      munitCatsEffect,
      hedgehogMunitMain
    ) ++ fs2 ++ smithy4s ++ iron ++ testDeps,
    scalacOptions ++= scala3Options
  )
  .enablePlugins(Smithy4sCodegenPlugin)

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
      workflows4sCore,
      ironUpickle
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
    `adk4s-record`,
    `adk4s-harness-testkit`,
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

// Task: merge ScalaZ3 classes + native libs into the Stainless plugin jar.
// The Stainless plugin's classloader only searches its own jar, so the
// ScalaZ3 wrapper (z3.Z3Wrapper) must be in the same jar for Inox to
// detect the native Z3 interface. This task is idempotent: it checks
// whether the plugin jar already contains z3/Z3Wrapper.class.
// Returns the path to the merged jar (may be the original if no merge
// was needed, or a new file if the merge was performed).
lazy val mergeScalaZ3Plugin = taskKey[java.io.File]("Merge ScalaZ3 into the Stainless plugin jar, returning the merged jar path")

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
    // Stainless plugin for Scala 3, so we build it from source and place it
    // in verified/unmanaged/. The jar bundles libscalaz3.so, libz3.so, and
    // libz3java.so for Z3 4.13.4, plus the com.microsoft.z3 Java bindings.
    //
    // The native Z3 interface is faster than the smt-z3 fallback (1.4s vs
    // 2.5s for 111 VCs) and can discharge VCs that the SMT-LIB subprocess
    // solver times out on.
    //
    // The ScalaZ3 classes must be in the SAME jar as the Stainless plugin
    // because the plugin's classloader only searches its own jar. We merge
    // the ScalaZ3 jar into the Stainless plugin jar before compilation.
    // See: https://github.com/epfl-lara/ScalaZ3
    // Build: see /tmp/scalaz3 (modified to use pre-built Z3 4.13.4)
    stainlessExtraDeps += "ch.epfl.lara" % "scalaz3_3" % "4.13.4"
      from s"file://${baseDirectory.value / "unmanaged" / "scalaz3_3-4.13.4.jar"}",
    // Task: merge ScalaZ3 jar into the Stainless plugin jar (idempotent).
    // Returns the path to the jar that should be used as the -Xplugin: path.
    // Does NOT depend on scalacOptions (which would create a cycle) — instead
    // computes the plugin jar path from known build constants.
    mergeScalaZ3Plugin := {
      val scalaz3  = baseDirectory.value / "unmanaged" / "scalaz3_3-4.13.4.jar"
      // The Stainless plugin jar is at:
      //   <root>/target/scala-<rootScalaVer>/compiler_plugins/
      //     stainless-dotty-plugin_<verifiedScalaVer>-<stainlessVer>.jar
      val pluginDir = baseDirectory.value.getParentFile / "target" /
        s"scala-${Versions.Scala}" / "compiler_plugins"
      val pluginJar = pluginDir / s"stainless-dotty-plugin_${Versions.ScalaVerified}-0.9.9.3.jar"
      if (!pluginJar.exists || !scalaz3.exists) {
        pluginJar  // return original path even if it doesn't exist yet
      } else {
        // The merged jar is a new file alongside the original plugin jar.
        // Check if the merged jar already exists and contains Z3Wrapper.
        val outJar = pluginJar.getParentFile / (pluginJar.getName.stripSuffix(".jar") + "-merged.jar")
        val needsMerge = !outJar.exists || {
          val p = new java.util.jar.JarFile(outJar)
          val has = p.getEntry("z3/Z3Wrapper.class") != null
          p.close()
          !has
        }
        if (needsMerge) {
          val log = streams.value.log
          log.info(s"Merging ScalaZ3 into Stainless plugin jar: $outJar")
          val tmpDir = java.nio.file.Files.createTempDirectory("stainless-merge")
          // Extract both jars into the same temp dir using the jar tool
          new java.lang.ProcessBuilder("jar", "xf", pluginJar.getAbsolutePath)
            .directory(tmpDir.toFile).inheritIO().start().waitFor()
          new java.lang.ProcessBuilder("jar", "xf", scalaz3.getAbsolutePath)
            .directory(tmpDir.toFile).inheritIO().start().waitFor()
          // Repackage with 0 compression (fast, avoids corruption issues)
          new java.lang.ProcessBuilder(
            "jar", "cf0", outJar.getAbsolutePath,
            "-C", tmpDir.toFile.getAbsolutePath, "."
          ).inheritIO().start().waitFor()
          // Clean up temp dir
          java.nio.file.Files.walk(tmpDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => java.nio.file.Files.delete(p))
          outJar
        } else {
          outJar
        }
      }
    },
    // Override scalacOptions to use the merged plugin jar when stainlessEnabled.
    // The .value call refers to the PREVIOUS definition (set by the Stainless
    // plugin), so this is not circular.
    Compile / scalacOptions := {
      val opts   = (Compile / scalacOptions).value
      val merged = mergeScalaZ3Plugin.value
      if (stainlessEnabled.value) {
        opts.map { opt =>
          if (opt.startsWith("-Xplugin:") && opt.contains("stainless-dotty-plugin"))
            "-Xplugin:" + merged.getAbsolutePath
          else
            opt
        }
      } else opts
    }
  )

// ── R-ARCH1: dependency-lint rule ──────────────────────────────────────────
// Fails if any workflow/* project's resolved classpath reaches a forbidden
// dependency (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck, adk4s-*).
// This is the build-level enforcement of the probatio leaf-by-construction
// invariant: probatio depends on NOTHING adk4s-side. The rule checks the
// resolved `update` report (not just direct deps) so transitive leaks are
// caught too.
lazy val dependencyLint = taskKey[Unit](
  "R-ARCH1: fail if this project's resolved classpath reaches a forbidden dependency"
)

/** R-ARCH1 forbidden predicate. Returns true if the module is in the closed
  * forbidden set: cats/cats-effect (org.typelevel), fs2 (co.fs2), llm4s
  * (org.llm4s), workflows4s (org.business4s), scalacheck (org.scalacheck),
  * adk4s-* (org.adk4s). For the sbt plugin (R-S1), org.sinemenda.probatio is
  * also forbidden — the plugin must NOT link probatio-core via Maven artifact.
  * probatio-cli legitimately depends on probatio-core via .dependsOn, so
  * org.sinemenda.probatio is NOT forbidden for probatio-cli.
  * munit (org.scalameta) and hedgehog (qa.hedgehog) are NOT forbidden —
  * they are in the R-X3 allowed set. */
def isForbiddenDependency(module: ModuleID, projectName: String): Boolean = {
  val org: String = module.organization
  val name: String = module.name
  org == "org.typelevel" && (name == "cats" || name.startsWith("cats-")) ||
  org == "co.fs2" ||
  org == "org.llm4s" ||
  org == "org.business4s" ||
  org == "org.scalacheck" ||
  org == "org.adk4s" ||
  (projectName == "sbt-probatio" && org == "org.sinemenda.probatio")
}

// ── probatio-core — ported logic (Scala 3.8.4, pure by construction) ───────
// ADTs (LedgerRecord, ChainStateReport, GatePayload, LintReport, Outcome),
// validators (ex-jq contracts), verdict logic, banner/drift engine, metals
// client. NO main, NO args, NO GraalVM config. Depends on NOTHING adk4s-side
// (R-ARCH1). NO cats, NO cats-effect, NO fs2 (R-X3).
lazy val `probatio-core` = (project in file("workflow/core"))
  .dependsOn(
    `probatio-verified` % Test
  )
  .settings(
    name := "probatio-core",
    organization := "org.sinemenda.probatio",
    libraryDependencies ++= Seq(
      Dependencies.osLib,
      Dependencies.upickle.head
    ) ++ Dependencies.probatioTestDeps,
    scalacOptions ++= scala3Options ++ probatioScalacOptions,
    // R-ARCH1: dependency-lint runs as part of compile to enforce the
    // leaf-by-construction invariant at build time.
    dependencyLint := {
      val report: UpdateReport = update.value
      val log: sbt.Logger = streams.value.log
      val forbidden: Seq[ModuleID] = report.allModules.filter(m => isForbiddenDependency(m, name.value))
      val msgs: Seq[String] = forbidden.map(m =>
        s"  ${m.organization}:${m.name}:${m.revision}"
      )
      if (forbidden.nonEmpty) {
        sys.error(
          s"R-ARCH1 violation: ${name.value} reaches forbidden dependencies:\n" +
            msgs.mkString("\n")
        )
      } else {
        log.info(s"R-ARCH1: ${name.value} classpath clean (no forbidden dependencies)")
      }
    }
  )

// ── probatio-cli — multicall CLI (Scala 3.8.4, mainargs + os-lib + uPickle) ─
// The public CLI protocol: one subcommand per predecessor script, three-way
// exit codes (0/1/2), byte-compatible stdout payloads, arg-parse error
// attribution, --help with defaults, multicall dispatch by argv(1) and argv(0).
// Depends on probatio-core (Outcome, LintReport, ChainStateReport, GatePayload,
// Ledger). NO cats, NO cats-effect, NO fs2 (R-X3). R-ARCH1: no adk4s deps.
lazy val `probatio-cli` = (project in file("workflow/cli"))
  .dependsOn(`probatio-core`)
  .enablePlugins(NativeImagePlugin)
  .settings(
    name := "probatio-cli",
    organization := "org.sinemenda.probatio",
    libraryDependencies ++= Seq(
      Dependencies.osLib,
      Dependencies.mainargs,
      Dependencies.upickle.head
    ) ++ Dependencies.probatioTestDeps,
    scalacOptions ++= scala3Options ++ probatioScalacOptions,
    // Native-image config (R-N1, R-N2): --no-fallback (V1 spike finding),
    // -O1 optimization for gate latency budget. The multicall binary is
    // named "probatio" (alias "prob"). Native-image is mandatory for the
    // gate subcommand; other subcommands MAY use the assembly JAR fallback.
    Compile / mainClass := Some("org.sinemenda.probatio.cli.ProbatioMain"),
    nativeImageOptions ++= Seq("--no-fallback", "-O1"),
    nativeImageOutput := target.value / "native-image" / "probatio",
    dependencyLint := {
      val report: UpdateReport = update.value
      val log: sbt.Logger = streams.value.log
      val forbidden: Seq[ModuleID] = report.allModules.filter(m => isForbiddenDependency(m, name.value))
      val msgs: Seq[String] = forbidden.map(m =>
        s"  ${m.organization}:${m.name}:${m.revision}"
      )
      if (forbidden.nonEmpty) {
        sys.error(
          s"R-ARCH1 violation: ${name.value} reaches forbidden dependencies:\n" +
            msgs.mkString("\n")
        )
      } else {
        log.info(s"R-ARCH1: ${name.value} classpath clean (no forbidden dependencies)")
      }
    }
  )

// ── sbt-probatio — sbt 1.x AutoPlugin (Scala 2.12, thin build adapter) ──────
// Thin build-integration plugin: declares settings/tasks, resolves the
// probatio binary, delegates all tool execution to the binary via argv +
// exit codes + stdout JSON. Links NO probatio-core code (R-S1) — all
// communication is subprocess invocation. NO cats, NO cats-effect (R-ARCH1).
// The plugin's only dependencies are sbt APIs and the Scala 2.12 stdlib.
// sbt-2-ready: no GlobalScope abuse, no deprecated operators, Def.task
// composition only (R-S2).
lazy val `sbt-probatio` = (project in file("workflow/plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-probatio",
    organization := "org.sinemenda.probatio",
    scalaVersion := Versions.Scala2_12,
    // SbtPlugin sets sbtPlugin := true and configures publishing.
    // The plugin targets sbt 1.x today; sbt 2.x migration is out of scope
    // (proposal §2.2) but the code is sbt-2-ready (R-S2).
    libraryDependencies ++= Dependencies.sbtPluginTestDeps,
    // R-ARCH1: dependency-lint rule — fails if any forbidden dependency
    // (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck, adk4s-*)
    // appears on the plugin's classpath. Also catches probatio-core leaks.
    dependencyLint := {
      val report: UpdateReport = update.value
      val log: sbt.Logger = streams.value.log
      val forbidden: Seq[ModuleID] = report.allModules.filter(m => isForbiddenDependency(m, name.value))
      val msgs: Seq[String] = forbidden.map(m =>
        s"  ${m.organization}:${m.name}:${m.revision}"
      )
      if (forbidden.nonEmpty) {
        sys.error(
          s"R-ARCH1 violation: ${name.value} reaches forbidden dependencies:\n" +
            msgs.mkString("\n")
        )
      } else {
        log.info(s"R-ARCH1: ${name.value} classpath clean (no forbidden dependencies)")
      }
    }
  )

// ── probatio-verified — Ring 6 mirror leaf (Scala 3.7.2, Stainless) ────────
// PureScala models of chain-state verdict logic, 12-clause validator, banner
// engine. Pinned to 3.7.2 for the Stainless frontend. Depends on NOTHING
// project-local. NOT aggregated by root (normal builds skip Stainless).
lazy val `probatio-verified` = (project in file("verified/probatio"))
  .enablePlugins(StainlessPlugin)
  .settings(
    name := "probatio-verified",
    organization := "org.sinemenda.probatio",
    scalaVersion := Versions.ScalaVerified,
    scalacOptions := Seq(
      "-deprecation",
      "-feature",
      "-Wconf:src=.*stainless-library.*:silent"
    ),
    wartremoverErrors := Seq.empty,
    libraryDependencies ~= (_.filterNot(_.organization == "org.wartremover")),
    semanticdbEnabled := false,
    stainlessEnabled := false,
    publish / skip := true,
    // Native Z3: same ScalaZ3 jar as the `verified` project, but located at
    // verified/unmanaged/ (one level up from this project's baseDirectory).
    // See verified/unmanaged/README.md for the jar-merge explanation.
    stainlessExtraDeps += "ch.epfl.lara" % "scalaz3_3" % "4.13.4"
      from s"file://${baseDirectory.value.getParentFile / "unmanaged" / "scalaz3_3-4.13.4.jar"}",
    mergeScalaZ3Plugin := {
      val scalaz3  = baseDirectory.value.getParentFile / "unmanaged" / "scalaz3_3-4.13.4.jar"
      val pluginDir = baseDirectory.value.getParentFile.getParentFile / "target" /
        s"scala-${Versions.Scala}" / "compiler_plugins"
      val pluginJar = pluginDir / s"stainless-dotty-plugin_${Versions.ScalaVerified}-0.9.9.3.jar"
      if (!pluginJar.exists || !scalaz3.exists) {
        pluginJar
      } else {
        val outJar = pluginJar.getParentFile / (pluginJar.getName.stripSuffix(".jar") + "-merged.jar")
        val needsMerge = !outJar.exists || {
          val p = new java.util.jar.JarFile(outJar)
          val has = p.getEntry("z3/Z3Wrapper.class") != null
          p.close()
          !has
        }
        if (needsMerge) {
          val log = streams.value.log
          log.info(s"Merging ScalaZ3 into Stainless plugin jar: $outJar")
          val tmpDir = java.nio.file.Files.createTempDirectory("stainless-merge")
          new java.lang.ProcessBuilder("jar", "xf", pluginJar.getAbsolutePath)
            .directory(tmpDir.toFile).inheritIO().start().waitFor()
          new java.lang.ProcessBuilder("jar", "xf", scalaz3.getAbsolutePath)
            .directory(tmpDir.toFile).inheritIO().start().waitFor()
          new java.lang.ProcessBuilder(
            "jar", "cf0", outJar.getAbsolutePath,
            "-C", tmpDir.toFile.getAbsolutePath, "."
          ).inheritIO().start().waitFor()
          java.nio.file.Files.walk(tmpDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => java.nio.file.Files.delete(p))
          outJar
        } else {
          outJar
        }
      }
    },
    Compile / scalacOptions := {
      val opts   = (Compile / scalacOptions).value
      val merged = mergeScalaZ3Plugin.value
      if (stainlessEnabled.value) {
        opts.map { opt =>
          if (opt.startsWith("-Xplugin:") && opt.contains("stainless-dotty-plugin"))
            "-Xplugin:" + merged.getAbsolutePath
          else
            opt
        }
      } else opts
    },
    dependencyLint := {
      val report: UpdateReport = update.value
      val log: sbt.Logger = streams.value.log
      val forbidden: Seq[ModuleID] = report.allModules.filter(m => isForbiddenDependency(m, name.value))
      val msgs: Seq[String] = forbidden.map(m =>
        s"  ${m.organization}:${m.name}:${m.revision}"
      )
      if (forbidden.nonEmpty) {
        sys.error(
          s"R-ARCH1 violation: ${name.value} reaches forbidden dependencies:\n" +
            msgs.mkString("\n")
        )
      } else {
        log.info(s"R-ARCH1: ${name.value} classpath clean (no forbidden dependencies)")
      }
    }
  )

// Command alias: run dependency-lint across all probatio subprojects.
addCommandAlias(
  "probatioDependencyLint",
  "; probatio-core/dependencyLint ; probatio-cli/dependencyLint ; sbt-probatio/dependencyLint ; probatio-verified/dependencyLint"
)

// ── V1 spike — throwaway native-image toolchain proof ──────────────────────
// NOT spec-1 production. This subproject exists only to discharge the V1
// hard-blocker: prove GraalVM native-image can build a probatio-style CLI
// (uPickle + os-lib + mainargs) without hand-maintained reflection config.
// Deleted after V1/V2 pass. Excluded from dependency-lint (spike-only).
lazy val `probatio-spike` = (project in file("workflow/spike"))
  .settings(
    name := "probatio-spike",
    organization := "org.sinemenda.probatio",
    libraryDependencies ++= Seq(
      Dependencies.osLib,
      Dependencies.mainargs,
      Dependencies.upickle.head
    ),
    scalacOptions ++= scala3Options,  // NO probatioScalacOptions — spike is throwaway
    // Assembly config for native-image input: single fat JAR, main class set.
    Compile / mainClass := Some("org.sinemenda.probatio.spike.SpikeMain"),
    assembly / mainClass := Some("org.sinemenda.probatio.spike.SpikeMain")
  )

// Ring 6 — run Stainless verification (needs a big heap; z3 single-threaded).
// The smt-z3 fallback solver requires the z3 binary in PATH:
//   PATH=/home/gruggiero/opt/z3-4.13.4/z3-4.13.4-x64-glibc-2.35/bin:$PATH \
//   sbt -J-Xmx6g ring6
// The mergeScalaZ3Plugin task runs automatically as part of scalacOptions
// evaluation, merging the ScalaZ3 native Z3 wrapper into the Stainless
// plugin jar before compilation.
addCommandAlias(
  "ring6",
  "; set verified / stainlessEnabled := true ; verified / compile ; set probatio-verified / stainlessEnabled := true ; probatio-verified / compile"
)
