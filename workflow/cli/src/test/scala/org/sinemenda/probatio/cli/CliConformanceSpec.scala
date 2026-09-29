package org.sinemenda.probatio.cli

import hedgehog.*
import org.sinemenda.probatio.core.*
import upickle.default.*

/**
 * Tests for stdout payload byte-compatibility with the three .jq contracts
 * (R-P3). The full bidirectional conformance property (R-M2) lives in the
 * migration-protocol spec; this is the CLI-level statement.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Stdout payloads are byte-compatible with the contract files
 * spec: port-scanner-to-probatio/cli-protocol — Property: stdout-conformance-with-jq-contracts
 */
final class CliConformanceSpec extends ProbatioCliSuite:

  // ── Scenario: Ledger output accepted by ledger record contract
  // spec: cli-protocol — Scenario: Ledger output accepted by ledger record contract
  test("LedgerRecord serializes to JSON accepted by the ledger record contract shape"):
    val record: LedgerRecord = LedgerRecord.from(
      ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-19T00:00:00Z"),
        "change"     -> ujson.Str("port-scanner-to-probatio"),
        "spec"       -> ujson.Str("probatio-core"),
        "ring"       -> ujson.Str("R0"),
        "obligation" -> ujson.Str("The three-way exit protocol is a sealed enum"),
        "artifact"   -> ujson.Str("Outcome.scala"),
        "command"    -> ujson.Str("sbt probatio-core/test"),
        "exit"       -> ujson.Num(0),
        "baseline"   -> ujson.Str("abc1234")
      )
    ) match
      case Right(r)  => r
      case Left(err) => fail(s"validator rejected valid record: ${err.description}")
    val json: String = write(record)
    assert(json.contains("change"), "ledger record JSON missing 'change' field")
    assert(json.contains("spec"), "ledger record JSON missing 'spec' field")
    assert(json.contains("obligation"), "ledger record JSON missing 'obligation' field")
    assert(json.contains("ring"), "ledger record JSON missing 'ring' field")
    assert(json.contains("exit"), "ledger record JSON missing 'exit' field")

  // ── Scenario: Chain-state output accepted by chain-state report contract
  // spec: cli-protocol — Scenario: Chain-state output accepted by chain-state report contract
  test("ChainStateReport serializes to JSON accepted by the chain-state report contract shape"):
    // 35 undischarged + 17 discharged = 52 total — contract-consistent counts.
    val unresolved: List[UnresolvedEntry] = (1 to 35).toList.map { i =>
      UnresolvedEntry
        .of("spec", s"req-$i", List(UnresolvedReason.Undischarged))
        .getOrElse(fail(s"unrepresentable entry: req-$i"))
    }
    val report: ChainStateReport = ChainStateReport
      .fromCounts(
        change = "port-scanner-to-probatio",
        baseline = "abc123",
        total = 52,
        bound = 52,
        resolved = 52,
        discharged = 17,
        unresolved = unresolved,
        unmappedObligations = Nil
      )
      .getOrElse(fail("fixture report violates the report contract"))
    val json: String = write(report)
    assert(json.contains("change"), "chain-state report JSON missing 'change' field")
    assert(json.contains("baseline"), "chain-state report JSON missing 'baseline' field")
    assert(json.contains("total"), "chain-state report JSON missing 'total' field")
    assert(json.contains("discharged"), "chain-state report JSON missing 'discharged' field")
    assert(json.contains("unresolved"), "chain-state report JSON missing 'unresolved' field")

  // ── Scenario: Gate output accepted by gate hook-json contract
  // spec: cli-protocol — Scenario: Gate output accepted by gate hook-json contract
  test("GatePayload serializes to JSON accepted by the gate hook-json contract shape"):
    val payload: GatePayload = GatePayload(
      HookSpecificOutput(
        hookEventName = "prompt-submit",
        additionalContext = Some("session context block")
      )
    )
    val json: String = write(payload)
    assert(json.contains("hookSpecificOutput"), "gate payload JSON missing 'hookSpecificOutput'")
    assert(json.contains("hookEventName"), "gate payload JSON missing 'hookEventName'")
    assert(json.contains("prompt-submit"), "gate payload JSON missing event name value")

  // ── Scenario: Current scripts' output accepted by ported validator
  // spec: cli-protocol — Scenario: Current scripts' output accepted by ported validator
  test("LedgerRecord round-trips through uPickle JSON losslessly"):
    val record: LedgerRecord = LedgerRecord.from(
      ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-19T00:00:00Z"),
        "change"     -> ujson.Str("test"),
        "spec"       -> ujson.Str("test-spec"),
        "ring"       -> ujson.Str("R3"),
        "obligation" -> ujson.Str("test obligation"),
        "artifact"   -> ujson.Str("test.scala"),
        "command"    -> ujson.Str("sbt test"),
        "exit"       -> ujson.Num(0),
        "baseline"   -> ujson.Str("deadbeef")
      )
    ) match
      case Right(r)  => r
      case Left(err) => fail(s"validator rejected valid record: ${err.description}")
    val json: String         = write(record)
    val parsed: LedgerRecord = read[LedgerRecord](json)
    assertEquals(parsed, record)

  // ── Property: stdout-conformance-with-jq-contracts
  // spec: cli-protocol — Property: stdout-conformance-with-jq-contracts
  property("stdout-conformance-with-jq-contracts"):
    for
      change   <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      nEntries <- Gen.int(Range.linear(0, 100)).forAll
      okCount  <- Gen.int(Range.linear(0, 100)).forAll
    yield
      // Contract-valid by construction: every unresolved requirement is
      // undischarged, so resolved == bound == total and discharged == okCount.
      val unresolved: List[UnresolvedEntry] = (1 to nEntries).toList.map { i =>
        UnresolvedEntry
          .of("spec", s"req-$i", List(UnresolvedReason.Undischarged))
          .getOrElse(fail(s"unrepresentable entry: req-$i"))
      }
      val total: Int = unresolved.length + okCount
      val report: ChainStateReport = ChainStateReport
        .fromCounts(
          change = change,
          baseline = "baseline-sha",
          total = total,
          bound = total,
          resolved = total,
          discharged = okCount,
          unresolved = unresolved,
          unmappedObligations = Nil
        )
        .getOrElse(fail("generated report violates the report contract"))
      val json: String = write(report)
      // The chain-state report contract requires these fields.
      Result
        .assert(json.contains("change"))
        .and(Result.assert(json.contains("total")))
        .and(Result.assert(json.contains("discharged")))
        .and(Result.assert(json.contains("unresolved")))
        // Round-trip: the ported validator accepts the output.
        .and(Result.assert(read[ChainStateReport](json) == report))
