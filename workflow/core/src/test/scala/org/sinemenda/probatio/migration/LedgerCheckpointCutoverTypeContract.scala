package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite
import org.sinemenda.probatio.verified.LedgerValidatorKernel

/**
 * Typed contract for ledger-checkpoint cutover (spec 8, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3
 * implementation must satisfy — compiled under the real probatio-core
 * test classpath, `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `ShimSwap` gains `comparison: GateRecord` as a REQUIRED field —
 *    a swap record that cannot state the comparison it rested on is
 *    unconstructible. `oracleGreen` becomes a DERIVED member
 *    (`comparison.authorisesSwap`): a record whose green flag could
 *    contradict its own gating comparison is unrepresentable. A record
 *    carrying a `Revert` verdict records an aborted swap (the audit
 *    trail the hook-cutover spec requires).
 *  - `CutoverGate.authoriseSwap(seam, comparison, exercising)` is the
 *    per-seam decision: it scopes the comparison to the oracle files
 *    exercising the seam (a file exercises the seam iff its tool-path
 *    set contains `ToolId.seamPath(seam)`), then decides and records.
 *    The returned `GateRecord.authorisesSwap` is the swap authorisation
 *    — true iff every exercising file produced a result in both arms
 *    and none is worse. A seam with no exercising file produces a
 *    record that cannot authorise (`hasEvidence` is false) — a swap
 *    MUST NOT proceed on an unmeasured seam.
 *  - `DifferentialResult` gains `unmeasuredFiles` and
 *    `justifyingFileNames`: a refusal must name the files justifying
 *    it — the worse files AND the files at least one arm did not
 *    measure. An absent result is not parity.
 *  - `SeamSwapRunner.attempt` is the measured driver: materialise the
 *    predecessor arm and the {seam}-ported arm, run the differential,
 *    scope, authorise, and — only on an authorising record — preserve
 *    the predecessor to `.predecessor.bak` and write the exec shim.
 *    The `Outcome` algebra covers the three exits: `Ran` = swapped and
 *    recorded; `Finding` = refused (names the justifying files or the
 *    absent predecessor); `Undetermined` = could not determine.
 *  - `GateRecord`/`CutoverVerdict`/`DifferentialResult` fields are
 *    UNCHANGED — additive probes only; no variant widening.
 *  - `LedgerValidatorKernel` gains `SwapFileComparison`, `SwapDecision`,
 *    and `authoriseSwap` with the spec's postcondition: authorised iff
 *    every file is measured in both arms and none is worse, and a
 *    refusal names at least one file. The kernel contract is verbatim
 *    the spec's — on an empty file list it authorises vacuously; the
 *    shipped driver never presents an empty scope (a seam with no
 *    exercising file is refused upstream by `hasEvidence`).
 *
 * spec: ledger-checkpoint-cutover — Requirement: Each seam is swapped only after its oracle files reach control parity
 * spec: ledger-checkpoint-cutover — Requirement: The evidence record format is preserved
 * spec: ledger-checkpoint-cutover — Requirement: The predecessor remains a revert target
 * spec: ledger-checkpoint-cutover — Formal Contract: authoriseSwap
 */
