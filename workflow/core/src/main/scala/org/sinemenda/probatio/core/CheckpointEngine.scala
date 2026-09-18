package org.sinemenda.probatio.core

/**
 * The pure checkpoint engine (spec 7).
 *
 * Generates a `CheckpointReport` from recorded evidence — never authored
 * prose — consuming the supplied correctness verdict verbatim (the
 * engine reads the verdict's own fields; it does not recompute
 * correctness — the compile-negative contract forbids any reference to
 * `ChainState` from this module).
 *
 * The R8 adversarial-review ladder (the predecessor's `report`):
 *   - no `--session` supplied          → `unverified-session` (limitation,
 *                                        never silently green)
 *   - row carries no session field     → `same-session` (cannot verify)
 *   - row session == implementing      → `same-session` (no fresh-context
 *                                        evidence)
 *   - row session is `ppid-*`          → `unverified-session` (unverifiable
 *                                        fallback)
 *   - otherwise                        → green/failed by the row's exit
 *
 * spec: ledger-checkpoint-parity — Concepts Introduced (new): CheckpointEngine (implementation anchor)
 * spec: ledger-checkpoint-parity — Compile-Negative: A correctness computation referenced from the checkpoint module
 */
object CheckpointEngine:

  /**
   * Generate the checkpoint for one change/spec at one baseline.
   * `requested` is de-duplicated (first occurrence wins) so every ring
   * is named exactly once. `records` is the already-filtered row set —
   * filtered by the record tool's own read path, not here: selection
   * is by ring only (the predecessor's `select(.ring == $ring) | tail -1`
   * over the filtered `ledger_out`), so a stale-baseline row the read
   * path FORGAVE (`--forgive-unchanged`) still evidences its ring —
   * re-checking the stored baseline here would drop it. Each ring's
   * verdict traces to the LAST matching record (the ledger is
   * append-only).
   */
  def report(
    change: String,
    spec: String,
    baseline: String,
    requested: List[Ring],
    records: List[LedgerRecord],
    chainState: ujson.Value,
    implementingSession: Option[SessionId]
  ): CheckpointReport =
    val deduped: List[Ring] = requested.distinct
    val evidences: List[RingEvidence] = deduped.map { (ring: Ring) =>
      val last: Option[LedgerRecord] =
        records
          .filter((r: LedgerRecord) => r.ring == ring)
          .lastOption
      classify(ring, last, implementingSession)
    }
    CheckpointReport.of(change, spec, baseline, evidences, chainState)

  /**
   * One ring's evidence: the last recorded row (or none) classified by
   * the R8 ladder / exit status.
   */
  def classify(
    ring: Ring,
    lastRecord: Option[LedgerRecord],
    implementingSession: Option[SessionId]
  ): RingEvidence =
    lastRecord match
      case None => RingEvidence(ring, RingStatus.Unevidenced, None, None)
      case Some(record) =>
        val byExit: RingStatus =
          if record.exit == 0 then RingStatus.Green else RingStatus.Failed
        if ring != Ring.R8 then RingEvidence(ring, byExit, Some(record), None)
        else
          implementingSession match
            case None =>
              // No implementing session — cannot verify fresh-context;
              // report the limitation, never green.
              RingEvidence(
                ring,
                if record.exit == 0 then RingStatus.UnverifiedSession else RingStatus.Failed,
                Some(record),
                Some(
                  "No implementing session provided to checkpoint — cannot verify fresh-context, requires explicit human attestation of freshness"
                )
              )
            case Some(impl) =>
              record.optional.session match
                case None =>
                  // A session-less R8 row should have been rejected by the
                  // contract; if a legacy row slipped through, flag it.
                  RingEvidence(
                    ring,
                    RingStatus.SameSession,
                    Some(record),
                    Some("R8 row has no session field — cannot verify fresh-context")
                  )
                case Some(sess) if sess == impl.raw =>
                  RingEvidence(
                    ring,
                    RingStatus.SameSession,
                    Some(record),
                    Some(
                      s"R8 review recorded in same session as implementation — no fresh-context evidence (session: $sess)"
                    )
                  )
                case Some(sess) =>
                  if sess.startsWith("ppid-") then
                    RingEvidence(
                      ring,
                      if record.exit == 0 then RingStatus.UnverifiedSession else RingStatus.Failed,
                      Some(record),
                      Some(
                        s"R8 session is the PPID fallback ($sess) — unverified session source, requires explicit human attestation of freshness"
                      )
                    )
                  else
                    // A different, non-ppid verified session — green or
                    // failed by the row's exit like any other ring.
                    RingEvidence(ring, byExit, Some(record), None)

  /**
   * The marker decision, mirrored by `LedgerValidatorKernel.markerDecision`:
   * granted iff every requested ring appears in the evidenced set and the
   * supplied verdict reports nothing unresolved.
   *
   * spec: ledger-checkpoint-parity — Formal Contract: markerDecision
   */
  def markerDecision(
    requested: List[Ring],
    evidenced: List[Ring],
    unresolvedCount: Int
  ): Boolean =
    requested.forall(evidenced.contains) && unresolvedCount == 0

  /**
   * The supplied verdict's unresolved count — jq `length` semantics on
   * the verdict's own `unresolved` member: absent/`null` → 0; an array
   * → its size; an object → its key count; a number → its absolute
   * value; a string → its length; a boolean → 1 (jq `length` on a
   * boolean is an error — the verdict is never "clean" when its
   * unresolved member cannot be measured). This READS the verdict; it
   * does not recompute it.
   */
  def unresolvedCountOf(chainState: ujson.Value): Int =
    chainState.objOpt match
      case None => 1 // a non-object verdict cannot be measured — never "clean"
      case Some(fields) =>
        fields.get("unresolved") match
          case None                   => 0
          case Some(ujson.Null)       => 0
          case Some(ujson.Arr(items)) => items.length
          case Some(ujson.Obj(m))     => m.size
          case Some(ujson.Num(n))     => math.abs(n).toInt
          case Some(ujson.Str(s))     => s.length
          case Some(
                _
              ) => // danger-scan:allow unmeasurable — jq `length` on a boolean errors; an unmeasurable unresolved member is never "clean", so the count is nonzero
            1

  /**
   * The per-spec baseline from an implementation-progress tracker — the
   * predecessor's awk: `## Spec N/M: <name>` sections whose name
   * CONTAINS `spec` as a substring, then the first `[a-f0-9]{7,40}`
   * match on a `**BASELINE SHA**` line within that section.
   */
  def specBaseline(progressText: String, spec: String): Option[String] =
    val sectionHeader: scala.util.matching.Regex = "^## Spec [0-9]+/[0-9]+: (.*)$".r
    val sha: scala.util.matching.Regex           = "[a-f0-9]{7,40}".r
    final case class Scan(inSpec: Boolean, found: Option[String])
    val result: Scan = progressText.linesIterator.foldLeft(Scan(inSpec = false, found = None)) {
      (acc: Scan, line: String) =>
        if acc.found.isDefined then acc
        else
          sectionHeader.findFirstMatchIn(line) match
            case Some(m: scala.util.matching.Regex.Match) =>
              // A section header — the spec match is a substring test,
              // exactly as the predecessor's `index(section, spec) > 0`.
              Scan(inSpec = m.group(1).contains(spec), found = None)
            case None =>
              if acc.inSpec && line.contains("**BASELINE SHA**") then acc.copy(found = sha.findFirstIn(line))
              else acc
    }
    result.found

  /**
   * Regenerate tasks.md checkbox state from the progress tracker — the
   * predecessor's two passes: `### N.` progress sections are complete
   * iff their `| Commit |` cell matches `^[0-9a-f]{7,40}$`; every
   * `- [ ]`/`- [x]` line inside a `## N.` tasks section is rewritten to
   * that spec's verdict (absent → incomplete). All other text is
   * preserved verbatim.
   */
  def regenerateTasks(progressText: String, tasksText: String): String =
    val progressSection: scala.util.matching.Regex = "^### ([0-9]+)\\.".r
    val commitCell: scala.util.matching.Regex      = "^\\|\\s*Commit\\s*\\|(.*)\\|\\s*$".r
    val commitSha: scala.util.matching.Regex       = "^[0-9a-f]{7,40}$".r

    // ── pass 1: which specs are complete, per the progress tracker ────
    // A spec counts as complete iff its own `| Commit |` cell holds a
    // value that matches the revision shape — backticks and whitespace
    // stripped first, as the predecessor does.
    final case class Pass1(
      curSpec: Option[String],
      curCommit: String,
      complete: Map[String, Boolean]
    )
    def flushed(p: Pass1): Map[String, Boolean] =
      p.curSpec match
        case None => p.complete
        case Some(n) =>
          val clean: String = p.curCommit.replace("`", "").trim
          // First matching section wins — the predecessor's pass-2 lookup
          // is `awk '$1==s{print $2; exit}'` over the TSV, so a duplicate
          // `### N.` section's later verdict must not overwrite the first.
          if p.complete.contains(n) then p.complete
          else p.complete + (n -> commitSha.matches(clean))

    val scanned: Pass1 =
      progressText.linesIterator.foldLeft(Pass1(None, "", Map.empty[String, Boolean])) { (acc: Pass1, line: String) =>
        progressSection.findFirstMatchIn(line) match
          case Some(m: scala.util.matching.Regex.Match) =>
            Pass1(Some(m.group(1)), "", flushed(acc))
          case None =>
            commitCell.findFirstMatchIn(line) match
              case Some(m: scala.util.matching.Regex.Match) =>
                acc.copy(curCommit = m.group(1))
              case None => acc
      }
    val complete: Map[String, Boolean] = flushed(scanned)

    // ── pass 2: rewrite tasks.md's checkbox state, section by section ─
    // Every line's text is preserved verbatim except the checkbox marker
    // itself; the spec-number lookup is exact (the predecessor's Ring-8
    // fix — a substring match lets spec "1" collide with spec "21").
    val tasksSection: scala.util.matching.Regex = "^## ([0-9]+)\\.".r
    val checkbox: scala.util.matching.Regex     = "^- \\[[ x]\\] (.*)$".r
    final case class Pass2(curSpec: Option[String], out: List[String])
    val rebuilt: Pass2 = tasksText.linesIterator.foldLeft(Pass2(None, List.empty[String])) {
      (acc: Pass2, line: String) =>
        tasksSection.findFirstMatchIn(line) match
          case Some(m: scala.util.matching.Regex.Match) =>
            Pass2(Some(m.group(1)), acc.out :+ line)
          case None =>
            acc.curSpec match
              case None => acc.copy(out = acc.out :+ line)
              case Some(specNo) =>
                checkbox.findFirstMatchIn(line) match
                  case Some(m: scala.util.matching.Regex.Match) =>
                    val mark: String =
                      if complete.getOrElse(specNo, default = false) then "- [x] " else "- [ ] "
                    acc.copy(out = acc.out :+ (mark + m.group(1)))
                  case None => acc.copy(out = acc.out :+ line)
    }
    // The predecessor emits one line + '\n' per input line — a non-empty
    // file's output always ends with a newline.
    if rebuilt.out.isEmpty then "" else rebuilt.out.mkString("\n") + "\n"

end CheckpointEngine
