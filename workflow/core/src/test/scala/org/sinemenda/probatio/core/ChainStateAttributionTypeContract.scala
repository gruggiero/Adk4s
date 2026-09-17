package org.sinemenda.probatio.core

/**
 * Typed contract for chain-state attribution (spec 5, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath, `-Werror`
 * active.
 *
 * Pinned decisions for human review:
 *
 *  - `RequirementSet` is the ONLY way requirements enter the computation:
 *    `ChainState.compute` takes a `RequirementSet`, never a bare
 *    `List[Requirement]` — a literal `Nil` no longer compiles, so the
 *    "no requirements parsed yet" placeholder defect is structurally dead.
 *    A `RequirementSet` whose `requirements` are empty but whose
 *    `specNames` are non-empty is a genuinely empty change, distinguishable
 *    from an unreadable extraction (which never produces a `RequirementSet`
 *    — the caller reports could-not-determine instead).
 *  - `FactSource` records WHICH extraction path produced the set —
 *    `Graph` (the `openspec-graph.py` subprocess export) or `Degraded`
 *    (the in-process fallback over parsed `SpecDocument`s). The path is
 *    data carried on the set, reported by the caller, never inferred.
 *  - `ExtractedObligation` normalises an obligation row across both paths.
 *    `unmappable` is the path's own judgment evaluated at extraction time
 *    (Degraded: no `Requirement: <title>` segment names a real requirement;
 *    Graph: the export's `sources` array is empty). An unmappable row that
 *    carries a finding lands in `unmapped_obligations` — never dropped,
 *    never silently attributed.
 *  - `ChainState.compute` is a pure kernel over typed facts: per-spec lint
 *    outcomes (`Map[String, Outcome[LintReport]]` — a missing or failed
 *    outcome is undetermined), the unfiltered ledger (ring eligibility —
 *    including manual rows — and baseline filtering happen inside the
 *    kernel), a `Map[String, String]` of per-spec baselines, the gate
 *    baseline, and `artifactUnchanged`, an injected pure predicate for the
 *    predecessor's `--forgive-unchanged` rule. Nothing performs I/O.
 *  - `ChainStateReport` / `UnresolvedEntry` are `final case class`es with
 *    private constructors and sealed `copy`: the only construction routes
 *    are `ChainStateReport.fromCounts` (rejecting any count/law violation
 *    the jq contract checks — monotonicity, the list-length law, unique
 *    (spec, requirement) pairs, count↔reason cross-consistency) and
 *    `UnresolvedEntry.of` (rejecting empty names, empty or repeated
 *    reasons). Impossible reports are unrepresentable, not merely untested.
 *    The wire codecs route reads through the same smart constructors.
 *
 * spec: chain-state-attribution — Concepts Introduced (new): RequirementSet
 * spec: chain-state-attribution — Concepts Introduced (new): FactSource
 * spec: chain-state-attribution — Concepts Introduced (new): RequirementExtractor
 */
