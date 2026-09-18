package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

/**
 * Oracle for the generated checkpoint (spec 7).
 *
 * Written from the spec and the approved Step-1 contract ONLY — before the
 * `???` implementations land. Derived from the spec's requirements and
 * scenarios, NOT from the implementation.
 *
 * The checkpoint is generated from recorded evidence: every requested ring
 * is named exactly once, each either evidenced by its LAST recorded row at
 * the current baseline or stated unevidenced. The supplied correctness
 * verdict is consumed verbatim — never recomputed. The presentation
 * marker is written iff every requested ring is evidenced AND the verdict
 * reports nothing unresolved.
 *
 * spec: ledger-checkpoint-parity — Requirement: The checkpoint is generated from recorded evidence, never authored
 * spec: ledger-checkpoint-parity — Requirement: The checkpoint does not recompute the correctness verdict
 * spec: ledger-checkpoint-parity — Requirement: The presentation marker is written only when every requested ring is evidenced and the verdict is fully discharged
 * spec: ledger-checkpoint-parity — Property: checkpoint-reports-every-requested-ring
 * spec: ledger-checkpoint-parity — Property: marker-written-iff-evidenced-and-discharged
 * spec: ledger-checkpoint-parity — Compile-Negative: A CheckpointReport constructed with a marker-written flag set while any requested ring is unevidenced
 * spec: ledger-checkpoint-parity — Compile-Negative: A correctness computation referenced from the checkpoint module
 */
