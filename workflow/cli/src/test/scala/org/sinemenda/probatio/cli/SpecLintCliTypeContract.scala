package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.LintContext
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.Outcome

import java.nio.file.Path

/**
 * Typed contract for the spec-lint CLI boundary (spec 4, Step 1).
 *
 * Pins the I/O-side surface the Step-2 oracle and Step-3 wiring must
 * satisfy:
 *
 *  - `SpecLintCmd.run` keeps the predecessor's invocation surface — a
 *    positional target plus `--artifacts` / `--context-only` /
 *    `--format json` — and returns the three-way `Outcome` (exit 0 /
 *    findings / could-not-determine).
 *  - `RepositoryFactsReader.readLintContext` reads every applicability
 *    fact once at the I/O boundary — registry presence and `# Concept:`
 *    headings, inventory type names, schema version, deterministic-kit
 *    profile, and the six install roots — and hands the engine a
 *    `LintContext` value.
 *  - `StdoutRenderer[LintContext]` renders the predecessor's CONTEXT
 *    block; `StdoutRenderer[LintReport]` renders the finding stream.
 *
 * spec: spec-lint-engine — Requirement: The spec-lint invocation surface is preserved: context-only, artifacts, and JSON output modes are all supported
 */
final class SpecLintCliTypeContract extends ProbatioCliSuite:

  // ── The subcommand entrypoint ───────────────────────────────────────
  val specLintRunSig: Array[String] => Outcome[Int] =
    SpecLintCmd.run

  // ── The applicability-facts read at the I/O boundary ────────────────
  val readLintContextSig: (Path, Path) => LintContext =
    RepositoryFactsReader.readLintContext

  // ── Renderers ───────────────────────────────────────────────────────
  val lintContextRendererSig: StdoutRenderer[LintContext] =
    StdoutRenderer[LintContext]

  val lintReportRendererSig: StdoutRenderer[LintReport] =
    StdoutRenderer[LintReport]

  // ── The pinned surface evaluates ────────────────────────────────────
  test("the cli boundary signatures resolve"):
    assert(specLintRunSig != null, "SpecLintCmd.run must resolve")
    assert(readLintContextSig != null, "readLintContext must resolve")
    assert(lintContextRendererSig != null, "StdoutRenderer[LintContext] must resolve")
    assert(lintReportRendererSig != null, "StdoutRenderer[LintReport] must resolve")