final class ChainStateAttributionTypeContract extends ProbatioSuite:

  // ── FactSource — which extraction path produced the set ─────────────
  val factSourceGraphSig: FactSource    = FactSource.Graph
  val factSourceDegradedSig: FactSource = FactSource.Degraded

  val factSourceNameSig: FactSource => String =
    FactSource.asString

  // ── RequirementSet — extracted facts, never a bare list ─────────────
  val reqSetApplySig: (
    List[String],
    List[ChainState.Requirement],
    List[ExtractedObligation],
    FactSource
  ) => RequirementSet = RequirementSet.apply

  val reqSetFieldsSig: RequirementSet => (
    List[String],
    List[ChainState.Requirement],
    List[ExtractedObligation],
    FactSource
  ) =
    (r: RequirementSet) => (r.specNames, r.requirements, r.obligations, r.source)

  val reqSetEmptySig: FactSource => RequirementSet =
    RequirementSet.empty

  val reqSetIsEmptySig: RequirementSet => Boolean =
    (r: RequirementSet) => r.isEmpty

  // ── ExtractedObligation — the normalised obligation row ─────────────
  val extractedApplySig: (
    String,
    Int,
    String,
    String,
    List[String],
    List[String],
    Boolean
  ) => ExtractedObligation = ExtractedObligation.apply

  val extractedFieldsSig: ExtractedObligation => (
    String,
    Int,
    String,
    String,
    List[String],
    List[String],
    Boolean
  ) =
    (o: ExtractedObligation) =>
      (o.spec, o.line, o.obligation, o.artifact, o.artifacts, o.requirementClaims, o.unmappable)

  // ── RequirementExtractor — the single extraction seam ───────────────
  val namedSpecApplySig: (String, SpecDocument) => RequirementExtractor.NamedSpec =
    RequirementExtractor.NamedSpec.apply

  val extractSig: (
    List[RequirementExtractor.NamedSpec],
    Option[ujson.Value]
  ) => RequirementSet = RequirementExtractor.extract

  // ── ChainState.compute — the pure attribution kernel ────────────────
  // Per-spec lint outcomes, unfiltered ledger, RequirementSet, per-spec
  // resolved baselines, the echoed effective baseline, the resolved
  // staleness filter, change, injected forgiveness predicate — no default
  // arguments, so none of them can be silently dropped.
  val computeSig: (
    Map[String, Outcome[LintReport]],
    Ledger.LedgerData,
    RequirementSet,
    Map[String, String],
    String,
    String,
    String,
    (String, String) => Boolean
  ) => Either[ChainStateUndetermined, ChainStateReport] = ChainState.compute

  // ── UnresolvedEntry — smart-constructor surface ─────────────────────
  val entryOfSig: (String, String, List[UnresolvedReason]) => Option[UnresolvedEntry] =
    UnresolvedEntry.of

  val entryFieldsSig: UnresolvedEntry => (String, String, List[UnresolvedReason]) =
    (e: UnresolvedEntry) => (e.spec, e.requirement, e.reasons)

  // ── ChainStateReport — smart-constructor surface ────────────────────
  val reportFromCountsSig: (
    String,
    String,
    Int,
    Int,
    Int,
    Int,
    List[UnresolvedEntry],
    List[UnmappedObligation]
  ) => Either[String, ChainStateReport] = ChainStateReport.fromCounts

  val reportFieldsSig: ChainStateReport => (
    String,
    String,
    Int,
    Int,
    Int,
    Int,
    List[UnresolvedEntry],
    List[UnmappedObligation]
  ) =
    (r: ChainStateReport) =>
      (
        r.change,
        r.baseline,
        r.total,
        r.bound,
        r.resolved,
        r.discharged,
        r.unresolved,
        r.unmappedObligations
      )

  // ── ChainStateUndetermined — the could-not-determine side ───────────
  val undeterminedFieldsSig: ChainStateUndetermined => (String, String, String) =
    (u: ChainStateUndetermined) => (u.change, u.baseline, u.reason)

  // ── UnmappedObligation — the separate report channel ────────────────
  val unmappedFieldsSig: UnmappedObligation => (String, Int, String) =
    (u: UnmappedObligation) => (u.spec, u.line, u.artifact)

  // ── The pinned surface evaluates ────────────────────────────────────
  test("UnresolvedEntry.of rejects an empty reasons list"):
    assertEquals(
      UnresolvedEntry.of("s", "r", Nil),
      None,
      "an unresolved requirement with no stated reason must be unrepresentable"
    )

  test("UnresolvedEntry.of rejects repeated reasons"):
    val dup: Option[UnresolvedEntry] = UnresolvedEntry.of(
      "s",
      "r",
      List(UnresolvedReason.Failed, UnresolvedReason.Failed)
    )
    assertEquals(dup, None, "the contract forbids duplicate reasons per entry")

  test("ChainStateReport.fromCounts rejects discharged > total"):
    val bad: Either[String, ChainStateReport] = ChainStateReport.fromCounts(
      "c",
      "b",
      total = 1,
      bound = 1,
      resolved = 1,
      discharged = 2,
      unresolved = Nil,
      unmappedObligations = Nil
    )
    assert(bad.isLeft, "an impossible count combination must be rejected")

  test("ChainStateReport.fromCounts rejects an unresolved list shorter than total - discharged"):
    val bad: Either[String, ChainStateReport] = ChainStateReport.fromCounts(
      "c",
      "b",
      total = 2,
      bound = 2,
      resolved = 2,
      discharged = 1,
      unresolved = Nil,
      unmappedObligations = Nil
    )
    assert(bad.isLeft, "unresolved must be the exact complement of discharged")

  test("ChainStateReport.fromCounts accepts a contract-valid report"):
    val entry: UnresolvedEntry = UnresolvedEntry
      .of("s", "r", List(UnresolvedReason.Undischarged))
      .getOrElse(fail("a valid entry must construct"))
    val good: Either[String, ChainStateReport] = ChainStateReport.fromCounts(
      "c",
      "b",
      total = 2,
      bound = 2,
      resolved = 2,
      discharged = 1,
      unresolved = List(entry),
      unmappedObligations = Nil
    )
    assert(good.isRight, s"a contract-valid report must construct, got $good")

  test("RequirementSet.empty carries its FactSource"):
    assertEquals(RequirementSet.empty(FactSource.Graph).source, FactSource.Graph)
    assert(RequirementSet.empty(FactSource.Degraded).isEmpty)
