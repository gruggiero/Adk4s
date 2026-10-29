package org.sinemenda.probatio.core

import hedgehog.*
import org.sinemenda.probatio.verified.BannerEngineKernel
import org.sinemenda.probatio.verified.ChainStateKernel
import org.sinemenda.probatio.verified.LedgerValidatorKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge spec — binds the three probatio-core production kernels to
 * their PureScala mirror models in `probatio-verified`.
 *
 * The models and shipped code operate on different value spaces: the models
 * use `BigInt` abstractions (non-zero = non-empty string, zero = empty)
 * because Stainless does not support string interpolation, while the shipped
 * code uses `String`, `ujson.Value`, and `Int`. The bridge therefore
 * compares STRUCTURAL properties that are invariant under the abstraction,
 * not exact equality.
 *
 * Three kernels are bridged:
 *   1. `LedgerValidatorKernel` — totality of the 12-clause validator
 *   2. `ChainStateKernel` — undetermined-never-collapses law
 *   3. `BannerEngineKernel` — purity / idempotence of the banner engine
 *
 * Uses MUnit native assertions (`assertEquals`, `assert`, `fail`) so that
 * Stryker4s can detect killed mutants. Uses Hedgehog only for the
 * graph reachability bridge property (the PO table pins it here).
 *
 * spec: probatio-core — Formal Contracts (Ring 6 bridge)
 */
