package org.sinemenda.probatio.core

import scala.util.matching.Regex

/**
 * One raw table row inside a spec document: its 1-based `line`, its raw
 * `text`, and its `cells` (the `|`-separated fields, stripped). The
 * graph's row semantics are the predecessor's `table_rows` helper —
 * every `|` row inside a section's line bounds minus the header — which
 * differs deliberately from `ObligationRow`'s lint-facing semantics and
 * is therefore a separate type.
 *
 * spec: graph-tool-port — Concepts Introduced (new): SpecGraphDoc
 */
final case class SpecTableRow(
  line: Int,
  text: String,
  cells: List[String]
)

/**
 * The parsed spec of one active change: `document` is the shared
 * `SpecDocument` (requirement titles and ordinals come from
 * `SpecDocumentParser`, reused rather than re-scanned), and the four
 * row sets are the graph's own tables — `## Concepts Used (behavioral)`,
 * `## Types Used`, `## Types Introduced`, and `## Proof Obligations` —
 * each under the predecessor's `table_rows` semantics.
 *
 * `unlinkable` carries rows read but unbindable at parse time; the
 * builder appends binding failures (citations resolving nowhere) to the
 * graph-level set.
 *
 * spec: graph-tool-port — Concepts Introduced (new): SpecGraphDoc
 * spec: graph-tool-port — Requirement: The graph is built from the three declared sources
 */
final case class SpecGraphDoc(
  change: String,
  capability: String,
  file: String,
  document: SpecDocument,
  conceptsUsed: List[SpecTableRow],
  typesUsed: List[SpecTableRow],
  typesIntroduced: List[SpecTableRow],
  obligations: List[SpecTableRow],
  unlinkable: List[UnlinkableRow]
)

object SpecGraphDoc:

  private val ConceptsUsedHeading: Regex = "^## Concepts Used \\(behavioral\\)".r
  private val TypesUsedHeading: Regex = "^## Concepts Used \\(from inventory\\)".r
  private val IntroducedHeading: Regex = "^## Concepts Introduced".r
  private val ObligationsHeading: Regex = "^## Proof Obligations".r

  /**
   * Parses one active spec's text — pure; the adapter reads the file and
   * supplies `change`, `capability` and the repository-relative `file`
   * path. Internally delegates requirement extraction to
   * `SpecDocumentParser`.
   *
   * The three concept tables keep only rows whose first cell parses to a
   * symbol — marker cells are not read, and rows naming no symbol are
   * reported in `unlinkable`. The Proof Obligations table keeps EVERY
   * data row: the predecessor numbers obligations by table position
   * (skipped rows still consume an ordinal), so binding decisions —
   * malformed rows, comment rows — are made at build time against the
   * row's position.
   */
  def parse(
    change: String,
    capability: String,
    file: String,
    text: String
  ): SpecGraphDoc =
    val lines: Vector[String] = GraphParse.linesOf(text)
    val document: SpecDocument = SpecDocumentParser.parse(file, text)

    def table(heading: Regex): List[GraphParse.RawRow] =
      GraphParse.sectionBounds(lines, heading) match
        case None            => Nil
        case Some((start, _)) => GraphParse.tableRows(lines, start + 1)

    /** Rows of a concept table: bindable rows + unlinkable rows. */
    def conceptRows(heading: Regex, what: String): (List[SpecTableRow], List[UnlinkableRow]) =
      table(heading).foldLeft((List.empty[SpecTableRow], List.empty[UnlinkableRow])) {
        case ((rs, us), row) =>
          val first: String = row.cells.headOption.getOrElse("")
          if GraphParse.isMarkerCell(first) then (rs, us)
          else
            GraphParse.firstSymbol(first) match
              case None =>
                (rs, us :+ UnlinkableRow(
                  file, row.line, row.text,
                  UnlinkableReason.stated(s"row names no $what symbol: $first")
                ))
              case Some(_) => (rs :+ SpecTableRow(row.line, row.text, row.cells), us)
      }

    val (conceptsUsed: List[SpecTableRow], unl1: List[UnlinkableRow]) =
      conceptRows(ConceptsUsedHeading, "concept")
    val (typesUsed: List[SpecTableRow], unl2: List[UnlinkableRow]) =
      conceptRows(TypesUsedHeading, "type")
    val (typesIntroduced: List[SpecTableRow], unl3: List[UnlinkableRow]) =
      conceptRows(IntroducedHeading, "type")
    // Proof Obligations keeps all data rows — the predecessor numbers
    // obligations by position, so even unbindable rows hold an ordinal.
    val obligations: List[SpecTableRow] =
      table(ObligationsHeading).map((r: GraphParse.RawRow) => SpecTableRow(r.line, r.text, r.cells))

    SpecGraphDoc(
      change, capability, file, document,
      conceptsUsed, typesUsed, typesIntroduced, obligations,
      unl1 ++ unl2 ++ unl3
    )
