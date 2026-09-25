package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Behavioural tests for the hermetic environment's non-property surface —
 * the paths the SessionIdSpec properties don't reach: base-only overrides,
 * stdin routing, merged capture, env-name resolution, and `=` inside a
 * variable's value.
 *
 * spec: hermetic-test-processes — Requirement: A shared helper constructs
 * child processes with only the fixed base and declared controls
 */
final class HermeticEnvSpec extends ProbatioSuite:

  // ── ControlledVariable.fromEnvName ──────────────────────────────────
  test("fromEnvName round-trips every declared env name and rejects unknowns"):
    ControlledVariable.values.foreach { (v: ControlledVariable) =>
      assertEquals(
        ControlledVariable.fromEnvName(v.envName),
        Some(v),
        s"${v.envName} must resolve back to ${v}"
      )
    }
    assertEquals(ControlledVariable.fromEnvName("NOT_A_CONTROLLED_VAR"), None)
    assertEquals(ControlledVariable.fromEnvName(""), None)

  // ── withBase ─────────────────────────────────────────────────────────
  test("withBase overrides fixed-base names and drops everything else"):
    val env: HermeticEnv = HermeticEnv.build(
      Map(ControlledVariable.Ci -> "true")
    )
    val rebased: HermeticEnv = env.withBase(
      Map(
        "HOME"                   -> "/tmp/sandbox-home",
        "TMPDIR"                 -> "/tmp/sandbox-tmp",
        "NOT_A_BASE_NAME"        -> "dropped",
        "CLAUDE_CODE_SESSION_ID" -> "must-stay-declared-only"
      )
    )
    val m: Map[String, String] = rebased.toMap
    assertEquals(m.get("HOME"), Some("/tmp/sandbox-home"))
    assertEquals(m.get("TMPDIR"), Some("/tmp/sandbox-tmp"))
    assert(!m.contains("NOT_A_BASE_NAME"), "a non-base name must be dropped")
    assertEquals(
      m.get("CLAUDE_CODE_SESSION_ID"),
      None,
      "withBase must never admit a controlled variable"
    )
    assertEquals(m.get("CI"), Some("true"), "declared controls survive withBase")

  // ── run ──────────────────────────────────────────────────────────────
  test("run returns the exit code with streams discarded and stdin not a pipe"):
    // `cat` would block forever on an inherited open stdin; /dev/null makes
    // it exit 0 with no output — this is the defect site's failure mode.
    val code: Int = HermeticEnv.run(List("cat"), HermeticEnv.empty)
    assertEquals(code, 0)

  // ── capture ──────────────────────────────────────────────────────────
  test("capture with no stdin gets EOF, not a blocking pipe"):
    val r: HermeticResult = HermeticEnv.capture(List("cat"), HermeticEnv.empty)
    assertEquals(r.exitCode, 0)
    assertEquals(r.out, "")

  test("capture writes declared stdin bytes to the child"):
    val payload: Array[Byte] = "hello-stdin".getBytes("UTF-8")
    val r: HermeticResult =
      HermeticEnv.capture(List("cat"), HermeticEnv.empty, stdin = Some(payload))
    assertEquals(r.exitCode, 0)
    assertEquals(r.out, "hello-stdin")

  test("capture separates stdout and stderr"):
    val r: HermeticResult = HermeticEnv.capture(
      List("sh", "-c", "echo out-line; echo err-line >&2"),
      HermeticEnv.empty
    )
    assertEquals(r.exitCode, 0)
    assertEquals(r.out.trim, "out-line")
    assertEquals(r.err.trim, "err-line")

  // ── captureMerged ────────────────────────────────────────────────────
  test("captureMerged folds stderr into out and leaves err empty"):
    val r: HermeticResult = HermeticEnv.captureMerged(
      List("sh", "-c", "echo merged-line; echo err-into-out >&2; exit 0"),
      HermeticEnv.empty
    )
    assertEquals(r.exitCode, 0)
    assert(r.out.contains("merged-line"), s"stdout missing: ${r.out}")
    assert(r.out.contains("err-into-out"), s"stderr not merged: ${r.out}")
    assertEquals(r.err, "")

  test("captureMerged writes declared stdin bytes instead of /dev/null"):
    // `cat` echoes its stdin; an empty-stdin captureMerged must not hang,
    // a bytes-stdin one must deliver them into the merged stream.
    val payload: Array[Byte] = "merged-stdin".getBytes("UTF-8")
    val r: HermeticResult =
      HermeticEnv.captureMerged(List("cat"), HermeticEnv.empty, stdin = Some(payload))
    assertEquals(r.exitCode, 0)
    assert(r.out.contains("merged-stdin"), s"stdin not delivered: ${r.out}")

  // ── probeChild ───────────────────────────────────────────────────────
  test("probeChild preserves a declared value containing '='"):
    val env: HermeticEnv = HermeticEnv.build(
      Map(ControlledVariable.Ci -> "a=b=c")
    )
    val observed: Map[String, String] = HermeticEnv.probeChild(env)
    assertEquals(
      observed.get("CI"),
      Some("a=b=c"),
      "a value with '=' must survive env parsing — first '=', not last"
    )
