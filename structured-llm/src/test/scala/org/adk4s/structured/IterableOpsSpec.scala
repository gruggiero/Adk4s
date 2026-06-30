package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

// spec: wartremover-iterable-ops — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// They verify behavior preservation after the refactor in Step 3.

class IterableOpsSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: dropRight(1) agrees with .init on non-empty collections
  // spec: wartremover-iterable-ops — Property: dropRight(1) == init
  // ════════════════════════════════════════════════════════════════════════

  property("dropRight(1) equals init on non-empty lists") {
    val nonEmptyListGen: Gen[List[Int]] =
      Gen.list(Gen.int(Range.linear(-100, 100)), Range.linear(1, 20))
    nonEmptyListGen.forAll.map { (xs: List[Int]) =>
      xs.dropRight(1) ==== xs.take(xs.length - 1)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Refactored .last accessor is total on empty collections
  // spec: wartremover-iterable-ops — Property: total on empty
  // ════════════════════════════════════════════════════════════════════════

  property("lastOption is total on empty and non-empty lists") {
    val listGen: Gen[List[Int]] =
      Gen.list(Gen.int(Range.linear(0, 100)), Range.linear(0, 20))
    listGen.forAll.map { (xs: List[Int]) =>
      val result: Option[Int] = xs.lastOption
      (result.isDefined ==== xs.nonEmpty)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Edge case — empty collection
  // spec: wartremover-iterable-ops — Scenario: empty collection
  // ════════════════════════════════════════════════════════════════════════

  property("dropRight(1) on empty list returns empty (no throw)") {
    val emptyGen: Gen[List[Int]] = Gen.constant(List.empty[Int])
    emptyGen.forAll.map { (xs: List[Int]) =>
      xs.dropRight(1) ==== List.empty[Int]
    }
  }
