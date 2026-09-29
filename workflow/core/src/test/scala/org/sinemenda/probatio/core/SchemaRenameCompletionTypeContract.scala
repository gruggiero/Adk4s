package org.sinemenda.probatio.core

/**
 * Typed contract for spec: schema-rename-completion (spec 10 of
 * `repair-probatio-cutover`). Typed contract tier: **minimal** — the spec
 * wires an existing verified kernel (`SchemaPolicy.migrateCache`) to a
 * caller and introduces one new pure classification (`RenameDeferral`).
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references. Zero runtime cost; any
 * later signature drift breaks `probatio-core/Test/compile`. The
 * spec-named compile-negative obligations live in
 * `LiveFactBannerTypeContract` (cli) per the spec's Proof Obligations
 * table.
 *
 * spec: schema-rename-completion — Step 1: typed contract (minimal)
 * spec: schema-rename-completion — Concepts Introduced (new): RenameDeferral
 */
final class SchemaRenameCompletionTypeContract extends ProbatioSuite:

  // ── Signature pins (eta-expanded against the real implementation) ───────

  // RenameDeferral.apply: (String, String, Coupling) => RenameDeferral —
  // item, reason and blocking coupling are ALL required; an
  // arity-shortened construction is the compile-negative.
  val deferralApplySig: (String, String, RenameDeferral.Coupling) => RenameDeferral =
    RenameDeferral.apply

  // RenameDeferral.Coupling.apply: (String, String, Int) => Coupling —
  // the coupling is structured: resolution mechanism, configuration pin,
  // and the count of recorded changes that pin the current name.
  val couplingApplySig: (String, String, Int) => RenameDeferral.Coupling =
    RenameDeferral.Coupling.apply

  // RenameDeferral.missing: RenameDeferral => List[Missing] — the
  // completeness check; a deferral entry lacking a reason is reported,
  // not silently accepted.
  val missingSig: RenameDeferral => List[RenameDeferral.Missing] =
    RenameDeferral.missing

  // RenameDeferral.recorded: List[RenameDeferral] — the recorded
  // deferrals. A `def` pin: the value is supplied at Step 3 and must not
  // be evaluated at contract-check time.
  def recordedSig: List[RenameDeferral] =
    RenameDeferral.recorded

  // RenameDeferral field accessors — the record is readable.
  val itemSig: RenameDeferral => String =
    (d: RenameDeferral) => d.item

  val reasonSig: RenameDeferral => String =
    (d: RenameDeferral) => d.reason

  val blockedBySig: RenameDeferral => RenameDeferral.Coupling =
    (d: RenameDeferral) => d.blockedBy

  // ── Existing-kernel pins (the caller this spec supplies) ─────────────

  // SchemaPolicy.migrateCache: CacheState => CacheState — the verified
  // migration this spec wires to the shipped tool.
  val migrateCacheSig: CacheState => CacheState =
    SchemaPolicy.migrateCache

  // SchemaPolicy.renameVersion: Int — the version the acceptance suite's
  // assertion must match.
  val renameVersionSig: Int =
    SchemaPolicy.renameVersion

  // ── Pin tests — the record is readable and carries its parts ─────────

  test("RenameDeferral carries its item, reason and coupling"):
    val d: RenameDeferral = RenameDeferral(
      item = "i",
      reason = "r",
      blockedBy = RenameDeferral.Coupling("m", "p", 3)
    )
    assertEquals(d.item, "i")
    assertEquals(d.reason, "r")
    assertEquals(d.blockedBy.recordedChangesPinning, 3)

  test("Missing has exactly five named gaps"):
    assertEquals(
      RenameDeferral.Missing.values.length,
      5,
      "the completeness check names item, reason, and the three coupling parts"
    )

  // ── Behaviour pins — `missing`/`recorded` semantics (Ring-5 surgical
  // coverage: the cli-side MigrationProtocolSpec cannot cover core
  // mutants, so the pure checks are pinned here in the type's own module)

  private def completeDeferral: RenameDeferral = RenameDeferral(
    item = "i",
    reason = "r",
    blockedBy = RenameDeferral.Coupling("m", "p", 3)
  )

  test("a complete deferral reports no missing parts"):
    assertEquals(RenameDeferral.missing(completeDeferral), List.empty)

  // spec: schema-rename-completion — Scenario: Adversarial — a deferral without a recorded reason is not accepted
  test("a deferral with a blank reason is reported incomplete, naming Reason"):
    val d: RenameDeferral = completeDeferral.copy(reason = "  ")
    assertEquals(RenameDeferral.missing(d), List(RenameDeferral.Missing.Reason))

  test("a deferral with a non-positive recorded-changes count is reported incomplete"):
    val zero: RenameDeferral =
      completeDeferral.copy(blockedBy = RenameDeferral.Coupling("m", "p", 0))
    assertEquals(RenameDeferral.missing(zero), List(RenameDeferral.Missing.RecordedChanges))
    val negative: RenameDeferral =
      completeDeferral.copy(blockedBy = RenameDeferral.Coupling("m", "p", -2))
    assertEquals(RenameDeferral.missing(negative), List(RenameDeferral.Missing.RecordedChanges))

  test("a deferral missing several parts names all of them"):
    val d: RenameDeferral = RenameDeferral(
      item = "",
      reason = "",
      blockedBy = RenameDeferral.Coupling("", "", 0)
    )
    assertEquals(
      RenameDeferral.missing(d).toSet,
      RenameDeferral.Missing.values.toSet,
      "every blank field and non-positive count is reported"
    )

  // spec: schema-rename-completion — Scenario: Happy path — the deferral names the coupling and the blocked items
  test("every recorded deferral is complete and names the blocked coupling"):
    val recorded: List[RenameDeferral] = RenameDeferral.recorded
    assert(recorded.nonEmpty, "at least the directory rename is recorded")
    recorded.foreach { (d: RenameDeferral) =>
      assertEquals(
        RenameDeferral.missing(d),
        List.empty,
        s"recorded deferral '${d.item}' must be complete"
      )
    }
    assert(
      recorded.exists((d: RenameDeferral) => d.item.contains("verified-scala3")),
      "the recorded deferral names the deferred directory rename"
    )
