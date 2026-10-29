package org.sinemenda.probatio.core

import scala.util.matching.Regex

/**
 * The named classes of dangerous construct the scan reports (spec 6).
 *
 * Eight cases — one per predecessor pattern. `label` is the predecessor's
 * `[label]` report token verbatim; parity with
 * `danger-scan.sh.predecessor.bak` is measured on (file, line, label)
 * triples. A new pattern class forces every match to be updated — the
 * enum is sealed and exhaustiveness is escalated to a compile error.
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerPattern
 * spec: danger-reconcile-engines — Compile-Negative: DangerPattern pattern match omitting a case
 */
enum DangerPattern:
  /** `.get` on Option/Either/Try (not `.get(...)` map lookups). // danger-scan:allow vocabulary-doc — the doc names the pattern class, it is not a call */
  case UnsafeGet

  /** `.head` / `.tail` / `.last` on collections. // danger-scan:allow vocabulary-doc — same */
  case UnsafeHead

  /** `case _ =>` / `case other =>` / `case _: T =>`. // danger-scan:allow vocabulary-doc — same */
  case CatchAll

  /** `asInstanceOf` / `@unchecked`. // danger-scan:allow vocabulary-doc — same */
  case Cast

  /** `Await.` / `Thread.sleep`. // danger-scan:allow vocabulary-doc — same */
  case Blocking

  /** `NonFatal` (the error may be discarded). // danger-scan:allow vocabulary-doc — same */
  case Swallowed

  /** "unreachable" / "cannot happen" / "should never" (case-insensitive). // danger-scan:allow vocabulary-doc — same */
  case UnreachableClaim // danger-scan:allow enum-case-name — the case names the claim class, it is not the claim
  /** `scalafix:off`. // danger-scan:allow vocabulary-doc — same */
  case LintOff

object DangerPattern:

  /** The predecessor's `[label]` report token for the pattern class. */
  def label(p: DangerPattern): String = p match
    case UnsafeGet        => "unsafe-get"
    case UnsafeHead       => "unsafe-head"
    case CatchAll         => "catch-all"
    case Cast             => "cast"
    case Blocking         => "blocking"
    case Swallowed        => "swallowed"
    case UnreachableClaim => "unreachable-claim" // danger-scan:allow vocabulary-label — the label token, not the claim
    case LintOff          => "lint-off"

/**
 * One occurrence a matcher found: the file (repo-relative path as the
 * caller reported it), the 1-based line number, the pattern class, the
 * raw matched line text, and whether the line carries a same-line
 * `// danger-scan:allow` justification.
 *
 * A justified occurrence is still an occurrence — it is excluded from the
 * report's `hits` and counted in `justifiedExcluded`, never silently
 * dropped (the predecessor's `grep -v 'danger-scan:allow'`).
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerHit
 */
final case class DangerHit(
  file: String,
  line: Int,
  pattern: DangerPattern,
  text: String,
  justified: Boolean
)

/**
 * The scan result: the occurrences reported (`hits`, each unjustified by
 * construction) and the count of occurrences excluded by justification.
 *
 * The primary constructor is private and `copy` is sealed: a report is
 * built only by `DangerReport.of`, which derives `hits` and
 * `justifiedExcluded` by partitioning the scan's own occurrence list. A
 * report whose summary and contents disagree — the shape a stubbed scan
 * produces — is therefore unrepresentable, not merely rejected.
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerReport
 * spec: danger-reconcile-engines — Compile-Negative: A DangerReport constructed with an empty hit list and a hit count greater than zero
 */
final case class DangerReport private (
  hits: List[DangerHit],
  justifiedExcluded: Int
):
  /**
   * The reported-hit count — derived from `hits`, so it can never
   * disagree with the report's contents.
   */
  def hitCount: Int = hits.length

  // A public `copy` would re-open the constructor's invariant. Declared
  // solely to suppress the compiler-generated public `copy`; it is
  // intentionally never invoked.
  @scala.annotation.nowarn("msg=unused private member")
  private def copy(
    hits: List[DangerHit] = hits,
    justifiedExcluded: Int = justifiedExcluded
  ): DangerReport = new DangerReport(hits, justifiedExcluded)

object DangerReport:

  /**
   * The only construction route: the report is derived from the scan's
   * raw occurrence list by partitioning on `justified`. The reported
   * count is `hits.length` by construction — a count that disagrees with
   * the contents cannot be written down.
   */
  def of(occurrences: List[DangerHit]): DangerReport =
    new DangerReport(
      hits = occurrences.filter((h: DangerHit) => !h.justified),
      justifiedExcluded = occurrences.count((h: DangerHit) => h.justified)
    )

