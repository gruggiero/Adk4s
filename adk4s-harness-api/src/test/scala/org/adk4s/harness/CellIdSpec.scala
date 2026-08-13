package org.adk4s.harness

// spec: add-iron-refined-types/harness-state — Test oracle (Step 2)
// Hedgehog properties and scenario tests for StateCell.CellId.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.upickle.given
import org.adk4s.core.error.ConfigError

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class CellIdSpec extends HedgehogSuite:

  // ── Scenario: Well-formed owner/name compiles ────────────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: Well-formed owner/name compiles

  test("well-formed owner/name literal compiles and .value returns the string"):
    val id: StateCell.CellId  = StateCell.CellId("counter/n")
    val underlying: String    = id.value
    assertEquals(underlying, "counter/n")

  // ── Scenario: Empty string is rejected at runtime ────────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: Empty string is rejected at runtime

  test("refineEither rejects empty string with ConfigError"):
    val result: Either[ConfigError, StateCell.CellId] = StateCell.CellId.refineEither("")
    assert(result.isLeft, s"Expected Left for empty string, got $result")

  // ── Scenario: Missing name segment is rejected ───────────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: Missing name segment is rejected

  test("refineEither rejects missing name segment (counter/)"):
    val result: Either[ConfigError, StateCell.CellId] = StateCell.CellId.refineEither("counter/")
    assert(result.isLeft, s"Expected Left for 'counter/', got $result")

  test("refineEither rejects missing owner segment (/n)"):
    val result: Either[ConfigError, StateCell.CellId] = StateCell.CellId.refineEither("/n")
    assert(result.isLeft, s"Expected Left for '/n', got $result")

  test("refineEither rejects no slash"):
    val result: Either[ConfigError, StateCell.CellId] = StateCell.CellId.refineEither("noslash")
    assert(result.isLeft, s"Expected Left for 'noslash', got $result")

  // ── Scenario: CellId.apply constructs a valid id from owner and name ──
  // spec: add-iron-refined-types/harness-state — Scenario: CellId.apply constructs a valid id from owner and name

  test("CellId.apply(owner, name) constructs a valid id"):
    val owner: MiddlewareName = MiddlewareName("counter")
    val id: StateCell.CellId  = StateCell.CellId(owner, "n")
    val underlying: String    = id.value
    assertEquals(underlying, "counter/n")

  // ── Property: CellId refineEither round-trips for well-formed inputs
  // spec: add-iron-refined-types/harness-state — Property: CellId refineEither round-trips

  property("CellId refineEither round-trips for well-formed inputs"):
    val gen: Gen[String] =
      for
        owner <- Gen.string(Gen.alpha, Range.linear(1, 10))
        name  <- Gen.string(Gen.alpha, Range.linear(1, 10))
      yield s"$owner/$name"
    gen.forAll.map { (s: String) =>
      val result: Either[ConfigError, StateCell.CellId] = StateCell.CellId.refineEither(s)
      result.map(_.value) ==== Right(s)
    }

  // ── Property: CellId JSON round-trip
  // spec: add-iron-refined-types/harness-state — Property: MiddlewareName and CellId JSON round-trip

  property("CellId JSON round-trip"):
    val gen: Gen[String] =
      for
        owner <- Gen.string(Gen.alpha, Range.linear(1, 10))
        name  <- Gen.string(Gen.alpha, Range.linear(1, 10))
      yield s"$owner/$name"
    gen.forAll.map { (s: String) =>
      val id: StateCell.CellId      = StateCell.CellId.refineEither(s).fold(err => throw err, identity)
      val json: String              = upickle.default.write(id)
      val decoded: StateCell.CellId = upickle.default.read[StateCell.CellId](json)
      decoded.value ==== id.value
    }
