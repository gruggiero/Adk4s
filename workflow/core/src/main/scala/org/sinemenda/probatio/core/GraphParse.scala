package org.sinemenda.probatio.core

import scala.util.matching.Regex

/**
 * Shared markdown-scanning helpers for the three graph-source parsers —
 * direct ports of the predecessor's `table_rows`, `section_bounds`,
 * `first_symbol`, `BACKTICK`, `MDLINK` and `_tokens`. Keeping them in one
 * object keeps the three parsers' row semantics identical to each other
 * and to the Python they replace.
 *
 * Row discipline: a "data row" is a `|` table line minus the header and
 * separator lines. Marker cells — `(none)`, `—`, `-`, empty, or an HTML
 * comment — in the FIRST cell are the table's own vocabulary for "this
 * row names nothing"; they are excluded from the row lists entirely
 * (not read, not unlinkable), except in the Proof Obligations table
 * where the predecessor's obligation numbering consumes every data row.
 *
 * spec: graph-tool-port — Requirement: A row that cannot be bound is reported, never dropped
 */
object GraphParse:

  /** `re.compile(r"`([^`]+)`")`. */
  val Backtick: Regex = "`([^`]+)`".r

  /** `BACKTICK.findall(text)` — group-1 contents, without the backticks. */
  def backticks(text: String): List[String] =
    Backtick.findAllMatchIn(text).map((m: scala.util.matching.Regex.Match) => m.group(1)).toList

  /** `re.compile(r"\[([^\]]+)\]\(([^)]+)\)")`. */
  val MdLink: Regex = "\\[([^\\]]+)\\]\\(([^)]+)\\)".r

  /** `re.compile(r"^### Requirement:\s*(.+?)\s*$")`. */
  val RequirementHeading: Regex = "^### Requirement:\\s*(.+?)\\s*$".r

  /** The separator-cell shape `:--`, `--`, `--:` — 2+ dashes. */
  private val SeparatorCell: Regex = ":?-{2,}:?".r

  /** The explicit-ordinal citation: `"Requirement 3"` / `"R3"`. */
  val ExplicitOrdinal: Regex = "(?:Requirement\\s+|\\bR)(\\d+)\\b".r

  /** The typed non-requirement source alternation (group 2 is the word). */
  val TypedSource: Regex =
    "(^|[^A-Za-z])(Property|Properties|Scenario|Scenarios|Invariant|Compile-Negative|Temporal|Criterion|Type-Constraint|MUST-CONFIRM|Design|Non-goal)\\s*(:|\\d)".r

  /** The `(new|created by)` marker on a Concepts Used row (case-insensitive). */
  val PlannedMarker: Regex = "(?i)\\((new|created by)".r

  /** Lowercase-token extraction for inferred linking. */
  private val TokenRe: Regex = "[A-Za-z][A-Za-z0-9/_-]{2,}".r

  private val Stopwords: Set[String] = Set(
    "the", "a", "an", "and", "or", "in", "on", "for", "to", "of", "is", "are",
    "with", "from", "by", "into", "that", "this", "its", "it", "not", "no",
    "returns", "return", "must", "shall", "when", "then", "given", "spec"
  )

  /** `_tokens` — distinctive lowercase tokens of a heading/cell. */
  def tokens(text: String): Set[String] =
    TokenRe.findAllIn(text.toLowerCase).toSet.diff(Stopwords)

  /** `text.splitlines()` — LF only (the corpus is LF-normalized). */
  def linesOf(text: String): Vector[String] =
    text.split("\n", -1).toVector match
      case v if v.nonEmpty && v.lastOption.contains("") => v.dropRight(1)
      case v                                            => v

  /** `c.strip()` — Python's str.strip. */
  private def stripped(s: String): String = s.trim

  /**
   * `line.strip().strip("|").split("|")` then strip each cell — the
   * predecessor's cell split. Keeps empty interior cells.
   */
  def cellsOf(line: String): List[String] =
    val s: String = stripped(line).replaceAll("^\\|+", "").replaceAll("\\|+$", "")
    s.split("\\|", -1).toList.map(stripped)

  /** Every cell in a separator row is `:?-{2,}:?` — the markdown rule row. */
  def isSeparator(cells: List[String]): Boolean =
    cells.forall((c: String) => c.isEmpty || SeparatorCell.matches(c))

  /**
   * The marker first-cells — the table's vocabulary for "nothing here",
   * or a comment row. Markers are NOT data rows: they are not read and
   * never appear in `unlinkable`.
   */
  def isMarkerCell(cell: String): Boolean =
    val c: String = cell.trim
    c.isEmpty || c == "(none)" || c == "—" || c == "-" || c.contains("<!--")

  /** One data row with its 1-based source line and raw text. */
  final case class RawRow(line: Int, text: String, cells: List[String])

  /**
   * `table_rows(lines, start)` — consume the first contiguous `|` block
   * at/after `start` (0-based index into `lines`): non-`|` lines are
   * skipped until the first row, then end the table; separator rows are
   * dropped; the header (first data row) is dropped.
   */
  def tableRows(lines: Vector[String], start: Int): List[RawRow] =
    def loop(i: Int, acc: List[RawRow]): List[RawRow] =
      if i >= lines.length then acc.reverse
      else
        val s: String = lines(i).trim
        if !s.startsWith("|") then
          if acc.nonEmpty then acc.reverse else loop(i + 1, acc)
        else
          val cells: List[String] = cellsOf(s)
          if isSeparator(cells) then loop(i + 1, acc)
          else loop(i + 1, RawRow(i + 1, s, cells) :: acc)
    loop(start, Nil) match
      case _ :: rest => rest // drop the header row — danger-scan:allow predecessor parity, the first row is always the column header
      case Nil       => Nil

  /**
   * `section_bounds(lines, heading_re)` — the line index of the first
   * line matching `heading` and the index of the next `## ` line (or
   * end of file). `###`/`####` headings do NOT end a section — the
   * predecessor's two-hash rule.
   */
  def sectionBounds(lines: Vector[String], heading: Regex): Option[(Int, Int)] =
    lines.indexWhere((l: String) => heading.pattern.matcher(l).lookingAt()) match
      case -1 => None
      case i  =>
        val end: Int = lines.indexWhere((l: String) => l.startsWith("## "), i + 1) match
          case -1 => lines.length
          case j  => j
        Some((i, end))

  /**
   * `first_symbol(cell)` — markdown link text when capitalised, else the
   * first backticked token when capitalised, else a leading capitalised
   * word (optionally `Concept/action`). `None` when none parse.
   */
  def firstSymbol(cell: String): Option[String] =
    MdLink.findFirstMatchIn(cell) match
      case Some(m) if m.group(1).take(1).exists(_.isUpper) => Some(m.group(1))
      case _ => // danger-scan:allow transparent — no capitalised link falls through to the backtick/bare arms
        Backtick.findFirstMatchIn(cell) match
          case Some(m) =>
            val tok: String = m.group(1).trim
            if tok.take(1).exists(_.isUpper) then Some(tok)
            else bareSymbol(cell)
          case None => bareSymbol(cell)

  private def bareSymbol(cell: String): Option[String] =
    val rx: Regex = "\\s*([A-Z][A-Za-z0-9]*(?:/[A-Za-z][A-Za-z0-9]*)?)".r
    rx.findPrefixMatchOf(cell).map((m: scala.util.matching.Regex.Match) => m.group(1))

  /** `re.match(rx, line)` — an anchored-at-start match. */
  def anchored(rx: Regex, line: String): Boolean =
    rx.findPrefixOf(line).nonEmpty