/**
 * The pure dangerous-pattern scan (spec 6) — the mechanical half of
 * Ring 1.
 *
 * No file I/O, no subprocesses, no environment reads: in-scope files
 * arrive as `ScannedFile` values from `ChangedFilesReader` (the CLI
 * adapter), and the report is data. Which files are in scope — changed
 * production sources plus caller-named extras — is the reader's
 * decision; the engine scans exactly what it is given.
 *
 * Emission order mirrors the predecessor: files in input order; within a
 * file, one group per pattern in declaration order, matching lines in
 * line order — a line matching two patterns produces two hits.
 *
 * spec: danger-reconcile-engines — Requirement: The dangerous-pattern scan examines the production files changed since the baseline
 * spec: danger-reconcile-engines — Implementation Anchor: DangerScanEngine
 */
object DangerScanEngine:

  /** One in-scope file: its path (as reported) and its lines. */
  final case class ScannedFile(path: String, lines: List[String])

  /**
   * The predecessor's eight `grep -nE` patterns, in its fixed emission
   * order (one group per pattern per file, matching lines in line
   * order). `unreachable-claim` carries the predecessor's `-i` as an // danger-scan:allow vocabulary-doc — names the pattern class
   * inline flag. The ERE-to-Java translation is literal: `[[:space:]]`
   * is `\s`; every other construct is identical.
   *
   * These regex literals are the pattern vocabulary itself — a Ring 1
   * scan of this file flags them; each line carries its justification.
   */
  private val patternsInOrder: List[(DangerPattern, Regex)] = List(
    DangerPattern.UnsafeGet -> "\\.get([^A-Za-z0-9_(]|$)".r, // danger-scan:allow pattern-vocabulary — the ERE text is the scan's own definition, not a .get call
    DangerPattern.UnsafeHead -> "\\.(head|tail|last)([^A-Za-z0-9_]|$)".r, // danger-scan:allow pattern-vocabulary — same
    DangerPattern.CatchAll   -> "case\\s+(_|other)\\s*(:[^=]*)?=>".r,     // danger-scan:allow pattern-vocabulary — same
    DangerPattern.Cast       -> "asInstanceOf|@unchecked".r,              // danger-scan:allow pattern-vocabulary — same
    DangerPattern.Blocking   -> "Await\\.|Thread\\.sleep".r,              // danger-scan:allow pattern-vocabulary — same
    DangerPattern.Swallowed  -> "NonFatal".r,                             // danger-scan:allow pattern-vocabulary — same
    DangerPattern.UnreachableClaim -> "(?i)(unreachable|cannot happen|should never)".r, // danger-scan:allow pattern-vocabulary — same
    DangerPattern.LintOff -> "scalafix:off".r // danger-scan:allow pattern-vocabulary — same
  )

  /** The predecessor's `grep -v 'danger-scan:allow'` exclusion line. */
  private val justificationMarker: String = "danger-scan:allow"

  /**
   * The scope predicate: a changed path is a production file iff it
   * contains `/src/main/` — the predecessor's `grep '/src/main/'` over
   * `git diff --name-only` output, verbatim (a repo-root
   * `src/main/x.scala` has no leading slash and is excluded in both).
   * `ChangedFilesReader` applies it to diff output only — `--also`
   * files bypass it by construction.
   *
   * spec: danger-reconcile-engines — Scenario: Edge case — test files are not in scope unless named by the caller
   */
  def isProductionPath(path: String): Boolean = path.contains("/src/main/")

  /**
   * Every occurrence the eight matchers find on one line, in
   * pattern-declaration order, each carrying the line's justification
   * flag (the line contains `danger-scan:allow` anywhere). A line
   * matching a pattern contributes exactly one hit for it — the
   * predecessor's `grep -n` emits the line once per `scan` call.
   */
  def scanLine(path: String, lineNo: Int, line: String): List[DangerHit] =
    val justified: Boolean = line.contains(justificationMarker)
    patternsInOrder.flatMap { case (pattern, re) =>
      if re.findFirstIn(line).nonEmpty then List(DangerHit(path, lineNo, pattern, line, justified))
      else List.empty[DangerHit]
    }

  /**
   * Scan the in-scope files and derive the report. `hits` are the
   * unjustified occurrences; `justifiedExcluded` counts the rest.
   *
   * Emission is the predecessor's: files in input order, then one group
   * per pattern in declaration order, matching lines in line order —
   * the predecessor runs eight sequential `grep -nE` scans per file.
   */
  def scan(files: List[ScannedFile]): DangerReport =
    DangerReport.of(
      files.flatMap { (f: ScannedFile) =>
        val lineHits: List[DangerHit] =
          f.lines.zipWithIndex.flatMap { case (line, idx) =>
            scanLine(f.path, idx + 1, line)
          }
        patternsInOrder.flatMap { case (pattern, _) =>
          lineHits.filter((h: DangerHit) => h.pattern == pattern)
        }
      }
    )
