package org.sinemenda.probatio.guard

import scala.annotation.unused

/**
 * The oracle guard's sanction decision (spec 5 of
 * `finish-probatio-replacement`, oracle-independence).
 *
 * Three decisions share this object, all pure:
 *
 *   - `classifyTest` — whether a test observes a tool's behaviour or
 *     asserts over its source text. Only behavioural tests belong to the
 *     acceptance oracle; a test that reads a tool's source is reported
 *     as structural, naming its file.
 *   - `sanctionVerdict` — the immutability guard's decision: pass
 *     exactly when every oracle modification since the recorded
 *     baseline has an accepted sanction; could-not-determine when the
 *     baseline, the history, or the record cannot be read. `Option`
 *     inputs model that readability boundary: `None` means the adapter
 *     could not produce the input, and the verdict is `Undeterminable`.
 *   - `writeSanctions` / `readSanctions` — the persisted sanction
 *     record's wire form; the Ring-4 contract is a lossless round-trip
 *     over requirement titles carrying quotes, em-dashes and non-ASCII
 *     characters.
 *
 * The cited-requirement rule is mechanical: a sanction is accepted only
 * when the cited spec's requirement block exists and its text names the
 * modified file (its basename) or one of that file's tests (a `@test`
 * title, verbatim). A citation that names neither is a rubber stamp and
 * does not cover the modification.
 *
 * spec: oracle-independence — Requirement: Every oracle modification cites the requirement that made it necessary
 * spec: oracle-independence — Requirement: The guard passes exactly when every modification is sanctioned
 */
