package org.sinemenda.probatio.migration

import hedgehog.Gen
import hedgehog.Range

import java.lang.ProcessBuilder
import java.nio.charset.StandardCharsets

/**
 * The environment a test gives a spawned workflow tool, and the closed set
 * of variables that can change a tool's behaviour.
 *
 * spec: hermetic-test-processes — Step 1 typed contract (spec 1 of
 * `finish-probatio-replacement`).
 *
 * Design:
 *  - A spawned process receives the fixed base (the search path, the home
 *    directory, the temporary directory) plus exactly the controlled
 *    variables the test declares — nothing else from the invoking shell.
 *  - `HermeticEnv` has a private constructor and no inherited-environment
 *    factory: the only way to obtain one is `HermeticEnv.build`, which
 *    filters the inherited map through the closed `ControlledVariable` set.
 *  - `processBuilder` is the single route from a hermetic environment to a
 *    child process: it clears the builder's inherited environment and
 *    installs `env` wholesale, so redirects, working directory and stdin
 *    handling stay with the caller while the environment decision cannot
 *    be revisited.
 *
 * This file is the canonical copy in probatio-core test sources. An
 * identical copy lives in probatio-cli test sources under the same package
 * — test sources are not shared between modules, the same convention as
 * `MigrationTypes.ToolId` duplicating `SeamTypes.ToolId`. The sbt-probatio
 * plugin tests receive the same contract in Scala 2.12 encoding at Step 3.
 */

/**
 * The closed set of environment variables that change a workflow tool's
 * behaviour: the harness session variables, and the workflow's own control
 * variables in their probatio and legacy (verified-scala3) spellings.
 *
 * Membership here means: a `HermeticEnv` carries this variable only when a
 * test declares it, and an inherited value for it is always dropped. Adding
 * a member is a deliberate act — a variable enters the set because a
 * workflow tool reads it, not because a test wanted to pass it.
 */
enum ControlledVariable(val envName: String):
  // harness session variables — the harness sets these on the shell that
  // invokes sbt/bats; gate.sh.predecessor ranks CLAUDE_CODE_SESSION_ID
  // above VERIFIED_SCALA3_SESSION_ID, which is the reported defect
  case ClaudeCodeSessionId extends ControlledVariable("CLAUDE_CODE_SESSION_ID")
  case ClaudeProjectDir extends ControlledVariable("CLAUDE_PROJECT_DIR")

  // workflow session + control variables (legacy spellings)
  case VerifiedScala3SessionId extends ControlledVariable("VERIFIED_SCALA3_SESSION_ID")
  case VerifiedScala3Hooks extends ControlledVariable("VERIFIED_SCALA3_HOOKS")
  case VerifiedScala3HooksTrace extends ControlledVariable("VERIFIED_SCALA3_HOOKS_TRACE")
  case VerifiedScala3ActiveSpec extends ControlledVariable("VERIFIED_SCALA3_ACTIVE_SPEC")
  case VerifiedScala3AllowPaths extends ControlledVariable("VERIFIED_SCALA3_ALLOW_PATHS")
  case VerifiedScala3SkipPredecessorCheck extends ControlledVariable("VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK")

  // probatio spellings of the workflow's control variables
  case ProbatioHooks extends ControlledVariable("PROBATIO_HOOKS")
  case ProbatioHooksTrace extends ControlledVariable("PROBATIO_HOOKS_TRACE")
  case ProbatioSchemaDir extends ControlledVariable("PROBATIO_SCHEMA_DIR")

  // root/environment signals the tools read
  case OpenspecRoot extends ControlledVariable("OPENSPEC_ROOT")
  case Ci extends ControlledVariable("CI")
  case Pwd extends ControlledVariable("PWD")

  // test seams — the tools' scripted oracle/override injection points
  case ChainStateOverride extends ControlledVariable("CHAIN_STATE_OVERRIDE")
  case DangerScanOverride extends ControlledVariable("DANGER_SCAN_OVERRIDE")
  case ReconcileOverride extends ControlledVariable("RECONCILE_OVERRIDE")
  case SpecLintOverride extends ControlledVariable("SPEC_LINT_OVERRIDE")

object ControlledVariable:
  /** The controlled variable an environment name denotes, if any. */
  def fromEnvName(name: String): Option[ControlledVariable] =
    values.find((v: ControlledVariable) => v.envName == name)

