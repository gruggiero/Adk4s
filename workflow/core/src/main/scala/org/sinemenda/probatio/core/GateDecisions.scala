package org.sinemenda.probatio.core

/**
 * The gate's pure decision functions (spec 8). Every function takes its
 * inputs as values — no file I/O, no process execution, no environment
 * reads. `probatio-cli` resolves the state and calls these.
 *
 * spec: gate-event-completeness — Compile-Negative: A file read or environment-variable read inside probatio-core's gate decision functions
 */
object GateDecisions:

  /**
   * The predecessor's read-only tool set — `tool-call` always allows
   * these. The empty string is the absent-name case (`TOOL_NAME=""`
   * matches the predecessor's `""` arm).
   */
  val readOnlyTools: Set[String] = Set(
    "Read",
    "read",
    "View",
    "view",
    "Grep",
    "grep",
    "Glob",
    "glob",
    "Search",
    "search",
    ""
  )

  /** True when the tool is in the read-only set. */
  def isReadOnlyTool(toolName: String): Boolean =
    readOnlyTools.contains(toolName)

  /**
   * The pre-execution tier's tool-name decision (spec 6,
   * oracle-fixture-repair): whether the call is allowed without
   * consulting the lock state — a supplied read-only tool, or an absent
   * tool name. `Absent` keeps the predecessor's verdict (the empty
   * string was a `readOnlyTools` member); the caller renders the absence
   * in its diagnostic, which only a `ToolNameSource` makes visible.
   *
   * Body lands at Step 3; the contract pins the signature.
   *
   * spec: oracle-fixture-repair — Requirement: An absent tool name keeps parity and is stated
   * spec: oracle-fixture-repair — Compile-Negative: A tool name represented as a possibly-empty string at the decision site
   */
  def preExecution(@annotation.unused toolName: ToolNameSource): Boolean = ???

  /**
   * The oracle lock's production-edit scope: a `/src/main/` path
   * segment followed by a `.scala` file — the predecessor's glob
   * semantics. Relative `src/main/...` paths (no leading slash) do not
   * match, exactly as the predecessor's pattern requires.
   */
  def isProductionEdit(path: String): Boolean =
    path.contains("/src/main/") && path.endsWith(".scala")

  /**
   * The post-edit tier's spec scope — the predecessor's two-case glob
   * check `STAR/openspec/changes/STAR/specs/STAR/spec.md` minus
   * `STAR/archive/STAR` (STAR = the bash `*` glob). Bash `*` spans `/`,
   * so the pattern reduces to an existence check: the path ends in
   * `/spec.md` and carries a `/specs/` segment after SOME
   * `/openspec/changes/` marker (the FIRST occurrence leaves the most
   * room for the `/specs/` segment — if any marker works, the first
   * does). `STAR/archive/STAR` is exactly `path.contains("/archive/")`.
   * The leading-slash marker means a bare `openspec/changes/...`
   * relative path does NOT match — the predecessor's normalization
   * precondition.
   */
  def isSpecEdit(path: String): Boolean =
    if !path.endsWith("/spec.md") || path.contains("/archive/") then false
    else
      val marker: String   = "/openspec/changes/"
      val specsSeg: String = "/specs/"
      val idx: Int         = path.indexOf(marker)
      if idx < 0 then false
      else
        val specsIdx: Int = path.indexOf(specsSeg, idx + marker.length)
        // The `/specs/` segment must end before the trailing `/spec.md`
        // begins — glob literals consume disjoint ranges.
        specsIdx >= 0 && specsIdx + specsSeg.length <= path.length - "/spec.md".length

  /**
   * The spec-edit change name — the predecessor's sed extraction over
   * the change path. Its greedy leading dot-star anchors on the LAST
   * `/openspec/changes/` marker occurrence whose following text is
   * `<segment>/specs/…` — a marker whose first segment is NOT
   * immediately followed by `/specs/` does not extract (the regex
   * backtracks to an earlier marker). `""` when no marker extracts.
   */
  def specEditChangeName(path: String): String =
    val marker: String = "/openspec/changes/"
    @annotation.tailrec
    def loop(from: Int): String =
      val idx: Int = path.lastIndexOf(marker, from)
      if idx < 0 then ""
      else
        val rest: String = path.substring(idx + marker.length)
        val segEnd: Int  = rest.indexOf('/')
        if segEnd >= 0 && rest.startsWith("/specs/", segEnd) then rest.substring(0, segEnd)
        else loop(idx - 1)
    loop(path.length - 1)

  /**
   * The implementation order's spec list — `specs/<name>/spec.md`
   * tokens, first-occurrence order, deduplicated (the predecessor's
   * `grep -oE … | awk '!seen[$0]++'`).
   */
  def specOrder(implOrderText: String): List[String] =
    val pat: scala.util.matching.Regex = "specs/([^/` ]+)/spec\\.md".r
    pat
      .findAllMatchIn(implOrderText)
      .map((m: scala.util.matching.Regex.Match) => m.group(1))
      .toList
      .distinct

  /**
   * Expected-Files table ownership: the spec whose table row's
   * backtick-quoted paths contain `filePath`. Only rows in the
   * `## Expected Changed Production Files` section count (the
   * predecessor's awk section bounds), ending at the next `## `
   * heading. A row matches when a backtick path equals the file's
   * repo-relative form or its absolute form under `repoRoot`.
   */
  def owningSpec(implOrderText: String, repoRoot: String, filePath: String): Option[String] =
    val relPath: String =
      if filePath.startsWith(repoRoot + "/") then filePath.substring(repoRoot.length + 1)
      else filePath
    val lines: List[String] = implOrderText.split("\n", -1).toList
    val inTable: List[String] =
      lines
        .dropWhile((l: String) => !l.startsWith("## Expected Changed Production Files"))
        .drop(1)
        .takeWhile((l: String) => !l.startsWith("## "))
    val backtick: scala.util.matching.Regex = "`([^`]+)`".r
    inTable
      .filter((l: String) => l.startsWith("|"))
      .flatMap { (row: String) =>
        val cols: Array[String] = row.split("\\|", -1)
        if cols.length < 4 then None
        else
          val spec: String = cols(2).trim
          if spec.isEmpty || spec.startsWith("---") then None
          else
            val paths: List[String] =
              backtick
                .findAllMatchIn(cols(3))
                .map((m: scala.util.matching.Regex.Match) => m.group(1))
                .toList
            val hit: Boolean = paths.exists((p: String) => (repoRoot + "/" + p) == filePath || p == relPath)
            if hit then Some(spec) else None
      }
      .headOption

  /**
   * The phase transition: `oracle → implementation` once a RED artifact
   * exists; `→ verified` once RED and GREEN both exist — the predecessor
   * requires `red_exists` for the verified transition too, so a spec in
   * `implementation` whose RED row is no longer at an ancestor baseline
   * does NOT verify on a bare GREEN. A phase never regresses.
   */
  def advancePhase(current: SpecPhase, redExists: Boolean, greenExists: Boolean): SpecPhase =
    current match
      case SpecPhase.Oracle =>
        if redExists && greenExists then SpecPhase.Verified
        else if redExists then SpecPhase.Implementation
        else SpecPhase.Oracle
      case SpecPhase.Implementation =>
        if redExists && greenExists then SpecPhase.Verified else SpecPhase.Implementation
      case SpecPhase.Verified => SpecPhase.Verified

  /**
   * The ambient ring-shape table — the command shapes whose exit the
   * post-tool observation event attributes to a ring.
   */
  final case class AmbientMatch(ring: Ring, obligation: String, artifact: String)

  /**
   * The ambient ring verdict — the ring match OR the predecessor's
   * skip reason (its trace message, minus the `post-bash:` prefix) for
   * every non-recorded shape, in the predecessor's order: `ledger.sh
   * run` first (the record tool deduplicates — the gate skips so a
   * double row is not written), then compound commands (`|`, `;`,
   * `&&`, trailing `&`, newline — the exit belongs to the pipeline,
   * not to the ring), then the ring-shape table.
   */
  def ambientVerdict(command: String): Either[String, AmbientMatch] =
    // De-duplication: an explicit `ledger.sh run` already wrote its own
    // row — the gate skips it so a double row is not written.
    if command.contains("ledger.sh run") then Left("de-duplicated (explicit ledger.sh run)")
    // Compound commands: `|`, `;`, `&&`, trailing `&`, newline — the
    // reported exit belongs to the LAST element of the pipeline/chain,
    // not to the ring. (`&` alone mid-command — `2>&1` — is fine; only
    // the trailing form backgrounds.) Not evidence; nothing recorded.
    else if command.contains("|") || command.contains(";") ||
      command.contains("&&") || command.endsWith("&") ||
      command.contains("\n")
    then Left("compound command — its exit is not the ring's, not recorded")
    // The enumerated ring-shape table — ambient obligations are prefixed
    // `ambient:` so chain-state's exact-equality binding can never match
    // one: an ambient row discharges NOTHING, by construction.
    else if (command.startsWith("sbt ") && command.substring(4).contains("test")) ||
      command.startsWith("bats ") || command.contains("/bats ")
    then Right(AmbientMatch(Ring.R3, "ambient: test execution", "tests/"))
    else if command.contains("danger-scan.sh") then
      Right(
        AmbientMatch(
          Ring.R1,
          "ambient: danger scan",
          "openspec/schemas/verified-scala3/scanner/danger-scan.sh"
        )
      )
    else if command.contains("registry-check.sh") then
      Right(
        AmbientMatch(
          Ring.R1,
          "ambient: registry check",
          "openspec/schemas/verified-scala3/scanner/registry-check.sh"
        )
      )
    else if command.contains("spec-lint.sh") then
      Right(
        AmbientMatch(
          Ring.R1,
          "ambient: spec lint",
          "openspec/schemas/verified-scala3/scanner/spec-lint.sh"
        )
      )
    else if command.contains("checkpoint.sh report") then
      // Checkpoint is the presentation, not evidence — never a ring shape.
      Left("checkpoint.sh report is not a ring shape")
    else Left("no ring shape match, not recorded")

  /**
   * Classify a command string against the ambient table — the verdict
   * without the skip reason. `None` for every non-recorded shape.
   */
  def ambientRingMatch(command: String): Option[AmbientMatch] =
    ambientVerdict(command).toOption

  /** The post-edit tier's Step-0 write target. */
  enum Step0Target:
    case ProgressFile
    case SpecDir(spec: String)

  /**
   * The Step-0 write map: `implementation-progress.md` under the change
   * dir → `ProgressFile`; a file under `specs/<name>/` → `SpecDir(name)`
   * (new spec dirs are seeded). Anything else → `None`.
   */
  def step0Target(filePath: String, change: String): Option[Step0Target] =
    val base: String = s"/openspec/changes/$change/"
    if filePath.endsWith(base + "implementation-progress.md") then Some(Step0Target.ProgressFile)
    else
      val specsMarker: String = base + "specs/"
      // The predecessor's `${FILE_PATH##*/…/specs/}` strips the LONGEST
      // matching prefix — the last marker occurrence.
      val idx: Int = filePath.lastIndexOf(specsMarker)
      if idx < 0 then None
      else
        val rest: String     = filePath.substring(idx + specsMarker.length)
        val specName: String = rest.takeWhile((c: Char) => c != '/')
        if specName.isEmpty then None else Some(Step0Target.SpecDir(specName))

  /**
   * Parse `<prefix><change>-<spec>-<session>` right-to-left — change
   * and spec names contain hyphens, so the last `-`-segment is the
   * session and the second-to-last is the spec (the predecessor's
   * `rest##*-` semantics, quirks included: a name with no hyphen after
   * the prefix yields all three fields equal to the remainder). `None`
   * when the prefix is absent or any segment is empty.
   */
  def markerTriple(prefix: String, fileName: String): Option[(String, String, String)] =
    if !fileName.startsWith(prefix) then None
    else
      val rest: String = fileName.substring(prefix.length)
      // `${rest##*-}` — last hyphen-segment (whole rest when no hyphen).
      val session: String =
        rest.substring(rest.lastIndexOf('-') + 1)
      // `${rest%-*}` — rest minus its shortest `-*` suffix (unchanged
      // when rest contains no hyphen).
      val restNoSession: String =
        if rest.contains('-') then rest.substring(0, rest.lastIndexOf('-'))
        else rest
      val spec: String =
        restNoSession.substring(restNoSession.lastIndexOf('-') + 1)
      val chg: String =
        if restNoSession.contains('-') then restNoSession.substring(0, restNoSession.lastIndexOf('-'))
        else restNoSession
      if chg.isEmpty || spec.isEmpty || session.isEmpty then None
      else Some((chg, spec, session))

  /** Ledger-row polarity for the oracle phase's evidence checks. */
  enum Polarity:
    case Red, Green

  // jq `// empty` parity: a present-but-non-string field reads as "",
  // and a non-object row contributes no fields at all.
  private def rowStr(v: ujson.Value, key: String): String =
    v.objOpt
      .flatMap((m: scala.collection.mutable.Map[String, ujson.Value]) => m.get(key))
      .getOrElse(ujson.Null) match
      case ujson.Str(s) => s
      case ujson.Num(n) =>
        if n == n.toInt.toDouble then n.toInt.toString else n.toString
      case _ => "" // danger-scan:allow jq-empty-parity — non-string fields read as absent

  /**
   * The baseline of a row that matches `(change, spec)` at ring `R3`
   * with the requested polarity — `Some` only when `.exit` and
   * `.baseline` are both non-empty and the polarity holds. Ancestry is
   * NOT checked here; callers compose it (the predecessor checks it
   * inside the scan loop).
   */
  private def ring3BaselineOf(
    row: ujson.Value,
    change: String,
    spec: String,
    polarity: Polarity
  ): Option[String] =
    val exit: String     = rowStr(row, "exit")
    val baseline: String = rowStr(row, "baseline")
    val polarityOk: Boolean = polarity match
      case Polarity.Red   => exit != "0"
      case Polarity.Green => exit == "0"
    if rowStr(row, "change") == change &&
      rowStr(row, "spec") == spec &&
      rowStr(row, "ring") == "R3" &&
      exit.nonEmpty && baseline.nonEmpty &&
      polarityOk
    then Some(baseline)
    else None

  /**
   * The R3-row polarity predicate — true when a ledger row exists for
   * `(change, spec)` at ring `R3` with the requested polarity whose
   * baseline is an ancestor of `HEAD`. RED is `.exit != "0"`; GREEN is
   * `.exit == "0"`. Fields read with the predecessor's `// empty`
   * semantics; the injected `isAncestor` performs the git check so the
   * function stays pure.
   */
  def hasRing3Row(
    rows: List[ujson.Value],
    change: String,
    spec: String,
    polarity: Polarity,
    isAncestor: String => Boolean
  ): Boolean =
    rows.exists((row: ujson.Value) => ring3BaselineOf(row, change, spec, polarity).exists(isAncestor))

  /**
   * The baseline of the FIRST matching row — the predecessor's
   * first-match `break` semantics. RED's recorded baseline is the green
   * check's ancestry anchor.
   */
  def firstRing3Baseline(
    rows: List[ujson.Value],
    change: String,
    spec: String,
    polarity: Polarity,
    isAncestor: String => Boolean
  ): Option[String] =
    rows.iterator
      .flatMap((row: ujson.Value) => ring3BaselineOf(row, change, spec, polarity))
      .find(isAncestor)

  /**
   * The GREEN predicate with the RED-descendant constraint: a matching
   * green row whose baseline is an ancestor of HEAD AND — when a RED
   * baseline exists — a descendant of it (the predecessor's two
   * `merge-base --is-ancestor` calls). Both ancestry relations are
   * injected so the function stays pure.
   */
  def hasGreenAfterRed(
    rows: List[ujson.Value],
    change: String,
    spec: String,
    redBaseline: Option[String],
    isAncestorOfHead: String => Boolean,
    isAncestorPair: (String, String) => Boolean
  ): Boolean =
    rows.exists { (row: ujson.Value) =>
      ring3BaselineOf(row, change, spec, Polarity.Green).exists { (base: String) =>
        isAncestorOfHead(base) &&
        redBaseline.forall((rb: String) => isAncestorPair(rb, base))
      }
    }

  /**
   * The completion refusal's unresolved block — the predecessor's jq
   * shape: at most ten entries, each `"    <requirement> (<reason1,reason2>)"`,
   * then `"    +N more"` when the chain state named more.
   */
  def unresolvedBlock(entries: List[(String, List[String])]): String =
    val shown: String =
      entries
        .take(10)
        .map { case (req: String, reasons: List[String]) =>
          s"    $req (${reasons.mkString(",")})"
        }
        .mkString("\n")
    if entries.length > 10 then shown + s"\n    +${entries.length - 10} more"
    else shown

  /**
   * The completion corroboration verdict over one change's reconcile
   * report, scoped to the CURRENT baseline: every uncorroborated claim
   * (testimony or contradicted) whose `baseline` equals `baseline` is a
   * refusal warrant; the FIRST such claim in record order is the row the
   * verdict names. Claims at any other baseline are out of scope — the
   * spec-literal scope, a declared divergence from the predecessor's
   * unfiltered reconcile invocation.
   *
   * The report is already-read evidence: this function performs no I/O.
   * An unreadable record never reaches it — the adapter constructs
   * `WitnessVerdict.Undeterminable` at the read boundary instead.
   *
   * spec: completion-witness-refusal — Requirement: A turn is refused when a green result has no corroboration
   * spec: completion-witness-refusal — Property: refusal-iff-an-uncorroborated-green-result-exists
   */
  def corroborationVerdict(report: ReconcileReport, baseline: String): WitnessVerdict =
    report.uncorroborated.find((c: ClaimVerdict) => c.baseline == baseline) match
      case Some(row: ClaimVerdict) => WitnessVerdict.Unwitnessed(row)
      case None                    => WitnessVerdict.Witnessed

  /**
   * The completion decision — the corroboration verdict bounded by the
   * per-turn refusal budget:
   *
   *  - `Witnessed` → `Allow`.
   *  - `Undeterminable` → `AllowUndetermined` — fail-open with the
   *    stated reason; abstention never consumes the budget.
   *  - `Unwitnessed` → `Refuse` while the budget is unspent, `Allow`
   *    once it is — at most one refusal per turn, and a spent budget
   *    allows rather than refusing again.
   *
   * spec: completion-witness-refusal — Contract: decideCompletion
   * spec: completion-witness-refusal — Requirement: At most one refusal is issued per turn
   */
  def decideCompletion(verdict: WitnessVerdict, budget: RefusalBudget): CompletionDecision =
    verdict match
      case WitnessVerdict.Witnessed              => CompletionDecision.Allow
      case WitnessVerdict.Undeterminable(reason) => CompletionDecision.AllowUndetermined(reason)
      case u @ WitnessVerdict.Unwitnessed(_) =>
        if budget.exhausted then CompletionDecision.Allow else CompletionDecision.Refuse(u)

end GateDecisions
