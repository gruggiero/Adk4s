package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Typed contract for spec: differential-harness-integrity
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved public
 * signatures via eta-expanded references and asserts the compile-negative
 * obligations. Any later signature drift breaks `probatio-core/Test/compile`.
 *
 * spec: differential-harness-integrity — Step 1: typed contract (full)
 * spec: differential-harness-integrity — Concepts Introduced: ArmTree
 * spec: differential-harness-integrity — Concepts Introduced: ArmDivergence
 * spec: differential-harness-integrity — Concepts Introduced: SeamResolution
 * spec: differential-harness-integrity — Type-Constraint: the seam enum gains a ledger seam and a checkpoint seam
 */
final class DifferentialHarnessIntegrityTypeContract extends ProbatioSuite:

  import SeamTypes.*
  import DifferentialHarness.SuiteRun

  // ── Signature pins (eta-expanded against the real implementation) ───────
  // These pins make "signatures stay as approved" compiler-checked.

  // ArmTree.materialise: (SeamConfiguration, String, os.Path, os.Path) => Outcome[ArmTree]
  val materialiseSig: (SeamConfiguration, String, os.Path, os.Path) => Outcome[ArmTree] =
    ArmTree.materialise

  // DifferentialHarness.divergence: (List[SeamResolution], List[SeamResolution]) => ArmDivergence
  val divergenceSig: (List[SeamResolution], List[SeamResolution]) => ArmDivergence =
    DifferentialHarness.divergence

  // DifferentialHarness.runSuite: ArmTree => SuiteRun
  val runSuiteSig: ArmTree => SuiteRun =
    DifferentialHarness.runSuite

  // DifferentialHarness.compare: (ArmTree, ArmTree) => Either[ArmDivergence.Identical, DifferentialResult]
  val compareSig: (ArmTree, ArmTree) => Either[ArmDivergence.Identical, DifferentialResult] =
    DifferentialHarness.compare

  // DifferentialHarness.exercisedToolPaths: ArmTree => Map[String, Set[String]]
  val exercisedToolPathsSig: ArmTree => Map[String, Set[String]] =
    DifferentialHarness.exercisedToolPaths

  // DifferentialHarness.unseamedToolPaths: ArmTree => Map[String, Set[String]]
  val unseamedToolPathsSig: ArmTree => Map[String, Set[String]] =
    DifferentialHarness.unseamedToolPaths

  // DifferentialHarness.checkPredecessorControl: (ArmTree, SuiteRun, os.Path) => Outcome[Unit]
  val checkPredecessorControlSig: (ArmTree, SuiteRun, os.Path) => Outcome[Unit] =
    DifferentialHarness.checkPredecessorControl

  // DifferentialHarness.diff: (SuiteRun, SuiteRun, String) => DifferentialResult (unchanged)
  val diffSig: (SuiteRun, SuiteRun, String) => DifferentialResult =
    DifferentialHarness.diff

  // DifferentialHarness.verifySuiteDigests: (os.Path, Map[String, String]) => Either[String, Unit] (unchanged)
  val verifySuiteDigestsSig: (os.Path, Map[String, String]) => Either[String, Unit] =
    DifferentialHarness.verifySuiteDigests

  // ToolId.overrideEnvVar: ToolId => Option[String] (widened: Ledger/Checkpoint have no override seam)
  val overrideEnvVarSig: ToolId => Option[String] =
    ToolId.overrideEnvVar

  // ToolId.seamPath: ToolId => String
  val seamPathSig: ToolId => String =
    ToolId.seamPath

  // ToolId.predecessorSource: ToolId => String
  val predecessorSourceSig: ToolId => String =
    ToolId.predecessorSource

  // CutoverGate.decide: DifferentialResult => CutoverVerdict (unchanged)
  val decideSig: DifferentialResult => CutoverVerdict =
    CutoverGate.decide

  // ── Seam-set widening pins ──────────────────────────────────────────────
  // spec: differential-harness-integrity — Scenario: Happy path — the seam set matches the declared swap order
  test("ToolId seam set has exactly seven tools, mirroring SwapOrder"):
    assertEquals(ToolId.swapOrder.length, 7, "the seam set must cover seven tools")
    assertEquals(SwapOrder.swapOrder.length, 7, "the swap order must have seven positions")
    assert(ToolId.swapOrder.toSet.size == 7, "seam set must be duplicate-free")

  test("every seam resolves a distinct live invocation path"):
    val paths: List[String] = ToolId.swapOrder.map(ToolId.seamPath)
    assertEquals(paths.toSet.size, 7, "each seam must name a distinct live path")

  // ── Compile-Negative obligations live in DifferentialHarnessCompileNegative
  // (the spec's named artifact for this spec). The signature pins above are
  // the contract's own compile-checked surface.

  // ── Property & generator obligations (become the Ring 3 test oracle) ───
  //
  // Property: identical-arms-never-proceed
  //   Invariant: for every seam configuration, if the two arms resolve every
  //   seam to the same implementation then the comparison's outcome is a
  //   refusal — never the proceed verdict, and never a revert verdict
  //   carrying evidence.
  //   Generator: genSeamConfiguration — constructive over subsets of the
  //   seven-seam set. Edge cases: empty, full, each singleton.
  //
  // Property: divergence-is-detected-by-content-not-path
  //   Invariant: the divergence verdict depends only on the seams' content
  //   digests — content-equal/path-differing arms are Identical;
  //   content-differing/path-equal arms are Diverged.
  //   Generator: genArmPair — constructive (content, path) pairs per seam
  //   from small alphabets.
  //
  // Property: seam-set-covers-the-swap-order
  //   Invariant: swap-order positions and seams are in bijection (7 = 7).
  //   Generator: enumerated finite domain, not sampled.
  //
  // Property: comparison-is-monotone-in-failures
  //   Invariant: a file is worse iff ported failures exceed predecessor
  //   failures.
  //   Generator: genSuiteRunPair — constructive per-file triples.
  //
  // Scenarios (14): predecessor arm holds predecessor impls; ported arm
  // holds ported impls; absent predecessor impl is could-not-determine;
  // unmaterialised side unconstructible; identical arms refuse (not
  // Proceed); one-seam difference still compares; identical bytes at
  // differing paths are the same implementation; seam set matches swap
  // order; unrepresented-tool file is not-compared; predecessor arm
  // matches control per file; different baseline is could-not-determine;
  // predecessor arm reporting ported counts is rejected; retargeted
  // drift-message test passes on ported tool / fails on bad tool; payload
  // test fails on pattern substitution.
  //
  // spec: differential-harness-integrity — Properties (Ring 3)
