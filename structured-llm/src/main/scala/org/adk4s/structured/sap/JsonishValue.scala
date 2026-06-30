package org.adk4s.structured.sap

/**
 * Completion state of a parsed value during streaming or partial parsing.
 */
enum CompletionState:
  case Pending
  case Incomplete
  case Complete

/**
 * JsonishValue — intermediate parse value with ambiguity support.
 *
 * This ADT mirrors BAML's jsonish parser, representing the result of
 * lenient JSON parsing where values may have ambiguous interpretations.
 * The `AnyOf` variant carries multiple plausible interpretations,
 * which are resolved by the `TypeCoercer` using the target schema.
 */
enum JsonishValue:
  case Null
  case Bool(value: Boolean, state: CompletionState)
  case Num(value: Double, state: CompletionState)
  case Str(value: String, state: CompletionState)
  case Arr(items: Vector[JsonishValue], state: CompletionState)
  case Obj(fields: Vector[(String, JsonishValue)], state: CompletionState)
  case Markdown(raw: String, inner: JsonishValue, state: CompletionState)
  case AnyOf(choices: Vector[JsonishValue], original: String)

  /**
   * The completion state of this value.
   */
  def completionState: CompletionState = this match
    case Null                              => CompletionState.Complete
    case Bool(_, state)                    => state
    case Num(_, state)                     => state
    case Str(_, state)                     => state
    case Arr(_, state)                     => state
    case Obj(_, state)                     => state
    case Markdown(_, _, state)             => state
    case AnyOf(choices, _)                 =>
      choices.map(_.completionState).foldLeft[CompletionState](CompletionState.Pending) {
        case (CompletionState.Complete, CompletionState.Complete) => CompletionState.Complete
        case (CompletionState.Pending, _)                        => CompletionState.Incomplete
        case (_, CompletionState.Pending)                        => CompletionState.Incomplete
        case _                                                   => CompletionState.Incomplete
      }
