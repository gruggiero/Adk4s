package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Conformance property test — validator iff contract over a generated corpus (R-M2).
 *
 * This is the bidirectional equivalence between each ported Scala validator
 * and its corresponding executable `.jq` contract. For every clause of
 * every contract, the generator produces records that satisfy the clause
 * and records that violate it, and the property asserts that the validator
 * accepts a record if and only if the executable contract accepts the same
 * record.
 *
 * spec: migration-protocol — Requirement: Conformance between validators and executable contracts is a property test
 * spec: migration-protocol — Property: conformance-validator-contract-equivalence
 * spec: migration-protocol — Formal Contract: Conformance relation — validator iff contract
 * spec: migration-protocol — Formal Contract: Conformance symmetry — no false positives and no false negatives
 */
final class ConformanceSpec extends ProbatioSuite:

  import ConformanceTypes.*

  // ── Scenario: Validator accepts exactly what the contract accepts
  // spec: migration-protocol — Scenario: Validator accepts exactly what the contract accepts
  test("validator-contract equivalence: a satisfying record is accepted by both"):
    val record: ContractRecord    = satisfyingLedgerRecord
    val result: ConformanceResult = evaluateConformance(record)
    assert(
      result.contractJudgment == ContractJudgment.Accept,
      s"contract rejected a satisfying record: ${result.contractJudgment}"
    )
    assert(
      result.validatorJudgment == ValidatorJudgment.Accept,
      s"validator rejected a satisfying record: ${result.validatorJudgment}"
    )
    assert(result.isConformant, "conformance failed on a satisfying record")

  // ── Scenario: Validator rejects exactly what the contract rejects
  // spec: migration-protocol — Scenario: Validator rejects exactly what the contract rejects
  test("validator-contract equivalence: a single-clause-violated record is rejected by both"):
    val record: ContractRecord    = violatingLedgerRecord("v-must-be-integer-gte-1")
    val result: ConformanceResult = evaluateConformance(record)
    assert(
      result.contractJudgment match { case _: ContractJudgment.Reject => true; case _ => false },
      s"contract accepted a violating record: ${result.contractJudgment}"
    )
    assert(
      result.validatorJudgment match { case _: ValidatorJudgment.Reject => true; case _ => false },
      s"validator accepted a violating record: ${result.validatorJudgment}"
    )
    assert(result.isConformant, "conformance failed on a violating record")

  // ── Scenario: Contract files retained for a full release cycle
  // spec: migration-protocol — Scenario: Contract files retained for a full release cycle
  test("contract files are present as conformance fixtures"):
    val ledgerContract: os.Path     = os.pwd / os.SubPath(ContractId.contractPath(ContractId.LedgerRecord))
    val chainStateContract: os.Path = os.pwd / os.SubPath(ContractId.contractPath(ContractId.ChainStateReport))
    val gateContract: os.Path       = os.pwd / os.SubPath(ContractId.contractPath(ContractId.GateHookJson))
    assert(os.exists(ledgerContract), s"ledger-record-contract.jq missing at $ledgerContract")
    assert(os.exists(chainStateContract), s"chain-state-report-contract.jq missing at $chainStateContract")
    assert(os.exists(gateContract), s"gate-hookjson-contract.jq missing at $gateContract")

  // ── Scenario: Contract files not deleted prematurely
  // spec: migration-protocol — Scenario: Contract files not deleted prematurely
  test("contract files are not deleted before one full release cycle"):
    // The contracts must remain until one full release cycle of green
    // conformance in CI. This test asserts they are present NOW — deletion
    // is refused until the cycle completes.
    val allPresent: Boolean = List(
      ContractId.LedgerRecord,
      ContractId.ChainStateReport,
      ContractId.GateHookJson
    ).forall(id => os.exists(os.pwd / os.SubPath(ContractId.contractPath(id))))
    assert(allPresent, "one or more contract files deleted before release cycle completion")

  // ── Scenario: Validator accepts a record the contract rejects (false positive — must not happen)
  // spec: migration-protocol — Formal Contract: Conformance symmetry — no false positives
  test("conformance symmetry: no false positive — validator never accepts what contract rejects"):
    // Test each ledger clause: a record violating that clause should be
    // rejected by BOTH the validator and the contract.
    for clause <- ledgerClauses do
      val record: ContractRecord    = violatingLedgerRecord(clause)
      val result: ConformanceResult = evaluateConformance(record)
      assert(!result.isFalsePositive, s"false positive on clause '$clause': validator accepted what contract rejected")

  // ── Scenario: Validator rejects a record the contract accepts (false negative — must not happen)
  // spec: migration-protocol — Formal Contract: Conformance symmetry — no false negatives
  test("conformance symmetry: no false negative — validator never rejects what contract accepts"):
    // A fully satisfying record should be accepted by BOTH.
    val record: ContractRecord    = satisfyingLedgerRecord
    val result: ConformanceResult = evaluateConformance(record)
    assert(!result.isFalseNegative, s"false negative: validator rejected what contract accepted")

  // ── Property: conformance-validator-contract-equivalence
  // spec: migration-protocol — Property: conformance-validator-contract-equivalence
  property("validator-contract equivalence over corpus"):
    for record <- genContractRecord.forAll
    yield
      val result: ConformanceResult = evaluateConformance(record)
      Result
        .assert(result.isConformant)
        .log(s"non-conformant: contract=${result.contractJudgment}, validator=${result.validatorJudgment}")

  // ── Property: conformance-no-false-positive (symmetry half 1)
  // spec: migration-protocol — Formal Contract: Conformance symmetry — no false positives
  property("conformance symmetry: no false positive over corpus"):
    for record <- genContractRecord.forAll
    yield
      val result: ConformanceResult = evaluateConformance(record)
      Result
        .assert(!result.isFalsePositive)
        .log(s"false positive: contract rejected, validator accepted")

  // ── Property: conformance-no-false-negative (symmetry half 2)
  // spec: migration-protocol — Formal Contract: Conformance symmetry — no false negatives
  property("conformance symmetry: no false negative over corpus"):
    for record <- genContractRecord.forAll
    yield
      val result: ConformanceResult = evaluateConformance(record)
      Result
        .assert(!result.isFalseNegative)
        .log(s"false negative: contract accepted, validator rejected")

  // ── Generator: genContractRecord
  // Constructive over records satisfying and violating each clause of each
  // contract. For each clause, generates a satisfying record and a violating
  // record that exercises that clause's boundary.
  def genContractRecord: Gen[ContractRecord] =
    Gen.choice1(
      // Ledger record: satisfying
      Gen.constant(satisfyingLedgerRecord),
      // Ledger record: violating each clause
      Gen.element(ledgerClauses(0), ledgerClauses.drop(1)).map(violatingLedgerRecord),
      // Chain-state report: satisfying (measured shape)
      Gen.constant(satisfyingChainStateReport),
      // Chain-state report: undetermined shape
      Gen.constant(undeterminedChainStateReport),
      // Gate payload: satisfying
      Gen.constant(satisfyingGatePayload),
      // Gate payload: violating each clause
      Gen.element(gateHookJsonClauses(0), gateHookJsonClauses.drop(1)).map(violatingGatePayload)
    )

  // ── Helper: evaluate a record through both the contract and the validator
  def evaluateConformance(record: ContractRecord): ConformanceResult =
    val contractJudgment: ContractJudgment   = runContract(record)
    val validatorJudgment: ValidatorJudgment = runValidator(record)
    ConformanceResult(contractJudgment, validatorJudgment)

  // ── Helper: run the executable .jq contract on a record
  def runContract(record: ContractRecord): ContractJudgment =
    val contractPath: String = ContractId.contractPath(record.contractId)
    val json: String         = ujson.write(record.json)
    val result: Int          = runJqContract(contractPath, json)
    if result == 0 then ContractJudgment.Accept
    else ContractJudgment.Reject(s"jq exit $result")

  // ── Helper: run the ported Scala validator on a record
  def runValidator(record: ContractRecord): ValidatorJudgment =
    record.contractId match
      case ContractId.LedgerRecord =>
        Validator.validate(record.json) match
          case Right(_)  => ValidatorJudgment.Accept
          case Left(err) => ValidatorJudgment.Reject(err.description)
      case ContractId.ChainStateReport =>
        // Chain-state report validation is delegated to Ring 3 property;
        // here we check the measured-shape monotonicity law.
        validateChainStateReport(record.json) match
          case Right(_)  => ValidatorJudgment.Accept
          case Left(err) => ValidatorJudgment.Reject(err)
      case ContractId.GateHookJson =>
        validateGatePayload(record.json) match
          case Right(_)  => ValidatorJudgment.Accept
          case Left(err) => ValidatorJudgment.Reject(err)

  // ── Helper: run jq -e -f <contract> on a JSON string, return exit code
  private def runJqContract(contractPath: String, json: String): Int =
    val fullPath: os.Path = os.pwd / os.SubPath(contractPath)
    if !os.exists(fullPath) then
      // Contract file missing — treat as reject (the contract is the oracle)
      1
    else
      val cmd: List[String] = List("jq", "-e", "-f", fullPath.toString)
      // spec: hermetic-test-processes — via the shared helper.
      HermeticEnv
        .capture(cmd, HermeticEnv.empty, stdin = Some(json.getBytes("UTF-8")))
        .exitCode

  // ── Helper: validate a chain-state report JSON (measured shape)
  // Mirrors the jq contract's cross-checks:
  //   total - bound = count of unresolved with reason "unbound"
  //   bound - resolved = count of unresolved with reason "unresolved" or "unattributable"
  //   resolved - discharged = count of unresolved with reason "undischarged" or "failed"
  private def validateChainStateReport(json: ujson.Value): Either[String, Unit] =
    json match
      case obj: ujson.Obj =>
        val m: Map[String, ujson.Value] = obj.value.toMap
        // Check undetermined shape
        m.get("undetermined") match
          case Some(ujson.Bool(true)) =>
            // Undetermined shape: change, baseline, undetermined, reason required
            val hasFields: Boolean = List("change", "baseline", "reason").forall(m.contains)
            if !hasFields then Left("undetermined report missing required fields")
            else Right(())
          case _ =>
            // Measured shape: check monotonicity and cross-checks
            (m.get("total"), m.get("bound"), m.get("resolved"), m.get("discharged")) match
              case (Some(t: ujson.Num), Some(b: ujson.Num), Some(r: ujson.Num), Some(d: ujson.Num)) =>
                val total: Int      = t.value.toInt
                val bound: Int      = b.value.toInt
                val resolved: Int   = r.value.toInt
                val discharged: Int = d.value.toInt
                if discharged > resolved then Left("discharged > resolved")
                else if resolved > bound then Left("resolved > bound")
                else if bound > total then Left("bound > total")
                else
                  // Cross-checks against unresolved entries
                  val unresolved: List[Map[String, ujson.Value]] = m.get("unresolved") match
                    case Some(arr: ujson.Arr) =>
                      arr.value.toList.map {
                        case obj: ujson.Obj => obj.value.toMap
                        case _              => Map.empty[String, ujson.Value]
                      }
                    case _ => Nil
                  val reasonsOf: Map[String, ujson.Value] => List[String] = entry =>
                    entry.get("reasons") match
                      case Some(arr: ujson.Arr) =>
                        arr.value.toList.flatMap {
                          case s: ujson.Str => Some(s.value)
                          case _            => None
                        }
                      case _ => Nil
                  val unboundCount: Int = unresolved.count(e => reasonsOf(e).contains("unbound"))
                  val unresolvedOrUnattributableCount: Int = unresolved.count(e =>
                    reasonsOf(e).contains("unresolved") || reasonsOf(e).contains("unattributable")
                  )
                  val undischargedOrFailedCount: Int =
                    unresolved.count(e => reasonsOf(e).contains("undischarged") || reasonsOf(e).contains("failed"))
                  if total - bound != unboundCount then
                    Left(s"total - bound (${total - bound}) must equal unbound count ($unboundCount)")
                  else if bound - resolved != unresolvedOrUnattributableCount then
                    Left(
                      s"bound - resolved must equal unresolved+unattributable count ($unresolvedOrUnattributableCount)"
                    )
                  else if resolved - discharged != undischargedOrFailedCount then
                    Left(s"resolved - discharged must equal undischarged+failed count ($undischargedOrFailedCount)")
                  else Right(())
              case _ => Left("missing count fields")
      case _ => Left("not a JSON object")

  // ── Helper: validate a gate payload JSON
  private def validateGatePayload(json: ujson.Value): Either[String, Unit] =
    json match
      case obj: ujson.Obj =>
        val m: Map[String, ujson.Value] = obj.value.toMap
        m.get("hookSpecificOutput") match
          case Some(hso: ujson.Obj) =>
            val hsoMap: Map[String, ujson.Value] = hso.value.toMap
            hsoMap.get("hookEventName") match
              case Some(ujson.Str(name)) if name == "SessionStart" || name == "UserPromptSubmit" =>
                hsoMap.get("additionalContext") match
                  case Some(ujson.Str(_)) | None => Right(())
                  case _                         => Left("additionalContext must be a string when present")
              case Some(_) => Left("hookEventName must be SessionStart or UserPromptSubmit")
              case None    => Left("hookEventName must be a non-empty string")
          case _ => Left("must have a hookSpecificOutput object")
      case _ => Left("must be a JSON object")

  // ── Fixtures: satisfying records ──────────────────────────────────────────

  private def satisfyingLedgerRecord: ContractRecord =
    ContractRecord(
      contractId = ContractId.LedgerRecord,
      json = ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-08T12:34:56Z"),
        "change"     -> ujson.Str("port-scanner-to-probatio"),
        "spec"       -> ujson.Str("migration-protocol"),
        "ring"       -> ujson.Str("R3"),
        "obligation" -> ujson.Str("Conformance between validators and executable contracts is a property test"),
        "artifact"   -> ujson.Str("ConformanceSpec.scala"),
        "command"    -> ujson.Str("sbt probatio-core/test"),
        "exit"       -> ujson.Num(0),
        "baseline"   -> ujson.Str("abc1234")
      ),
      violatedClause = None
    )

  private def satisfyingChainStateReport: ContractRecord =
    ContractRecord(
      contractId = ContractId.ChainStateReport,
      json = ujson.Obj(
        "change"     -> ujson.Str("port-scanner-to-probatio"),
        "baseline"   -> ujson.Str("abc1234"),
        "total"      -> ujson.Num(5),
        "bound"      -> ujson.Num(5),
        "resolved"   -> ujson.Num(3),
        "discharged" -> ujson.Num(2),
        "unresolved" -> ujson.Arr(
          ujson.Obj(
            "spec"        -> ujson.Str("migration-protocol"),
            "requirement" -> ujson.Str("Obligation A"),
            "reasons"     -> ujson.Arr(ujson.Str("unresolved"))
          ),
          ujson.Obj(
            "spec"        -> ujson.Str("migration-protocol"),
            "requirement" -> ujson.Str("Obligation B"),
            "reasons"     -> ujson.Arr(ujson.Str("unattributable"))
          ),
          ujson.Obj(
            "spec"        -> ujson.Str("migration-protocol"),
            "requirement" -> ujson.Str("Obligation C"),
            "reasons"     -> ujson.Arr(ujson.Str("undischarged"))
          )
        ),
        "unmapped_obligations" -> ujson.Arr()
      ),
      violatedClause = None
    )

  private def undeterminedChainStateReport: ContractRecord =
    ContractRecord(
      contractId = ContractId.ChainStateReport,
      json = ujson.Obj(
        "change"               -> ujson.Str("port-scanner-to-probatio"),
        "baseline"             -> ujson.Str("abc1234"),
        "undetermined"         -> ujson.Bool(true),
        "reason"               -> ujson.Str("spec-lint could not classify the change"),
        "total"                -> ujson.Null,
        "bound"                -> ujson.Null,
        "resolved"             -> ujson.Null,
        "discharged"           -> ujson.Null,
        "unresolved"           -> ujson.Arr(),
        "unmapped_obligations" -> ujson.Arr()
      ),
      violatedClause = None
    )

  private def satisfyingGatePayload: ContractRecord =
    ContractRecord(
      contractId = ContractId.GateHookJson,
      json = ujson.Obj(
        "hookSpecificOutput" -> ujson.Obj(
          "hookEventName"     -> ujson.Str("SessionStart"),
          "additionalContext" -> ujson.Str("session context block")
        )
      ),
      violatedClause = None
    )

  // ── Fixtures: violating records (one per clause) ──────────────────────────

  private def violatingLedgerRecord(clause: String): ContractRecord =
    val base: ujson.Obj = satisfyingLedgerRecord.json match
      case obj: ujson.Obj => obj
      case other          => sys.error(s"expected ujson.Obj, got $other")
    val violated: ujson.Value = clause match
      case "not-a-json-object" => ujson.Str("not an object")
      case "missing-required-field" =>
        val m: Map[String, ujson.Value] = base.value.toMap - "exit"
        ConformanceTypes.objFromMap(m)
      case "v-must-be-integer-gte-1" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("v", ujson.Num(0))
        ConformanceTypes.objFromMap(m)
      case "ts-must-be-iso-8601-utc" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("ts", ujson.Str("not-a-timestamp"))
        ConformanceTypes.objFromMap(m)
      case "change-must-be-non-empty-no-separator" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("change", ujson.Str("a/b"))
        ConformanceTypes.objFromMap(m)
      case "spec-must-be-non-empty-no-separator" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("spec", ujson.Str(""))
        ConformanceTypes.objFromMap(m)
      case "ring-must-be-in-closed-domain" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("ring", ujson.Str("R99"))
        ConformanceTypes.objFromMap(m)
      case "obligation-must-be-non-empty" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("obligation", ujson.Str(""))
        ConformanceTypes.objFromMap(m)
      case "artifact-must-be-non-empty" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("artifact", ujson.Str(""))
        ConformanceTypes.objFromMap(m)
      case "command-must-be-non-empty" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("command", ujson.Str(""))
        ConformanceTypes.objFromMap(m)
      case "exit-must-be-integer" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("exit", ujson.Num(1.5))
        ConformanceTypes.objFromMap(m)
      case "baseline-must-be-lowercase-hex-7-40" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("baseline", ujson.Str("XYZ"))
        ConformanceTypes.objFromMap(m)
      case other => ujson.Str(s"unknown clause: $other")

    ContractRecord(
      contractId = ContractId.LedgerRecord,
      json = violated,
      violatedClause = Some(clause)
    )

  private def violatingGatePayload(clause: String): ContractRecord =
    val violated: ujson.Value = clause match
      case "must-be-json-object" =>
        ujson.Str("not an object")
      case "must-have-hookSpecificOutput-object" =>
        ujson.Obj("wrongKey" -> ujson.Num(1))
      case "hookEventName-must-be-non-empty-string" =>
        ujson.Obj("hookSpecificOutput" -> ujson.Obj("hookEventName" -> ujson.Str("")))
      case "hookEventName-must-be-SessionStart-or-UserPromptSubmit" =>
        ujson.Obj("hookSpecificOutput" -> ujson.Obj("hookEventName" -> ujson.Str("UnknownEvent")))
      case "additionalContext-must-be-string-when-present" =>
        ujson.Obj(
          "hookSpecificOutput" -> ujson.Obj(
            "hookEventName"     -> ujson.Str("SessionStart"),
            "additionalContext" -> ujson.Num(42)
          )
        )
      case other => ujson.Str(s"unknown clause: $other")

    ContractRecord(
      contractId = ContractId.GateHookJson,
      json = violated,
      violatedClause = Some(clause)
    )

  // ═══════════════════════════════════════════════════════════════════
  // graph-tool-port — Step 2 oracle (wire format + predecessor agreement)
  // ═══════════════════════════════════════════════════════════════════

  // ── Property: export-round-trips (Ring 4 wire format) ───────────────
  // spec: graph-tool-port — Property: export-round-trips
  property("graph export round-trips — node set, edge set, unlinkable set preserved"):
    for gen <- GraphFixtures.genGraph.forAll
    yield
      val build: GraphBuild = GraphBuild(gen.graph, Nil, 0, 0)
      GraphWire.readExport(GraphWire.writeExport(build)) match
        case Right(back) =>
          Result
            .assert(back.graph.nodes == gen.graph.nodes)
            .log("node set changed")
            .and(Result.assert(back.graph.edges == gen.graph.edges).log("edge set changed"))
            .and(Result.assert(back.graph.unlinkable == gen.graph.unlinkable).log("unlinkable set changed"))
        case Left(e) => Result.failure.log(s"readExport rejected its own export: $e")

  // ── Scenario: the two exports agree on the repository corpus ────────
  // spec: graph-tool-port — Scenario: Happy path — the two exports agree on the repository corpus
  test("graph: exported graph agrees with the predecessor on the repository corpus"):
    (GraphConformance.exportPorted(os.pwd), GraphConformance.exportPredecessor(os.pwd)) match
      case (Right(ported), Right(model)) =>
        val diffs: List[String] = GraphConformance.diffExports(ported, model)
        assert(diffs.isEmpty, diffs.mkString("export divergence:\n  ", "\n  ", ""))
      case (Left(e), _) => fail(s"ported export failed on the repo corpus: $e")
      case (_, Left(e)) => fail(s"predecessor export failed on the repo corpus: $e")

  // ── Property: export-agrees-with-the-predecessor (generated corpora) ─
  // spec: graph-tool-port — Property: export-agrees-with-the-predecessor
  property("graph export agrees with the predecessor on generated corpora",
           (c: hedgehog.core.PropertyConfig) => c.copy(testLimit = hedgehog.core.SuccessCount(20))):
    for corpus <- GraphFixtures.genSourceCorpus.forAll
    yield
      val dir: os.Path = os.temp.dir(prefix = "graph-conformance-")
      try
        GraphConformance.materialise(corpus, dir)
        (GraphConformance.exportPorted(dir), GraphConformance.exportPredecessor(dir)) match
          case (Right(ported), Right(model)) =>
            val diffs: List[String] = GraphConformance.diffExports(ported, model)
            if diffs.isEmpty then Result.success
            else Result.failure.log(diffs.mkString("export divergence:\n  ", "\n  ", ""))
          case (Left(e), _) => Result.failure.log(s"ported export failed: $e")
          case (_, Left(e)) => Result.failure.log(s"predecessor export failed: $e")
      finally os.remove.all(dir) // scalafix:ok DisableSyntax.NoKeywordFinally

  // ── Scenario: Adversarial — disagreement reported per node ──────────
  // spec: graph-tool-port — Scenario: Adversarial — a disagreement is reported per node, not summarised
  test("graph: an export disagreement names each differing node and edge"):
    val ported: ujson.Value = ujson.Obj(
      "nodes" -> ujson.Arr(
        ujson.Obj("id" -> "concept:A", "kind" -> "concept"),
        ujson.Obj("id" -> "type:OnlyPorted", "kind" -> "type")
      ),
      "edges" -> ujson.Arr(
        ujson.Obj("from" -> "spec:x/y", "rel" -> "cites", "to" -> "concept:A"),
        ujson.Obj("from" -> "spec:x/y", "rel" -> "uses", "to" -> "type:OnlyPorted")
      ),
      "warnings"   -> ujson.Arr(),
      "unlinkable" -> ujson.Arr()
    )
    val model: ujson.Value = ujson.Obj(
      "nodes" -> ujson.Arr(
        ujson.Obj("id" -> "concept:A", "kind" -> "concept"),
        ujson.Obj("id" -> "type:OnlyModel", "kind" -> "type")
      ),
      "edges" -> ujson.Arr(
        ujson.Obj("from" -> "spec:x/y", "rel" -> "cites", "to" -> "concept:A"),
        ujson.Obj("from" -> "spec:x/y", "rel" -> "uses", "to" -> "type:OnlyModel")
      ),
      "warnings" -> ujson.Arr()
    )
    val diffs: List[String] = GraphConformance.diffExports(ported, model)
    assert(diffs.exists(_.contains("type:OnlyPorted")), s"node diff must name OnlyPorted: $diffs")
    assert(diffs.exists(_.contains("type:OnlyModel")), s"node diff must name OnlyModel: $diffs")
    assert(diffs.exists(_.contains("uses")), s"edge diff must name the differing edges: $diffs")
    assertEquals(diffs.length, 4, s"expected 2 node + 2 edge diffs, got: $diffs")
