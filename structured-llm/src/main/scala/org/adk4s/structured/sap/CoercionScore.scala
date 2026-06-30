package org.adk4s.structured.sap

/**
 * Flags recording each coercion action taken during type-aware parsing.
 */
enum CoercionFlag:
  case StringToInt
  case StringToBool
  case StringToFloat
  case IntToFloat
  case FloatToInt
  case SingleToArray
  case ObjectToString
  case StrippedNonAlphaNumeric
  case DefaultFromNoValue
  case CaseInsensitive
  case PunctuationStripped
  case AnyOfResolved

/**
 * Score — lower is better (0 = no coercion needed).
 * Score is computed as the sum of flag penalties.
 */
final case class CoercionScore(value: Int):
  def +(other: CoercionScore): CoercionScore = CoercionScore(value + other.value)
  def <=(other: CoercionScore): Boolean       = value <= other.value
  def <(other: CoercionScore): Boolean        = value < other.value

object CoercionScore:
  val Zero: CoercionScore = CoercionScore(0)

  /**
   * Penalty for each coercion flag. Higher penalty = more invasive coercion.
   */
  def fromFlags(flags: Vector[CoercionFlag]): CoercionScore =
    CoercionScore(flags.map(flagPenalty).sum)

  private def flagPenalty(flag: CoercionFlag): Int = flag match
    case CoercionFlag.StringToInt           => 1
    case CoercionFlag.StringToBool          => 1
    case CoercionFlag.StringToFloat         => 1
    case CoercionFlag.IntToFloat            => 1
    case CoercionFlag.FloatToInt            => 2
    case CoercionFlag.SingleToArray         => 1
    case CoercionFlag.ObjectToString        => 2
    case CoercionFlag.StrippedNonAlphaNumeric => 3
    case CoercionFlag.DefaultFromNoValue    => 3
    case CoercionFlag.CaseInsensitive       => 2
    case CoercionFlag.PunctuationStripped   => 3
    case CoercionFlag.AnyOfResolved         => 1

/**
 * Result of a successful coercion — the coerced value plus flags and score.
 */
final case class BamlValueWithFlags[A](
  value: A,
  flags: Vector[CoercionFlag],
  score: CoercionScore
)

/**
 * Error during coercion.
 */
final case class ParsingError(
  message: String,
  path: String,
  expectedType: String,
  actualValue: Option[String] = None
)