final class CheckpointParitySpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(120))

  // ── Fixture helpers ─────────────────────────────────────────────────

  private val baseline: String = "abc1234"

  private def recordFor(
    ring: Ring,
    exit: Int,
    base: String = baseline,
    session: Option[String] = None
  ): LedgerRecord =
    val json: ujson.Obj = ujson.Obj(
      "v"          -> ujson.Num(1),
      "ts"         -> ujson.Str("2026-09-18T00:00:00Z"),
      "change"     -> ujson.Str("chg"),
      "spec"       -> ujson.Str("spc"),
      "ring"       -> ujson.Str(Ring.asString(ring)),
      "obligation" -> ujson.Str(s"obligation for ${Ring.asString(ring)}"),
      "artifact"   -> ujson.Str(s"artifact-${Ring.asString(ring)}"),
      "command"    -> ujson.Str(s"cmd-${Ring.asString(ring)}"),
      "exit"       -> ujson.Num(exit),
      "baseline"   -> ujson.Str(base)
    )
    session.foreach((s: String) => json.value("session") = ujson.Str(s))
    LedgerRecord.from(json).getOrElse(fail("fixture record must construct"))

  /** A chain-state.sh-shaped verdict with `n` unresolved entries. */
  private def verdict(unresolved: Int): ujson.Value =
    val entries: ujson.Arr =
      ujson.Arr(
        (0 until unresolved).map((i: Int) =>
          ujson.Obj(
            "spec"        -> ujson.Str("spc"),
            "requirement" -> ujson.Str(s"Req $i"),
            "reasons"     -> ujson.Arr(ujson.Str("undischarged"))
          )
        )*
      )
    ujson.Obj(
      "change"               -> ujson.Str("chg"),
      "baseline"             -> ujson.Str(baseline),
      "total"                -> ujson.Num(4),
      "bound"                -> ujson.Num(4),
      "resolved"             -> ujson.Num(4),
      "discharged"           -> ujson.Num(4 - unresolved),
      "unresolved"           -> entries,
      "unmapped_obligations" -> ujson.Arr()
    )

  private def evidenceOf(report: CheckpointReport, ring: Ring): RingEvidence =
    report.rings
      .find((e: RingEvidence) => e.ring == ring)
      .getOrElse(fail(s"ring ${Ring.asString(ring)} must be reported"))

  // ── Generator: genCheckpointInputs ───────────────────────────────────
  // Declared before the tests: the class-initialisation checker requires
  // every field a test closure could touch to be already initialised.

  private val allRings: List[Ring] =
    List(Ring.R0, Ring.R1, Ring.R2, Ring.R3, Ring.R4, Ring.R5, Ring.R6, Ring.R7, Ring.R8, Ring.R9, Ring.Manual)

  /**
   * Random rings WITH repetition — the de-dup (first occurrence wins) is
   * only exercised when the requested list can hold a ring twice.
   */
  private val genRequested: Gen[List[Ring]] =
    Gen.elementUnsafe(allRings).list(Range.linear(1, 6))

  private val genRecord: Gen[LedgerRecord] =
    for
      ring <- Gen.elementUnsafe(allRings)
      exit <- Gen.frequency1(70 -> Gen.constant(0), 30 -> Gen.int(Range.linear(1, 5)))
      base <- Gen.frequency1(60 -> Gen.constant(baseline), 40 -> Gen.constant("deadbee"))
      session <- Gen.frequency1(
        40 -> Gen.constant(Option("other-session")),
        30 -> Gen.constant(Option("impl-session")),
        30 -> Gen.constant(Option.empty[String])
      )
    yield recordFor(ring, exit = exit, base = base, session = session)

  /**
   * A record for a specific ring AT the current baseline — used to
   * guarantee coverage of requested rings.
   */
  private def genCoveredRecord(ring: Ring): Gen[LedgerRecord] =
    for
      exit <- Gen.frequency1(70 -> Gen.constant(0), 30 -> Gen.int(Range.linear(1, 5)))
      session <- Gen.frequency1(
        40 -> Gen.constant(Option("other-session")),
        30 -> Gen.constant(Option("impl-session")),
        30 -> Gen.constant(Option.empty[String])
      )
    yield recordFor(ring, exit = exit, session = session)

  /**
   * A record at a STALE baseline — feeds the evidence-at-wrong-baseline
   * class (the extras list alone produces one too rarely: Hedgehog list
   * sizes skew small, so a 40%-wrong extra fires in only ~23% of draws
   * against the 25% gate).
   */
  private val genWrongBaselineRecord: Gen[LedgerRecord] =
    for
      ring <- Gen.elementUnsafe(allRings)
      exit <- Gen.frequency1(70 -> Gen.constant(0), 30 -> Gen.int(Range.linear(1, 5)))
      session <- Gen.frequency1(
        40 -> Gen.constant(Option("other-session")),
        30 -> Gen.constant(Option("impl-session")),
        30 -> Gen.constant(Option.empty[String])
      )
    yield recordFor(ring, exit = exit, base = "deadbee", session = session)

  final case class CheckpointInputs(
    requested: List[Ring],
    records: List[LedgerRecord],
    unresolved: Int
  ):
    /**
     * Every requested ring has a row in the supplied set — selection is
     * by ring only (the read path owns baseline filtering; Ring-8
     * remediation).
     */
    def allRequestedRingsEvidenced: Boolean =
      requested.forall((r: Ring) => records.exists((rec: LedgerRecord) => rec.ring == r))

  // Oracle construction fix (Step 3, recorded in implementation-progress):
  // the un-biased generator produced `allRequestedRingsEvidenced` ~1% of
  // the time, so the approved 15% coverage labels could never fire. The
  // records are now biased — ~45% of inputs evidence every requested ring
  // at the baseline, the rest cover a random subset — and `unresolved` is
  // 50/50 zero/nonzero. Assertions, labels, and thresholds are unchanged.
  private val genCheckpointInputs: Gen[CheckpointInputs] =
    for
      requested <- genRequested
      distinct = requested.distinct
      coverage <- Gen.frequency1(
        45 -> Gen.constant(distinct),
        55 -> Gen
          .elementUnsafe(distinct)
          .list(Range.linear(0, distinct.length))
          .map((rs: List[Ring]) => rs.distinct)
      )
      coverageRecords <- coverage.foldRight(Gen.constant(List.empty[LedgerRecord])) {
        (r: Ring, acc: Gen[List[LedgerRecord]]) =>
          for
            rs  <- acc
            rec <- genCoveredRecord(r)
          yield rec :: rs
      }
      extras <- genRecord.list(Range.linear(0, 5))
      wrongBase <- Gen.frequency1(
        35 -> genWrongBaselineRecord.map((r: LedgerRecord) => List(r)),
        65 -> Gen.constant(List.empty[LedgerRecord])
      )
      unresolved <- Gen.frequency1(50 -> Gen.constant(0), 50 -> Gen.int(Range.linear(1, 3)))
    yield CheckpointInputs(requested, coverageRecords ++ extras ++ wrongBase, unresolved)

  // ── Scenario: Happy path — a ring with records at the current
  //    baseline is reported green with them ────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — a ring with records at the current baseline is reported green with them

  test("a ring with a recorded green row at the current baseline is reported green"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0),
      records = List(recordFor(Ring.R0, exit = 0)),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R0)
    assertEquals(ev.status, RingStatus.Green)
    assert(ev.record.isDefined, "the green ring must trace to its record")

  // ── Scenario: Adversarial — a ring with no records is reported
  //    unevidenced, not green ──────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — a ring with no records is reported unevidenced, not green

  test("a ring with no recorded rows is reported unevidenced, never green"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0, Ring.R1),
      records = List(recordFor(Ring.R0, exit = 0)),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R1)
    assertEquals(ev.status, RingStatus.Unevidenced)
    assert(ev.record.isEmpty, "an unevidenced ring must not cite a record")
    assert(!report.markerWritten, "an unevidenced ring forbids the marker")

  // ── Scenario: Edge case — a record at a superseded baseline does not
  //    evidence the ring ───────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Edge case — a record at a superseded baseline does not evidence the ring
  //
  // Ring-8 remediation: the spec's scenario is exercised END-TO-END in
  // LedgerParitySpec through `readRowsFiltered` — the layer that owns
  // exclusion (a stale row the read path did not forgive never reaches
  // the engine). The engine itself selects by ring only, mirroring the
  // predecessor's `select(.ring == $ring)` over the already-filtered
  // `ledger_out`: a row the read path FORGAVE keeps its stored baseline,
  // and re-checking it here would drop exactly the evidence forgiveness
  // exists to keep. This test pins the delegation contract.

  test("the engine selects by ring over the supplied rows — baseline filtering belongs to the read path"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0),
      records = List(recordFor(Ring.R0, exit = 0, base = "deadbee")),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R0)
    assertEquals(
      ev.status,
      RingStatus.Green,
      "a stale-baseline row the read path emitted (forgiven) still evidences its ring"
    )
    assertEquals(ev.record.map(_.baseline), Some("deadbee"))

  // ── Scenario: last duplicate row wins ────────────────────────────────
  // spec: ledger-checkpoint-parity — Requirement: The checkpoint is generated from recorded evidence, never authored

  test("the LAST recorded row for a ring is the one the verdict traces to"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0),
      records = List(
        recordFor(Ring.R0, exit = 0),
        recordFor(Ring.R0, exit = 1)
      ),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R0)
    assertEquals(ev.status, RingStatus.Failed, "the last row records exit 1 — the ring is failed, not green")
    assertEquals(ev.record.map(_.exit), Some(1))

  // ── Scenario: Adversarial — a review-ring record produced by the
  //    implementing session is reported as such ────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — a review-ring record produced by the implementing session is reported as such

  test("an R8 row recorded by the implementing session is same-session, not green"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("impl-session"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.SameSession)
    assert(ev.note.isDefined, "the same-session status must carry the predecessor's note")

  test("an R8 row recorded by a different session is green by its exit"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("fresh-context-reviewer"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.Green)

  test("an R8 row with no implementing session supplied is unverified-session, not green"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("any-reviewer"))),
      chainState = verdict(0),
      implementingSession = None
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.UnverifiedSession)
    assert(
      ev.note.exists((n: String) => n.contains("No implementing session")),
      "the limitation must be named, not silently unverified"
    )

  test("an R8 row whose session is the ppid fallback is unverified-session"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("ppid-1234"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.UnverifiedSession)

  // ── R8 ladder edges: a failing exit is FAILED, never soft-pedalled ──
  // The unverified-session branches still honour the row's own exit — a
  // session problem AND a failing command is a failure, not a limitation.

  test("an R8 row with no implementing session and a failing exit is failed"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 1, session = Some("any-reviewer"))),
      chainState = verdict(0),
      implementingSession = None
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.Failed)

  test("an R8 row on the ppid fallback with a failing exit is failed"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 2, session = Some("ppid-99"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.Failed)

  test("an R8 row carrying no session field is same-session and says why"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = None)),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ev: RingEvidence = evidenceOf(report, Ring.R8)
    assertEquals(ev.status, RingStatus.SameSession)
    assert(
      ev.note.exists((n: String) => n.contains("no session field")),
      s"the session-less row must carry the no-session note, got ${ev.note}"
    )

  test("the same-session and unverified notes name their reason"):
    val sameSession: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("impl-session"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val ppid: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R8),
      records = List(recordFor(Ring.R8, exit = 0, session = Some("ppid-42"))),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    assert(
      evidenceOf(sameSession, Ring.R8).note.exists((n: String) => n.contains("same session")),
      "same-session evidence must name the session collision"
    )
    assert(
      evidenceOf(ppid, Ring.R8).note.exists((n: String) => n.contains("PPID")),
      "ppid-fallback evidence must name the unverified session source"
    )

  // ── ReplayVerdict.classifyName — the shipped verify path's raw-name
  //    classifier: judgment rings by name, everything else by exit ─────

  test("classifyName treats R8 and manual as unreplayable judgment rings"):
    assertEquals(ReplayVerdict.classifyName("R8", Some(0), 0), ReplayVerdict.Unreplayable)
    assertEquals(ReplayVerdict.classifyName("manual", Some(1), 1), ReplayVerdict.Unreplayable)

  test("classifyName judges a name outside the closed domain by exit, never unreplayable"):
    assertEquals(ReplayVerdict.classifyName("bogus-ring", Some(0), 0), ReplayVerdict.Matches)
    assertEquals(ReplayVerdict.classifyName("bogus-ring", Some(1), 0), ReplayVerdict.Diverges)
    assertEquals(ReplayVerdict.classifyName("bogus-ring", None, 0), ReplayVerdict.Unreplayable)

  test("classifyName judges runnable ring names by the shared exit comparison"):
    assertEquals(ReplayVerdict.classifyName("R0", Some(0), 0), ReplayVerdict.Matches)
    assertEquals(ReplayVerdict.classifyName("R2", Some(3), 0), ReplayVerdict.Diverges)

  // ── Report renderers — the predecessor's emitted shapes ─────────────

  test("toJson renders the predecessor object — per-ring rows, verdict verbatim"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0, Ring.R1, Ring.R8),
      records = List(
        recordFor(Ring.R0, exit = 0),
        recordFor(Ring.R8, exit = 0, session = Some("impl-session"))
      ),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    val json: ujson.Value = CheckpointReport.toJson(report)
    assertEquals(json("change").str, "chg")
    assertEquals(json("spec").str, "spc")
    assertEquals(json("baseline").str, baseline)
    assertEquals(json("chain_state"), report.chainState, "the verdict is emitted verbatim")
    val rows: List[ujson.Value] = json("rings").arr.toList
    assertEquals(rows.length, 3)
    assertEquals(rows(0)("ring").str, "R0")
    assertEquals(rows(0)("status").str, "green")
    assertEquals(rows(0)("obligation").str, "obligation for R0")
    assertEquals(rows(0)("artifact").str, "artifact-R0")
    assertEquals(rows(0)("command").str, "cmd-R0")
    assertEquals(rows(0)("exit").num, 0.0)
    assertEquals(rows(1)("ring").str, "R1")
    assertEquals(rows(1)("status").str, "unevidenced")
    assert(!rows(1).obj.contains("command"), "an unevidenced row carries only ring and status")
    assertEquals(rows(2)("ring").str, "R8")
    assertEquals(rows(2)("status").str, "same-session")
    assert(
      rows(2)("note").str.contains("same session"),
      "a noted status emits its note member"
    )

  test("toText renders per-ring lines then the supplied counts and unresolved block"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0, Ring.R1, Ring.R2),
      records = List(recordFor(Ring.R0, exit = 0), recordFor(Ring.R1, exit = 3)),
      chainState = verdict(1),
      implementingSession = None
    )
    val text: String = CheckpointReport.toText(report)
    assert(text.startsWith(s"checkpoint: chg/spc @ $baseline\n"), s"missing header:\n$text")
    assert(text.contains("  R0: green (cmd-R0)"), s"missing green line:\n$text")
    assert(text.contains("  R1: FAILED (cmd-R1, exit 3)"), s"missing failed line:\n$text")
    assert(text.contains("  R2: no recorded evidence"), s"missing unevidenced line:\n$text")
    assert(
      text.contains("green (cmd-R0)\n  R1: FAILED"),
      s"ring lines join on newlines:\n$text"
    )
    assert(
      text.contains("  chain state: total 4  bound 4  resolved 4  discharged 3  unresolved 1"),
      s"missing chain-state line:\n$text"
    )
    assert(
      text.contains("unresolved 1\n    Req 0 (undischarged)"),
      s"the unresolved block follows the count line:\n$text"
    )

  test("toText renders jq-null interpolations for absent members and non-floor numbers"):
    val partialVerdict: ujson.Value = ujson.Obj(
      "total"      -> ujson.Num(4.5),
      "unresolved" -> ujson.Arr(ujson.Obj("spec" -> ujson.Str("spc"), "reasons" -> ujson.Str("not-an-array")))
    )
    val report: CheckpointReport = CheckpointReport.of(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      rings = List(
        RingEvidence(Ring.R0, RingStatus.Green, None, None),
        RingEvidence(Ring.R1, RingStatus.Failed, None, None)
      ),
      chainState = partialVerdict
    )
    val text: String = CheckpointReport.toText(report)
    assert(text.contains("  R0: green (null)"), s"a record-less row interpolates null:\n$text")
    assert(text.contains("  R1: FAILED (null, exit null)"), s"a record-less failure interpolates null:\n$text")
    assert(text.contains("total 4.5"), s"a non-floor number renders its decimal form:\n$text")
    assert(text.contains("bound null"), s"an absent count member renders null:\n$text")
    assert(text.contains("null (null)"), s"missing requirement/reasons members render null:\n$text")
    val emptyRings: CheckpointReport = CheckpointReport.of(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      rings = List.empty[RingEvidence],
      chainState = verdict(0)
    )
    assert(
      CheckpointReport.toText(emptyRings).startsWith(s"checkpoint: chg/spc @ $baseline\n  chain state:"),
      "a ring-less report emits no blank ring block"
    )
    assert(
      CheckpointReport.toText(emptyRings).endsWith("unresolved 0"),
      "an empty unresolved array emits no dangling unresolved block"
    )

  test("toText joins multiple unresolved entries and multiple reasons with the predecessor separators"):
    val multiVerdict: ujson.Value = ujson.Obj(
      "total" -> ujson.Num(4),
      "unresolved" -> ujson.Arr(
        ujson.Obj(
          "requirement" -> ujson.Str("Req A"),
          "reasons"     -> ujson.Arr(ujson.Str("r1"), ujson.Str("r2"))
        ),
        ujson.Obj(
          "requirement" -> ujson.Str("Req B"),
          "reasons"     -> ujson.Arr(ujson.Str("r3"))
        )
      )
    )
    val report: CheckpointReport = CheckpointReport.of(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      rings = List.empty[RingEvidence],
      chainState = multiVerdict
    )
    val text: String = CheckpointReport.toText(report)
    assert(
      text.contains("unresolved 2\n    Req A (r1,r2)\n    Req B (r3)"),
      s"entries join on newlines, reasons on commas:\n$text"
    )

  test("toText renders the session statuses' fallback notes and jq renders a null member"):
    val nullVerdict: ujson.Value = ujson.Obj("total" -> ujson.Null)
    val report: CheckpointReport = CheckpointReport.of(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      rings = List(
        RingEvidence(Ring.R8, RingStatus.SameSession, None, None),
        RingEvidence(Ring.R7, RingStatus.UnverifiedSession, Some(recordFor(Ring.R7, exit = 0)), None)
      ),
      chainState = nullVerdict
    )
    val text: String = CheckpointReport.toText(report)
    assert(
      text.contains("  R8: SAME-SESSION (null) — no fresh-context evidence"),
      s"a note-less same-session row emits the fallback note:\n$text"
    )
    assert(
      text.contains("  R7: UNVERIFIED-SESSION (cmd-R7) — requires human attestation"),
      s"a note-less unverified-session row emits the fallback note:\n$text"
    )
    assert(text.contains("total null"), s"an explicit null member renders as jq's null:\n$text")

  test("toJson emits the failed and unverified-session status tokens"):
    val report: CheckpointReport = CheckpointReport.of(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      rings = List(
        RingEvidence(Ring.R1, RingStatus.Failed, Some(recordFor(Ring.R1, exit = 2)), None),
        RingEvidence(Ring.R8, RingStatus.UnverifiedSession, Some(recordFor(Ring.R8, exit = 0)), Some("note-R8"))
      ),
      chainState = verdict(0)
    )
    val rows: List[ujson.Value] = CheckpointReport.toJson(report)("rings").arr.toList
    assertEquals(rows(0)("status").str, "failed")
    assertEquals(rows(1)("status").str, "unverified-session")
    assertEquals(rows(1)("note").str, "note-R8")

  // ── Tracker functions — specBaseline + regenerateTasks ──────────────

  test("specBaseline finds the per-spec baseline under a matching section, else none"):
    val progress: String =
      """- **BASELINE SHA**: `fffffffff`
        |## Spec 6/9: danger-reconcile
        |- **BASELINE SHA**: `deadbee1234`
        |## Spec 7/9: ledger-checkpoint-parity
        |- a text line mentioning `feedface` is not a baseline line
        |x ## Spec 8/9: mid-line decoy is not a section header
        |- **BASELINE SHA**: `abc1234def`
        |## Spec 8/9: gate-event-completeness
        |- **BASELINE SHA**: `0000000000`
        |""".stripMargin
    assertEquals(
      CheckpointEngine.specBaseline(progress, "ledger-checkpoint-parity"),
      Some("abc1234def"),
      "the baseline line inside the spec-7 section only — not the pre-section line, the decoy hex, or the mid-line header"
    )
    assertEquals(CheckpointEngine.specBaseline(progress, "no-such-spec"), None)
    assertEquals(
      CheckpointEngine.specBaseline(
        "## Spec 77/9: ledger-checkpoint-parity\n- **BASELINE SHA**: `aaa1111`\n",
        "ledger-checkpoint-parity"
      ),
      Some("aaa1111"),
      "a two-digit spec number is a valid section"
    )
    assertEquals(
      CheckpointEngine.specBaseline(
        "## Spec 7/99: ledger-checkpoint-parity\n- **BASELINE SHA**: `bbb2222`\n",
        "ledger-checkpoint-parity"
      ),
      Some("bbb2222"),
      "a two-digit total is a valid section"
    )

  test("regenerateTasks rewrites only checkbox markers inside matching ## N. sections"):
    val progress: String =
      """### 1. spec-one
        || Commit | `aaa1111` |
        |### 7. spec-seven
        || Commit | pending |
        |x| Commit | `bbb2222` |
        |x### 42. mid-line decoy is not a section
        || Commit | `fff6666` |
        |x| Commit | pending |
        |### 8. spec-eight
        || Commit | `AAA1111` |
        |### 9. spec-nine
        || Commit | `ccc3333` |
        |### 9. spec-nine-duplicate
        || Commit | pending |
        |### 10. spec-ten
        ||Commit | `ddd4444` |
        |### 11. spec-eleven
        || Commit | `eee5555` | trailing junk after the cell
        |### 12. spec-twelve
        || Commit  | `def1111` |
        |### 13. spec-thirteen
        || Commit | `fff9999` |junk
        |""".stripMargin
    val tasks: String =
      """preamble line — preserved verbatim
        |## 1. spec-one
        |- [ ] task alpha
        |- [ ]
        |  - [ ] indented is not a checkbox
        |## 7. spec-seven
        |- [x] task beta
        |## 8. spec-eight
        |- [ ] task uppercase sha is not a commit
        |## 9. spec-nine
        |- [ ] task first-wins
        |x## 2. mid-line is not a section
        |- [ ] stray stays inside section nine
        |## 7x the dot must be literal
        |- [ ] also stays inside section nine
        |## 10. spec-ten
        |- [ ] task delta
        |## 11. spec-eleven
        |- [ ] task epsilon
        |## 12. spec-twelve
        |- [ ] task eta
        |## 13. spec-thirteen
        |- [ ] task theta
        |## 21. spec-twenty-one
        |- [ ] task gamma
        |## 42. spec-forty-two
        |- [ ] task zeta
        |""".stripMargin
    val expected: String =
      """preamble line — preserved verbatim
        |## 1. spec-one
        |- [x] task alpha
        |- [ ]
        |  - [ ] indented is not a checkbox
        |## 7. spec-seven
        |- [x] task beta
        |## 8. spec-eight
        |- [ ] task uppercase sha is not a commit
        |## 9. spec-nine
        |- [x] task first-wins
        |x## 2. mid-line is not a section
        |- [x] stray stays inside section nine
        |## 7x the dot must be literal
        |- [x] also stays inside section nine
        |## 10. spec-ten
        |- [x] task delta
        |## 11. spec-eleven
        |- [ ] task epsilon
        |## 12. spec-twelve
        |- [x] task eta
        |## 13. spec-thirteen
        |- [ ] task theta
        |## 21. spec-twenty-one
        |- [ ] task gamma
        |## 42. spec-forty-two
        |- [ ] task zeta
        |""".stripMargin
    assertEquals(CheckpointEngine.regenerateTasks(progress, tasks), expected)
    assertEquals(
      CheckpointEngine.regenerateTasks(progress, ""),
      "",
      "an empty tasks file regenerates to nothing"
    )

  // ── Scenario: Happy path — supplied counts appear unchanged ─────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — supplied counts appear unchanged

  test("the supplied verdict's counts are reported unchanged"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0),
      records = List(recordFor(Ring.R0, exit = 0)),
      chainState = verdict(2),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    assertEquals(report.unresolvedCount, 2, "the verdict's own unresolved member is the reported count")
    assertEquals(report.chainState, verdict(2), "the verdict is carried verbatim")

  // ── Scenario: Adversarial — an undischarged verdict writes no marker ─
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — an undischarged verdict writes no marker

  test("a fully evidenced ring set with an undischarged verdict writes no marker"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0),
      records = List(recordFor(Ring.R0, exit = 0)),
      chainState = verdict(1),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    assert(!report.markerWritten, "an unresolved requirement forbids the marker")

  // ── Scenario: Adversarial — an unevidenced ring writes no marker ────
  // spec: ledger-checkpoint-parity — Scenario: Adversarial — an unevidenced ring writes no marker

  test("a discharged verdict with an unevidenced ring writes no marker"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0, Ring.R1),
      records = List(recordFor(Ring.R0, exit = 0)),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    assert(!report.markerWritten, "an unevidenced requested ring forbids the marker")

  // ── Scenario: Happy path — a fully evidenced and discharged
  //    checkpoint writes the marker ────────────────────────────────────
  // spec: ledger-checkpoint-parity — Scenario: Happy path — a fully evidenced and discharged checkpoint writes the marker

  test("a fully evidenced and discharged checkpoint permits the marker"):
    val report: CheckpointReport = CheckpointEngine.report(
      change = "chg",
      spec = "spc",
      baseline = baseline,
      requested = List(Ring.R0, Ring.R1),
      records = List(recordFor(Ring.R0, exit = 0), recordFor(Ring.R1, exit = 0)),
      chainState = verdict(0),
      implementingSession = Some(SessionId.fromRaw("impl-session"))
    )
    assert(report.markerWritten, "evidenced + discharged must permit the marker")

  // ── Property: checkpoint-reports-every-requested-ring ───────────────
  // spec: ledger-checkpoint-parity — Property: checkpoint-reports-every-requested-ring

  property("checkpoint-reports-every-requested-ring", coverConfig):
    for in <- genCheckpointInputs.forAll
        .cover(15, "all-rings-evidenced", (x: CheckpointInputs) => x.allRequestedRingsEvidenced)
        .cover(15, "none-evidenced", (x: CheckpointInputs) => !x.allRequestedRingsEvidenced)
        .cover(
          25,
          "evidence-at-wrong-baseline",
          (x: CheckpointInputs) => x.records.exists((r: LedgerRecord) => r.baseline != baseline)
        )
    yield
      val report: CheckpointReport = CheckpointEngine.report(
        change = "chg",
        spec = "spc",
        baseline = baseline,
        requested = in.requested,
        records = in.records,
        chainState = verdict(in.unresolved),
        implementingSession = Some(SessionId.fromRaw("impl-session"))
      )
      val reported: List[Ring] = report.rings.map((e: RingEvidence) => e.ring)
      Result
        .assert(reported.toSet == in.requested.toSet)
        .log(s"requested ${in.requested} but reported $reported")
        .and(
          Result
            .assert(reported.length == reported.distinct.length)
            .log(s"a ring is reported more than once: $reported")
        )

  // ── Property: marker-written-iff-evidenced-and-discharged ───────────
  // spec: ledger-checkpoint-parity — Property: marker-written-iff-evidenced-and-discharged

  property("marker-written-iff-evidenced-and-discharged", coverConfig):
    for in <- genCheckpointInputs.forAll
        .cover(15, "evidenced-discharged", (x: CheckpointInputs) => x.allRequestedRingsEvidenced && x.unresolved == 0)
        .cover(15, "evidenced-undischarged", (x: CheckpointInputs) => x.allRequestedRingsEvidenced && x.unresolved > 0)
        .cover(
          15,
          "unevidenced-discharged",
          (x: CheckpointInputs) => !x.allRequestedRingsEvidenced && x.unresolved == 0
        )
        .cover(
          15,
          "unevidenced-undischarged",
          (x: CheckpointInputs) => !x.allRequestedRingsEvidenced && x.unresolved > 0
        )
    yield
      val report: CheckpointReport = CheckpointEngine.report(
        change = "chg",
        spec = "spc",
        baseline = baseline,
        requested = in.requested,
        records = in.records,
        chainState = verdict(in.unresolved),
        implementingSession = Some(SessionId.fromRaw("impl-session"))
      )
      // The expectation is derived from the INPUTS, never from the report
      // under test: "evidenced" for the marker means GREEN-evidenced —
      // the ring's LAST supplied row exists AND classifies green under
      // the R8 ladder (re-derived here independently of the engine:
      // implementing session is "impl-session" in these draws; a row's
      // same/ppid/absent session is never green).
      def greenByLadder(rec: LedgerRecord, ring: Ring): Boolean =
        if ring != Ring.R8 then rec.exit == 0
        else
          rec.optional.session match
            case Some(s) if s != "impl-session" && !s.startsWith("ppid-") => rec.exit == 0
            case _ => false // danger-scan:allow ladder-default — same/ppid/absent session is never green
      val expectedMarker: Boolean =
        in.requested.distinct.forall { (ring: Ring) =>
          in.records
            .filter((rec: LedgerRecord) => rec.ring == ring)
            .lastOption
            .exists((rec: LedgerRecord) => greenByLadder(rec, ring))
        } && in.unresolved == 0
      Result
        .assert(report.markerWritten == expectedMarker)
        .log(
          s"marker=${report.markerWritten} but expected=$expectedMarker unresolved=${in.unresolved} requested=${in.requested}"
        )

  // ── Compile-Negative: a CheckpointReport constructed with a
  //    marker-written flag set while any requested ring is unevidenced ──
  // spec: ledger-checkpoint-parity — Compile-Negative: A CheckpointReport constructed with a marker-written flag set while any requested ring is unevidenced

  test("CheckpointReport's raw constructor is private — a hand-set markerWritten does not compile"):
    val err: String = compileErrors(
      "CheckpointReport(change = \"c\", spec = \"s\", baseline = \"b\", rings = Nil, chainState = ujson.Obj(), unresolvedCount = 0, markerWritten = true)"
    )
    assert(
      err.nonEmpty,
      "the raw constructor must not be callable — markerWritten derives only through CheckpointReport.of"
    )

  // ── Compile-Negative: a correctness computation referenced from the
  //    checkpoint module ───────────────────────────────────────────────
  // spec: ledger-checkpoint-parity — Compile-Negative: A correctness computation referenced from the checkpoint module

  test("CheckpointEngine.report does not accept a computed ChainStateReport as its verdict"):
    val err: String = compileErrors(
      "CheckpointEngine.report(change = \"c\", spec = \"s\", baseline = \"b\", requested = List(Ring.R0), records = Nil, chainState = ChainStateReport.fromCounts(\"c\", \"b\", 1, 1, 1, 1, Nil, Nil).toOption.get, implementingSession = None)"
    )
    assert(
      err.nonEmpty,
      "the verdict parameter is opaque ujson.Value — a computed report type must not fit"
    )
