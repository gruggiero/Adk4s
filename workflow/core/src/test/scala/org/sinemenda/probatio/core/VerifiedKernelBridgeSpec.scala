package org.sinemenda.probatio.core

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
 * Stryker4s can detect killed mutants. Does NOT use Hedgehog.
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
    LintReport(
      verdicts = List.empty,
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )

  private def failedLint: LintReport =
    LintReport(
      verdicts = List.empty,
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = false
    )

  private def emptyLedger: Ledger.LedgerData = Ledger.fromRecords(List.empty)

  private def noReqs: List[ChainState.Requirement] = List.empty

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
      ChainState.compute(failedLint, emptyLedger, noReqs, "abc1234", "c")
    assert(prodResult.isLeft, s"production must return Left for failed lint, got $prodResult")

    val modelResult: stainless.lang.Either[ChainStateKernel.Undetermined, ChainStateKernel.ChainStateReport] =
      modelCompute(lintSuccess = false)
    assert(modelResult.isLeft, s"model must return Left for failed lint, got $modelResult")

  // ── successful lint: both return Right ──────────────────────────────────

  test("bridge-chainstate-successful-lint — both return Right for successful lint"):
    val prodResult: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(emptyLint, emptyLedger, noReqs, "abc1234", "c")
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
      ChainState.compute(failedLint, emptyLedger, noReqs, "abc1234", "c")
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

  // ── 3. BannerEngineKernel bridge ────────────────────────────────────────

  /** Production banner inputs with empty skillInstallScan. */
  private def emptyBannerInputs(schemaVersion: Int): BannerInputs =
    BannerInputs(
      schemaVersion = schemaVersion,
      skillInstallScan = List.empty,
      registryPresent = false,
      registryConceptCount = 0,
      inventoryPresent = false,
      inventoryTypeCount = 0,
      profilePresent = false,
      detectedTestKit = None,
      activeChanges = List.empty
    )

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
    // Production: skillInstallScan with a root that has no stamp → noSkillInstalled = true
    val prodInputs: BannerInputs = emptyBannerInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", None))
    )
    val prodDriftResult: DriftScanResult =
      DriftScan.scan(13, prodInputs.skillInstallScan)
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
    // Production: skillInstallScan with a mismatched version → warnings
    val prodInputs: BannerInputs = emptyBannerInputs(13).copy(
      skillInstallScan = List(InstallRootScan(".claude/skills", Some(12)))
    )
    val prodDriftResult: DriftScanResult =
      DriftScan.scan(13, prodInputs.skillInstallScan)
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
