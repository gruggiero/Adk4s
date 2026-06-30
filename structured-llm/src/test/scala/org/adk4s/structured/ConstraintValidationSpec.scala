package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.core.Constraint.*
import org.adk4s.structured.core.StructuredLLMError.ValidationFailed
import smithy4s.schema.Schema as Smithy4sSchema

// spec: constraint-validation — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.

class ConstraintValidationSpec extends HedgehogSuite:

  // ── Types for testing ──────────────────────────────────────────────────

  final case class Student(name: String, age: Int)
  given s4sStudent: Smithy4sSchema[Student] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.string.required[Student]("name", _.name),
      Schema.int.required[Student]("age", _.age)
    )(Student.apply)
  }
  given schemaStudent: Schema[Student] = Schema.instance(
    """structure Student { @required name: String @required age: Integer }"""
  )(using s4sStudent)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Check constraints never fail the parse
  // spec: constraint-validation — Property: check non-failing
  // ════════════════════════════════════════════════════════════════════════

  property("check constraints never change the returned value") {
    val studentGen: Gen[Student] =
      for
        name <- Gen.string(Gen.char('a', 'z'), Range.linear(1, 20))
        age  <- Gen.int(Range.linear(0, 100))
      yield Student(name, age)
    studentGen.forAll.map { (s: Student) =>
      val constraint: Constraint[Student] = Constraint.check("old_enough")(_.age > 5)
      val result: ValidationResult[Student] = constraint.evaluate(s)
      result.value ==== s
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Assert constraints fail the parse when predicate is false
  // spec: constraint-validation — Property: assert failing
  // ════════════════════════════════════════════════════════════════════════

  property("assert constraints fail when predicate returns false") {
    val studentGen: Gen[Student] =
      for
        name <- Gen.string(Gen.char('a', 'z'), Range.linear(1, 20))
        age  <- Gen.int(Range.linear(0, 100))
      yield Student(name, age)
    studentGen.forAll.map { (s: Student) =>
      val constraint: Constraint[Student] = Constraint.assert("always_fail")(_ => false)
      val result: Either[ValidationFailed, Student] = constraint.evaluateStrict(s)
      result.isLeft ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: All checks pass
  // ════════════════════════════════════════════════════════════════════════

  test("all passing checks produce all succeeded results") {
    val student: Student = Student("Alice", 20)
    val checks: Vector[Constraint[Student]] = Vector(
      Constraint.check("old_enough")(_.age > 5),
      Constraint.check("has_name")(_.name.nonEmpty)
    )
    val result: ValidationResult[Student] = Constraint.evaluateAll(student, checks)
    assertEquals(result.value, student)
    assert(result.checks.forall(_.status == CheckStatus.Succeeded))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Check fails — non-failing
  // ════════════════════════════════════════════════════════════════════════

  test("failing check does not change the value") {
    val student: Student = Student("Bob", 3)
    val checks: Vector[Constraint[Student]] = Vector(
      Constraint.check("old_enough")(_.age > 5)
    )
    val result: ValidationResult[Student] = Constraint.evaluateAll(student, checks)
    assertEquals(result.value, student)
    assert(result.checks.exists(_.status == CheckStatus.Failed))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Assert fails — error
  // ════════════════════════════════════════════════════════════════════════

  test("failing assert produces ValidationFailed") {
    val student: Student = Student("", 20)
    val asserts: Vector[Constraint[Student]] = Vector(
      Constraint.assert("nonempty_name")(_.name.nonEmpty)
    )
    val result: Either[ValidationFailed, Student] = Constraint.evaluateStrictAll(student, asserts)
    result match
      case Left(failure) =>
        assert(failure.failedAsserts.contains("nonempty_name"))
      case Right(_) => fail("Expected Left(ValidationFailed)")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: withCheck attaches constraint to schema
  // ════════════════════════════════════════════════════════════════════════

  test("withCheck attaches a check constraint to a schema") {
    val schemaWithCheck: Schema[Student] =
      schemaStudent.withCheck("old_enough")(_.age > 5)
    assert(schemaWithCheck.constraints.exists(_.label == "old_enough"))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: withAssert attaches assert constraint to schema
  // ════════════════════════════════════════════════════════════════════════

  test("withAssert attaches an assert constraint to a schema") {
    val schemaWithAssert: Schema[Student] =
      schemaStudent.withAssert("nonempty_name")(_.name.nonEmpty)
    assert(schemaWithAssert.constraints.exists(_.label == "nonempty_name"))
    assert(schemaWithAssert.constraints.exists(_.level == ConstraintLevel.Assert))
  }
