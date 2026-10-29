package org.sinemenda.probatio.migration

import org.sinemenda.probatio.cli.ProbatioCliSuite

import MigrationTypes.*

/**
 * Typed contract for spec: differential-harness-integrity (cli side)
 *
 * `MigrationTypes.ToolId` is the cli-test duplicate of `SeamTypes.ToolId`
 * (core test sources are not visible to probatio-cli tests). This contract
 * pins the widened seam set so the two enums cannot drift apart silently —
 * the conformance property additionally checks they agree.
 *
 * spec: differential-harness-integrity — Step 1: typed contract (full)
 * spec: differential-harness-integrity — Type-Constraint: the seam enum gains a ledger seam and a checkpoint seam
 */
final class DifferentialHarnessCliTypeContract extends ProbatioCliSuite:

  // ── Seam-set widening pins ──────────────────────────────────────────────
  // spec: differential-harness-integrity — Scenario: Happy path — the seam set matches the declared swap order
  test("cli ToolId seam set has exactly seven tools"):
    assertEquals(ToolId.swapOrder.length, 7, "the cli seam set must cover seven tools")
    assert(ToolId.swapOrder.toSet.size == 7, "seam set must be duplicate-free")
    assert(ToolId.swapOrder.contains(ToolId.Ledger), "Ledger seam missing")
    assert(ToolId.swapOrder.contains(ToolId.Checkpoint), "Checkpoint seam missing")

  // ── Compile-Negative: a seam named outside the enum
  // spec: differential-harness-integrity — Compile-Negative: A seam identifier written as a free string
  test("cli MigrationState cannot be built from a free string seam"):
    val err: String = compileErrors("MigrationState(Set(\"ledger\"))")
    assert(err.nonEmpty, "MigrationState(Set[String]) should not compile — the field is typed by the seam enum")
