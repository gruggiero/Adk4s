package org.sinemenda.probatio.core

import scala.util.matching.Regex

/**
 * The resolved form of one part of an obligation's `Source` cell — the
 * input to the reachability (F6/F7) and existence (F8) checks.
 *
 *  - `ByTitle`      — the cell names a requirement title; carries the
 *                     index into `SpecDocument.requirements`
 *  - `ByOrdinal`    — `R<N>`/`Requirement N` resolved to the N-th
 *                     requirement; carries the index
 *  - `Typed`        — a non-requirement typed source (`Property: X`,
 *                     `Scenario: Y`, `Invariant`, `Design`, …) that is a
 *                     legitimate obligation source but covers no
 *                     requirement; existence of `Property`/`Scenario`
 *                     names is checked separately (F8)
 *  - `Unresolvable` — the cell (or part) resolves to nothing; an
 *                     out-of-range ordinal (`Requirement 9` in a
 *                     three-requirement spec) resolves here too,
 *                     carrying the ordinal fragment so the caller can
 *                     emit the predecessor's "cites Requirement N" form
 *
 * spec: spec-lint-engine — Concepts Introduced (new): ObligationSource
 */
enum ObligationSource:
  case ByTitle(requirementIndex: Int)
  case ByOrdinal(requirementIndex: Int)
  case Typed(kind: String, name: String)
  case Unresolvable(cell: String)

/**
 * The pure spec-lint engine — the F1–F10 checks and W1–W7 warnings as a
 * total function over a parsed `SpecDocument` and a `LintContext`.
 *
 * No file I/O, no environment reads, no subprocesses: the artifact check
 * (F9) receives `git ls-files` results through the injected
 * `artifactTracked` predicate, and applicability facts arrive as a
 * `LintContext` value. A check whose fact is `Unreadable` makes the run
 * `Outcome.Undetermined` — an unevaluated check is never folded into a
 * passing report.
 *
 * spec: spec-lint-engine — Requirement: The engine is a pure function over a parsed spec document and a repository-facts context
 * spec: spec-lint-engine — Requirement: Every obligation row is either resolved to a requirement or reported as unresolvable
 * spec: spec-lint-engine — Requirement: The engine emits exactly the predecessor's failure and warning identifiers for every fixture document
 */
