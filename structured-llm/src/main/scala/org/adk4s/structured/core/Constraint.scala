package org.adk4s.structured.core

import org.adk4s.structured.core.StructuredLLMError.ValidationFailed

/**
 * Constraint level — controls whether a failed constraint is non-failing (Check)
 * or strict (Assert, raises error).
 */
enum ConstraintLevel:
  case Check
  case Assert

/**
 * Status of a constraint evaluation.
 */
enum CheckStatus:
  case Succeeded
  case Failed

/**
 * Result of evaluating a single constraint (check).
 */
final case class ResponseCheck(
  name: String,
  status: CheckStatus
)

/**
 * A constraint attached to a schema, evaluated after parsing.
 *
 * @param label   Human-readable name for the constraint
 * @param level   Check (non-failing) or Assert (strict, raises error)
 * @param predicate The predicate that must hold for the parsed value
 */
final case class Constraint[A](
  label: String,
  level: ConstraintLevel,
  predicate: A => Boolean
)

object Constraint:

  /**
   * Create a non-failing check constraint.
   * Failed checks are collected as warnings but do not fail the parse.
   */
  def check[A](label: String)(predicate: A => Boolean): Constraint[A] =
    Constraint(label, ConstraintLevel.Check, predicate)

  /**
   * Create a strict assert constraint.
   * Failed asserts raise ValidationFailed.
   */
  def assert[A](label: String)(predicate: A => Boolean): Constraint[A] =
    Constraint(label, ConstraintLevel.Assert, predicate)

  /**
   * Evaluate a single constraint on a value, returning the check result.
   * The value is always returned regardless of check status.
   */
  def evaluate[A](value: A, constraint: Constraint[A]): ValidationResult[A] =
    val status: CheckStatus =
      if constraint.predicate(value) then CheckStatus.Succeeded else CheckStatus.Failed
    ValidationResult(value, Vector(ResponseCheck(constraint.label, status)))

  extension [A](constraint: Constraint[A])
    /**
     * Evaluate this constraint on a value, returning the check result.
     */
    def evaluate(value: A): ValidationResult[A] =
      Constraint.evaluate(value, constraint)

    /**
     * Evaluate this constraint strictly — returns Left(ValidationFailed) if
     * this is an Assert and the predicate fails.
     */
    def evaluateStrict(value: A): Either[ValidationFailed, A] =
      if constraint.predicate(value) then Right(value)
      else constraint.level match
        case ConstraintLevel.Check  => Right(value)
        case ConstraintLevel.Assert => Left(ValidationFailed(Vector(constraint.label)))

  /**
   * Evaluate all constraints on a value, collecting check results.
   * Check failures are non-failing; Assert failures are collected into failedAsserts.
   */
  def evaluateAll[A](value: A, constraints: Vector[Constraint[A]]): ValidationResult[A] =
    val checks: Vector[ResponseCheck] = constraints.map { c =>
      val status: CheckStatus = if c.predicate(value) then CheckStatus.Succeeded else CheckStatus.Failed
      ResponseCheck(c.label, status)
    }
    ValidationResult(value, checks)

  /**
   * Evaluate all constraints strictly — returns Left(ValidationFailed) if
   * any Assert constraint fails.
   */
  def evaluateStrictAll[A](
    value: A,
    constraints: Vector[Constraint[A]]
  ): Either[ValidationFailed, A] =
    val failedAsserts: Vector[String] = constraints.flatMap { c =>
      if c.predicate(value) then None
      else c.level match
        case ConstraintLevel.Check  => None
        case ConstraintLevel.Assert => Some(c.label)
    }
    if failedAsserts.isEmpty then Right(value)
    else Left(ValidationFailed(failedAsserts))

/**
 * Result of validating a parsed value against its constraints.
 *
 * @param value   The parsed value (always present — checks don't fail the parse)
 * @param checks  Results of each constraint evaluation
 */
final case class ValidationResult[A](
  value: A,
  checks: Vector[ResponseCheck]
):
  /**
   * Check results for constraints that failed.
   */
  def failedChecks: Vector[ResponseCheck] = checks.filter(_.status == CheckStatus.Failed)

  /**
   * Check results for constraints that succeeded.
   */
  def succeededChecks: Vector[ResponseCheck] = checks.filter(_.status == CheckStatus.Succeeded)

  /**
   * Whether all checks succeeded.
   */
  def allChecksPassed: Boolean = failedChecks.isEmpty
