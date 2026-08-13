package org.adk4s.harness

// spec: add-iron-refined-types/harness-state — Test oracle (Step 2)
// Hedgehog properties and scenario tests for MiddlewareName.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.upickle.given
import org.adk4s.core.error.ConfigError

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class MiddlewareNameSpec extends HedgehogSuite:

  // ── Scenario: Non-empty literal compiles ─────────────────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: Non-empty literal compiles

  test("non-empty literal compiles and .value returns the string"):
    val name: MiddlewareName = MiddlewareName("todo")
    val underlying: String   = name.value
    assertEquals(underlying, "todo")

  // ── Scenario: Empty string is rejected at runtime ────────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: Empty string is rejected at runtime

  test("refineEither rejects empty string with ConfigError"):
    val result: Either[ConfigError, MiddlewareName] = MiddlewareName.refineEither("")
    result match
      case Left(err: ConfigError) =>
        assertEquals(err.field, "MiddlewareName")
        assertEquals(err.invalidValue, "")
        assertEquals(err.constraint, "NonEmpty")
      case other =>
        fail(s"Expected Left(ConfigError), got $other")

  // ── Scenario: .value returns the underlying string ───────────────────
  // spec: add-iron-refined-types/harness-state — Scenario: .value returns the underlying string

  test(".value returns the underlying string"):
    val name: MiddlewareName = MiddlewareName("counter")
    val underlying: String   = name.value
    assertEquals(underlying, "counter")

  // ── Property: MiddlewareName refineEither round-trips for non-empty inputs
  // spec: add-iron-refined-types/harness-state — Property: MiddlewareName refineEither round-trips

  property("MiddlewareName refineEither round-trips for non-empty inputs"):
    val gen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 30))
    gen.forAll.map { (s: String) =>
      val result: Either[ConfigError, MiddlewareName] = MiddlewareName.refineEither(s)
      result.map(_.value) ==== Right(s)
    }

  // ── Property: MiddlewareName JSON round-trip
  // spec: add-iron-refined-types/harness-state — Property: MiddlewareName and CellId JSON round-trip

  property("MiddlewareName JSON round-trip"):
    val gen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 30))
    gen.forAll.map { (s: String) =>
      val name: MiddlewareName     = MiddlewareName.refineEither(s).fold(err => throw err, identity)
      val json: String             = upickle.default.write(name)
      val decoded: MiddlewareName  = upickle.default.read[MiddlewareName](json)
      decoded.value ==== name.value
    }