final class VerifiedKernelBridgeSpec extends ProbatioSuite:

  // ── Helpers: Scala ↔ Stainless type conversions ──────────────────────────

  /** Convert a Scala List to a Stainless List. */
  def scalaToStainlessList[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  /** Convert a Stainless List to a Scala List. */
  def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  // ── 1. LedgerValidatorKernel bridge ──────────────────────────────────────

  /** A valid JSON ledger record for the production Validator. */
  private def validRecordJson: ujson.Value =
    ujson.Obj(
      "v"          -> 1,
      "ts"         -> "2026-08-08T12:34:56Z",
      "change"     -> "port-scanner-to-probatio",
      "spec"       -> "probatio-core",
      "ring"       -> "R3",
      "obligation" -> "F7 reachability",
      "artifact"   -> "workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala",
      "command"    -> "sbt probatio-core/test",
      "exit"       -> 0,
      "baseline"   -> "abc1234"
    )

  /** An invalid JSON ledger record: empty `change` field. */
  private def invalidRecordJson: ujson.Value =
    ujson.Obj(
      "v"          -> 1,
      "ts"         -> "2026-08-08T12:34:56Z",
      "change"     -> "",
      "spec"       -> "probatio-core",
      "ring"       -> "R3",
      "obligation" -> "F7 reachability",
      "artifact"   -> "workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala",
      "command"    -> "sbt probatio-core/test",
      "exit"       -> 0,
      "baseline"   -> "abc1234"
    )

  /**
   * Call the model's validate with all-valid BigInt inputs (non-zero =
   * non-empty) and a valid Ring. All 15 clauses pass.
   */
  private def modelValidateValid
    : stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
    LedgerValidatorKernel.validate(
      v = BigInt(1),
      ts = BigInt(1),
      tsValidIso = true,
      tsNoSep = true,
      change = BigInt(1),
      spec = BigInt(1),
      ring = stainless.lang.Some(LedgerValidatorKernel.Ring.R0),
      obligation = BigInt(1),
      artifact = BigInt(1),
      artifactNoSep = true,
      command = BigInt(1),
      exit = BigInt(0),
      exitIsInteger = true,
      baseline = BigInt(1),
      baselineValidHex = true,
      optFieldsValidType = true,
      observerProvenanceValid = true,
      sessionProvenanceValid = true
    )

  /**
   * Call the model's validate with an empty `change` (BigInt(0) = empty
   * string in the abstraction). All other fields are valid.
   */
  private def modelValidateInvalidChange
    : stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
    LedgerValidatorKernel.validate(
      v = BigInt(1),
      ts = BigInt(1),
      tsValidIso = true,
      tsNoSep = true,
      change = BigInt(0),
      spec = BigInt(1),
      ring = stainless.lang.Some(LedgerValidatorKernel.Ring.R0),
      obligation = BigInt(1),
      artifact = BigInt(1),
      artifactNoSep = true,
      command = BigInt(1),
      exit = BigInt(0),
      exitIsInteger = true,
      baseline = BigInt(1),
      baselineValidHex = true,
      optFieldsValidType = true,
      observerProvenanceValid = true,
      sessionProvenanceValid = true
    )

  // ── totality: both production and model return exactly one outcome ──────

  test("bridge-validator-totality — both production and model return exactly one outcome"):
    // Production: valid input
    val prodValid: Either[ContractViolation, LedgerRecord] =
      Validator.validate(validRecordJson)
    assert(
      prodValid.isLeft || prodValid.isRight,
      s"production must return exactly one outcome for valid input, got $prodValid"
    )

    // Production: invalid input
    val prodInvalid: Either[ContractViolation, LedgerRecord] =
      Validator.validate(invalidRecordJson)
    assert(
      prodInvalid.isLeft || prodInvalid.isRight,
      s"production must return exactly one outcome for invalid input, got $prodInvalid"
    )

    // Model: valid input
    val modelValid: stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
      modelValidateValid
    assert(
      modelValid.isLeft || modelValid.isRight,
      s"model must return exactly one outcome for valid input, got $modelValid"
    )

    // Model: invalid input (change = 0 = empty)
    val modelInvalid: stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
      modelValidateInvalidChange
    assert(
      modelInvalid.isLeft || modelInvalid.isRight,
      s"model must return exactly one outcome for invalid input, got $modelInvalid"
    )

  // ── valid input: both return Right ──────────────────────────────────────

  test("bridge-validator-valid-input — both return Right for valid input"):
    val prodResult: Either[ContractViolation, LedgerRecord] =
      Validator.validate(validRecordJson)
    assert(prodResult.isRight, s"production must return Right for valid input, got $prodResult")

    val modelResult: stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
      modelValidateValid
    assert(modelResult.isRight, s"model must return Right for valid input, got $modelResult")

  // ── invalid input: both return Left ─────────────────────────────────────

  test("bridge-validator-invalid-input — both return Left for invalid input"):
    val prodResult: Either[ContractViolation, LedgerRecord] =
      Validator.validate(invalidRecordJson)
    assert(prodResult.isLeft, s"production must return Left for empty change, got $prodResult")

    val modelResult: stainless.lang.Either[LedgerValidatorKernel.Violation, LedgerValidatorKernel.ValidRecord] =
      modelValidateInvalidChange
    assert(
      modelResult.isLeft,
      s"model must return Left for empty change (change=0), got $modelResult"
    )

  // ── 2. ChainStateKernel bridge ───────────────────────────────────────────

  private def emptyLint: LintReport =
    SpecLintFixtures.report(
      verdicts = List.empty,
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )

  private def emptyLedger: Ledger.LedgerData = Ledger.fromRecords(List.empty)

  // A spec that was READ and produced no requirements — its lint outcome
  // is still consulted (distinct from RequirementSet.empty, where no spec
  // was read at all and no lint is needed).
  private def noReqs: RequirementSet =
    RequirementSet(List("s"), List.empty, List.empty, FactSource.Degraded)

  private def okLints(lint: LintReport): Map[String, Outcome[LintReport]] =
    Map("s" -> Outcome.Ran(lint))

  private val failedLints: Map[String, Outcome[LintReport]] =
    Map("s" -> Outcome.Undetermined("spec-lint did not complete successfully"))

  private val noForgive: (String, String) => Boolean = (_, _) => false

  /**
   * Call the model's compute with the given lintSuccess flag and empty
   * collections (no verdicts, no ledger records, no requirements).
   */
  private def modelCompute(
    lintSuccess: Boolean
  ): stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
    ChainStateKernel.compute(
      lintSuccess = lintSuccess,
      verdicts = stainless.lang.Map.empty[BigInt, ChainStateKernel.Verdict],
      ledgerRecords = stainless.collection.List.empty[ChainStateKernel.LedgerRecord],
      requirements = stainless.collection.List.empty[ChainStateKernel.Requirement],
      baseline = BigInt(1),
      change = BigInt(1)
    )

  // ── failed lint: both return Left ───────────────────────────────────────

  test("bridge-chainstate-failed-lint — both return Left for failed lint"):
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(prodResult.isLeft, s"production must return Left for failed lint, got $prodResult")

    val modelResult: stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      modelCompute(lintSuccess = false)
    assert(modelResult.isLeft, s"model must return Left for failed lint, got $modelResult")

  // ── successful lint: both return Right ──────────────────────────────────

  test("bridge-chainstate-successful-lint — both return Right for successful lint"):
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(
      prodResult.isRight,
      s"production must return Right for successful lint, got $prodResult"
    )

    val modelResult: stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      modelCompute(lintSuccess = true)
    assert(
      modelResult.isRight,
      s"model must return Right for successful lint, got $modelResult"
    )

  // ── undetermined-never-collapses ────────────────────────────────────────

  test("bridge-chainstate-undetermined-never-collapses — Left never contains a clean report"):
    // Production: a failed lint must yield Left (undetermined), never Right
    // with a clean report (discharged=0). The defect class is collapsing
    // undetermined into a clean "0 discharged" Right.
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(
      prodResult.isLeft,
      "production: failed lint must yield Left (undetermined), never Right (clean report)"
    )

    // Model: the ensuring clause on compute guarantees that a failed lint
    // always produces Left, never Right. If it produced Right with a clean
    // report (all counts zero), that would be the collapsed defect.
    val modelResult: stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      modelCompute(lintSuccess = false)
    assert(
      modelResult.isLeft,
      "model: failed lint must yield Left (undetermined), never Right (clean report)"
    )

  // ── 2b. chainStateFold bridge — non-empty generated inputs ─────────────
  //
  // `chainStateFold` is the spec-5 Ring-6 contract: verdicts as 0/1/2
  // (unbound / bound / resolved), the ledger as a discharged index set,
  // and the unreachable-obligation boundary as an unattributable index
  // set. The bridge derives the kernel inputs by projecting a production
  // `ChainState.compute` report: a requirement's verdict code is its
  // unresolved reason (Unbound → 0; Unattributable/Unresolved → 1;
  // Undischarged/Failed/absent → 2), the discharged set is the indices
  // carrying no unresolved entry, and the unattributable set is the
  // indices carrying Unattributable. It then asserts the fold's counts
  // and unresolved index set agree with the production report exactly.
  //
  // spec: chain-state-attribution — Formal Contracts (Ring 6): chainStateFold

  private def foldDoc(titles: List[String], name: String): SpecDocument =
    SpecDocument(
      name = name,
      lines = Vector.empty,
      requirements = titles.zipWithIndex.map { case (t, i) =>
        RequirementBlock(t, i * 10 + 1, i * 10 + 9, true, false, 1, "")
      },
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = Nil,
      dataRowCount = 0,
      bridgeRowCount = 0,
      hasProofObligations = true,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      artifactRows = Nil,
      chainRows = Nil
    )

  private def foldLint(
    titles: List[String],
    requirementRows: Map[String, List[ObligationRow]],
    findings: List[CheckOutcome] = Nil,
    name: String = "s"
  ): Outcome[LintReport] =
    Outcome.Ran(
      LintReport.fromRun(
        foldDoc(titles, name),
        findings,
        Map.empty,
        resolvedRows = requirementRows.values.flatten.toList.distinct,
        unresolvableRows = Nil,
        requirementRows = requirementRows,
        artifactUnresolved = Some(Set.empty)
      )
    )

  private def foldRow(line: Int, source: String): ObligationRow =
    ObligationRow(line, 5, source, "", "", s"| | $source | | |")

  private def foldObl(line: Int, text: String, claims: List[String]): ExtractedObligation =
    ExtractedObligation("s", line, text, "a/b.scala", List("a/b.scala"), claims, unmappable = false)

  private def foldReq(title: String): ChainState.Requirement =
    ChainState.Requirement("s", title)

  private def foldLedgerRow(obligation: String, exit: Int = 0, ring: Ring = Ring.R3): LedgerRecord =
    LedgerRecord(
      1,
      "2026-09-17T00:00:00Z",
      "c",
      "s",
      ring,
      obligation,
      "a/b.scala",
      "sbt test",
      exit,
      "base0",
      LedgerRecordOptional()
    )

  /**
   * Run production compute on the scenario and the kernel fold on the
   * projected indices, asserting count and index-set agreement.
   */
  private def assertFoldBridge(
    reqs: List[ChainState.Requirement],
    obligations: List[ExtractedObligation],
    ledger: Ledger.LedgerData,
    lint: Outcome[LintReport],
    source: FactSource
  ): Unit =
    val set: RequirementSet =
      RequirementSet(List("s"), reqs, obligations, source)
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(Map("s" -> lint)),
        ledger,
        set,
        Map.empty,
        "base0",
        "base0",
        "c",
        noForgive
      )
    val report: ChainStateReport = prodResult match
      case Right(r) => r
      case Left(u)  => fail(s"production must produce a report, got undetermined: ${u.reason}")

    // Project the production verdict onto kernel inputs.
    val idxByTitle: Map[String, Int] = reqs.map(_.requirement).zipWithIndex.toMap
    val reasonAt: Map[Int, UnresolvedReason] = report.unresolved.flatMap { (e: UnresolvedEntry) =>
      idxByTitle.get(e.requirement).flatMap((i: Int) => e.reasons.headOption.map(i -> _))
    }.toMap
    val verdicts: ScalaList[BigInt] = reqs.indices.map { (i: Int) =>
      reasonAt.get(i) match
        case Some(UnresolvedReason.Unbound)                                      => BigInt(0)
        case Some(UnresolvedReason.Unattributable | UnresolvedReason.Unresolved) => BigInt(1)
        case _                                                                   => BigInt(2)
    }.toList
    val dischargedIdx: ScalaList[BigInt] =
      reqs.indices.filterNot(reasonAt.contains).map(BigInt(_)).toList
    val unattrIdx: ScalaList[BigInt] =
      reasonAt.collect { case (i, UnresolvedReason.Unattributable) => BigInt(i) }.toList

    val kernelResult: (BigInt, BigInt, BigInt, stainless.collection.List[BigInt]) =
      ChainStateKernel.chainStateFold(
        BigInt(reqs.length),
        scalaToStainlessList(verdicts),
        scalaToStainlessList(dischargedIdx),
        scalaToStainlessList(unattrIdx)
      )
    val kBound: BigInt                                 = kernelResult._1
    val kResolved: BigInt                              = kernelResult._2
    val kDis: BigInt                                   = kernelResult._3
    val kUnresolved: stainless.collection.List[BigInt] = kernelResult._4

    assertEquals(kBound, BigInt(report.bound), "kernel bound must equal production bound")
    assertEquals(kResolved, BigInt(report.resolved), "kernel resolved must equal production resolved")
    assertEquals(kDis, BigInt(report.discharged), "kernel dis must equal production discharged")
    val kernelUnresolvedIdx: Set[Int] = stainlessListToScala(kUnresolved).map(_.toInt).toSet
    val prodUnresolvedIdx: Set[Int]   = reasonAt.keySet
    assertEquals(
      kernelUnresolvedIdx,
      prodUnresolvedIdx,
      "kernel unresolved must be the exact index complement of production's unresolved entries"
    )

  test("bridge-chainstatefold-degraded — fold agrees with production on a mixed degraded scenario"):
    // Alpha: unbound (no row names it). Beta: bound via ordinal row but
    // unmappable → unattributable. Gamma: resolved + green row → discharged.
    // Delta: resolved + red row → failed.
    val titles: List[String] = List("Alpha", "Beta", "Gamma", "Delta")
    val lint: Outcome[LintReport] = foldLint(
      titles,
      Map(
        "Beta"  -> List(foldRow(21, "Requirement 2")),
        "Gamma" -> List(foldRow(31, "Requirement: Gamma")),
        "Delta" -> List(foldRow(41, "Requirement: Delta"))
      )
    )
    val obligations: List[ExtractedObligation] = List(
      foldObl(21, "obl beta", Nil),
      foldObl(31, "obl gamma", List("Gamma")),
      foldObl(41, "obl delta", List("Delta"))
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(
      List(foldLedgerRow("obl gamma"), foldLedgerRow("obl delta", exit = 1))
    )
    assertFoldBridge(
      List("Alpha", "Beta", "Gamma", "Delta").map(foldReq),
      obligations,
      ledger,
      lint,
      FactSource.Degraded
    )

  test("bridge-chainstatefold-graph — fold agrees with production on a graph-mode scenario"):
    // Graph mode: a bound title with no mapped obligations is Unresolved
    // (not Unattributable); an F9 artifact finding marks its obligation's
    // requirement unresolved too.
    val titles: List[String] = List("Alpha", "Beta")
    val lint: Outcome[LintReport] = foldLint(
      titles,
      Map(
        "Alpha" -> List(foldRow(11, "Requirement: Alpha")),
        "Beta"  -> List(foldRow(21, "Requirement: Beta"))
      ),
      findings = List(CheckOutcome.Fail(CheckId.F9, Some(21), "artifact 'a/b.scala' does not resolve"))
    )
    // Alpha is bound but has no claiming obligation → graph-mode
    // Unresolved. Beta's obligation carries the F9 artifact → Unresolved.
    val obligations: List[ExtractedObligation] = List(foldObl(21, "obl beta", List("Beta")))
    assertFoldBridge(
      titles.map(foldReq),
      obligations,
      Ledger.fromRecords(List.empty),
      lint,
      FactSource.Graph
    )

  test("bridge-chainstate-manual-ring — model admits Manual-ring rows like production"):
    // Production: a Manual-ring row discharges (ledger.sh read has no
    // ring filter). The model's isDischarged must agree — the kernel's
    // matchesBaselineChange carries no ring check.
    val req: ChainStateKernel.Requirement = ChainStateKernel.Requirement(BigInt(1), BigInt(7))
    val rec: ChainStateKernel.LedgerRecord = ChainStateKernel.LedgerRecord(
      spec = BigInt(1),
      obligation = BigInt(7),
      ring = ChainStateKernel.Manual,
      change = BigInt(1),
      baseline = BigInt(1)
    )
    assert(
      ChainStateKernel.isDischarged(
        req,
        scalaToStainlessList(ScalaList(rec)),
        BigInt(1),
        BigInt(1)
      ),
      "model: a Manual-ring row matching change/baseline/spec/obligation must discharge"
    )

  // ── 2b. PrePassOutcome boundary bridge (chain-state-undetermined-fidelity) ──

  /**
   * A deliberately-populated production input — requirements, mapped
   * obligations and a qualifying ledger row — so that a `Right` would be
   * constructible if the boundary consulted the evidence.
   */
  private lazy val populatedReqs: RequirementSet =
    RequirementSet(
      List("s"),
      List(foldReq("Alpha")),
      List(foldObl(11, "obl alpha", List("Alpha"))),
      FactSource.Degraded
    )

  private lazy val populatedLedger: Ledger.LedgerData =
    Ledger.fromRecords(List(foldLedgerRow("obl alpha")))

  test("bridge-prepass-didnotrun — populated evidence is ignored, both sides return Left"):
    // Production: a did-not-run carries no lint data; the populated
    // ledger/requirements must never be consulted — the defect this spec
    // removes is populated evidence masquerading as a measurement.
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.DidNotRun(
          UndeterminedReason.stated("spec-lint not found at /x/spec-lint.sh; cannot determine bound/resolved")
        ),
        populatedLedger,
        populatedReqs,
        Map.empty,
        "base0",
        "base0",
        "c",
        noForgive
      )
    prodResult match
      case Left(u) =>
        assert(
          u.reason.text.contains("spec-lint not found at /x/spec-lint.sh"),
          s"production: the undetermined must carry the stated reason, got ${u.reason.text}"
        )
      case Right(r) =>
        fail(s"production: a did-not-run must never produce a report, got $r")

    // Model: the same boundary on populated kernel inputs.
    val kernelVerdicts: stainless.lang.Map[BigInt, ChainStateKernel.Verdict] =
      stainless.lang.Map(BigInt(1) -> ChainStateKernel.Resolved())
    val kernelRecords: stainless.collection.List[ChainStateKernel.LedgerRecord] =
      scalaToStainlessList(
        ScalaList(ChainStateKernel.LedgerRecord(BigInt(1), BigInt(1), ChainStateKernel.Manual, BigInt(1), BigInt(1)))
      )
    val kernelReqs: stainless.collection.List[ChainStateKernel.Requirement] =
      scalaToStainlessList(ScalaList(ChainStateKernel.Requirement(BigInt(1), BigInt(1))))
    val modelResult: stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      ChainStateKernel.computeOutcome(
        ChainStateKernel.PrePassDidNotRun(BigInt(7)),
        kernelVerdicts,
        kernelRecords,
        kernelReqs,
        BigInt(1),
        BigInt(1)
      )
    assert(modelResult.isLeft, s"model: a did-not-run must never produce a report, got $modelResult")
    modelResult match
      case stainless.lang.Left(u) =>
        assertEquals(u.reason, BigInt(7), "model: the undetermined must carry the stated reason")
      case stainless.lang.Right(_) =>
        fail("model: a did-not-run must never produce a report")

  test("bridge-prepass-completed — Completed is the only arm that reaches the fold"):
    // Production: Completed with a failing lint is undetermined; with a
    // successful lint it produces a measured report.
    val prodFailed: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(prodFailed.isLeft, s"production: a completed pre-pass with a failed lint is Left, got $prodFailed")
    val prodOk: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(prodOk.isRight, s"production: a completed pre-pass reaches the fold, got $prodOk")

    // Model: computeOutcome(PrePassCompleted(ran)) delegates to compute(ran).
    val emptyKernelInput
        : stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      ChainStateKernel.computeOutcome(
        ChainStateKernel.PrePassCompleted(false),
        stainless.lang.Map.empty[BigInt, ChainStateKernel.Verdict],
        stainless.collection.List.empty[ChainStateKernel.LedgerRecord],
        stainless.collection.List.empty[ChainStateKernel.Requirement],
        BigInt(1),
        BigInt(1)
      )
    assert(emptyKernelInput.isLeft, s"model: Completed(false) delegates to the failed-lint arm, got $emptyKernelInput")

  test("bridge-prepass-counts-monotone — every measured report is monotone and bounded"):
    // Over a constructive corpus of completed pre-passes, every production
    // report obeys 0 <= discharged <= resolved <= bound <= total — the
    // model's derivedCountsMonotone is the Stainless-proven mirror.
    val corpus: List[(String, Either[ChainStateUndetermined, ChainStateReport])] = List(
      "all-satisfied" -> ChainState.compute(
        PrePassOutcome.Completed(Map("s" -> foldLint(List("Alpha"), Map("Alpha" -> List(foldRow(11, "Requirement: Alpha")))))),
        Ledger.fromRecords(List(foldLedgerRow("obl alpha"))),
        RequirementSet(
          List("s"),
          List(foldReq("Alpha")),
          List(foldObl(11, "obl alpha", List("Alpha"))),
          FactSource.Degraded
        ),
        Map.empty,
        "base0",
        "base0",
        "c",
        noForgive
      ),
      "unbound-only" -> ChainState.compute(
        PrePassOutcome.Completed(Map("s" -> foldLint(List("Alpha"), Map.empty))),
        emptyLedger,
        RequirementSet(List("s"), List(foldReq("Alpha")), List.empty, FactSource.Degraded),
        Map.empty,
        "base0",
        "base0",
        "c",
        noForgive
      ),
      "undischarged" -> ChainState.compute(
        PrePassOutcome.Completed(Map("s" -> foldLint(List("Alpha"), Map("Alpha" -> List(foldRow(11, "Requirement: Alpha")))))),
        emptyLedger,
        RequirementSet(
          List("s"),
          List(foldReq("Alpha")),
          List(foldObl(11, "obl alpha", List("Alpha"))),
          FactSource.Degraded
        ),
        Map.empty,
        "base0",
        "base0",
        "c",
        noForgive
      )
    )
    corpus.foreach { (name: String, result: Either[ChainStateUndetermined, ChainStateReport]) =>
      result match
        case Right(r) =>
          assert(
            r.discharged >= 0 && r.discharged <= r.resolved &&
              r.resolved <= r.bound && r.bound <= r.total,
            s"$name: counts must obey 0 <= discharged <= resolved <= bound <= total, got $r"
          )
          assert(
            ChainStateKernel.derivedCountsMonotone(
              BigInt(r.total),
              BigInt(r.total - r.bound),
              BigInt(r.bound - r.resolved),
              BigInt(r.resolved - r.discharged)
            ),
            s"$name: the model's derivedCountsMonotone must accept production's derived components"
          )
        case Left(u) => fail(s"$name: a completed pre-pass fixture must produce a report, got ${u.reason.text}")
    }

  // ── 3. BannerEngineKernel bridge ────────────────────────────────────────

  /** Production banner facts with no install roots and all facts absent. */
  private def emptyBannerFacts(schemaVersion: Int): RepositoryFacts =
    RepositoryFacts(
      schemaVersion = FactRead.Present(schemaVersion),
      registry = FactRead.Absent,
      inventory = FactRead.Absent,
      profile = FactRead.Absent,
      installRoots = List.empty,
      activeChanges = FactRead.Present(List.empty)
    )

  /** Production banner inputs from a facts record (the only construction path). */
  private def emptyBannerInputs(schemaVersion: Int): BannerInputs =
    BannerInputs.from(emptyBannerFacts(schemaVersion))

  // ── idempotence: both production and model are idempotent ───────────────

  test("bridge-bannerengine-idempotence — both production and model are idempotent"):
    // Production idempotence: render(inputs) == render(inputs)
    val prodInputs: BannerInputs = emptyBannerInputs(13)
    val prodOnce: BannerOutput   = BannerEngine.render(prodInputs)
    val prodTwice: BannerOutput  = BannerEngine.render(prodInputs)
    assertEquals(prodOnce, prodTwice, "production: render(inputs) must equal render(inputs)")

    // Model idempotence: driftScan is the core pure function of the banner
    // engine. We test driftScan idempotence rather than render idempotence
    // because render's `ensuring` clause is self-referential
    // (`result == render(inputs)`) — a proof obligation that Stainless treats
    // as a no-op, but which causes infinite recursion when executed as plain
    // Scala (stainlessEnabled := false). driftScan has no such issue and
    // exercises the same purity property.
    val modelRoots: stainless.collection.List[BannerEngineKernel.InstallRoot] =
      scalaToStainlessList(
        ScalaList(
          BannerEngineKernel.InstallRoot(BigInt(1), stainless.lang.Some(BigInt(13)))
        )
      )
    val modelOnce: BannerEngineKernel.DriftScanResult =
      BannerEngineKernel.driftScan(BigInt(13), modelRoots)
    val modelTwice: BannerEngineKernel.DriftScanResult =
      BannerEngineKernel.driftScan(BigInt(13), modelRoots)
    assertEquals(
      modelOnce,
      modelTwice,
      "model: driftScan(inputs) must equal driftScan(inputs) (purity / idempotence)"
    )

  // ── no skill installed: both detect it ──────────────────────────────────

  test("bridge-bannerengine-no-skill — both detect no skill installed"):
    // Production: installRoots with an absent root → noSkillInstalled = true
    val prodRoots: List[InstallRootScan] =
      List(InstallRootScan(".claude/skills", InstallRootState.Absent))
    val prodDriftResult: DriftScanResult =
      DriftScan.scan(Some(13), prodRoots)
    assert(
      prodDriftResult.noSkillInstalled,
      "production: no skill installed should be detected (noSkillInstalled = true)"
    )

    // Model: roots with stampVersion = None → noSkillInstalled = true
    val modelRoots: stainless.collection.List[BannerEngineKernel.InstallRoot] =
      scalaToStainlessList(
        ScalaList(
          BannerEngineKernel.InstallRoot(BigInt(1), stainless.lang.None[BigInt]())
        )
      )
    val modelDriftResult: BannerEngineKernel.DriftScanResult =
      BannerEngineKernel.driftScan(BigInt(13), modelRoots)
    assert(
      modelDriftResult.noSkillInstalled,
      "model: no skill installed should be detected (noSkillInstalled = true)"
    )

  // ── version mismatch: both detect drift ────────────────────────────────

  test("bridge-bannerengine-version-mismatch — both detect drift"):
    // Production: installRoots with a mismatched version → warnings
    val prodRoots: List[InstallRootScan] =
      List(InstallRootScan(".claude/skills", InstallRootState.Stamped(12, StampFormat.New)))
    val prodDriftResult: DriftScanResult =
      DriftScan.scan(Some(13), prodRoots)
    assert(
      prodDriftResult.warnings.nonEmpty,
      "production: version mismatch (schema=13, found=12) should produce warnings"
    )

    // Model: roots with stampVersion = Some(12) and schemaVersion = 13 → warnings
    val modelRoots: stainless.collection.List[BannerEngineKernel.InstallRoot] =
      scalaToStainlessList(
        ScalaList(
          BannerEngineKernel.InstallRoot(BigInt(1), stainless.lang.Some(BigInt(12)))
        )
      )
    val modelDriftResult: BannerEngineKernel.DriftScanResult =
      BannerEngineKernel.driftScan(BigInt(13), modelRoots)
    val modelWarnings: ScalaList[BannerEngineKernel.DriftWarning] =
      stainlessListToScala(modelDriftResult.warnings)
    assert(
      modelWarnings.nonEmpty,
      "model: version mismatch (schema=13, found=12) should produce warnings"
    )

  // ── graph-tool-port — ReachabilityKernel bridge ─────────────────────
  //
  // The kernel's reaches/audit are ??? until Step 3 — the bridge is RED
  // at polarity by design.
  //
  // spec: graph-tool-port — Ring 6 Contract: reachability is grounded and complete
  // spec: graph-tool-port — Ring 6 Contract: the audit conserves requirements

  test("bridge-reachability — shipped audit agrees with the kernel on a generated graph"):
    val gen: GraphFixtures.GeneratedGraph =
      GraphFixtures.GeneratedGraph(
        TraceabilityGraph(
          Vector(
            GraphNode.Spec("ch", "cap", "f"),
            GraphNode.Requirement("ch/cap", 1, "r1"),
            GraphNode.Requirement("ch/cap", 2, "r2"),
            GraphNode.Obligation("ch/cap", 1, "o", "t", None),
            GraphNode.Artifact("a/t.scala")
          ),
          List(
            Edge.plain("spec:ch/cap", GraphEdge.HasRequirement, "req:ch/cap#1"),
            Edge.plain("spec:ch/cap", GraphEdge.HasRequirement, "req:ch/cap#2"),
            Edge("req:ch/cap#1", GraphEdge.EnforcedBy, "oblig:ch/cap#1", None, Some(ObligationLink.Explicit)),
            Edge.plain("oblig:ch/cap#1", GraphEdge.VerifiedBy, "artifact:a/t.scala")
          ),
          Nil
        ),
        Set("a/t.scala")
      )
    val shipped: Option[ReachabilityResult] =
      GraphAudit.audit(gen.graph, None, (p: String) => gen.resolving.contains(p))
    shipped match
      case Some(result) =>
        assertEquals(result.reaching.map(_.ordinal), List(1))
        assertEquals(result.unenforcedRequirements.map(_.ordinal), List(2))
      case None => fail("shipped audit must run on a graph with spec nodes")

    val (reqs, edges, artifacts) =
      GraphFixtures.encodeForKernel(gen.graph, gen.resolving.contains)
    val (modelReaching, modelUnenforced): (stainless.collection.List[BigInt], stainless.collection.List[BigInt]) =
      org.sinemenda.probatio.verified.ReachabilityKernel.audit(reqs, edges, artifacts)
    // req ids are 1-based node positions: spec=1, req1=2, req2=3
    assertEquals(stainlessListToScala(modelReaching), ScalaList(BigInt(2)))
    assertEquals(stainlessListToScala(modelUnenforced), ScalaList(BigInt(3)))

  property("bridge-reachability — shipped and kernel audit agree over genGraph"):
    for gen <- GraphFixtures.genGraph.forAll
    yield GraphAudit.audit(gen.graph, None, gen.resolving.contains) match
      case Some(result) =>
        val (reqs, edges, artifacts) =
          GraphFixtures.encodeForKernel(gen.graph, gen.resolving.contains)
        val (modelReaching, modelUnenforced) =
          org.sinemenda.probatio.verified.ReachabilityKernel.audit(reqs, edges, artifacts)
        val ids: Map[String, BigInt] =
          gen.graph.nodes.zipWithIndex.map { case (n: GraphNode, i: Int) => n.id -> BigInt(i + 1) }.toMap
        val shippedReaching: Set[BigInt]    = result.reaching.flatMap((r: GraphNode.Requirement) => ids.get(r.id)).toSet
        val shippedUnenforced: Set[BigInt]  = result.unenforcedRequirements.flatMap((r: GraphNode.Requirement) => ids.get(r.id)).toSet
        Result
          .assert(GraphFixtures.stainlessListToScala(modelReaching).toSet == shippedReaching)
          .and(Result.assert(GraphFixtures.stainlessListToScala(modelUnenforced).toSet == shippedUnenforced))
      case None =>
        if gen.graph.nodes.exists((n: GraphNode) => n match { case _: GraphNode.Spec => true; case _ => false }) then
          Result.failure.log("audit returned None on a graph with spec nodes")
        else Result.assert(true)