/**
 * The environment a test gives a spawned tool: the fixed base plus exactly
 * the controlled variables the test declares.
 *
 * The constructor is private and there is no public `apply`: a HermeticEnv
 * exists only through `HermeticEnv.build`, which filters the inherited
 * environment — there is no route that hands a child process the invoking
 * environment wholesale.
 */
final case class HermeticEnv private (private val vars: Map[String, String]):

  /** The complete variable set the spawned process receives. */
  def toMap: Map[String, String] = vars

  /** True iff the environment carries this controlled variable — which, by
   * construction, happens exactly when a test declared it. */
  def has(v: ControlledVariable): Boolean = vars.contains(v.envName)

  /** The value of a controlled variable, if the test declared it. */
  def value(v: ControlledVariable): Option[String] = vars.get(v.envName)

  /**
   * This environment with fixed-base entries replaced — for a test that
   * must sandbox HOME or TMPDIR. Only names in `HermeticEnv.baseNames`
   * may be overridden; anything else is dropped. Controlled variables
   * still come only from declarations — there is no route through here.
   */
  def withBase(overrides: Map[String, String]): HermeticEnv =
    HermeticEnv(
      vars ++ overrides.filter((entry: (String, String)) => HermeticEnv.baseNames.contains(entry._1))
    )

/** The captured result of a hermetic child process. */
final case class HermeticResult(exitCode: Int, out: String, err: String)

