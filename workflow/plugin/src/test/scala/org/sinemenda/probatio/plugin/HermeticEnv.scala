package org.sinemenda.probatio.plugin

import java.lang.ProcessBuilder
import java.nio.charset.StandardCharsets

/**
 * The environment a test gives a spawned workflow tool, and the closed set
 * of variables that can change a tool's behaviour — the Scala 2.12 encoding
 * of `workflow/core/src/test/scala/org/sinemenda/probatio/migration/HermeticEnv.scala`
 * (the plugin compiles under 2.12, so the `enum` becomes a sealed abstract
 * class of case objects). The Hedgehog generators are omitted: no plugin
 * test draws them.
 *
 * spec: hermetic-test-processes — Step 3 shared helper (spec 1 of
 * `finish-probatio-replacement`).
 */

/**
 * The closed set of environment variables that change a workflow tool's
 * behaviour: the harness session variables, and the workflow's own control
 * variables in their probatio and legacy (verified-scala3) spellings.
 */
sealed abstract class ControlledVariable(val envName: String) extends Product with Serializable

object ControlledVariable {
  case object ClaudeCodeSessionId               extends ControlledVariable("CLAUDE_CODE_SESSION_ID")
  case object ClaudeProjectDir                  extends ControlledVariable("CLAUDE_PROJECT_DIR")
  case object VerifiedScala3SessionId           extends ControlledVariable("VERIFIED_SCALA3_SESSION_ID")
  case object VerifiedScala3Hooks               extends ControlledVariable("VERIFIED_SCALA3_HOOKS")
  case object VerifiedScala3HooksTrace          extends ControlledVariable("VERIFIED_SCALA3_HOOKS_TRACE")
  case object VerifiedScala3ActiveSpec          extends ControlledVariable("VERIFIED_SCALA3_ACTIVE_SPEC")
  case object VerifiedScala3AllowPaths          extends ControlledVariable("VERIFIED_SCALA3_ALLOW_PATHS")
  case object VerifiedScala3SkipPredecessorCheck extends ControlledVariable("VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK")
  case object ProbatioHooks                     extends ControlledVariable("PROBATIO_HOOKS")
  case object ProbatioHooksTrace                extends ControlledVariable("PROBATIO_HOOKS_TRACE")
  case object ProbatioSchemaDir                 extends ControlledVariable("PROBATIO_SCHEMA_DIR")
  case object OpenspecRoot                      extends ControlledVariable("OPENSPEC_ROOT")
  case object Ci                                extends ControlledVariable("CI")
  case object Pwd                               extends ControlledVariable("PWD")
  case object ChainStateOverride                extends ControlledVariable("CHAIN_STATE_OVERRIDE")
  case object DangerScanOverride                extends ControlledVariable("DANGER_SCAN_OVERRIDE")
  case object ReconcileOverride                 extends ControlledVariable("RECONCILE_OVERRIDE")
  case object SpecLintOverride                  extends ControlledVariable("SPEC_LINT_OVERRIDE")

  val values: List[ControlledVariable] = List(
    ClaudeCodeSessionId, ClaudeProjectDir,
    VerifiedScala3SessionId, VerifiedScala3Hooks, VerifiedScala3HooksTrace,
    VerifiedScala3ActiveSpec, VerifiedScala3AllowPaths, VerifiedScala3SkipPredecessorCheck,
    ProbatioHooks, ProbatioHooksTrace, ProbatioSchemaDir,
    OpenspecRoot, Ci, Pwd,
    ChainStateOverride, DangerScanOverride, ReconcileOverride, SpecLintOverride
  )

  /** The controlled variable an environment name denotes, if any. */
  def fromEnvName(name: String): Option[ControlledVariable] =
    values.find(_.envName == name)
}

/**
 * The environment a test gives a spawned tool: the fixed base plus exactly
 * the controlled variables the test declares. Private constructor, no
 * public apply — constructible only through `HermeticEnv.build`.
 */
final class HermeticEnv private (private val vars: Map[String, String]) {

  /** The complete variable set the spawned process receives. */
  def toMap: Map[String, String] = vars

