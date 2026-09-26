package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

import java.nio.file.Path

/**
 * Typed contract for spec: entrypoint-split
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. The split is a mechanical
 * move — one object per file — so the contract pins the signatures that must
 * survive it unchanged: the eleven `run` entrypoints (the ProbatioMain
 * dispatch surface) and the `private[cli]` members shared across entrypoints.
 *
 * The shared members are pinned ON THEIR CURRENT OWNERS: `SpecLintCmd.gitOut`
 * is called by `GateCmd`, `ChainStateCmd` and `MetalsCmd` (and by
 * `SubcommandWiring`). Moving such a member to a shared-helpers object would
 * force a qualifier rewrite at a call site inside another moved body, which
 * the spec's moved-identical requirement forbids — they are already
 * `private[cli]`, so they remain reachable from every file in the package.
 *
 * spec: entrypoint-split — Requirement: Each subcommand's entrypoint resides in its own source file
 * spec: entrypoint-split — Requirement: Moved code is moved, not edited
 */
final class EntrypointSplitTypeContract extends ProbatioCliSuite:

  // ── The eleven entrypoint signatures ───────────────────────────────────
  // The ProbatioMain dispatch surface: one `run` per subcommand.
  //
  // spec: entrypoint-split — Requirement: Each subcommand's entrypoint resides in its own source file

  val gateRunSig: Array[String] => Outcome[Int]          = GateCmd.run
  val specLintRunSig: Array[String] => Outcome[Int]      = SpecLintCmd.run
  val chainStateRunSig: Array[String] => Outcome[Int]    = ChainStateCmd.run
  val graphRunSig: Array[String] => Outcome[Int]         = GraphCmd.run
  val ledgerRunSig: Array[String] => Outcome[Int]        = LedgerCmd.run
  val checkpointRunSig: Array[String] => Outcome[Int]    = CheckpointCmd.run
  val reconcileRunSig: Array[String] => Outcome[Int]     = ReconcileCmd.run
  val dangerScanRunSig: Array[String] => Outcome[Int]    = DangerScanCmd.run
  val metalsRunSig: Array[String] => Outcome[Int]        = MetalsCmd.run
  val installSkillsRunSig: Array[String] => Outcome[Int] = InstallSkillsCmd.run
  val installHooksRunSig: Array[String] => Outcome[Int]  = InstallHooksCmd.run

  // ── Cross-entrypoint members — pinned in place ─────────────────────────
  // Used by more than one entrypoint; already `private[cli]` so the split
  // needs no relocation and no call-site edit.
  //
  // spec: entrypoint-split — Implementation Anchor: Shared helpers

  // SpecLintCmd.gitOut: (Path, List[String]) => Option[String]
  //   callers: GateCmd, ChainStateCmd, MetalsCmd, SubcommandWiring
  val specLintGitOutSig: (Path, List[String]) => Option[String] =
    SpecLintCmd.gitOut

  // SpecLintCmd.findSpecs: (Path, Path => Boolean) => Either[String, List[Path]]
  //   caller: ChainStateCmd
  val specLintFindSpecsSig: (Path, Path => Boolean) => Either[String, List[Path]] =
    SpecLintCmd.findSpecs

  // SpecLintCmd.repoRoot: Path
  //   callers: ChainStateCmd, MetalsCmd
  val specLintRepoRootSig: Path =
    SpecLintCmd.repoRoot

  // SpecLintCmd.userHome: Map[String, String] => Path
  //   caller: ChainStateCmd
  val specLintUserHomeSig: Map[String, String] => Path =
    SpecLintCmd.userHome

  // LedgerCmd.runAppend: (Map[String, String], String) => Outcome[Int]
  //   caller: GateCmd (observation call, outcome discarded)
  val ledgerRunAppendSig: (Map[String, String], String) => Outcome[Int] =
    LedgerCmd.runAppend

  // LedgerCmd.readRowsFiltered — caller: CheckpointCmd
  val ledgerReadRowsFilteredSig: (String, String, String, String, (String, String) => Boolean) => Outcome[List[ujson.Value]] =
    LedgerCmd.readRowsFiltered

  // ChainStateCmd.forgivePredicate: String => (String, String) => Boolean
  //   caller: CheckpointCmd
  val chainStateForgivePredicateSig: String => (String, String) => Boolean =
    ChainStateCmd.forgivePredicate

  // GraphCmd.exportObligations — caller: ChainStateCmd
  val graphExportObligationsSig: (Path, String) => Either[String, ujson.Value] =
    GraphCmd.exportObligations
