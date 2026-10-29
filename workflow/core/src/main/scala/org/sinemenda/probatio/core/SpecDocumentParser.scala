package org.sinemenda.probatio.core

/**
 * The structural scan — a faithful port of the predecessor's single awk
 * pass — producing a `SpecDocument`.
 *
 * Recognizes exactly the predecessor's block markers:
 * `### Requirement:`/`### Property:`/`### Temporal:`/`#### Scenario:`
 * headings, `## ` section headings (which flush open blocks), the
 * `## Proof Obligations` table rows, `## Concepts Used (behavioural)`,
 * and `## Formal Contracts` content lines. Pure and total: malformed
 * markdown simply yields fewer blocks.
 *
 * Two section flags track the Proof Obligations region because the
 * predecessor tracks it twice: the main scan's `in_po` is also cleared
 * by the three `### X:` block headings, while the artifact pass's `m`
 * is toggled only by `## ` headings.
 *
 * spec: spec-lint-engine — Requirement: The engine is a pure function over a parsed spec document and a repository-facts context
 */
object SpecDocumentParser:

  private val reqPrefix: String      = "### Requirement:"
  private val propPrefix: String     = "### Property:"
  private val tempPrefix: String     = "### Temporal:"
  private val scenPrefix: String     = "#### Scenario:"
  private val sectionPrefix: String  = "## "
  private val poPrefix: String       = "## Proof Obligations"
  private val fcPrefix: String       = "## Formal Contracts"
  private val conceptsPrefix: String = "## Concepts Used"

  /** `^\|[ \t:]*-` — a table separator row. */
  private val separatorRe = "^\\|[ \t:]*-".r

  /** `^\| *Obligation` — the obligation table's header row. */
  private val headerRe = "^\\| *Obligation".r

  /**
   * The chain-state script's separator row —
   * `^\|[ \t:-]*\|[ \t:-]*\|[ \t:-]*\|[ \t:-]*\|[ \t]*$` — four cells of
   * dashes/colons/blanks ending the line. Stricter about shape than
   * `separatorRe` but requiring NO dash: `|||||` sets the flag.
   */
  private val chainSeparatorRe = "^\\|[ \t:-]*\\|[ \t:-]*\\|[ \t:-]*\\|[ \t:-]*\\|[ \t]*$".r

  /**
   * `(^|[^A-Za-z])(SHALL|MUST)([^A-Za-z]|$)` — case-sensitive; a digit
   *  adjacent to the keyword still counts (`SHALL2`, `9MUST`).
   */
  private val normativeRe = "(^|[^A-Za-z])(SHALL|MUST)([^A-Za-z]|$)".r

  /** Negativity words — the title check adds `cannot`, the body check does not. */
  private val titleNegativeRe =
    "(^|[^A-Za-z0-9])(only|never|cannot)([^A-Za-z0-9]|$)".r
  private val bodyNegativeRe =
    "(^|[^A-Za-z0-9])(only|never)([^A-Za-z0-9]|$)".r

  /** `tolower($0) ~ /behaviou?ral/` on a `## Concepts Used` heading. */
  private def isBehaviouralConcepts(line: String): Boolean =
    line.startsWith(conceptsPrefix) &&
      line.toLowerCase.matches(".*behaviou?ral.*")

  /** A `^[ \t]*$` blank line — spaces and tabs only. */
  private def isBlank(line: String): Boolean =
    line.forall(c => c == ' ' || c == '\t')

  /** `^[ \t]*-->` — a comment-closing line (leading blanks allowed). */
  private def isCommentClose(line: String): Boolean =
    line.dropWhile(c => c == ' ' || c == '\t').startsWith("-->")

  /**
   * The predecessor's `substr($0, N)` title extraction: `N` is one past
   * the heading prefix plus its space; a heading that ends at the colon
   * yields the empty title.
   */
  private def headingTitle(line: String, prefix: String): String =
    if line.length > prefix.length then line.substring(prefix.length + 1).trim
    else ""

  /** Requirement-block accumulation state (the awk `req_*` arrays' per-block slice). */
  final private case class ReqAcc(
    title: String,
    line: Int,
    hasNorm: Boolean,
    seenGiven: Boolean,
    negative: Boolean,
    scenarioCount: Int,
    // Lowercased body lines before the first **Given**, in order — the
    // accumulated normative statement W5's claims_impossible reads.
    normText: List[String]
  )

  final private case class PropAcc(
    title: String,
    line: Int,
    hasGenerator: Boolean
  )

  final private case class TempAcc(
    title: String,
    line: Int,
    hasTrigger: Boolean,
    hasResponse: Boolean
  )

  final private case class Acc(
    requirements: List[RequirementBlock],
    properties: List[PropertyBlock],
    temporals: List[TemporalBlock],
    scenarios: List[ScenarioHeading],
    obligationRows: List[ObligationRow],
    artifactRows: List[ObligationRow],
    chainRows: List[ObligationRow],
    // The chain-state script's separator flag — sticky for the whole file:
    // once the first four-cell separator is seen inside a Proof
    // Obligations region it is never reset, so a second `## Proof
    // Obligations` section admits its pre-separator rows as data.
    chainSepSeen: Boolean,
    dataRowCount: Int,
    bridgeRowCount: Int,
    hasProofObligations: Boolean,
    formalContractsContentLines: Int,
    hasBehavioralConcepts: Boolean,
    openReq: Option[ReqAcc],
    openProp: Option[PropAcc],
    openTemp: Option[TempAcc],
    inPo: Boolean,
    inPoArtifacts: Boolean,
    inFc: Boolean
  )

  private object Acc:
    val empty: Acc = Acc(
      requirements = Nil,
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = Nil,
      artifactRows = Nil,
      chainRows = Nil,
      chainSepSeen = false,
      dataRowCount = 0,
      bridgeRowCount = 0,
      hasProofObligations = false,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      openReq = None,
      openProp = None,
      openTemp = None,
      inPo = false,
      inPoArtifacts = false,
      inFc = false
    )

  /**
   * `flush_req(); flush_prop(); flush_temp()` — close every open block at
   * `endLine`, the line of the heading that closed it (or `lines.size + 1`
   * at end of file).
   */
  private def flush(acc: Acc, endLine: Int): Acc =
    val reqs: List[RequirementBlock] = acc.openReq match
      case Some(r) =>
        acc.requirements :+ RequirementBlock(
          title = r.title,
          line = r.line,
          endLine = endLine,
          hasNormative = r.hasNorm,
          negative = r.negative,
          scenarioCount = r.scenarioCount,
          normativeText = r.normText.mkString(" ")
        )
      case None => acc.requirements
    val props: List[PropertyBlock] = acc.openProp match
      case Some(p) =>
        acc.properties :+ PropertyBlock(p.title, p.line, endLine, p.hasGenerator)
      case None => acc.properties
    val temps: List[TemporalBlock] = acc.openTemp match
      case Some(t) =>
        acc.temporals :+ TemporalBlock(t.title, t.line, endLine, t.hasTrigger, t.hasResponse)
      case None => acc.temporals
    acc.copy(
      requirements = reqs,
      properties = props,
      temporals = temps,
      openReq = None,
      openProp = None,
      openTemp = None
    )

  /**
   * The awk main-rule block — the `{ ... }` action every non-flush line
   * reaches (including `#### Scenario:` lines, which fall through).
   * `nr` is the 1-based line number stamped onto parsed rows.
   */
  private def generic(acc: Acc, line: String, nr: Int): Acc =
    val low: String = line.toLowerCase
    // The predecessor conflates "no open block" and "empty-title block"
    // (req_name == "" means both): an empty-title block's body is never
    // scanned, though the block still counts toward n_reqs.
    val withReq: Acc = acc.openReq match
      case Some(req) if req.title.nonEmpty =>
        // The predecessor sets seen-given first; a line carrying both
        // **Given** and SHALL does not count as normative, and the Given
        // line itself is not folded into the normative statement.
        val seen: Boolean = req.seenGiven || line.contains("**Given**")
        val updated: ReqAcc = req.copy(
          seenGiven = seen,
          hasNorm = req.hasNorm || (!seen && normativeRe.findFirstIn(line).nonEmpty),
          negative = req.negative ||
            bodyNegativeRe.findFirstIn(low).nonEmpty || low.contains("must not"),
          normText = if seen then req.normText else req.normText :+ low,
          scenarioCount =
            if line.startsWith(scenPrefix) then req.scenarioCount + 1
            else req.scenarioCount
        )
        acc.copy(openReq = Some(updated))
      case _ => // danger-scan:allow reject-to-unchanged — no open requirement, or an empty-title block whose body the predecessor never scans
        acc
    val withProp: Acc = withReq.openProp match
      case Some(p) if p.title.nonEmpty && line.contains("**Generator strategy**") =>
        withReq.copy(openProp = Some(p.copy(hasGenerator = true)))
      case _ => // danger-scan:allow reject-to-unchanged — no open property block, an empty-title one, or no generator marker
        withReq
    val withTemp: Acc = withProp.openTemp match
      case Some(t) if t.title.nonEmpty =>
        withProp.copy(
          openTemp = Some(
            t.copy(
              hasTrigger = t.hasTrigger || line.contains("**Trigger event**"),
              hasResponse = t.hasResponse || line.contains("**Response event**")
            )
          )
        )
      case _ => // danger-scan:allow reject-to-unchanged — no open temporal block, or an empty-title one the predecessor never scans
        withProp
    val withFc: Acc =
      if withTemp.inFc && !isBlank(line) &&
        !line.startsWith("<!--") && !isCommentClose(line)
      then withTemp.copy(formalContractsContentLines = withTemp.formalContractsContentLines + 1)
      else withTemp
    val dataRow: Boolean =
      line.startsWith("|") &&
        separatorRe.findFirstIn(line).isEmpty &&
        headerRe.findFirstIn(line).isEmpty
    val withPo: Acc =
      if withFc.inPo && dataRow then
        val fields: Array[String] = line.split("\\|", -1)
        val nf: Int               = fields.length
        val src: String           = fields(2).trim
        val base: Acc = withFc.copy(
          dataRowCount = withFc.dataRowCount + 1,
          bridgeRowCount =
            if low.contains("bridge") || low.contains("parity") then withFc.bridgeRowCount + 1
            else withFc.bridgeRowCount
        )
        // check_source's early returns: fewer than four fields, or an
        // empty / comment Source cell, never reach the source check.
        if nf >= 4 && src.nonEmpty && !src.startsWith("<!--") then
          base.copy(
            obligationRows = base.obligationRows :+ ObligationRow(
              line = nr,
              fieldCount = nf,
              source = src,
              enforcement = if nf >= 5 then fields(3).trim else "",
              artifact = if nf >= 5 then fields(4).trim else "",
              raw = line
            )
          )
        else base
      else withFc
    val withArt: Acc =
      if withPo.inPoArtifacts && dataRow then
        val fields: Array[String] = line.split("\\|", -1)
        val nf: Int               = fields.length
        if nf >= 5 then
          withPo.copy(
            artifactRows = withPo.artifactRows :+ ObligationRow(
              line = nr,
              fieldCount = nf,
              source = fields(2).trim,
              enforcement = fields(3).trim,
              artifact = fields(4).trim,
              raw = line
            )
          )
        else withPo
      else withPo
    // The chain-state script's OWN row set (chain-state.sh's awk, not
    // spec-lint's): `m` is `inPoArtifacts` (toggled by `## ` headings
    // only); the separator pattern sets a sticky flag and is never a row;
    // a `|` row after the separator is a chain row iff its Obligation
    // cell ($2) is non-empty — the Source cell is never tested, and the
    // `^\| *Obligation` content exclusion does not apply.
    val withChain: Acc =
      if withArt.inPoArtifacts && chainSeparatorRe.findFirstIn(line).nonEmpty then withArt.copy(chainSepSeen = true)
      else if withArt.inPoArtifacts && withArt.chainSepSeen && line.startsWith("|") then
        val fields: Array[String] = line.split("\\|", -1)
        val ob: String            = fields.lift(1).map(_.trim).getOrElse("")
        if ob.nonEmpty then
          withArt.copy(
            chainRows = withArt.chainRows :+ ObligationRow(
              line = nr,
              fieldCount = fields.length,
              source = fields.lift(2).map(_.trim).getOrElse(""),
              enforcement = fields.lift(3).map(_.trim).getOrElse(""),
              artifact = fields.lift(4).map(_.trim).getOrElse(""),
              raw = line
            )
          )
        else withArt
      else withArt
    withChain

  /** One line of the awk pass. `nr` is the 1-based line number. */
  private def step(acc: Acc, line: String, nr: Int): Acc =
    if line.startsWith(reqPrefix) then
      val flushed: Acc     = flush(acc, nr)
      val title: String    = headingTitle(line, reqPrefix)
      val lowTitle: String = title.toLowerCase
      flushed.copy(
        openReq = Some(
          ReqAcc(
            title = title,
            line = nr,
            hasNorm = false,
            seenGiven = false,
            negative = titleNegativeRe.findFirstIn(lowTitle).nonEmpty ||
              lowTitle.contains("must not"),
            scenarioCount = 0,
            normText = Nil
          )
        ),
        inPo = false
      )
    else if line.startsWith(propPrefix) then
      flush(acc, nr).copy(
        openProp = Some(PropAcc(headingTitle(line, propPrefix), nr, hasGenerator = false)),
        inPo = false
      )
    else if line.startsWith(tempPrefix) then
      flush(acc, nr).copy(
        openTemp = Some(
          TempAcc(headingTitle(line, tempPrefix), nr, hasTrigger = false, hasResponse = false)
        ),
        inPo = false
      )
    else if line.startsWith(scenPrefix) then
      // No flush and no `next` — the scenario heading also feeds the open
      // requirement's scenario count via the generic block.
      generic(
        acc.copy(
          scenarios = acc.scenarios :+ ScenarioHeading(headingTitle(line, scenPrefix), nr)
        ),
        line,
        nr
      )
    else if line.startsWith(sectionPrefix) then
      val flushed: Acc  = flush(acc, nr)
      val inPo: Boolean = line.startsWith(poPrefix)
      flushed.copy(
        inPo = inPo,
        inPoArtifacts = inPo,
        hasProofObligations = flushed.hasProofObligations || inPo,
        inFc = line.startsWith(fcPrefix),
        hasBehavioralConcepts = flushed.hasBehavioralConcepts || isBehaviouralConcepts(line)
      )
    else generic(acc, line, nr)

  /**
   * Parse `source` (the spec file's full text) into a `SpecDocument`.
   * `name` is the display path the findings block is headed with — the
   * predecessor's `spec-lint: <path>` — carried so the renderer can
   * frame the report without re-deriving it.
   */
  def parse(name: String, source: String): SpecDocument =
    val lines: Vector[String] = source.linesIterator.toVector
    val scanned: Acc =
      lines.zipWithIndex.foldLeft(Acc.empty) { case (acc, (line, i)) =>
        step(acc, line, i + 1)
      }
    // END: the predecessor flushes open blocks once more at end of file.
    val finalAcc: Acc = flush(scanned, lines.size + 1)
    SpecDocument(
      name = name,
      lines = lines,
      requirements = finalAcc.requirements,
      properties = finalAcc.properties,
      temporals = finalAcc.temporals,
      scenarios = finalAcc.scenarios,
      obligationRows = finalAcc.obligationRows,
      dataRowCount = finalAcc.dataRowCount,
      bridgeRowCount = finalAcc.bridgeRowCount,
      hasProofObligations = finalAcc.hasProofObligations,
      formalContractsContentLines = finalAcc.formalContractsContentLines,
      hasBehavioralConcepts = finalAcc.hasBehavioralConcepts,
      artifactRows = finalAcc.artifactRows,
      chainRows = finalAcc.chainRows
    )
end SpecDocumentParser
