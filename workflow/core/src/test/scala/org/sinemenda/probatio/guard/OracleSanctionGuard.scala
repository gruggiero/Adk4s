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
  // extension (.scala, .sh, .bash, .py, .ts, .js, .jq, .bats), to an
  // extensionless file under a source-tree segment, or to a path reached
  // through variable bindings resolved to a fixpoint; or a recursive read
  // over a source tree. Test-created files (under the closed fixture set
  // $FX, $FAKE_*, $BATS_*, TEST_TMPDIR) and document/data files (.md,
  // .yaml, .json, .html …) are behavioural: the first is a prop the tool
  // acts on, the second is a spec-governed deliverable, not how a tool is
  // written. `run`ning a tool is behavioural — execution observes output
  // and status, never the source text.
  //
  // Known under-match boundary (Ring 8 review): this is a heuristic over
  // shell tokens. An indirection the fixpoint cannot reach — a source
  // path smuggled through a variable whose VALUE lives in another file —
  // stays behavioural; the closed fixture-name set and the extensionless
  // rule are the deliberately conservative edges.

  // `source` is gated: `--source`, `$source`, `.source` are arguments,
  // not the shell builtin — the lookbehind keeps the flag out.
  private val readCmd: scala.util.matching.Regex =
    "\\b(?:grep|cat|sed|awk|head|tail|diff|find|xargs|eval)\\b|(?<![-$.])\\bsource\\b".r
  private val codeExt: scala.util.matching.Regex =
    "\\.(scala|sh|bash|py|ts|js|jq|bats)\\b".r
  private val docExt: scala.util.matching.Regex =
    "\\.(md|json|jsonl|yaml|yml|html|txt|log)\\b".r
  // A CLOSED set of harness fixture roots — $FX, $FAKE_*, $BATS_*,
  // TEST_TMPDIR (and their ${...} spellings). An open prefix like $TEST_*
  // is not a fixture: an unbound $TEST_* name may resolve to real source
  // at run time, so it is classified on its suffix, not exempted by name.
  private val fixtureRef: scala.util.matching.Regex =
    "\\$\\{?(?:FX|FAKE[A-Z_0-9]*|BATS_[A-Z_0-9]*|TEST_TMPDIR)\\b|\\bTEST_TMPDIR\\b".r
  // name="$(…)" | name="v" | name='v' | name=$v | name=literal — every
  // assignment spelling the oracle uses feeds variable resolution. The
  // `$(…)` alternative comes first: a quoted `[^"]*` capture truncates a
  // substitution carrying its own quotes (git_dir="$(cd "$FX" && …)"),
  // losing the fixture marker inside.
  private val binding: scala.util.matching.Regex =
    "([A-Za-z_]\\w*)=\"(\\$\\(.*?\\))\"|([A-Za-z_]\\w*)=\"([^\"]*)\"|([A-Za-z_]\\w*)='([^']*)'|([A-Za-z_]\\w*)=(\\$[^\\s\"']+)|([A-Za-z_]\\w*)=([^\\s\"'$][^\\s\"']*)".r
  private val token: scala.util.matching.Regex =
    "\"([^\"]+)\"|'([^']+)'|(\\S+)".r
  private val varRef: scala.util.matching.Regex =
    "\\$(?:\\{([A-Za-z_]\\w*)[^}]*\\}|([A-Za-z_]\\w*))".r
  // A WHOLE path segment, ended by `/` or the token's end: `verified`
  // names the Stainless tree, NOT `verified-scala3-gate` (a state dir).
  private val sourceTree: scala.util.matching.Regex =
    "/(?:workflow|scanner|hooks|bin|src|adapters|verified|openspec|tests|project)(?:/|$)".r
  private val recGrep: scala.util.matching.Regex =
    "\\bgrep\\s+-\\w*r".r

  /**
   * The line's command segments, split on UNQUOTED `|` `&` `;` — quote
   * state and backslash escapes are tracked so a `'a|b'` pattern or a
   * `\;` terminator stays inside its command.
   */
  private def segmentsOf(line: String): List[String] =
    val (segs, cur, _, _, _) = line.foldLeft(
      (List.empty[String], "", false, false, false)
    ) { case ((segs, cur, sq, dq, esc), c) =>
      if esc then (segs, cur :+ c, sq, dq, false)
      else if !sq && c == '\\' then (segs, cur :+ c, sq, dq, true)
      else if sq then (segs, cur :+ c, c != '\'', dq, false)
      else if dq then (segs, cur :+ c, sq, c != '"', false)
      else if c == '|' || c == '&' || c == ';' then (segs :+ cur, "", false, false, false)
      else (segs, cur :+ c, c == '\'', c == '"', false)
    }
    segs :+ cur

  private def looksLikePath(tok: String): Boolean =
    tok.contains('/') || tok.startsWith("$")

  /**
   * The path's last segment is a CONCRETE name carrying no `.` — an
   * extensionless tool. An unresolved `$VAR` basename is undecidable:
   * it may be a fixture name, and is not evidence either way.
   */
  private def extensionless(tok: String): Boolean =
    val base: String = tok.substring(tok.lastIndexOf('/') + 1)
    base.nonEmpty && !base.contains('.') && !base.contains('$')

  /** Bindings visible to a body: every `X=...` assignment spelling. */
  private def bindingsOf(text: String): Map[String, String] =
    binding
      .findAllMatchIn(text)
      .map { (m: scala.util.matching.Regex.Match) =>
        val groups: List[Int] = List(1, 3, 5, 7, 9)
        val nameIdx: Int      = groups.find((g: Int) => m.group(g) != null).getOrElse(1)
        m.group(nameIdx) -> m.group(nameIdx + 1)
      }
      .toMap

  /**
   * Resolve `$X`/`${X}` references to a fixpoint (bounded — reference
   * cycles like `A=$B, B=$A` settle after the iteration cap instead of
   * looping). Single-pass resolution misses `$A$B` two-step indirection
   * and a binding whose value is itself a reference.
   */
  private def resolveAll(raw: String, binds: Map[String, String]): String =
    def resolveOnce(s: String): String =
      varRef.replaceAllIn(
        s,
        (m: scala.util.matching.Regex.Match) =>
          val name: String =
            if m.group(1) != null then m.group(1) else m.group(2)
          binds.get(name) match
            case Some(value) => java.util.regex.Matcher.quoteReplacement(value)
            case None        => java.util.regex.Matcher.quoteReplacement(m.matched)
      )
    def loop(s: String, passes: Int): String =
      if passes <= 0 then s
      else
        val next: String = resolveOnce(s)
        if next == s then s else loop(next, passes - 1)
    loop(raw, 10)

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
      // a `#`-led line is prose — a comment mentioning a read verb and a
      // path runs nothing
      if line.trim.startsWith("#") then false
      else
        // a line is a pipeline of COMMANDS — only segments that contain
        // a read verb are reads: `tail f | jq -f contract.jq` reads f
        // and EXECUTES the contract (jq is not a read verb), so the
        // contract's own token must not flag the line.
        segmentsOf(line).exists { (segment: String) =>
          if readCmd.findFirstIn(segment).isEmpty then false
          else
            val resolvedTokens: List[String] = token
              .findAllMatchIn(segment)
              .map { (m: scala.util.matching.Regex.Match) =>
                // `raw` strips the quoting characters — a quoted
                // token's content. A match always sets exactly one
                // alternative's group, so no fallback arm is needed.
                val raw: String =
                  if m.group(1) != null then m.group(1)
                  else if m.group(2) != null then m.group(2)
                  else m.group(3)
                resolveAll(raw, binds)
              }
              .toList
            val codeRead: Boolean = resolvedTokens.exists { (resolved: String) =>
              fixtureRef.findFirstIn(resolved).isEmpty &&
              looksLikePath(resolved) &&
              docExt.findFirstIn(resolved).isEmpty &&
              (codeExt.findFirstIn(resolved).isDefined ||
                // extensionless file under a source-tree segment —
                // shipped tools include extensionless launchers
                // (bin/probatio)
                (sourceTree.findFirstIn(resolved).isDefined && extensionless(resolved)))
            }
            // a recursive grep over a source tree
            // (grep -rl … "$root/workflow" --include='*.scala'): the -r
            // flag distinguishes reading a tree from grepping command
            // output whose PATTERN mentions a path; the tree segment is
            // checked on the RESOLVED tokens so the bound value decides,
            // not the variable's name
            val treeRead: Boolean =
              recGrep.findFirstIn(segment).isDefined &&
                resolvedTokens.exists { (resolved: String) =>
                  sourceTree.findFirstIn(resolved).isDefined &&
                  fixtureRef.findFirstIn(resolved).isEmpty
                }
            codeRead || treeRead
        }
    }
    if structural then OracleTestKind.Structural else OracleTestKind.Behavioural

  /**
   * The structural tests in one bats file, each finding naming the file
   * and the `@test` title — what "a structural test left in the oracle
   * is reported, naming its file" requires. File-scope variable bindings
   * feed the variable resolution of every block.
   *
   * A source read inside a file-scope helper reaches the tests that
   * CALL it: each `name() {…}` function whose name is invoked —
   * transitively — from a `@test` block is classified and reported as
   * `(file scope: name)`. Bats lifecycle machinery (`setup`,
   * `teardown`, `setup_file`, `teardown_file`) is invoked by bats, not
   * by the tests, so fixture-building reads there are setup-scope
   * machinery — covered by the file's sanctions, not this check.
   * Bare top-level executable lines run at file load for every test and
   * are reported as `(file scope)`.
   */
  def structuralIn(file: String, text: String): List[StructuralTest] =
    val fileBinds: Map[String, String] = bindingsOutsideTestBlocks(text)
    val blocks: List[(String, String)] = batsTestBlocks(text)
    val testFindings: List[StructuralTest] =
      blocks.flatMap { case (title: String, block: String) =>
        classifyWith(block, fileBinds ++ bindingsOf(block)) match
          case OracleTestKind.Structural  => List(StructuralTest(file, title))
          case OracleTestKind.Behavioural => List.empty[StructuralTest]
      }
    helperFindings(file, text, fileBinds, blocks) ++ testFindings

  /**
   * `(name, body)` pairs of the file-scope `name() {…}` functions, plus
   * the bare top-level text (outside functions and tests) under the
   * sentinel name `"(file scope)"`.
   */
  private def functionSpans(text: String): List[(String, String)] =
    val fnRe: scala.util.matching.Regex =
      "^([A-Za-z_]\\w*)\\s*\\(\\)\\s*\\{".r
    val lines: List[String]  = text.split("\n", -1).toList
    val marks: List[Boolean] = testLineMarks(lines)
    val outsideTest: List[(String, Int)] =
      lines.zip(marks).zipWithIndex.collect { case ((line, false), i) => (line, i) }
    // walk the outside-test lines; a `name() {` header opens a function
    // whose body runs to brace depth zero
    val (spans, topLevel, open) = outsideTest.foldLeft(
      (List.empty[(String, String)], List.empty[String], Option.empty[(String, List[String], Int)])
    ) { case ((acc, top, open), (line, _)) =>
      open match
        case Some((name, body, depth)) =>
          val next: Int = depth + bracesOf(line)
          if next <= 0 then (acc :+ (name -> (line +: body).reverse.mkString("\n")), top, None)
          else (acc, top, Some((name, line +: body, next)))
        case None =>
          fnRe.findFirstMatchIn(line) match
            case Some(m) =>
              val depth: Int = bracesOf(line)
              if depth <= 0 then (acc :+ (m.group(1) -> ""), top, None)
              else (acc, top, Some((m.group(1), List.empty[String], depth)))
            case None => (acc, top :+ line, None)
    }
    val unclosed: List[(String, String)] =
      open match
        case Some((name, body, _)) => List(name -> body.reverse.mkString("\n"))
        case None                  => List.empty[(String, String)]
    spans ++ unclosed :+ ("(file scope)" -> topLevel.mkString("\n"))

  /**
   * The file-scope functions reachable — transitively — from a `@test`
   * body. `setup`/`teardown`/`setup_file`/`teardown_file` are invoked
   * by bats itself, so reads there stay out of scope.
   */
  private def helperFindings(
    file: String,
    text: String,
    fileBinds: Map[String, String],
    blocks: List[(String, String)]
  ): List[StructuralTest] =
    val fns: List[(String, String)] = functionSpans(text)
    val batsLifecycle: Set[String] =
      Set("setup", "teardown", "setup_file", "teardown_file", "(file scope)")
    val invoked: String => scala.util.matching.Regex = (name: String) =>
      ("\\b" + java.util.regex.Pattern.quote(name) + "\\b").r
    val direct: Set[String] =
      blocks.flatMap { case (_, block) =>
        fns.collect { case (name, _) if !batsLifecycle(name) && invoked(name).findFirstIn(block).isDefined => name }
      }.toSet
    // transitively: a helper called by a REACHABLE helper is reachable
    val bodyOf: Map[String, String] = fns.toMap
    def reachable(seen: Set[String]): Set[String] =
      val grown: Set[String] = fns.foldLeft(seen) { (s, fn) =>
        fn match
          case (name, _)
              if !batsLifecycle(name) && !s(name) &&
                s.exists((r: String) => invoked(name).findFirstIn(bodyOf.getOrElse(r, "")).isDefined) =>
            s + name
          case _ => s // danger-scan:allow fail-closed — a non-reachable helper stays out, never silently in
      }
      if grown == seen then seen else reachable(grown)
    val scope: Set[String] = reachable(direct)
    fns.flatMap { case (name: String, body: String) =>
      if name == "(file scope)" then
        // bare top-level lines run at file load — they reach every test
        classifyWith(body, fileBinds) match
          case OracleTestKind.Structural  => List(StructuralTest(file, "(file scope)"))
          case OracleTestKind.Behavioural => List.empty[StructuralTest]
      else if scope(name) then
        classifyWith(body, fileBinds) match
          case OracleTestKind.Structural =>
            List(StructuralTest(file, s"(file scope: $name)"))
          case OracleTestKind.Behavioural => List.empty[StructuralTest]
      else List.empty[StructuralTest]
    }

  /** The file's variable bindings outside `@test` blocks. */
  private def bindingsOutsideTestBlocks(text: String): Map[String, String] =
    val lines: List[String]  = text.split("\n", -1).toList
    val marks: List[Boolean] = testLineMarks(lines)
    lines
      .zip(marks)
      .collect { case (line, false) => line }
      .map(bindingsOf)
      .foldLeft(Map.empty[String, String])(_ ++ _)

  /**
   * Per-line membership in a `@test` block, by brace depth: a block
   * opens at `@test "…" {` and closes when the depth returns to zero —
   * the oracle's column-0 `}` convention, or any brace balancing. A
   * mid-test `}` that closes a NESTED group does not truncate the body.
   */
  private def testLineMarks(lines: List[String]): List[Boolean] =
    val testRe: scala.util.matching.Regex = "^@test \"([^\"]+)\"\\s*\\{".r
    val (marks, _, _) = lines.foldLeft((List.empty[Boolean], false, 0)) { case ((acc, inside, depth), line) =>
      if !inside then
        val opened: Int = depth + bracesOf(line)
        if testRe.findFirstIn(line).isDefined then (acc :+ true, opened > 0, opened)
        else (acc :+ false, false, depth)
      else
        // the line that returns depth to zero closes the block and is
        // still part of it
        val next: Int = depth + bracesOf(line)
        (acc :+ true, next > 0, next)
    }
    marks

  private def bracesOf(line: String): Int =
    line.count((c: Char) => c == '{') - line.count((c: Char) => c == '}')

  /**
   * The `@test` blocks of a bats file as (title, body) pairs — the unit
   * of classification and the "names the test" domain. A block ends
   * when its brace depth returns to zero — the oracle's column-0 `}`
   * convention.
   */
  def batsTestBlocks(text: String): List[(String, String)] =
    val testRe: scala.util.matching.Regex = "^@test \"([^\"]+)\"\\s*\\{".r
    val lines: List[String]               = text.split("\n", -1).toList
    val marks: List[Boolean]              = testLineMarks(lines)
    lines.indices.toList.flatMap { (i: Int) =>
      testRe.findFirstMatchIn(lines(i)) match
        case Some(m) =>
          // the block's last line is the one before the first
          // OUTSIDE line OR the next `@test` header — adjacent blocks
          // have no unmarked line between them
          val end: Int = ((i + 1) until lines.length)
            .find((j: Int) => !marks(j) || testRe.findFirstIn(lines(j)).isDefined)
            .map((j: Int) => j - 1)
            .getOrElse(lines.length - 1)
          List(m.group(1) -> lines.slice(i + 1, end).mkString("\n"))
        case None => List.empty[(String, String)]
    }

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
