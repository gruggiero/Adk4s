package org.adk4s.core.types

import cats.{Eq, Show, Order}
import io.github.iltotore.iron.{:|, Constraint, RefinedType}
import io.github.iltotore.iron.constraint.any.Not
import io.github.iltotore.iron.constraint.string.Blank
import io.github.iltotore.iron.constraint.numeric
import io.github.iltotore.iron.cats.given
import org.adk4s.core.error.{NodeKeyError, ConfigError}

// ── Custom constraints ───────────────────────────────────────────

// NonEmpty: rejects empty and whitespace-only strings. Defined as
// Not[Blank] to leverage Iron's built-in compile-time checking.
// This is slightly stricter than just "non-empty" (also rejects
// whitespace-only strings), which is desirable for node keys.
type NonEmpty = Not[Blank]

// Reserved: satisfied when the value is one of the reserved node keys
// ("__start__" or "__end__"). Used with Not to forbid these in NodeKey.
final class Reserved

given Constraint[String, Reserved] with
  override inline def test(inline value: String): Boolean =
    value == "__start__" || value == "__end__"
  override inline def message: String = "Should not be a reserved node key"

// ── NodeKey: refined opaque type via RefinedType ─────────────────
// NodeKey is a String that is non-empty (not blank) and not reserved.
// The constraint is enforced at compile time for inline literals via
// NodeKey("literal") (RefinedType.apply) and at runtime via
// NodeKey.either(key) / NodeKey.from(key).
//
// RefinedType makes the type opaque, preventing accidental mixing with
// other refined types. The .value extension is inherited from Refined.
//
// spec: add-iron-refined-types/core-types — Requirement: NodeKey is a refined opaque type rejecting empty and reserved values

type NodeKey = NodeKey.T

object NodeKey extends RefinedType[String, NonEmpty & Not[Reserved]]:

  // ── Runtime smart constructors ─────────────────────────────────

  /** Refinement returning a structured ConfigError on failure.
    * This is the spec-required API: `NodeKey.refineEither(s)` returns
    * `Either[ConfigError, NodeKey]` with the field name, invalid value,
    * and constraint name. */
  def refineEither(key: String): Either[ConfigError, NodeKey] =
    either(key) match
      case Right(nk) => Right(nk)
      case Left(_)   => Left(ConfigError("NodeKey", key, "NonEmpty & Not[Reserved]"))

  /** Total smart constructor returning a typed error.
    * Uses RefinedType.either for constraint checking, then maps the
    * error to NodeKeyError. Retained for backward compatibility. */
  def from(key: String): Either[NodeKeyError, NodeKey] =
    either(key) match
      case Right(nk) => Right(nk)
      case Left(_)   => Left(NodeKeyError(key))

  given Eq[NodeKey]    = Eq.fromUniversalEquals
  given Show[NodeKey]  = Show.show(_.value)
  given Order[NodeKey] = Order.by(_.value)

// ── ReservedNodeKey: enum for reserved node key values ───────────
// Replaces the old NodeKey.START / NodeKey.END constants.
// A ReservedNodeKey is NOT a NodeKey — it is a distinct type for the
// reserved routing values. This enforces at the type level that
// __start__ and __end__ cannot be used as regular node keys.
//
// spec: add-iron-refined-types/core-types — Scenario: Reserved value is constructible as ReservedNodeKey

enum ReservedNodeKey:
  case Start
  case End

  def value: String = this match
    case Start => "__start__"
    case End   => "__end__"

// ── Shared numeric constraint aliases ────────────────────────────
// Reusable across tools-node, react-agent, memory-orchestration-hook,
// structured-llm specs.
//
// spec: add-iron-refined-types/core-types — Requirement: Positive and NonNegative constraint aliases are reusable

type Positive = Int :| numeric.Positive
type NonNegative = Int :| numeric.Positive0
