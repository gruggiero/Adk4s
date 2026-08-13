package org.adk4s.orchestration.interrupt

// spec: add-iron-refined-types/checkpoint-store-fpoly — Test oracle (Step 2)
// Hedgehog properties and scenario tests for CheckpointStore.CheckpointId.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.upickle.given
import org.adk4s.core.error.ConfigError

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class CheckpointIdSpec extends HedgehogSuite:

  // ── Scenario: Non-empty literal compiles ─────────────────────────────
  // spec: add-iron-refined-types/checkpoint-store-fpoly — Scenario: Non-empty literal compiles

  test("non-empty literal compiles and .value returns the string"):
    val id: CheckpointStore.CheckpointId = CheckpointStore.CheckpointId("ckpt-1")
    val underlying: String               = id.value
    assertEquals(underlying, "ckpt-1")

  // ── Scenario: Empty string is rejected at runtime ────────────────────
  // spec: add-iron-refined-types/checkpoint-store-fpoly — Scenario: Empty string is rejected at runtime

  test("refineEither rejects empty string with ConfigError"):
    val result: Either[ConfigError, CheckpointStore.CheckpointId] =
      CheckpointStore.CheckpointId.refineEither("")
    result match
      case Left(err: ConfigError) =>
        assertEquals(err.field, "CheckpointId")
        assertEquals(err.invalidValue, "")
        assertEquals(err.constraint, "NonEmpty")
      case other =>
        fail(s"Expected Left(ConfigError), got $other")

  // ── Scenario: Raw String is not assignable to CheckpointId ───────────
  // spec: add-iron-refined-types/checkpoint-store-fpoly — Scenario: Raw String is not assignable to CheckpointId

  test("raw String assignment to CheckpointId does not compile"):
    val errors: String = compileErrors("""val id: CheckpointStore.CheckpointId = "ckpt-1"""")
    assert(errors.nonEmpty, "Raw String must not be assignable to CheckpointId")

  test("empty literal does not compile"):
    val errors: String = compileErrors("""val id: CheckpointStore.CheckpointId = CheckpointStore.CheckpointId("")""")
    assert(errors.nonEmpty, "CheckpointId(\"\") must not compile — empty violates NonEmpty")

  // ── Property: CheckpointId refineEither round-trips for non-empty inputs
  // spec: add-iron-refined-types/checkpoint-store-fpoly — Property: CheckpointId refineEither round-trips

  property("CheckpointId refineEither round-trips for non-empty inputs"):
    val gen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 30))
    gen.forAll.map { (s: String) =>
      val result: Either[ConfigError, CheckpointStore.CheckpointId] =
        CheckpointStore.CheckpointId.refineEither(s)
      result.map(_.value) ==== Right(s)
    }

  // ── Property: CheckpointId JSON round-trip
  // spec: add-iron-refined-types/checkpoint-store-fpoly — Property: CheckpointId JSON round-trip

  property("CheckpointId JSON round-trip"):
    val gen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 30))
    gen.forAll.map { (s: String) =>
      val id: CheckpointStore.CheckpointId =
        CheckpointStore.CheckpointId.refineEither(s).fold(err => throw err, identity)
      val json: String                    = upickle.default.write(id)
      val decoded: CheckpointStore.CheckpointId =
        upickle.default.read[CheckpointStore.CheckpointId](json)
      decoded.value ==== id.value
    }