object OracleSanctionGuard:

  /**
   * Whether the cited requirement's text names the modified oracle file
   * or a test in it: the file's basename appears verbatim, or one of the
   * file's `@test` titles does.
   *
   * spec: oracle-independence — Scenario: Happy path — a requirement that names the test sanctions its edit
   * spec: oracle-independence — Scenario: Adversarial — a sanction citing an unrelated requirement is rejected
   */
  def requirementNames(
    requirementText: String,
    file: String,
    testTitles: List[String]
  ): Boolean =
    val base: String = file.substring(file.lastIndexOf('/') + 1)
    requirementText.contains(base) ||
      testTitles.exists((t: String) => t.nonEmpty && requirementText.contains(t))

  /**
   * Check one sanction against the spec tree: `RejectedUnresolvable`
   * when the cited requirement does not exist in the cited spec,
   * `RejectedUnrelated` when it resolves but names neither the modified
   * file nor a test in it, `Accepted` otherwise. The rejection variants
   * carry the sanction so a rejection names the modification AND the
   * cited requirement.
   *
   * spec: oracle-independence — Scenario: Adversarial — a sanction citing an unrelated requirement is rejected
   * spec: oracle-independence — Scenario: Adversarial — a sanction citing a requirement that does not exist is rejected
   */
  def checkSanction(
    sanction: OracleSanction,
    requirementText: (String, String) => Option[String],
    testTitles: String => List[String]
  ): SanctionCheck =
    requirementText(sanction.spec, sanction.requirement) match
      case None => SanctionCheck.RejectedUnresolvable(sanction)
      case Some(text) =>
        if requirementNames(text, sanction.file, testTitles(sanction.file)) then
          SanctionCheck.Accepted(sanction)
        else SanctionCheck.RejectedUnrelated(sanction)

  /**
   * The subset of `history` covered by an accepted sanction: a
   * modification is covered iff the record holds a sanction for that
   * (file, commit) whose cited requirement resolves AND names the file
   * or a test in it.
   */
  def acceptedModifications(
    history: List[OracleModification],
    record: List[OracleSanction],
    requirementText: (String, String) => Option[String],
    testTitles: String => List[String]
  ): Set[OracleModification] =
    history
      .filter { (m: OracleModification) =>
        record.exists { (s: OracleSanction) =>
          s.file == m.file && s.commit == m.commit &&
            (checkSanction(s, requirementText, testTitles) match
              case SanctionCheck.Accepted(_) => true
              case _                         => false) // danger-scan:allow fail-closed — a rejected sanction never covers a modification
        }
      }
      .toSet

  /**
   * The guard's decision. `None` on any input means the adapter could
   * not read it — baseline unresolvable, history unreadable, record
   * unparseable — and the verdict is `Undeterminable` naming the input,
   * never a pass. Otherwise every uncovered modification is named.
   *
   * spec: oracle-independence — Scenario: Happy path — all modifications sanctioned passes
   * spec: oracle-independence — Scenario: Adversarial — one unsanctioned modification fails the guard
   * spec: oracle-independence — Scenario: Error path — an unreadable baseline is could-not-determine, not a pass
   */
  def sanctionVerdict(
    baseline: Option[OracleBaseline],
    history: Option[List[OracleModification]],
    record: Option[List[OracleSanction]],
    requirementText: (String, String) => Option[String],
    testTitles: String => List[String]
  ): SanctionVerdict =
    (baseline, history, record) match
      case (None, _, _) =>
        SanctionVerdict.Undeterminable(
          "the recorded baseline could not be read or does not resolve to a commit"
        )
      case (_, None, _) =>
        SanctionVerdict.Undeterminable(
          "the oracle's modification history could not be read"
        )
      case (_, _, None) =>
        SanctionVerdict.Undeterminable(
          "the sanction record could not be read or is malformed"
        )
      case (Some(b), Some(h), Some(r)) =>
        val accepted: Set[OracleModification] =
          acceptedModifications(h, r, requirementText, testTitles)
        val uncovered: List[OracleModification] =
          h.filterNot((m: OracleModification) => accepted.contains(m))
        if uncovered.isEmpty then SanctionVerdict.AllSanctioned(b)
        else SanctionVerdict.Unsanctioned(uncovered)

  /**
   * The oracle modifications strictly after `baseline` in `history`
   * (newest-first, `git log` order): every oracle file touched by every
   * entry more recent than the recorded baseline commit. `None` when the
   * recorded baseline is absent from the history — a baseline that does
   * not resolve makes the verdict could-not-determine upstream.
   * `isOracleFile` is the adapter's domain predicate (the tests
   * directory's top-level .bats files — not the shape suite, not the
   * record files).
   *
   * Entry messages are never inspected: a later commit whose message
   * contains the phrase the old guard searched for cannot move the
   * recorded baseline.
   *
   * spec: oracle-independence — Requirement: The baseline is recorded, not discovered
   * spec: oracle-independence — Scenario: Happy path — the recorded baseline is used
   * spec: oracle-independence — Scenario: Adversarial — a commit message containing the old search phrase does not move the baseline
   */
  def modificationsSince(
    baseline: OracleBaseline,
    history: List[HistoryEntry],
    isOracleFile: String => Boolean
  ): Option[List[OracleModification]] =
    val cut: Int = history.indexWhere((e: HistoryEntry) => e.commit == baseline.commit)
    if cut < 0 then None
    else
      Some(
        history.take(cut).flatMap { (e: HistoryEntry) =>
          e.files
            .filter(isOracleFile)
            .map((f: String) => OracleModification(f, e.commit))
        }
      )

  // ── Classification ────────────────────────────────────────────────────
  //
  // A test is STRUCTURAL when its body reads the text of a code-source
  // file in the repository under test — a read verb (grep, cat, sed, awk,
  // head, tail, diff, source) applied to a path ending in a code
  // extension (.scala, .sh, .py, .ts, .js), directly or through a
  // variable bound earlier, or a recursive read over the workflow/scanner
  // trees filtered to code files. Test-created files (under $FX, $BATS_*,
  // $TEST_*) and document/data files (.md, .yaml, .json, .html, .jq …)
  // are behavioural: the first is a prop the tool acts on, the second is
  // a spec-governed deliverable, not how a tool is written. `run`ning a
  // tool is behavioural — execution observes output and status, never
  // the source text.

  private val readCmd: scala.util.matching.Regex =
    "\\b(?:grep|cat|sed|awk|head|tail|diff|source)\\b".r
  private val codeExt: scala.util.matching.Regex =
    "\\.(scala|sh|py|ts|js)\\b".r
  private val docExt: scala.util.matching.Regex =
    "\\.(md|json|jsonl|yaml|yml|html|txt|log)\\b".r
  private val fixtureRef: scala.util.matching.Regex =
    "\\$FX|\\$BATS|TEST_TMPDIR|\\$TEST_|\\$FAKE".r
  private val binding: scala.util.matching.Regex =
    "([A-Za-z_]\\w*)=\"([^\"]*)\"|([A-Za-z_]\\w*)=(\\$[^\\s\"]+)".r
  private val token: scala.util.matching.Regex =
    "\"([^\"]+)\"|'([^']+)'|(\\S+)".r
  private val varRef: scala.util.matching.Regex =
    "\\$([A-Za-z_]\\w*)".r
  private val sourceTree: scala.util.matching.Regex =
    "/(?:workflow|scanner|hooks|bin|src|adapters)\\b".r
  private val recGrep: scala.util.matching.Regex =
    "\\bgrep\\s+-\\w*r".r

  private def looksLikePath(tok: String): Boolean =
    tok.contains('/') || tok.startsWith("$")

  /** Bindings visible to a body: `X="..."` and `X=$Y/...` assignments. */
  private def bindingsOf(text: String): Map[String, String] =
    binding
      .findAllMatchIn(text)
      .map { (m: scala.util.matching.Regex.Match) =>
        val name: String = if m.group(1) != null then m.group(1) else m.group(3)
        val value: String = if m.group(2) != null then m.group(2) else m.group(4)
        name -> value
      }
      .toMap

  /**
   * Classify one oracle test from its name and body: `Structural` when
   * the body asserts over a tool's source text, `Behavioural` when it
   * observes the tool — runs it, reads its output and status.
   *
   * spec: oracle-independence — Scenario: Adversarial — a structural test left in the oracle is reported
   */
  def classifyTest(@unused name: String, body: String): OracleTestKind =
    classifyWith(body, bindingsOf(body))

  /** The decision, with `binds` supplying variable resolution only. */
  private def classifyWith(body: String, binds: Map[String, String]): OracleTestKind =
    val structural: Boolean = body.linesIterator.exists { (line: String) =>
      if !readCmd.findFirstIn(line).isDefined then false
      else
        val codeRead: Boolean = token
          .findAllMatchIn(line)
          .map { (m: scala.util.matching.Regex.Match) =>
            // `raw` strips the quoting characters — a quoted token's
            // content. A match always sets exactly one alternative's
            // group, so no fallback arm is needed.
            val raw: String =
              if m.group(1) != null then m.group(1)
              else if m.group(2) != null then m.group(2)
              else m.group(3)
            val resolved: String = varRef
              .findFirstMatchIn(raw)
              .map((v: scala.util.matching.Regex.Match) =>
                if binds.contains(v.group(1)) then
                  raw.substring(0, v.start) + binds(v.group(1)) + raw.substring(v.end)
                else raw
              )
              .getOrElse(raw)
            fixtureRef.findFirstIn(resolved).isEmpty &&
              looksLikePath(resolved) &&
              codeExt.findFirstIn(resolved).isDefined &&
              docExt.findFirstIn(resolved).isEmpty
          }
          .exists((b: Boolean) => b)
        // a recursive grep over a source tree
        // (grep -rl … "$root/workflow" --include='*.scala'): the -r
        // flag distinguishes reading a tree from grepping command
        // output whose PATTERN mentions a path
        val treeRead: Boolean =
          recGrep.findFirstIn(line).isDefined &&
            sourceTree.findFirstIn(line).isDefined &&
            fixtureRef.findFirstIn(line).isEmpty
        codeRead || treeRead
    }
    if structural then OracleTestKind.Structural else OracleTestKind.Behavioural

  /**
   * The structural tests in one bats file, each finding naming the file
   * and the `@test` title — what "a structural test left in the oracle
   * is reported, naming its file" requires. File-scope variable bindings
   * (setup-scope `X="..."` assignments outside `@test` blocks) feed the
   * variable RESOLUTION only — they are never scanned as read commands,
   * so a setup helper that itself reads a forwarding script does not
   * mark every test in the file structural.
   */
  def structuralIn(file: String, text: String): List[StructuralTest] =
    val fileBinds: Map[String, String] = bindingsOutsideTestBlocks(text)
    batsTestBlocks(text).flatMap { case (title: String, block: String) =>
      classifyWith(block, fileBinds ++ bindingsOf(block)) match
        case OracleTestKind.Structural  => List(StructuralTest(file, title))
        case OracleTestKind.Behavioural => List.empty[StructuralTest]
    }

  /** The file's variable bindings outside `@test` blocks. */
  private def bindingsOutsideTestBlocks(text: String): Map[String, String] =
    // mark each line: inside a @test block from the `@test` line through
    // the column-0 `}` that closes it
    val (marks, _) = text.linesIterator.foldLeft((List.empty[Boolean], false)) {
      case ((acc, inside), line) =>
        if line.startsWith("@test") then (acc :+ true, true)
        else if inside then (acc :+ true, line != "}")
        else (acc :+ false, false)
    }
    text.linesIterator
      .zip(marks)
      .collect { case (line, false) => line }
      .map(bindingsOf)
      .foldLeft(Map.empty[String, String])(_ ++ _)

  /**
   * The `@test` blocks of a bats file as (title, body) pairs — the unit
   * of classification and the "names the test" domain. A block ends at
   * the first column-0 `}` — the oracle's formatting convention.
   */
  def batsTestBlocks(text: String): List[(String, String)] =
    val testRe: scala.util.matching.Regex = "^@test \"([^\"]+)\"\\s*\\{".r
    val lines: List[String]               = text.split("\n", -1).toList
    val (_, blocks) = lines.foldLeft(
      (Option.empty[(String, List[String])], List.empty[(String, String)])
    ) { case ((open, acc), line) =>
      (open, line) match
        case (None, _) =>
          testRe.findFirstMatchIn(line) match
            case Some(m) => (Some(m.group(1) -> List.empty[String]), acc)
            case None    => (None, acc)
        case (Some((title, body)), _) =>
          if line == "}" then
            (None, acc :+ (title -> body.reverse.mkString("\n")))
          else (Some(title -> (line +: body)), acc)
    }
    blocks

  /**
   * The persisted baseline record's text form — one 40-hex commit line.
   */
  def writeBaseline(baseline: OracleBaseline): String = baseline.commit + "\n"

  /**
   * Parse the persisted baseline record; `Left` names the malformed
   * input so an unreadable baseline is could-not-determine upstream.
   *
   * spec: oracle-independence — Scenario: Error path — an unreadable baseline is could-not-determine, not a pass
   */
  def readBaseline(text: String): Either[String, OracleBaseline] =
    val lines: List[String] =
      text.split("\n").toList.map(_.trim).filter(_.nonEmpty)
    lines match
      case List(sha) if sha.matches("[0-9a-f]{40}") => Right(OracleBaseline(sha))
      case _ => // danger-scan:allow fail-closed — a malformed baseline record is a Left, never a baseline
        Left(
          s"baseline record malformed: expected one 40-hex commit line, got ${lines.length} non-empty line(s)"
        )

  /** The sanction record's persisted text form (JSON lines). */
  def writeSanctions(record: List[OracleSanction]): String =
    record
      .map { (s: OracleSanction) =>
        ujson.write(
          ujson.Obj(
            "file"        -> s.file,
            "commit"      -> s.commit,
            "spec"        -> s.spec,
            "requirement" -> s.requirement
          )
        )
      }
      .mkString("", "\n", "\n")

  /**
   * Parse the persisted sanction record; `Left` names the malformed
   * input so an unreadable record is could-not-determine upstream.
   *
   * spec: oracle-independence — Property: sanction-record-round-trips
   */
  def readSanctions(text: String): Either[String, List[OracleSanction]] =
    def parseLine(line: String, i: Int): Either[String, OracleSanction] =
      try
        ujson.read(line) match
          case obj: ujson.Obj =>
            Right(
              OracleSanction(
                file = obj("file").str,
                commit = obj("commit").str,
                spec = obj("spec").str,
                requirement = obj("requirement").str
              )
            )
          case _ => Left(s"sanction record line ${i + 1} is not a JSON object") // danger-scan:allow fail-closed — a non-object line is a Left, never a sanction
      catch
        case e: Exception =>
          Left(s"sanction record line ${i + 1} malformed: ${e.getMessage}")
    val parsed: List[Either[String, OracleSanction]] =
      text.split("\n").toList.zipWithIndex.collect {
        case (line, i) if line.trim.nonEmpty => parseLine(line, i)
      }
    parsed.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    => Right(parsed.collect { case Right(s) => s })
