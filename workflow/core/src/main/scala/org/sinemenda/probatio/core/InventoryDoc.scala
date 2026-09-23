package org.sinemenda.probatio.core

/**
 * One row of the type inventory — the predecessor reads `cells[0]` for
 * the type name (first backticked symbol, else the stripped cell), the
 * enclosing `## ` section name, and `cells[-1]` for provenance; the
 * `Service Traits` section additionally reads `cells[3]` for
 * scanner-linked implementations. `cells` is kept raw because the
 * column layout varies by section and fidelity to the predecessor's
 * positional reads is the contract.
 *
 * spec: graph-tool-port — Concepts Introduced (new): InventoryDoc
 */
final case class InventoryRow(
  line: Int,
  text: String,
  name: String,
  section: String,
  cells: List[String]
)

/**
 * The parsed type inventory: every table row that passed the
 * predecessor's row filter (non-separator, name parses to a capitalised
 * symbol, not a comment) as an `InventoryRow`, and every row that was
 * read but rejected as an `UnlinkableRow` with the rejection reason.
 *
 * spec: graph-tool-port — Concepts Introduced (new): InventoryDoc
 * spec: graph-tool-port — Requirement: A row that cannot be bound is reported, never dropped
 */
final case class InventoryDoc(
  file: String,
  rows: List[InventoryRow],
  unlinkable: List[UnlinkableRow]
)

object InventoryDoc:

  /**
   * Parses the inventory's text — pure; the adapter reads the file.
   * `file` is the repository-relative path used in diagnostics.
   *
   * The predecessor scans every `|` line inside a `## ` section — NOT
   * `table_rows`, so table header rows pass through the row filter and
   * bind (`type:Type`, `type:Concept` — the header quirk is reproduced
   * for parity). Rejected rows are reported in `unlinkable`, never
   * dropped: fewer than two cells, or a first cell that names no
   * capitalised symbol.
   */
  def parse(file: String, text: String): InventoryDoc =
    val lines: Vector[String] = GraphParse.linesOf(text)
    val step: (
      (List[InventoryRow], List[UnlinkableRow], Option[String]),
      (String, Int)
    ) => (List[InventoryRow], List[UnlinkableRow], Option[String]) = {
      case ((rows, unl, section), (line, idx)) =>
        val lineno: Int = idx + 1
        if line.startsWith("## ") then
          (rows, unl, Some(line.substring(3).trim))
        else if !line.trim.startsWith("|") then (rows, unl, section)
        else section match
          case None => (rows, unl, section) // `|` lines before any section are not read
          case Some(sec) =>
            val cells: List[String] = GraphParse.cellsOf(line)
            if GraphParse.isSeparator(cells) then (rows, unl, section)
            else if cells.length < 2 then
              (rows, unl :+ UnlinkableRow(
                file, lineno, line.trim,
                UnlinkableReason.stated("fewer than two cells")
              ), section)
            else
              val first: String = cells.headOption.getOrElse("")
              val name: String =
                GraphParse.firstSymbol(first)
                  .getOrElse(first.replaceAll("^`+|`+$", ""))
              if name.isEmpty || !name.take(1).exists(_.isUpper) || name.startsWith("<!--") then
                (rows, unl :+ UnlinkableRow(
                  file, lineno, line.trim,
                  UnlinkableReason.stated(s"row names no capitalised type symbol: $first")
                ), section)
              else
                (rows :+ InventoryRow(lineno, line.trim, name, sec, cells), unl, section)
    }
    val (rows: List[InventoryRow], unl: List[UnlinkableRow], _: Option[String]) =
      lines.zipWithIndex.foldLeft(
        (List.empty[InventoryRow], List.empty[UnlinkableRow], Option.empty[String])
      )(step)
    InventoryDoc(file, rows, unl)