object SpecLintEngine:

  // ── the predecessor's regexes, verbatim ──────────────────────────────

  /** `\*\*(Given|When|Then|And)\*\*` — a clause marker line. */
  private val clauseRe: Regex = "\\*\\*(Given|When|Then|And)\\*\\*".r

  /** Vague words: `(^|[^[:alnum:]])(valid|fast|reasonable|correct|appropriate)([^[:alnum:]]|$)`. */
  private val vagueRe: Regex =
    "(^|[^A-Za-z0-9])(valid|fast|reasonable|correct|appropriate)([^A-Za-z0-9]|$)".r

  /** `^R[0-9]+$`. */
  private val rOrdinalRe: Regex = "R[0-9]+".r

  /** `^(Property|Properties|Scenario|Scenarios)[ ]*:[ ]*` — a typed source part. */
  private val typedPartRe: Regex =
    "^(Property|Properties|Scenario|Scenarios)[ ]*:[ ]*".r

  /**
   * The legitimate non-requirement source grammar — the whole-cell check
   * `check_source` applies before the generic F6.
   */
  private val typedSourceRe: Regex =
    ("(^|[^A-Za-z])" +
      "(Property|Properties|Scenario|Scenarios|Invariant|Compile-Negative|" +
      "Temporal|Criterion|Type-Constraint|MUST-CONFIRM|Design|Non-goal)" +
      "[ ]*(:|[0-9])").r
  private val mustConfirmRe: Regex =
    "^(MUST-CONFIRM|Compile-Negative)([^A-Za-z]|$)".r

  /** W7 token shapes: build command, source file, fully-qualified name. */
  private val buildCommandRe: Regex = ".*[A-Za-z0-9]/(compile|test|Test).*".r
  private val fqnRe: Regex =
    "[a-z][a-z0-9]*(\\.[a-z][A-Za-z0-9_]*)+\\.[A-Z][\\s\\S]*".r
  private val identifierRe: Regex = "[A-Za-z_][A-Za-z0-9_.]*".r

  /** `is_strong(enf)` — ladder tier 1/2 enforcement mechanisms. */
  private val strongRe: Regex =
    ("type system|type-level|opaque|smart constructor|unrepresentable|" +
      "compile-negative|assertdoesnotcompile|compileerrors|exhaustiv|sealed").r

  /** `claims_impossible` — the line-joined normative statement's shapes. */
  private val impossibleRe: Regex =
    ("cannot be (constructed|created|built|expressed|represented)|" +
      "unrepresentable|" +
      "(must|shall) not be constructible|" +
      "impossible to (express|construct|represent)|" +
      "never be constructed").r

  /** The three `### X:` prefixes and `## ` — lines the per-line scans skip. */
  private def isFlushHeading(line: String): Boolean =
    line.startsWith("### Requirement:") ||
      line.startsWith("### Property:") ||
      line.startsWith("### Temporal:") ||
      line.startsWith("## ")

  // ── source-cell resolution ───────────────────────────────────────────

  /** The predecessor's typed-source legitimacy check over a whole cell. */
  private def typedLegitimate(src: String): Boolean =
    typedSourceRe.findFirstIn(src).nonEmpty ||
      mustConfirmRe.findFirstIn(src).nonEmpty

  /**
   * The requirement blocks visible to a proof-obligation row — the
   * predecessor's live `req_titles[1..n_reqs]` at the row's line: a row
   * resolves only against headings declared ABOVE it, so a Proof
   * Obligations section that precedes the requirements cannot satisfy
   * them. `requirements` is in line order, so the visible set is a
   * prefix and its indices are document indices.
   */
  private def visibleRequirements(document: SpecDocument, row: ObligationRow): List[RequirementBlock] =
    document.requirements.filter(_.line < row.line)

  /**
   * The F8 name-existence check — `named_exists`, bidirectional substring
   * against the headings declared above the row (the predecessor's live
   * `prop_titles`/`scen_titles` at the row's line).
   */
  private def namedExists(document: SpecDocument, row: ObligationRow, kind: String, name: String): Boolean =
    val low: String = name.toLowerCase.trim
    if low.length < 4 then true
    else if kind.startsWith("Propert") then
      document.properties.exists { p =>
        p.line < row.line && {
          val k: String = p.title.toLowerCase
          k.contains(low) || low.contains(k)
        }
      }
    else if kind.startsWith("Scenario") then
      document.scenarios.exists { s =>
        s.line < row.line && {
          val k: String = s.title.toLowerCase
          k.contains(low) || low.contains(k)
        }
      }
    else true

  /**
   * Resolve one obligation row's `Source` cell to its parts — the
   * predecessor's `check_source` partition: in-range ordinals and
   * requirement-title hits become `ByOrdinal`/`ByTitle`, out-of-range
   * ordinals become `Unresolvable` carrying the ordinal fragment, each
   * `Property:`/`Scenario:` part becomes `Typed`, and a cell that resolves
   * to nothing (and is not a legitimate typed source) is `Unresolvable`
   * carrying the whole cell.
   * Exposed for the parity and conservation properties.
   */
  def obligationSources(document: SpecDocument, row: ObligationRow): List[ObligationSource] =
    val src: String                     = row.source
    val visible: List[RequirementBlock] = visibleRequirements(document, row)
    val nReqs: Int                      = visible.length
    val low: String                     = src.toLowerCase
    // Ordinal references: `R<N>` and `Requirement N` tokens.
    val toks: List[String] = src.split("[^A-Za-z0-9]+", -1).toList
    val ordinals: List[ObligationSource] =
      toks.zipWithIndex.flatMap { case (t, i) =>
        val num: Option[Int] =
          if rOrdinalRe.matches(t) then t.drop(1).toIntOption
          else if t == "Requirement" && i + 1 < toks.length &&
            toks(i + 1).matches("[0-9]+")
          then toks(i + 1).toIntOption
          else None
        num match
          case Some(n) if n >= 1 && n <= nReqs =>
            List(ObligationSource.ByOrdinal(n - 1))
          case Some(n) if n > nReqs =>
            List(
              ObligationSource.Unresolvable(
                if t == "Requirement" then s"Requirement $n" else t
              )
            )
          case _ => // danger-scan:allow reject-to-Nil — a token that is not an ordinal reference contributes no ordinal source
            Nil
      }
    // Title references: the cell contains a requirement's (first-40-char,
    // lowercased) title — no `Requirement:` prefix is required.
    val titles: List[ObligationSource] =
      visible.zipWithIndex.flatMap { case (req, j) =>
        val key: String = req.title.take(40).toLowerCase
        if key.nonEmpty && low.contains(key) then List(ObligationSource.ByTitle(j))
        else Nil
      }
    // Typed parts: the cell split on " + ", each part that opens with a
    // Property/Scenario prefix becomes a Typed source.
    val typed: List[ObligationSource] =
      src.split(" \\+ ", -1).toList.flatMap { (rawPart: String) =>
        val part: String = rawPart.trim
        typedPartRe.findFirstMatchIn(part) match
          case Some(m) =>
            val kind: String = m.matched.replaceAll("[ :]+$", "")
            List(ObligationSource.Typed(kind, part.substring(m.end)))
          case None => Nil
      }
    val resolved: List[ObligationSource] = ordinals ++ titles ++ typed
    if resolved.isEmpty && !typedLegitimate(src) then List(ObligationSource.Unresolvable(src))
    else resolved

  // ── W5's impossibility/strength judgement helpers ────────────────────

  /** `claims_impossible(norm_text)` — space-collapsed lowercase prose. */
  private def claimsImpossible(normText: String): Boolean =
    val s: String = normText.replaceAll("[ \t]+", " ")
    impossibleRe.findFirstIn(s).nonEmpty

  /** `is_strong(enf)` — 0/1 plus the case-sensitive `tier-justified` override to 2. */
  private def enforcementStrength(enf: String): Int =
    val strong: Int = if strongRe.findFirstIn(enf.toLowerCase).nonEmpty then 1 else 0
    if enf.contains("tier-justified") then 2 else strong

  // ── W7's altitude token scan ─────────────────────────────────────────

  /**
   * `alt_scan` over one clause line: the backtick-quoted tokens at odd
   * split positions, classified by the four deterministic shapes plus the
   * inventory-membership test. `seen` is the document-wide dedup set.
   */
  private def w7Tokens(
    line: String,
    seen: Set[String],
    codeIds: Set[String]
  ): (List[String], Set[String]) =
    val parts: List[String] = line.split("`", -1).toList
    parts.zipWithIndex.foldLeft((List.empty[String], seen)) { case ((found, seenAcc), (tok, i)) =>
      if i % 2 == 0 || tok.isEmpty || seenAcc.contains(tok) then (found, seenAcc)
      else
        w7Message(tok, codeIds) match
          case Some(msg) => (found :+ msg, seenAcc + tok)
          case None      => (found, seenAcc)
    }

  /** One W7 token's warning message, or None when the token is prose. */
  private def w7Message(tok: String, codeIds: Set[String]): Option[String] =
    val belongs: String =
      s"inside a clause — belongs in ## Implementation Anchors (ALTITUDE)"
    if tok.startsWith("sbt ") || buildCommandRe.matches(tok) then Some(s"build command '$tok' $belongs")
    else if tok.endsWith(".scala") || tok.endsWith(".sbt") ||
      tok.endsWith(".smithy") || tok.endsWith(".java")
    then Some(s"source file '$tok' $belongs")
    else if fqnRe.matches(tok) then Some(s"fully-qualified name '$tok' $belongs")
    else if codeIds.nonEmpty then
      val base: String = tok.replaceAll("\\[.*", "")
      if identifierRe.matches(base) && codeIds.contains(base) then
        Some(
          s"'$tok' is in the type inventory but is not a registry concept — " +
            s"confirm it is domain vocabulary here, not a code identifier (ALTITUDE)"
        )
      else None
    else None

  // ── F9's artifact-token scan ─────────────────────────────────────────

  /** The backtick-quoted tokens of an artifact cell (odd split positions). */
  private def artifactTokens(cell: String): List[String] =
    cell.split("`", -1).toList.zipWithIndex.collect {
      case (tok, i) if i % 2 == 1 && tok.nonEmpty => tok
    }

  /**
   * The code-shape test: `${tok%.*}` strips one trailing extension, then
   * the glob alternation `*[A-Z]*Spec` / `*Test` / `*Suite` /
   * `*Properties` / `*TypeContract` / any path containing a slash.
   * Returns the stripped base for the `git ls-files "*base*"` lookup.
   */
  private def artifactBase(tok: String): Option[String] =
    if tok.exists(_.isWhitespace) then None
    else
      val base: String = tok.replaceAll("\\.[^.]*$", "")
      val codeShaped: Boolean =
        base.matches(".*[A-Z].*(Spec|Test|Suite|Properties|TypeContract)") ||
          base.contains("/")
      if codeShaped then Some(base) else None

  // ── the run ──────────────────────────────────────────────────────────

  /**
   * Run every applicable check over `document`.
   *
   * `checkArtifacts` is the `--artifacts` decision — there is no default;
   * a caller that omits it does not compile. `artifactTracked` is the
   * repository's tracked-file predicate (`git ls-files` membership),
   * consulted only when `checkArtifacts` is true.
   *
   * Returns `Outcome.Undetermined` when an applicability fact in
   * `context` is `Unreadable`; otherwise `Outcome.Ran` with the report —
   * lint findings (F-failures) are report content, not run failure.
   */
  def lint(
    document: SpecDocument,
    context: LintContext,
    checkArtifacts: Boolean,
    artifactTracked: String => Boolean
  ): Outcome[LintReport] =
    val unreadable: List[String] =
      List[FactRead[?]](
        context.schemaVersion,
        context.registry,
        context.registryConcepts,
        context.inventoryTypes,
        context.profile
      ).collect { case FactRead.Unreadable(reason) => reason }
    if unreadable.nonEmpty then
      Outcome.Undetermined(
        s"could not determine applicability — unreadable repository facts: " +
          unreadable.mkString("; ")
      )
    else Outcome.Ran(runLint(document, context, checkArtifacts, artifactTracked))

  private def runLint(
    document: SpecDocument,
    context: LintContext,
    checkArtifacts: Boolean,
    artifactTracked: String => Boolean
  ): LintReport =
    val nReqs: Int           = document.requirements.length
    val hasPo: Boolean       = document.hasProofObligations
    val hasRegistry: Boolean = context.hasRegistry
    val codeIds: Set[String] = context.codeIdentifiers

    // ── W7: the altitude scan — per non-heading line, deduped document-wide
    val w7s: List[(Int, Int, CheckOutcome)] =
      if !hasRegistry then Nil
      else
        val (found, _): (List[(Int, CheckOutcome)], Set[String]) =
          document.lines.zipWithIndex.foldLeft(
            (List.empty[(Int, CheckOutcome)], Set.empty[String])
          ) { case ((acc, seen), (line, i)) =>
            if isFlushHeading(line) || clauseRe.findFirstIn(line).isEmpty then (acc, seen)
            else
              val (msgs, seenNext): (List[String], Set[String]) = w7Tokens(line, seen, codeIds)
              (
                acc ++ msgs.map(m => (i + 1, CheckOutcome.Warn(LintWarning("W7", Some(i + 1), m)))),
                seenNext
              )
          }
        found.map { case (l, o) => (l, 0, o) }

    // ── W1: vague words inside requirement blocks (heading lines excluded)
    // An empty-title block's body is never scanned — the predecessor's
    // `req_name != ""` gate covers both "no open block" and "empty title".
    val w1s: List[(Int, Int, CheckOutcome)] =
      document.requirements.filter(_.title.nonEmpty).flatMap { req =>
        document.lines.zipWithIndex.slice(req.line, req.endLine - 1).flatMap { case (line, i) =>
          if vagueRe.findFirstIn(line.toLowerCase).nonEmpty then
            List(
              (
                i + 1,
                1,
                CheckOutcome.Warn(
                  LintWarning(
                    "W1",
                    Some(i + 1),
                    s"vague word in requirement \"${req.title}\": $line"
                  )
                )
              )
            )
          else Nil
        }
      }

    // ── the source check per obligation row: F6 ordinals, F8, coverage ──
    final case class RowScan(
      row: ObligationRow,
      sources: List[ObligationSource],
      covered: List[Int],
      findings: List[CheckOutcome]
    )
    val rowScans: List[RowScan] = document.obligationRows.map { row =>
      val sources: List[ObligationSource] = obligationSources(document, row)
      val covered: List[Int] = sources.collect {
        case ObligationSource.ByTitle(i)   => i
        case ObligationSource.ByOrdinal(i) => i
      }
      val f6s: List[CheckOutcome] = sources.collect { case ObligationSource.Unresolvable(frag) =>
        ordinalFragment(frag) match
          case Some(n) =>
            CheckOutcome.Fail(
              CheckId.F6,
              Some(row.line),
              s"Source cites Requirement $n but the spec has " +
                s"${visibleRequirements(document, row).length}"
            )
          case None =>
            CheckOutcome.Fail(
              CheckId.F6,
              Some(row.line),
              s"Source names no resolvable reference: $frag"
            )
      }
      val f8s: List[CheckOutcome] = sources.collect {
        case ObligationSource.Typed(kind, name) if !namedExists(document, row, kind, name) =>
          CheckOutcome.Fail(
            CheckId.F8,
            Some(row.line),
            s"Source cites $kind \"${name.take(52)}\" but no such heading exists in this spec"
          )
      }
      RowScan(row, sources, covered, f6s ++ f8s)
    }

    // ── coverage + W5 strength, accumulated per requirement index ─────
    val coveredIdx: Set[Int] = rowScans.flatMap(_.covered).toSet
    val strength: Map[Int, Int] =
      rowScans.foldLeft(Map.empty[Int, Int].withDefaultValue(0)) { (m, scan) =>
        scan.covered.foldLeft(m) { (mm, i) =>
          mm.updated(i, math.max(mm(i), enforcementStrength(scan.row.enforcement)))
        }
      }
    val ordinalRefs: Int =
      rowScans.map(_.sources).flatten.count {
        case ObligationSource.ByOrdinal(_) => true
        case _ => // danger-scan:allow reject-to-false — only ByOrdinal sources count toward the ordinal-reference total
          false
      }

    // ── block-flush findings, emitted at the closing heading's line ────
    // Empty-title blocks are flushed without checks — the predecessor's
    // `flush_*` early-return on `name == ""`. They still count toward
    // n_reqs and the F7 loop.
    val flushFindings: List[(Int, Int, CheckOutcome)] =
      document.requirements.filter(_.title.nonEmpty).flatMap { req =>
        val f1: Option[CheckOutcome] =
          if !req.hasNormative then
            Some(
              CheckOutcome.Fail(
                CheckId.F1,
                Some(req.line),
                s"requirement \"${req.title}\" has no SHALL/MUST before its first **Given**"
              )
            )
          else None
        val neg: Option[CheckOutcome] =
          if req.negative && req.scenarioCount == 0 then
            Some(
              CheckOutcome.Fail(
                CheckId.F2,
                Some(req.line),
                s"negative requirement \"${req.title}\" (only/never/must not) has no scenario at all"
              )
            )
          else if req.negative then
            Some(
              CheckOutcome.Warn(
                LintWarning(
                  "W3",
                  Some(req.line),
                  s"requirement \"${req.title}\" is negative — confirm at least one scenario input is forbidden by it"
                )
              )
            )
          else None
        List(f1, neg).flatten.map(o => (req.endLine, 3, o))
      } ++
        document.properties.filter(_.title.nonEmpty).flatMap { p =>
          if !p.hasGeneratorStrategy then
            List(
              (
                p.endLine,
                3,
                CheckOutcome.Fail(
                  CheckId.F3,
                  Some(p.line),
                  s"property \"${p.title}\" has no **Generator strategy** line"
                )
              )
            )
          else Nil
        } ++
        document.temporals.filter(_.title.nonEmpty).flatMap { t =>
          val trig: Option[CheckOutcome] =
            if !t.hasTriggerEvent then
              Some(
                CheckOutcome.Fail(
                  CheckId.F5,
                  Some(t.line),
                  s"temporal \"${t.title}\" has no **Trigger event** line"
                )
              )
            else None
          val resp: Option[CheckOutcome] =
            if !t.hasResponseEvent then
              Some(
                CheckOutcome.Fail(
                  CheckId.F5,
                  Some(t.line),
                  s"temporal \"${t.title}\" has no **Response event** line"
                )
              )
            else None
          List(trig, resp).flatten.map(o => (t.endLine, 3, o))
        }

    // ── the END-block findings, in predecessor order ────────────────────
    val endBlock: List[CheckOutcome] =
      val f4: List[CheckOutcome] =
        if nReqs > 0 && !hasPo then
          List(
            CheckOutcome.Fail(
              CheckId.F4,
              None,
              s"spec has $nReqs requirement(s) but no ## Proof Obligations section"
            )
          )
        else Nil
      val f10: List[CheckOutcome] =
        if hasRegistry && nReqs > 0 && !document.hasBehavioralConcepts then
          List(
            CheckOutcome.Fail(
              CheckId.F10,
              None,
              "a behavioural registry exists (openspec/concepts/) but this spec has no " +
                "\"## Concepts Used (behavioral)\" section — cite the concepts the " +
                "requirements touch, or state that they introduce new ones"
            )
          )
        else Nil
      val w2: List[CheckOutcome] =
        if hasPo && document.dataRowCount < nReqs then
          List(
            CheckOutcome.Warn(
              LintWarning(
                "W2",
                None,
                s"Proof Obligations has ${document.dataRowCount} data row(s) for $nReqs requirement(s)"
              )
            )
          )
        else Nil
      val f7s: List[CheckOutcome] =
        if hasPo then
          document.requirements.zipWithIndex.collect {
            case (req, i) if !coveredIdx.contains(i) =>
              CheckOutcome.Fail(
                CheckId.F7,
                Some(req.line),
                s"requirement \"${req.title}\" is named by NO proof obligation (unenforced)"
              )
          }
        else Nil
      val w6: List[CheckOutcome] =
        if document.formalContractsContentLines > 2 && hasPo && document.bridgeRowCount == 0
        then
          List(
            CheckOutcome.Warn(
              LintWarning(
                "W6",
                None,
                "spec declares Formal Contracts (Ring 6) but no obligation names a " +
                  "bridge/mirror artifact — a proof about a model nobody runs says " +
                  "nothing about the shipped code (templates/verified-mirror.md)"
              )
            )
          )
        else Nil
      val w4: List[CheckOutcome] =
        if ordinalRefs > 0 then
          List(
            CheckOutcome.Warn(
              LintWarning(
                "W4",
                None,
                s"$ordinalRefs obligation Source(s) reference requirements BY ORDINAL — " +
                  "reordering requirements silently re-points them; prefer " +
                  "\"Requirement: <exact title>\""
              )
            )
          )
        else Nil
      val w5s: List[CheckOutcome] =
        if hasPo then
          document.requirements.zipWithIndex.collect {
            case (req, i)
                if claimsImpossible(req.normativeText) &&
                  coveredIdx.contains(i) && strength(i) == 0 =>
              CheckOutcome.Warn(
                LintWarning(
                  "W5",
                  Some(req.line),
                  s"requirement \"${req.title}\" claims a state is impossible but is " +
                    "enforced only by tests — a type or smart constructor (ladder " +
                    "tier 1–2) can make it unrepresentable; otherwise write " +
                    "\"tier-justified: <why not>\" in the Enforcement cell"
                )
              )
          }
        else Nil
      f4 ++ f10 ++ w2 ++ f7s ++ w6 ++ w4 ++ w5s

    // ── F9: the artifact-existence pass, opt-in ─────────────────────────
    val f9s: List[CheckOutcome] =
      if !checkArtifacts then Nil
      else
        document.artifactRows.flatMap { row =>
          artifactTokens(row.artifact).flatMap { tok =>
            artifactBase(tok) match
              case Some(base) if !artifactTracked(base) =>
                List(
                  CheckOutcome.Fail(
                    CheckId.F9,
                    Some(row.line),
                    s"artifact '$tok' does not resolve to any tracked file"
                  )
                )
              case _ => // danger-scan:allow reject-to-Nil — a non-code-shaped or tracked token produces no F9
                Nil
          }
        }

    // ── assemble: line findings in (line, rank) order, then END, then F9 ──
    val perLine: List[(Int, Int, CheckOutcome)] =
      w7s ++ w1s ++
        rowScans.flatMap(s => s.findings.map(o => (s.row.line, 2, o))) ++
        flushFindings
    val findings: List[CheckOutcome] =
      perLine.sortBy { case (l, r, _) => (l, r) }.map(_._3) ++ endBlock ++ f9s

    // ── report data ─────────────────────────────────────────────────────
    val requirementRows: Map[String, List[ObligationRow]] =
      rowScans.foldLeft(Map.empty[String, List[ObligationRow]]) { (m, scan) =>
        scan.covered.distinct.foldLeft(m) { (mm, i) =>
          val title: String = document.requirements(i).title
          mm.updated(title, mm.getOrElse(title, Nil) :+ scan.row)
        }
      }
    // A row is resolved when it covers a requirement or is a legitimate
    // typed source; the remainder are the unresolvable (F6-reported) rows.
    // The partition is positional — duplicate row values cannot collapse.
    val (resolvedRows, unresolvableRows): (List[ObligationRow], List[ObligationRow]) =
      document.obligationRows.zip(rowScans).partitionMap { case (row, scan) =>
        if scan.covered.nonEmpty || typedLegitimate(row.source) then Left(row)
        else Right(row)
      }
    val f9RowLines: Set[Int] =
      f9s.collect { case CheckOutcome.Fail(CheckId.F9, Some(l), _) => l }.toSet
    val artifactUnresolved: Option[Set[String]] =
      if !checkArtifacts then None
      else
        Some(
          requirementRows.collect {
            case (title, rows) if rows.exists(r => f9RowLines.contains(r.line)) => title
          }.toSet
        )

    LintReport.fromRun(
      document,
      findings,
      applicability(context, hasRegistry),
      resolvedRows,
      unresolvableRows,
      requirementRows,
      artifactUnresolved
    )

  /** Re-extract the ordinal N from an Unresolvable fragment ("R9"/"Requirement 9"). */
  private def ordinalFragment(frag: String): Option[Int] =
    if rOrdinalRe.matches(frag) then frag.drop(1).toIntOption
    else
      frag match
        case s if s.startsWith("Requirement ") =>
          s.stripPrefix("Requirement ").toIntOption
        case _ => None // danger-scan:allow reject-to-None — a fragment in neither ordinal form yields no ordinal

  /**
   * The applicability record the report carries — which repository facts
   * each conditional check's applicability rested on.
   */
  private def applicability(
    context: LintContext,
    hasRegistry: Boolean
  ): Map[String, String] =
    val registryState: String = context.registry match
      case FactRead.Present(n)    => s"PRESENT ($n concepts)"
      case FactRead.Absent        => "ABSENT"
      case FactRead.Unreadable(_) => "UNREADABLE"
    val inventoryState: String = context.inventoryTypes match
      case FactRead.Present(ts)   => s"PRESENT (${ts.length} typed rows)"
      case FactRead.Absent        => "ABSENT"
      case FactRead.Unreadable(_) => "UNREADABLE"
    val profileState: String = context.profile match
      case FactRead.Present(Some(kit)) => s"PRESENT (kit: $kit)"
      case FactRead.Present(None)      => "PRESENT (no deterministic test kit)"
      case FactRead.Absent             => "ABSENT"
      case FactRead.Unreadable(_)      => "UNREADABLE"
    Map(
      "schema" -> (context.schemaVersion match
        case FactRead.Present(v)    => s"v$v"
        case FactRead.Absent        => "ABSENT"
        case FactRead.Unreadable(_) => "UNREADABLE"
      ),
      "behavioural registry" -> registryState,
      "check 17 ALTITUDE"    -> (if hasRegistry then "APPLIES" else "N/A (attested, not assumed)"),
      "type inventory"       -> inventoryState,
      "check 6" -> (context.inventoryTypes match
        case FactRead.Present(_) => "APPLIES"
        case _ => // danger-scan:allow reject-to-N/A — Absent inventory makes check 6 inapplicable
          "N/A"
      ),
      "capability profile" -> profileState
    )

  /**
   * The reachability fold — which requirement indices are unenforced and
   * how many rows resolved to no requirement.
   *
   * `rowTargets` carries, per evaluated row, the requirement indices it
   * resolved to (`-1` markers for rows that resolved to none). This is
   * the function `SpecLintKernel.reachabilityFold` mirrors under
   * Stainless and the `SpecLintBridgeSpec` law-checks against.
   *
   * spec: spec-lint-engine — Requirement: Unenforced requirements and unresolvable rows are computed by a single total fold over the obligation rows
   */
  def reachabilityFold(requirementCount: Int, rowTargets: List[Int]): (List[Int], Int) =
    val covered: Set[Int] = rowTargets.filter(_ >= 0).toSet
    val unenforced: List[Int] =
      (0 until requirementCount).toList.filterNot(covered.contains)
    (unenforced, rowTargets.count(_ < 0))

end SpecLintEngine