  /** True iff the environment carries this controlled variable. */
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
    new HermeticEnv(
      vars ++ overrides.filter { case (k, _) => HermeticEnv.baseNames.contains(k) }
    )
}

/** The captured result of a hermetic child process. */
final case class HermeticResult(exitCode: Int, out: String, err: String)

object HermeticEnv {

  /** The fixed base every spawned process receives. */
  val baseNames: Set[String] = Set("PATH", "HOME", "TMPDIR")

  /** The only constructor: fixed base from `inherited` plus declared vars. */
  def build(
      declared: Map[ControlledVariable, String],
      inherited: Map[String, String]
  ): HermeticEnv = {
    val base: Map[String, String] =
      inherited.filter { case (k, _) => baseNames.contains(k) }
    val declaredVars: Map[String, String] =
      declared.map { case (v, s) => v.envName -> s }
    new HermeticEnv(base ++ declaredVars)
  }

  /** Build against this test JVM's own environment, filtered. */
  def build(declared: Map[ControlledVariable, String]): HermeticEnv =
    build(declared, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * Build against this test JVM's environment plus `extra` — for a scenario
   * simulating a shell carrying a specific variable.
   */
  def buildWithExtras(
      declared: Map[ControlledVariable, String],
      extra: Map[String, String]
  ): HermeticEnv =
    build(declared, sys.env ++ extra) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * The single route from a hermetic environment to a child process: the
   * builder's inherited environment cleared, `env` installed wholesale.
   */
  def processBuilder(command: List[String], env: HermeticEnv): ProcessBuilder = {
    val pb: ProcessBuilder = new ProcessBuilder(command: _*) // scalafix:ok DisableSyntax.NoRawProcessBuilder
    val childEnv: java.util.Map[String, String] = pb.environment() // scalafix:ok DisableSyntax.NoBuilderEnvMutation
    childEnv.clear()
    env.toMap.foreach { case (k, v) => childEnv.put(k, v) }
    pb
  }

  /** Spawn `env` under this environment and read back the child's variables. */
  def probeChild(env: HermeticEnv): Map[String, String] = {
    val pb: ProcessBuilder = processBuilder(List("env"), env)
    val p: Process = pb.start()
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    val _ = p.waitFor()
    out.split("\n").toList.collect { case line if line.contains('=') =>
      val idx: Int = line.indexOf('=')
      line.substring(0, idx) -> line.substring(idx + 1)
    }.toMap
  }

  /** An environment declaring nothing — the fixed base only. */
  def empty: HermeticEnv = build(Map.empty)

  /** Run with no stdin and both streams discarded; returns the exit code. */
  def run(
      command: List[String],
      env: HermeticEnv,
      cwd: Option[java.io.File] = None
  ): Int = {
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    val _ = pb
      .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.start().waitFor()
  }

  /**
   * Run and capture streams: (exit code, stdout, stderr). `stdin` is
   * written before output is drained; absent stdin is /dev/null.
   */
  def capture(
      command: List[String],
      env: HermeticEnv,
      cwd: Option[java.io.File] = None,
      stdin: Option[Array[Byte]] = None
  ): HermeticResult = {
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    if (stdin.isEmpty) {
      val _ = pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    }
    val p: Process = pb.start()
    stdin.foreach { (bytes: Array[Byte]) =>
      p.getOutputStream.write(bytes)
      p.getOutputStream.close()
    }
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    val err: String = new String(p.getErrorStream.readAllBytes, StandardCharsets.UTF_8)
    val code: Int   = p.waitFor()
    HermeticResult(code, out, err)
  }

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
  ): HermeticResult = {
    val pb: ProcessBuilder = processBuilder(command, env)
    cwd.foreach((d: java.io.File) => pb.directory(d))
    val _ = pb.redirectErrorStream(true)
    if (stdin.isEmpty) {
      val _ = pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    }
    val p: Process = pb.start()
    stdin.foreach { (bytes: Array[Byte]) =>
      p.getOutputStream.write(bytes)
      p.getOutputStream.close()
    }
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    val code: Int   = p.waitFor()
    HermeticResult(code, out, "")
  }
}
