package org.sinemenda.probatio.core

/**
 * The unported-tool register's checks (spec 11 of
 * `repair-probatio-cutover`).
 *
 * Every executable tool in the workflow's tool directories is classified
 * into exactly one of `ToolSurfaceClassification.Ported` or
 * `ToolSurfaceClassification.Registered`. The classification is pure:
 * directory listings and the set of present documents arrive already
 * read, so the core never touches the filesystem (No-I/O-in-core).
 *
 * A `*.predecessor.bak` file is NOT a tool: it is the recorded revert
 * target of a swapped seam, already tracked by the migration protocol's
 * `ShimSwap` records — `isRevertTarget` keeps it out of the tool domain
 * so the totality check measures tools, not backups.
 *
 * spec: unported-tool-register — Requirement: Every tool in the tree is either on the ported surface or in the register
 * spec: unported-tool-register — Requirement: Every register entry states what blocks its port
 * spec: unported-tool-register — Requirement: A registered tool's citations resolve
 * spec: unported-tool-register — Requirement: A tool that becomes ported leaves the register
 */
object UnportedToolRegister:

  /**
   * The workflow's tool directories, relative to the repository root —
   * the directories whose executable tools the classification must
   * account for.
   */
  def toolDirectories: List[String] = List(
    "openspec/schemas/verified-scala3/scanner",
    "openspec/schemas/verified-scala3/hooks"
  )

  /**
   * What one tool directory yielded when read. The directory listing is
   * an adapter product: `Read` carries the directory's entry names;
   * `Unreadable` carries the directory and nothing else, so a directory
   * that could not be read cannot contribute tools — or silence — to the
   * classification.
   *
   * spec: unported-tool-register — Scenario: Error path — an unreadable tool directory is could-not-determine
   */
  enum DirListing:
    case Read(directory: String, entries: List[String])
    case Unreadable(directory: String)

  /**
   * The outcome of classifying a set of tool paths.
   *
   * `classified` pairs each tool path with its two-variant
   * classification; `unclassified` names every tool in NEITHER the
   * ported surface nor the register; `doublyClassified` names every
   * tool in BOTH; `unresolvedCitations` pairs each register entry with
   * the citation that names no present document. A clean report has all
   * three problem lists empty.
   *
   * spec: unported-tool-register — Property: classification-is-total-and-exclusive
   */
  final case class Report(
    classified: List[(String, ToolSurfaceClassification)],
    unclassified: List[String],
    doublyClassified: List[String],
    unresolvedCitations: List[(UnportedTool, String)]
  ):
    /** True iff no tool escaped classification, none is doubly classified, and every citation resolves. */
    def isClean: Boolean =
      unclassified.isEmpty && doublyClassified.isEmpty && unresolvedCitations.isEmpty

  /**
   * True iff `fileName` is a `*.predecessor.bak` revert target of a
   * swapped seam — recorded by the swap protocol, not a tool to
   * classify.
   */
  def isRevertTarget(fileName: String): Boolean =
    fileName.endsWith(".predecessor.bak")

  /**
   * Classify every tool path against the ported surface and the
   * register. A path in the surface is `Ported(its subcommand name)`; a
   * path in the register is `Registered(its entry)`; a path in both is
   * doubly classified; a path in neither is unclassified.
   *
   * `portedSurface` maps each ported tool's predecessor path to its
   * subcommand CLI name; the adapter derives it from `Subcommand` plus
   * the known script names (the mapping is not nominal —
   * `openspec-graph.py` is `graph`).
   *
   * spec: unported-tool-register — Scenario: Adversarial — a tool in neither is reported
   * spec: unported-tool-register — Scenario: Adversarial — a tool both ported and registered is reported
   */
  def classifyAll(
    toolPaths: List[String],
    portedSurface: Map[String, String],
    register: List[UnportedTool],
    presentDocuments: Set[String]
  ): Report =
    val registerByPath: Map[String, UnportedTool] =
      register.map((t: UnportedTool) => t.path -> t).toMap
    val tools: List[String] = toolPaths.filterNot(isRevertTarget)
    val classified: List[(String, ToolSurfaceClassification)] =
      tools.flatMap { (path: String) =>
        // A path on the ported surface classifies Ported — a register
        // entry for it is the anomaly, reported via doublyClassified.
        portedSurface.get(path) match
          case Some(subcommand) =>
            Some(path -> ToolSurfaceClassification.Ported(subcommand))
          case None =>
            registerByPath.get(path).map(t => path -> ToolSurfaceClassification.Registered(t))
      }
    Report(
      classified = classified,
      unclassified = tools.filter((p: String) => !portedSurface.contains(p) && !registerByPath.contains(p)),
      doublyClassified = tools.filter((p: String) => portedSurface.contains(p) && registerByPath.contains(p)),
      unresolvedCitations = unresolvedCitations(register, presentDocuments)
    )

  /**
   * Every register entry paired with a citation that names no present
   * document.
   *
   * spec: unported-tool-register — Scenario: Adversarial — a citation naming an absent document is reported
   */
  def unresolvedCitations(
    register: List[UnportedTool],
    presentDocuments: Set[String]
  ): List[(UnportedTool, String)] =
    register.flatMap { (tool: UnportedTool) =>
      tool.citedBy
        .filterNot(presentDocuments.contains)
        .map((citation: String) => tool -> citation)
    }

  /**
   * The register check's three-way verdict over the tool directories.
   *
   * An `Unreadable` listing is `Undetermined` naming the directory — no
   * tool is reported unclassified on the strength of an unread
   * directory. Otherwise the report is `Ran` when clean and `Finding`
   * naming every unclassified tool, every doubly classified tool, and
   * every unresolved citation when it is not.
   *
   * spec: unported-tool-register — Scenario: Happy path — every present tool classifies
   * spec: unported-tool-register — Scenario: Error path — an unreadable tool directory is could-not-determine
   */
  def checkSurface(
    listings: List[DirListing],
    portedSurface: Map[String, String],
    register: List[UnportedTool],
    presentDocuments: Set[String]
  ): Outcome[Report] =
    val unreadable: List[String] = listings.collect { case DirListing.Unreadable(directory) => directory }
    if unreadable.nonEmpty then
      Outcome.Undetermined(
        s"unported-tool-register: unreadable tool directories: ${unreadable.mkString(", ")}"
      )
    else
      val toolPaths: List[String] = listings.flatMap {
        case DirListing.Read(directory, entries) =>
          entries.map((e: String) => s"$directory/$e")
        case DirListing.Unreadable(_) => List.empty
      }
      val report: Report = classifyAll(toolPaths, portedSurface, register, presentDocuments)
      if report.isClean then Outcome.Ran(report)
      else Outcome.Finding(describeProblems(report))

  /** The finding description names every problem — unclassified tools, doubly classified tools, unresolved citations. */
  private def describeProblems(report: Report): String =
    val problems: List[String] = List(
      Option.when(report.unclassified.nonEmpty)(
        s"unclassified tools: ${report.unclassified.mkString(", ")}"
      ),
      Option.when(report.doublyClassified.nonEmpty)(
        s"doubly classified tools: ${report.doublyClassified.mkString(", ")}"
      ),
      Option.when(report.unresolvedCitations.nonEmpty)(
        s"unresolved citations: ${report.unresolvedCitations
            .map { case (t: UnportedTool, c: String) => s"${t.name} cites $c" }
            .mkString(", ")}"
      )
    ).flatten
    s"unported-tool-register: ${problems.mkString("; ")}"

  /**
   * Parse the committed register document (markdown) into entries. The
   * blocker column is the closed `PortBlocker` set — a row whose blocker
   * does not parse is a `Left`, never a defaulted entry.
   *
   * spec: unported-tool-register — Property: every-entry-carries-a-blocker
   */
  def parseRegister(document: String): Either[String, List[UnportedTool]] =
    val rows: List[List[String]] = document.linesIterator.toList
      .map((line: String) => line.trim)
      .filter((line: String) => line.startsWith("|") && line.endsWith("|"))
      .map((line: String) => line.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toList)
      .filter(_.length >= 4)
      .filterNot(isSeparatorRow)
      .filterNot(isHeaderRow)
    rows.foldRight[Either[String, List[UnportedTool]]](Right(List.empty)) {
      (cells: List[String], acc: Either[String, List[UnportedTool]]) =>
        for
          rest <- acc
          tool <- parseRow(cells)
        yield tool :: rest
    }

  /**
   * Render register entries to the register document's row format —
   * `parseRegister` and `renderRegister` round-trip.
   */
  def renderRegister(entries: List[UnportedTool]): String =
    val header: String =
      "| Tool | Path | Blocker | Cited by |\n|------|------|---------|----------|"
    (header :: entries.map(renderRow)).mkString("\n") + "\n"

  /** A `| --- | --- |` separator row: every cell is dashes and colons. */
  private def isSeparatorRow(cells: List[String]): Boolean =
    cells.nonEmpty && cells.forall((c: String) =>
      c.nonEmpty && c.forall((ch: Char) => ch == '-' || ch == ':' || ch == ' ')
    )

  /** The register document's header row: the first two cells name the Tool and Path columns. */
  private def isHeaderRow(cells: List[String]): Boolean =
    cells.take(2).map((c: String) => c.toLowerCase) == List("tool", "path")

  private def parseRow(cells: List[String]): Either[String, UnportedTool] =
    cells match
      case name :: path :: blockerText :: citedText :: _ =>
        parseBlocker(blockerText).map { (blocker: PortBlocker) =>
          UnportedTool(
            name = name,
            path = path,
            blocker = blocker,
            citedBy = citedText
              .split(",")
              .toList
              .map(_.trim)
              .filter(_.nonEmpty)
          )
        }
      case _ =>
        // unreachable — rows are filtered to >= 4 cells; kept for
        // exhaustiveness rather than a partial destructure
        Left(s"malformed register row: ${cells.mkString("|")}")

  /**
   * The blocker column parses into the closed `PortBlocker` set or the
   * parse fails — a free-text reason is a `Left`, never a defaulted
   * entry.
   */
  private def parseBlocker(text: String): Either[String, PortBlocker] =
    if text == "NotOnEnforcementPath" then Right(PortBlocker.NotOnEnforcementPath)
    else if text.startsWith("GatedOnSpike(") && text.endsWith(")") then
      val spike: String = text.stripPrefix("GatedOnSpike(").stripSuffix(")")
      Either.cond(spike.nonEmpty, PortBlocker.GatedOnSpike(spike), s"empty spike name: $text")
    else if text.startsWith("SupersededByPortedTool(") && text.endsWith(")") then
      val sub: String = text.stripPrefix("SupersededByPortedTool(").stripSuffix(")")
      Either.cond(sub.nonEmpty, PortBlocker.SupersededByPortedTool(sub), s"empty subcommand: $text")
    else Left(s"blocker outside the closed set: $text")

  private def renderRow(tool: UnportedTool): String =
    s"| ${tool.name} | ${tool.path} | ${renderBlocker(tool.blocker)} | ${tool.citedBy.mkString(", ")} |"

  private def renderBlocker(blocker: PortBlocker): String =
    blocker match
      case PortBlocker.GatedOnSpike(spike)         => s"GatedOnSpike($spike)"
      case PortBlocker.NotOnEnforcementPath        => "NotOnEnforcementPath"
      case PortBlocker.SupersededByPortedTool(sub) => s"SupersededByPortedTool($sub)"

end UnportedToolRegister
