package org.adk4s.structured.core

import smithy4s.schema.Schema as SmithySchema

/**
 * Typeclass for types that can have partial representations during streaming.
 *
 * A `Partial[A]` provides a loosened version of `A` where all fields are
 * `Option[T]`, enabling streaming UIs to show partial data before the
 * full response is received.
 *
 * The typeclass is derived from smithy4s schemas, producing a `Repr` type
 * that represents the partial form of `A`.
 */
trait Partial[A]:
  /**
   * The partial representation type — all fields are Option[T].
   */
  type Repr

  /**
   * Schema for the partial representation (all fields optional).
   */
  def partialSchema: Schema[Repr]

  /**
   * Convert a partial representation back to the full type,
   * filling defaults for missing fields.
   */
  def fromPartial(repr: Repr): A

object Partial:
  /**
   * Create a Partial instance with an explicit Repr type.
   */
  def instance[A, R](
    schema: Schema[R],
    convert: R => A
  ): Partial[A] =
    new Partial[A]:
      type Repr = R
      def partialSchema: Schema[R] = schema
      def fromPartial(repr: R): A = convert(repr)
