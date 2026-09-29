package org.sinemenda.probatio.core

import upickle.default.*

/**
 * A parsed spec document — the typed input the lint engine checks.
 *
 * The parser is a faithful port of the predecessor's awk structural scan:
 * it records every `### Requirement:`/`### Property:`/`### Temporal:`/
 * `#### Scenario:` heading, every proof-obligation table row that reaches
 * the source check, the section flags (Proof Obligations table, behavioral
 * Concepts Used, Formal Contracts content), and each block's source line
 * numbers. It performs no checks and no I/O — the checks live in
 * `SpecLintEngine` and every value here is derived data.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): SpecDocument
 */
final case class SpecDocument(
  name: String,
  lines: Vector[String],
  requirements: List[RequirementBlock],
  properties: List[PropertyBlock],
  temporals: List[TemporalBlock],
  scenarios: List[ScenarioHeading],
  obligationRows: List[ObligationRow],
  dataRowCount: Int,
  bridgeRowCount: Int,
  hasProofObligations: Boolean,
  formalContractsContentLines: Int,
  hasBehavioralConcepts: Boolean,
  artifactRows: List[ObligationRow],
  chainRows: List[ObligationRow]
)

/**
 * A `### Requirement:` block.
 *
 * `line`/`endLine` bracket the block: `line` is the heading line and
 * `endLine` is the line of the next block/section heading that flushed it
 * (or `lines.size + 1` at end of file). The checks that rescan the body
 * (the vague-word warning) operate on `document.lines.slice` over this
 * range, exactly as the predecessor applies them while `in_req` is set.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): RequirementBlock
 */
final case class RequirementBlock(
  title: String,
  line: Int,
  endLine: Int,
  hasNormative: Boolean,
  negative: Boolean,
  scenarioCount: Int,
  normativeText: String
)

/**
 * A `### Property:` block — records whether a `**Generator strategy**`
 * line appears anywhere inside it (F3).
 *
 * spec: spec-lint-engine — Concepts Introduced (new): PropertyBlock
 */
final case class PropertyBlock(
  title: String,
  line: Int,
  endLine: Int,
  hasGeneratorStrategy: Boolean
)

/**
 * A `### Temporal:` block — records whether `**Trigger event**` and
 * `**Response event**` lines appear inside it (F5).
 *
 * spec: spec-lint-engine — Concepts Introduced (new): TemporalBlock
 */
final case class TemporalBlock(
  title: String,
  line: Int,
  endLine: Int,
  hasTriggerEvent: Boolean,
  hasResponseEvent: Boolean
)

/**
 * A `#### Scenario:` heading, wherever it appears. The existence checks
 * (F8) resolve `Scenario:` source parts against this list; a requirement
 * block's own scenarios are counted separately on `RequirementBlock`.
 */
final case class ScenarioHeading(
  title: String,
  line: Int
)

/**
 * One proof-obligation table row that the source check evaluates.
 *
 * Rows skipped by the predecessor (`Source` cell empty or a `<!--` comment,
 * or fewer than four `|`-separated fields) never reach `check_source` —
 * they are counted in `SpecDocument.dataRowCount` (the W2 denominator)
 * but do not appear here, so every row in this list is accounted for by
 * exactly one of `LintReport.resolvedRows` / `LintReport.unresolvableRows`.
 *
 * `fieldCount` is the awk `NF` of the raw row — the artifact check's
 * `NF < 5` guard reads it. `bridgeRowCount` on `SpecDocument` counts the
 * rows whose text mentions `bridge|parity` across *all* data rows (the
 * predecessor counts them before the source-check early-returns), which
 * W6 reads alongside `formalContractsContentLines`.
 *
 * `artifactRows` is the F9 scan's row set — every data row with
 * `fieldCount >= 5`, including rows `check_source` skips (empty or
 * comment `Source` cells). It is tracked separately from
 * `obligationRows` because the predecessor's artifact pass never tests
 * the `Source` cell, and its section flag is only toggled by `## `
 * headings (a `### Requirement:` heading inside the Proof Obligations
 * section stops the main scan but not the artifact scan).
 *
 * `chainRows` is the chain-state script's own awk row set — a THIRD
 * consumer with its own, different row semantics: section toggled only
 * by `## ` headings (like `artifactRows`), but the header is excluded
 * STRUCTURALLY (every `|` row before the first four-cell separator is
 * skipped; the separator flag is sticky across the whole file), the
 * `Source` cell is never tested (empty and `<!--` comment sources are
 * admitted), and the only content test is a non-empty Obligation cell.
 * The `^\| *Obligation` content exclusion `obligationRows` applies does
 * NOT apply here — a data row whose Obligation cell starts with the
 * word "Obligation" is a real chain-state row.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): ObligationRow
 */
final case class ObligationRow(
  line: Int,
  fieldCount: Int,
  source: String,
  enforcement: String,
  artifact: String,
  raw: String
) derives ReadWriter