final class LedgerCheckpointCutoverTypeContract extends ProbatioSuite:

  import SeamTypes.*

  // ── ShimSwap — the swap record requires its comparison ──────────────
  val shimSwapApplySig: (
    ToolId,
    String,
    String,
    String,
    GateRecord,
    String
  ) => ShimSwap = ShimSwap.apply

  val shimSwapFieldsSig: ShimSwap => (
    ToolId,
    String,
    String,
    String,
    GateRecord,
    String
  ) =
    (s: ShimSwap) => (s.tool, s.predecessorPath, s.shimPath, s.binaryPath, s.comparison, s.timestamp)

  val shimSwapOracleGreenSig: ShimSwap => Boolean =
    (s: ShimSwap) => s.oracleGreen

  // ── CutoverGate — per-seam authorisation ────────────────────────────
  val authoriseSwapSig: (
    ToolId,
    DifferentialResult,
    Map[String, Set[String]]
  ) => GateRecord = CutoverGate.authoriseSwap

  // ── GateRecord — reused concept, unchanged ──────────────────────────
  val gateRecordApplySig: (CutoverVerdict, DifferentialResult) => GateRecord =
    GateRecord.apply

  val authorisesSwapSig: GateRecord => Boolean =
    (r: GateRecord) => r.authorisesSwap

  val hasEvidenceSig: GateRecord => Boolean =
    (r: GateRecord) => r.hasEvidence

  // ── DifferentialResult — refusal-naming probes ──────────────────────
  val unmeasuredFilesSig: DifferentialResult => List[FileComparison] =
    (d: DifferentialResult) => d.unmeasuredFiles

  val justifyingFileNamesSig: DifferentialResult => List[String] =
    (d: DifferentialResult) => d.justifyingFileNames

  // ── SeamSwapRunner — the measured per-seam driver ───────────────────
  val attemptSig: (
    ToolId,
    os.Path,
    String,
    os.Path,
    String
  ) => Outcome[ShimSwap] = SeamSwapRunner.attempt

  // ── ToolId seam wiring — reused concept ─────────────────────────────
  val seamPathSig: ToolId => String          = ToolId.seamPath
  val predecessorSourceSig: ToolId => String = ToolId.predecessorSource
  val swapOrderSig: List[ToolId]             = ToolId.swapOrder

  // ── Kernel mirror — LedgerValidatorKernel extension ─────────────────
  val kernelAuthoriseSwapSig
    : stainless.collection.List[LedgerValidatorKernel.SwapFileComparison] => LedgerValidatorKernel.SwapDecision =
    LedgerValidatorKernel.authoriseSwap

  val kernelSwapDecisionSig: (Boolean, stainless.collection.List[BigInt]) => LedgerValidatorKernel.SwapDecision =
    LedgerValidatorKernel.SwapDecision.apply

  // ── Evaluation: the derived members behave ───────────────────────────

  test("ShimSwap carries its comparison; oracleGreen derives from it"):
    val parity: DifferentialResult = DifferentialResult(
      List(FileComparison("evidence-ledger.bats", 23, 0, 0, true, true)),
      "/repo",
      Set("evidence-ledger.bats")
    )
    val regression: DifferentialResult = DifferentialResult(
      List(FileComparison("evidence-ledger.bats", 23, 0, 2, true, true)),
      "/repo",
      Set("evidence-ledger.bats")
    )
    val swapped: ShimSwap = ShimSwap(
      ToolId.Ledger,
      "scanner/ledger.sh.predecessor.bak",
      "scanner/ledger.sh",
      "bin/probatio",
      CutoverGate.record(parity),
      "2026-09-24T00:00:00Z"
    )
    val aborted: ShimSwap = swapped.copy(comparison = CutoverGate.record(regression))
    assert(swapped.oracleGreen, "a Proceed comparison authorises the swap")
    assert(!aborted.oracleGreen, "a Revert comparison records an aborted swap")
    assert(swapped.comparison.authorisesSwap, "the carried record is the authorisation")

  test("justifyingFileNames names worse and unmeasured files"):
    val d: DifferentialResult = DifferentialResult(
      List(
        FileComparison("worse.bats", 5, 1, 3, true, true),
        FileComparison("unmeasured.bats", 5, 0, 0, false, true),
        FileComparison("fine.bats", 5, 2, 1, true, true)
      ),
      "/repo",
      Set("worse.bats", "unmeasured.bats", "fine.bats")
    )
    assertEquals(d.justifyingFileNames, List("worse.bats", "unmeasured.bats"))
    assertEquals(d.unmeasuredFiles.map(_.fileName), List("unmeasured.bats"))

  test("a seam with no exercising file cannot authorise (hasEvidence guard)"):
    val emptyScoped: DifferentialResult = DifferentialResult(List.empty, "/repo", Set.empty)
    val record: GateRecord              = CutoverGate.record(emptyScoped)
    assert(!record.authorisesSwap, "a comparison without evidence cannot authorise a swap")
