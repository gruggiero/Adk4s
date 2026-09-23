package org.sinemenda.probatio.core

import scala.util.matching.Regex

/**
 * One row of a concept file's `## Implementation map` table — the row's
 * 1-based `line` in the source file, its raw `text`, and the `citations`
 * extracted from it: the backticked tokens containing both `/` and `.`
 * (the predecessor's path-shape test). Rows whose citations are empty
 * name no code path — they are read rows that bind nothing; whether they
 * are silently ignored or reported is decided at build time, where the
 * resolution predicate lives.
 *
 * spec: graph-tool-port — Concepts Introduced (new): ConceptRegistryDoc
 */
final case class ImplMapRow(
  line: Int,
  text: String,
  citations: List[String]
)

/**
 * The parsed behavioural-registry file: the declared `concept`, its
 * `actions` and `syncs` (from the fenced blocks the predecessor scans),
 * and every `## Implementation map` table `row` — including rows that
 * will not bind, so that nothing the parser read can be dropped without
 * a trace.
 *
 * `unlinkable` carries the rows rejected at parse time (malformed table
 * rows the predecessor would skip); the builder appends binding-time
 * failures (citations that resolve nowhere) to the same graph-level set.
 *
 * `parse` returns `Left` when the file declares no concept — the
 * predecessor's "concept file declares no concept" warning path, lifted
 * to a typed result so the caller decides whether it is a warning or a
 * could-not-determine.
 *
 * spec: graph-tool-port — Concepts Introduced (new): ConceptRegistryDoc
 * spec: graph-tool-port — Requirement: A row that cannot be bound is reported, never dropped
 */
final case class ConceptRegistryDoc(
  file: String,
  concept: String,
  actions: List[String],
  syncs: List[String],
  implMapRows: List[ImplMapRow],
  unlinkable: List[UnlinkableRow]
)

object ConceptRegistryDoc:

  private val ConceptDecl1: Regex =
    "^#+ Concept: ([A-Za-z][A-Za-z0-9]*)".r
  private val ConceptDecl2: Regex =
    "^concept ([A-Za-z][A-Za-z0-9]*)".r
  private val ActionsBlock: Regex = "^actions\\s*$".r
  private val ActionsEnd: Regex =
    "^(operational principle|synchronizations|state|purpose)".r
  private val ActionName: Regex = "^\\s{0,8}([a-z][A-Za-z0-9]*)\\s*\\[".r
  private val SyncSection: Regex = "^\\s*(synchronizations|## Synchronizations)".r
  private val SyncName: Regex = "^\\s*(?:sync\\s+)?([A-Z][A-Za-z0-9]*):?\\s*$".r
  private val ImplMapHeading: Regex = "^## Implementation map".r

  /**
   * Parses one registry file's text — pure; the adapter reads the file.
   * `file` is the repository-relative path used in diagnostics and the
   * concept node's `file` attribute.
   *
   * `Left` when the file declares no concept — the predecessor's
   * "concept file declares no concept" warning path.
   */
  def parse(file: String, text: String): Either[String, ConceptRegistryDoc] =
    val lines: Vector[String] = GraphParse.linesOf(text)
    val names: List[String] = lines.toList.flatMap { (line: String) =>
      ConceptDecl1.findFirstMatchIn(line)
        .orElse(ConceptDecl2.findFirstMatchIn(line))
        .map((m: Regex.Match) => m.group(1))
    }
    names match
      case Nil =>
        val base: String = file.substring(file.lastIndexOf('/') + 1)
        Left(s"concept file declares no concept: $base")
      case primary :: _ =>
        // actions block — between a bare `actions` line and the next
        // section keyword or fence, lines are `name[`
        val actions: List[String] =
          lines.foldLeft((false, List.empty[String])) {
            case ((inBlock: Boolean, acc: List[String]), line: String) =>
              if ActionsBlock.findPrefixOf(line).nonEmpty then (true, acc)
              else if inBlock && (ActionsEnd.findPrefixOf(line).nonEmpty || line.startsWith("```")) then
                (false, acc)
              else if inBlock then
                ActionName.findFirstMatchIn(line) match
                  case Some(m) => (true, acc :+ m.group(1))
                  case None    => (true, acc)
              else (inBlock, acc)
          }._2

        // synchronizations — every `synchronizations` / `## Synchronizations`
        // heading opens a 60-line scan window ended by the next `## ` heading
        val syncs: List[String] =
          lines.indices.toList.flatMap { (i: Int) =>
            if !GraphParse.anchored(SyncSection, lines(i)) then Nil
            else
              lines.slice(i + 1, math.min(lines.length, i + 60)).toList
                .takeWhile((l: String) => !(l.startsWith("## ") && !l.contains("Synchron")))
                .flatMap((l: String) => SyncName.findFirstMatchIn(l).map(_.group(1)))
          }

        // implementation map — the data rows under `## Implementation map`
        val (rows: List[ImplMapRow], unlinkable: List[UnlinkableRow]) =
          GraphParse.sectionBounds(lines, ImplMapHeading) match
            case None => (Nil, Nil)
            case Some((start, _)) =>
              // Every data row is scanned — the predecessor exempts no
              // impl-map cell (a `<!--` row still contributes citations).
              GraphParse.tableRows(lines, start + 1)
                .foldLeft((List.empty[ImplMapRow], List.empty[UnlinkableRow])) {
                  case ((rs: List[ImplMapRow], us: List[UnlinkableRow]), row: GraphParse.RawRow) =>
                    val citations: List[String] =
                      row.cells.drop(1).flatMap { (cell: String) =>
                        GraphParse.backticks(cell)
                          .filter((tok: String) => tok.contains("/") && tok.contains(".") && !tok.contains(" "))
                      }
                    if citations.isEmpty then
                      (rs, us :+ UnlinkableRow(
                        file,
                        row.line,
                        row.text,
                        UnlinkableReason.stated("row binds nothing: no path-shaped (`a/b.c`) citation")
                      ))
                    else (rs :+ ImplMapRow(row.line, row.text, citations), us)
                }
        Right(ConceptRegistryDoc(file, primary, actions, syncs, rows, unlinkable))
