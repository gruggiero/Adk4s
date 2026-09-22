package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of multicall dispatch (`MulticallDispatch.resolveAndSplit`).
 *
 * The dispatch function selects a tool from two signals — the invocation
 * name's basename and at most the first caller argument — and delivers the
 * remaining arguments to that tool. The defect class this model averts is a
 * surface that consumes TWO caller arguments (one as "invocation name", one
 * as tool name), which handed every selected tool an argument list beginning
 * with a bare value it then rejected.
 *
 * Reduction (per spec contract):
 *   - the invocation name reduces to `Option[toolIndex]`: `Some(n)` (n >= 1)
 *     when the basename names tool n, `None` when the basename is a generic
 *     name (`probatio` / `prob`). A basename that is neither is OUT OF
 *     DOMAIN — the shipped code rejects it unconditionally, which the
 *     bridge spec asserts directly.
 *   - the argument list reduces to `List[BigInt]` of token classifications:
 *     `0` = not a tool name, `n >= 1` = tool index n, `Sep` = the POSIX `--`
 *     separator (consumed only in leading position under a generic name —
 *     DEFECT-3).
 *
 * The proved law is that the delivered remainder is a bounded suffix of the
 * input: the input itself (name dispatch), the input minus its head (generic
 * dispatch on a tool token), or the input minus a `--`-then-tool prefix
 * (generic dispatch across the separator). Nothing else may be dropped.
 *
 * spec: cli-entrypoint-contract — Formal Contracts (Ring 6)
 * spec: cli-entrypoint-contract — Contract: resolveAndSplit
 */
