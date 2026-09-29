package org.sinemenda.probatio.plugin

import java.io.File

import munit.FunSuite
import sbt.Logger

/**
 * Typed contract for spec: native-gate-delivery — plugin half (Step 1)
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * signatures via eta-expanded references and asserts the compile-negative
 * obligations. Zero runtime cost; any later signature drift breaks
 * `sbt-probatio/Test/compile`.
 *
 * spec: native-gate-delivery — Step 1: typed contract
 * spec: native-gate-delivery — Requirement: The resolution result determines the shim's target
 * spec: native-gate-delivery — Compile-Negative: literal shim target is unconstructible
 * spec: native-gate-delivery — Compile-Negative: delegating task cannot be invoked with only a tool name
 */
final class NativeGateDeliveryPluginTypeContract extends FunSuite {

  // ── Signature pins (eta-expanded against the real implementation) ────────
  // These pins make "signatures stay as approved" compiler-checked.

  // ShimGenerator.generateShim: (ResolutionResult, ShimTargetScope, String) => Either[String, String]
  // (scope gained as a required parameter by workflow-delivery-hygiene)
  val generateShimSig: (ResolutionResult, ShimTargetScope, String) => Either[String, String] =
    ShimGenerator.generateShim _

  // ShimGenerator.generateShim: (ResolutionResult, ShimTargetScope) => Either[String, String]
  val generateShimGateSig: (ResolutionResult, ShimTargetScope) => Either[String, String] =
    ShimGenerator.generateShim _

  // InstallResolver.resolveForShim: (ResolutionScenario, String, Boolean) => ResolutionResult
  val resolveForShimSig: (ResolutionScenario, String, Boolean) => ResolutionResult =
    InstallResolver.resolveForShim _

  // ProbatioPlugin.runDelegatingTask: (File, String, Seq[String], Logger) => Unit
  val runDelegatingTaskSig: (File, String, Seq[String], Logger) => Unit =
    ProbatioPlugin.runDelegatingTask _

  // ProbatioPlugin.currentPlatformHasNative: Boolean
  val currentPlatformHasNativeSig: Boolean =
    ProbatioPlugin.currentPlatformHasNative

  // ProbatioPlugin.writeShim: (ResolutionResult, ShimTargetScope, String, File) => Either[String, File]
  val writeShimSig: (ResolutionResult, ShimTargetScope, String, File) => Either[String, File] =
    ProbatioPlugin.writeShim _

  // ── Compile-negative: a shim cannot be generated from a literal path ────
  // spec: native-gate-delivery — Compile-Negative: literal shim target is unconstructible
  test("ShimGenerator.generateShim cannot take a literal path") {
    val err: String = compileErrors("ShimGenerator.generateShim(\"/literal/path\", \"gate\")")
    assert(
      err.nonEmpty,
      "generateShim(\"/literal/path\", \"gate\") should not compile — the shim binds a resolution result, not a path"
    )
  }

  test("ShimGenerator.generateShim cannot take a bare path for the default subcommand") {
    val err: String = compileErrors("ShimGenerator.generateShim(\"/literal/path\")")
    assert(
      err.nonEmpty,
      "generateShim(\"/literal/path\") should not compile — the shim binds a resolution result, not a path"
    )
  }

  // spec: sbt-plugin — Compile-Negative: ShimGenerator.generateShim with a logic flag
  test("ShimGenerator.generateShim with a logic flag does not compile") {
    val err: String =
      compileErrors("ShimGenerator.generateShim(\"/literal/path\", \"gate\", logic = true)")
    assert(err.nonEmpty, "generateShim(..., logic = true) should not compile — flagged API does not exist")
  }

  // ── Compile-negative: a delegating task cannot be invoked with only a
  //    tool name — the argument list is a required parameter ───────────────
  // spec: native-gate-delivery — Compile-Negative: delegating task missing its argument list
  test("runDelegatingTask cannot be invoked with only a tool name") {
    val err: String = compileErrors(
      "ProbatioPlugin.runDelegatingTask(new java.io.File(\"b\"), \"spec-lint\", sbt.Logger.Null)"
    )
    assert(
      err.nonEmpty,
      "runDelegatingTask(binary, subcommand, log) should not compile — the argument list is required"
    )
  }
}