object HermeticEnv:

  /**
   * The fixed base every spawned process receives — the search path, the
   * home directory, the temporary directory — taken from the inherited
   * environment when present there. Widening the base is a deliberate act:
   * add a name here, not per test.
   */
  val baseNames: Set[String] = Set("PATH", "HOME", "TMPDIR")

  /**
   * The only way to construct a HermeticEnv: the fixed base drawn from
   * `inherited`, plus exactly the declared controlled variables. Every
   * other entry of `inherited` — controlled or not — does not reach the
   * child.
   */
  def build(
      declared: Map[ControlledVariable, String],
      inherited: Map[String, String]
  ): HermeticEnv =
    val base: Map[String, String] =
      inherited.filter((entry: (String, String)) => baseNames.contains(entry._1))
    val declaredVars: Map[String, String] =
      declared.map((entry: (ControlledVariable, String)) => entry._1.envName -> entry._2)
    HermeticEnv(base ++ declaredVars)

  /**
   * Build against this test JVM's own environment. The read of the process environment
   * happens exactly here, and it is handed to `build` — which filters it.
   * This is not an inherit-everything factory.
   */
  def build(declared: Map[ControlledVariable, String]): HermeticEnv =
    build(declared, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * Build against this test JVM's environment plus `extra` — the route for
   * a scenario that simulates an invoking shell carrying a specific
   * variable. `extra` flows through the same filter: a controlled name in
   * it reaches the child only when also declared, and the fixed base still
   * comes from the real environment.
   */
  def buildWithExtras(
      declared: Map[ControlledVariable, String],
      extra: Map[String, String]
  ): HermeticEnv =
    build(declared, sys.env ++ extra) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * The single route from a hermetic environment to a child process: a
   * ProcessBuilder whose environment is exactly `env` — the builder's
   * inherited environment cleared first so nothing undeclared survives.
   * Working directory, redirects and stdin are the caller's concern; the
   * environment decision is made here.
   */
  def processBuilder(command: List[String], env: HermeticEnv): ProcessBuilder =
    val pb: ProcessBuilder = new ProcessBuilder(command*) // scalafix:ok DisableSyntax.NoRawProcessBuilder
    val childEnv: java.util.Map[String, String] =
      pb.environment() // scalafix:ok DisableSyntax.NoBuilderEnvMutation
    childEnv.clear()
    env.toMap.foreach((entry: (String, String)) => childEnv.put(entry._1, entry._2))
    pb

  /**
   * The observation half of the process boundary: spawn `env` under this
   * hermetic environment and return exactly what the child process
   * received. Requires `PATH` in the environment — the fixed base supplies
   * it when the inherited environment has it.
   */
  def probeChild(env: HermeticEnv): Map[String, String] =
    val pb: ProcessBuilder = processBuilder(List("env"), env)
    val p: Process = pb.start()
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    p.waitFor()
    out.linesIterator.collect { case (line: String) if line.contains('=') =>
      val idx: Int = line.indexOf('=')
      line.substring(0, idx) -> line.substring(idx + 1)
    }.toMap

  /**
   * An environment declaring nothing — the fixed base only. The common
   * case for a spawned tool: nothing inherited, nothing declared.
   */
  def empty: HermeticEnv = build(Map.empty)

  /**
   * Run a command under a hermetic environment with no stdin and both
   * streams discarded; returns the exit code. The child's stdin is always
   * redirected — an inherited open pipe blocks a payload-reading tool
   * forever (the defect site's documented failure mode).
   */
  def run(
      command: List[String],
      env: HermeticEnv,
      cwd: Option[java.io.File] = None
  ): Int =
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.start().waitFor()

  /**
   * Run a command under a hermetic environment and capture its streams:
   * (exit code, stdout, stderr). `stdin` is written to the child before
   * its output is drained; absent stdin is /dev/null, not an open pipe.
   */
  def capture(
      command: List[String],
      env: HermeticEnv,
      cwd: Option[java.io.File] = None,
      stdin: Option[Array[Byte]] = None
  ): HermeticResult =
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    if stdin.isEmpty then
      pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    val p: Process = pb.start()
    stdin.foreach { (bytes: Array[Byte]) =>
      p.getOutputStream.write(bytes)
      p.getOutputStream.close()
    }
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    val err: String = new String(p.getErrorStream.readAllBytes, StandardCharsets.UTF_8)
    val code: Int   = p.waitFor()
    HermeticResult(code, out, err)

  /**
   * Like `capture`, but the child's stderr is merged into its stdout —
   * the `redirectErrorStream(true)` semantic. The combined stream lands
   * in `out`; `err` is empty.
   */
  def captureMerged(
      command: List[String],
      env: HermeticEnv,
      cwd: Option[java.io.File] = None,
      stdin: Option[Array[Byte]] = None
  ): HermeticResult =
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    pb.redirectErrorStream(true)
    if stdin.isEmpty then
      pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    val p: Process = pb.start()
    stdin.foreach { (bytes: Array[Byte]) =>
      p.getOutputStream.write(bytes)
      p.getOutputStream.close()
    }
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    val code: Int   = p.waitFor()
    HermeticResult(code, out, "")

/**
 * Generators over the closed controlled-variable set — shared by the
 * hermetic-environment properties. Both generators are constructive:
 * subset shape is chosen first so the spec's edge cases (the empty set,
 * the harness session variable alone, the full set) always appear.
 */
object HermeticEnvGens:

  val allControlled: List[ControlledVariable] = ControlledVariable.values.toList

  /**
   * `genControlledSubset` — subsets of the closed set, category-first:
   * empty, the harness session variable alone, the full set, or a uniform
   * bitmask over the remaining space.
   */
  val genControlledSubset: Gen[Set[ControlledVariable]] = Gen.frequency1(
    4  -> Gen.constant(Set.empty[ControlledVariable]),
    4  -> Gen.constant(Set(ControlledVariable.ClaudeCodeSessionId)),
    6  -> Gen.constant(allControlled.toSet),
    8  -> Gen.long(Range.linear(0L, (1L << allControlled.size) - 1L)).map((mask: Long) =>
      allControlled.zipWithIndex.collect { case (v, i) if (mask & (1L << i)) != 0L => v }.toSet
    )
  )

  /**
   * `genDeclared` — the variables a test declares: a drawn subset at
   * `declared-` sentinel values, so a declared value is always
   * distinguishable from an inherited one.
   */
  val genDeclared: Gen[Map[ControlledVariable, String]] =
    genControlledSubset.map((s: Set[ControlledVariable]) =>
      s.map((v: ControlledVariable) => v -> s"declared-${v.envName}").toMap
    )

  /**
   * `genInvokingEnvironment` — the invoking shell's environment: the fixed
   * base, the drawn subset of controlled variables at `inherited-`
   * sentinel values (an alphabet disjoint from the declared alphabet, per
   * the spec's generator strategy), and non-controlled noise that must
   * not survive the build.
   */
  val genInvokingEnvironment: Gen[Map[String, String]] =
    genControlledSubset.map((s: Set[ControlledVariable]) =>
      s.map((v: ControlledVariable) => v.envName -> s"inherited-${v.envName}").toMap ++
        Map(
          "PATH"               -> "/usr/bin",
          "HOME"               -> "/tmp",
          "TMPDIR"             -> "/tmp",
          "UNCONTROLLED_NOISE" -> "must-not-survive"
        )
    )