object DispatchKernel:

  /** Token classification for the POSIX `--` separator. Not a tool name. */
  val Sep: BigInt = BigInt(-1)

  /** A positive classification identifies a tool by index (1-based). */
  @pure
  def isTool(t: BigInt): Boolean = t > BigInt(0)

  /**
   * Select a tool and split the argument list.
   *
   * Mirrors the shipped priority order:
   *   1. `Some(tool)`: the invocation name named a tool — select it and pass
   *      ALL tokens unchanged (no token is consumed as a tool name).
   *   2. `None`: the invocation name is generic — consume a leading `Sep` if
   *      present, then select iff the (new) head classifies as a tool,
   *      delivering the tail.
   *
   * Precondition: none — the function is total over its domain; that
   * totality is itself the obligation.
   *
   * Postcondition: when a tool is selected, the remainder is a suffix of
   * the input whose dropped prefix is one of exactly three shapes — empty
   * (name dispatch), the selected tool token alone (generic dispatch), or
   * separator-then-tool (generic dispatch across `--`). The length bound
   * `rest.length >= tokens.length - 2` follows.
   *
   * spec: cli-entrypoint-contract — Contract: resolveAndSplit
   * spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
   * spec: cli-entrypoint-contract — Scenario: Symlink invocation reaches the tool with all its arguments
   * spec: cli-entrypoint-contract — Requirement: POSIX -- separator is consumed before dispatch (DEFECT-3)
   */
  @pure
  def resolveAndSplit(
    nameTool: Option[BigInt],
    tokens: List[BigInt]
  ): Option[(BigInt, List[BigInt])] = {
    nameTool match
      case Some(tool) => Some((tool, tokens))
      case None() =>
        val effective: List[BigInt] = tokens match
          case Cons(h, t) if h == Sep => t
          case _ => tokens // danger-scan:allow non-Sep head — the input is kept verbatim, no variant is remapped
        effective match
          case Cons(h, t) => if isTool(h) then Some((h, t)) else None()
          case Nil()      => None()
  }.ensuring { (res: Option[(BigInt, List[BigInt])]) =>
    res match
      case None() => true
      case Some((tool, rest)) =>
        rest == tokens ||
        tokens == Cons(tool, rest) ||
        tokens == Cons(Sep, Cons(tool, rest))
  }

  // ---------------------------------------------------------------------------
  // Property lemmas — proven over the reduced domain.
  // ---------------------------------------------------------------------------

  /**
   * Law: name dispatch selects the named tool and delivers every token,
   * even tokens that classify as tool names or separators — a value in
   * argument position is never read as a tool name.
   * spec: cli-entrypoint-contract — Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name
   */
  @pure
  def nameDispatchPassesAllTokens(tool: BigInt, tokens: List[BigInt]): Boolean = {
    resolveAndSplit(Some(tool), tokens) == Some((tool, tokens))
  }.ensuring(_ == true)

  /**
   * Law: generic dispatch selects a leading tool token and delivers the tail.
   * spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
   */
  @pure
  def genericSelectsLeadingTool(tool: BigInt, rest: List[BigInt]): Boolean = {
    require(isTool(tool))
    resolveAndSplit(None(), Cons(tool, rest)) == Some((tool, rest))
  }.ensuring(_ == true)

  /**
   * Law: generic dispatch across a leading separator behaves like generic
   * dispatch on the remainder.
   * spec: cli-entrypoint-contract — Requirement: POSIX -- separator is consumed before dispatch (DEFECT-3)
   */
  @pure
  def genericConsumesLeadingSeparator(tool: BigInt, rest: List[BigInt]): Boolean = {
    require(isTool(tool))
    resolveAndSplit(None(), Cons(Sep, Cons(tool, rest))) == Some((tool, rest))
  }.ensuring(_ == true)

  /**
   * Law: under a generic name, a non-tool first token selects nothing —
   * even when a later token classifies as a tool.
   * spec: cli-entrypoint-contract — Property: no-silent-selection
   */
  @pure
  def genericNonToolHeadFails(head: BigInt, tail: List[BigInt]): Boolean = {
    require(!isTool(head) && head != Sep)
    resolveAndSplit(None(), Cons(head, tail)) == None()
  }.ensuring(_ == true)

  /**
   * Law: a separator is consumed only once — `-- --` does not select.
   * spec: cli-entrypoint-contract — Requirement: POSIX -- separator is consumed before dispatch (DEFECT-3)
   */
  @pure
  def separatorConsumedOnlyOnce(tokens: List[BigInt]): Boolean = {
    resolveAndSplit(None(), Cons(Sep, Cons(Sep, tokens))) == None()
  }.ensuring(_ == true)

  /**
   * Law: a lone separator under a generic name selects nothing.
   * spec: cli-entrypoint-contract — Scenario: Edge case — no arguments at all under the generic invocation name
   */
  @pure
  def separatorOnlyFails: Boolean = {
    resolveAndSplit(None(), Cons(Sep, Nil())) == None()
  }.ensuring(_ == true)

  /**
   * Law: an empty token list under a generic name selects nothing.
   * spec: cli-entrypoint-contract — Scenario: Edge case — no arguments at all under the generic invocation name
   */
  @pure
  def genericEmptyFails: Boolean = {
    resolveAndSplit(None(), Nil()) == None()
  }.ensuring(_ == true)

  /**
   * Law: the remainder is never longer than the input — the dispatch can
   * only drop tokens, never invent them. Follows from the suffix
   * postcondition on every selecting input shape.
   * spec: cli-entrypoint-contract — Property: argument-preservation
   */
  @pure
  def remainderNeverLonger(nameTool: Option[BigInt], tokens: List[BigInt]): Boolean = {
    resolveAndSplit(nameTool, tokens) match
      case None()          => true
      case Some((_, rest)) => rest.length <= tokens.length
  }.ensuring(_ == true)

  // ---------------------------------------------------------------------------
  // Gate event-name classification (spec: gate-event-compatibility)
  // ---------------------------------------------------------------------------

  /**
   * The dispatch decision over a supplied event name, abstracted: names
   * reduce to codes — `1..6` for the six recognised names (the index
   * into `recognisedEventNames`), every other code an unrecognised
   * name. `EventInjection` carries the supplied code so the fallback
   * preserves what it fell back from; the shipped `Injection` carries
   * the supplied string itself.
   *
   * spec: gate-event-compatibility — Contract: classify
   */
  sealed abstract class EventDispatchModel
  case class EventTier(event: BigInt)         extends EventDispatchModel
  case class EventInjection(supplied: BigInt) extends EventDispatchModel

  /** The six recognised event names as codes — the closed name set. */
  val recognisedEventNames: List[BigInt] =
    List(BigInt(1), BigInt(2), BigInt(3), BigInt(4), BigInt(5), BigInt(6))

  /** Whether the dispatch selects a tier. */
  @pure
  def isEventTier(d: EventDispatchModel): Boolean = d match
    case EventTier(_)      => true
    case EventInjection(_) => false

  /** Whether the dispatch routes to the injection tier. */
  @pure
  def isEventInjection(d: EventDispatchModel): Boolean = !isEventTier(d)

  /**
   * The total event-name classification — the mirror of the shipped
   * `EventDispatch.classify`. Every input lands in exactly one arm: a
   * recognised code yields `EventTier` for that code, every other code
   * yields `EventInjection` carrying the supplied code.
   *
   * Postcondition (the spec's `classify` contract):
   *  - totality: every input lands in exactly one arm (structural —
   *    the sealed two-variant type has no third shape);
   *  - recognised names land on a tier, and only they do;
   *  - the fallback preserves what it fell back from.
   *
   * spec: gate-event-compatibility — Contract: classify
   * spec: gate-event-compatibility — Property: event-dispatch-is-total
   * spec: gate-event-compatibility — Property: recognised-names-never-fall-back
   */
  @pure
  def classifyEvent(name: BigInt): EventDispatchModel = {
    if recognisedEventNames.contains(name) then EventTier(name)
    else EventInjection(name)
  }.ensuring { (res: EventDispatchModel) =>
    (isEventTier(res) || isEventInjection(res)) &&
    (recognisedEventNames.contains(name) == isEventTier(res)) &&
    (res match
      case EventInjection(n) => n == name
      case EventTier(_)      => true
    )
  }

  /**
   * Law: the recognised-name mapping is injective — two recognised
   * names that classify to the same tier are the same name. Holds
   * because `EventTier` wraps the supplied code itself.
   * spec: gate-event-compatibility — Property: recognised-names-never-fall-back
   */
  @pure
  def classifyEventInjective(a: BigInt, b: BigInt): Boolean = {
    require(recognisedEventNames.contains(a) && recognisedEventNames.contains(b))
    (classifyEvent(a) == classifyEvent(b)) == (a == b)
  }.ensuring(_ == true)

  /** Structural no-duplicates check (stainless `List` has no `distinct`). */
  @pure
  private def noDup(l: List[BigInt]): Boolean = l match
    case Nil()      => true
    case Cons(h, t) => !t.contains(h) && noDup(t)

  /**
   * Law: the recognised set is closed and duplicate-free — the six
   * codes are pairwise distinct, so the enumerated domain and the
   * shipped six-name table cannot drift apart silently.
   * spec: gate-event-compatibility — Property: recognised-names-never-fall-back
   */
  @pure
  def recognisedEventNamesDistinct: Boolean = {
    recognisedEventNames.length == BigInt(6) && noDup(recognisedEventNames)
  }.ensuring(_ == true)
