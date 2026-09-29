package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

import java.nio.file.Path

/**
 * Typed contract for the ledger/checkpoint CLI adapters (spec 7, Step 1).
 *
 * Pins the public shapes the Step-2 oracle exercises against the built
 * artifact — compiled under the real probatio-cli classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `LedgerCmd.Action` is the predecessor's closed operation set:
 *    `append`, `run`, `read`, `verify`. `validate` is renamed to the
 *    predecessor's `verify`; `update`/`delete`/`rewrite`/`edit` remain
 *    rejected by name before parameter parsing (append-only).
 *  - `LedgerCmd.runVerify` takes the injected seams — `repoRootOf`
 *    (the ledger file's repository root), `sha256Of` (file hashing for
 *    manual/R8 artifacts), `shellParses` (the predecessor's `bash -n`
 *    check), `replayExit` (re-run under a directory, observed exit) —
 *    so tests drive the boundary without a process env.
 *  - `LedgerCmd.readRowsFiltered` is the record tool's own read path:
 *    read + contract-validate, then change/spec/baseline filtering with
 *    the injected `artifactUnchanged` forgiveness predicate. The
 *    checkpoint delegates row filtering to THIS path.
 *  - `CheckpointCmd.runReport` takes the injected forgive-predicate
 *    factory (the same discipline `chain-state` uses) so the per-spec
 *    baseline fallback honours `--forgive-unchanged`.
 *  - `CheckpointCmd.runRegenerateTasks` takes read/write file seams —
 *    text-preserving regeneration is testable without touching the
 *    filesystem.
 *  - `SubcommandWiring` owns the shared git/file I/O adapters —
 *    `repoRootOf`, `sha256OfFile`, `shellParses`, `replayCommand`,
 *    `executeCaptured`, `readTextFile`, `writeTextFile`,
 *    `forgivePredicate`, `absoluteGitDirOf` — moved out of
 *    `ChainStateCmd` so the record and checkpoint tools share them.
 *
 * spec: ledger-checkpoint-parity — Requirement: Record filtering SHALL delegate to the record tool's own read path
 * spec: ledger-checkpoint-parity — Compile-Negative: A record operation type with an update, delete, or rewrite operation
 */
final class LedgerCheckpointCliTypeContract extends ProbatioCliSuite:

  // ── LedgerCmd.Action — the predecessor's four operations ────────────
  val actionAppendSig: LedgerCmd.Action = LedgerCmd.Action.Append
  val actionRunSig: LedgerCmd.Action    = LedgerCmd.Action.Run
  val actionReadSig: LedgerCmd.Action   = LedgerCmd.Action.Read
  val actionVerifySig: LedgerCmd.Action = LedgerCmd.Action.Verify

  // ── LedgerCmd — entrypoint and seam-injected operations ─────────────
  val ledgerRunSig: Array[String] => Outcome[Int] =
    LedgerCmd.run

  val runVerifySig: (
    Map[String, String],
    Path => Path,
    Path => Option[String],
    String => Boolean,
    (Path, String) => Int
  ) => Outcome[Int] =
    LedgerCmd.runVerify

  val readRowsFilteredSig: (
    String,
    String,
    String,
    String,
    (String, String) => Boolean
  ) => Outcome[List[ujson.Value]] =
    LedgerCmd.readRowsFiltered

  // ── CheckpointCmd — the two predecessor operations ──────────────────
  val checkpointRunSig: Array[String] => Outcome[Int] =
    CheckpointCmd.run

  val runReportSig: (
    Array[String],
    String => (String, String) => Boolean
  ) => Outcome[Int] =
    CheckpointCmd.runReport

  val runRegenerateTasksSig: (
    Array[String],
    Path => Either[String, String],
    (Path, String) => Either[String, Unit]
  ) => Outcome[Int] =
    CheckpointCmd.runRegenerateTasks

  // ── SubcommandWiring — the shared I/O adapters ──────────────────────
  val repoRootOfSig: Path => Path =
    SubcommandWiring.repoRootOf

  val sha256OfFileSig: Path => Option[String] =
    SubcommandWiring.sha256OfFile

  val shellParsesSig: String => Boolean =
    SubcommandWiring.shellParses

  val replayCommandSig: (Path, String) => Int =
    SubcommandWiring.replayCommand

  val executeCapturedSig: (Path, String) => (Int, Array[Byte], Long) =
    SubcommandWiring.executeCaptured

  val readTextFileSig: Path => Either[String, String] =
    SubcommandWiring.readTextFile

  val writeTextFileSig: (Path, String) => Either[String, Unit] =
    SubcommandWiring.writeTextFile

  val forgivePredicateSig: String => (String, String) => Boolean =
    SubcommandWiring.forgivePredicate

  val absoluteGitDirOfSig: Path => Option[Path] =
    SubcommandWiring.absoluteGitDirOf
