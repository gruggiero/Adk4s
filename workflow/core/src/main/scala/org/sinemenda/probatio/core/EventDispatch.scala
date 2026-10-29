package org.sinemenda.probatio.core

/**
 * The total classification of a supplied `--event` name (spec 4 of
 * `repair-probatio-cutover`).
 *
 * The predecessor dispatches the names it recognises and routes every
 * other name to the context-injection tier. The port's parse was a
 * total function into `Option[GateEvent]` — `None` for every
 * unrecognised name — which the caller mapped to an error status,
 * turning a vocabulary mismatch into a gate that does not run. The
 * classification is now total into `EventDispatch` itself: a
 * recognised name yields `Tier` for its own event; anything else
 * yields `Injection` carrying the supplied name, so the fallback can
 * name what it fell back from instead of vanishing into a default
 * branch.
 *
 * `Injection` is a decision, not I/O — it lives in core beside
 * `GateEvent`, and the parse can no longer fail: every caller that
 * handled the old `None` arm is exhaustiveness-escalated by the enum
 * (Ring 0).
 *
 * spec: gate-event-compatibility — Concepts Introduced (new): EventDispatch
 * spec: gate-event-compatibility — Compile-Negative: An event classification that can fail
 * spec: gate-event-compatibility — Compile-Negative: An injection dispatch built without the supplied name
 */
enum EventDispatch:
  /** A recognised name dispatches to its own tier. */
  case Tier(event: GateEvent)

  /**
   * An unrecognised name routes to the context-injection tier. The
   * supplied name is a required field — a fallback that discards what
   * it fell back from is the silent drift this spec keeps visible.
   */
  case Injection(suppliedName: String)

  /** Whether the dispatch selects a tier. */
  def isTier: Boolean = this match
    case EventDispatch.Tier(_)      => true
    case EventDispatch.Injection(_) => false

  /** Whether the dispatch routes to the injection tier. */
  def isInjection: Boolean = !isTier

object EventDispatch:

  /**
   * The (supplied token, tier event) table — the gate's own event
   * vocabulary, in the predecessor's dispatch order. The six tokens are
   * distinct keys, so the name→tier mapping is injective by
   * construction; `recognisedNames` is derived from this one table, so
   * the closed name set and the classification cannot drift apart.
   */
  private val recognisedTable: List[(String, GateEvent)] = List(
    "session-start" -> GateEvent.SessionStart,
    "prompt-submit" -> GateEvent.PromptSubmit,
    "tool-call"     -> GateEvent.ToolCall,
    "post-edit"     -> GateEvent.PostEdit,
    "post-bash"     -> GateEvent.PostBash,
    "completion"    -> GateEvent.Completion
  )

  /**
   * The six recognised event names — the closed name set the totality
   * and injectivity contracts quantify over. Derived from
   * `recognisedTable`, never a second list.
   */
  val recognisedNames: List[String] = recognisedTable.map((p: (String, GateEvent)) => p._1)

  /**
   * The total classification: every input lands in exactly one arm — a
   * recognised name yields `Tier` for its own event, every other name
   * yields `Injection` carrying the supplied name verbatim. Total over
   * all strings by construction: the result is an `EventDispatch`,
   * never an optional and never an either.
   *
   * spec: gate-event-compatibility — Requirement: A recognised event name dispatches to its tier
   * spec: gate-event-compatibility — Requirement: An unrecognised event name routes to the injection tier
   * spec: gate-event-compatibility — Property: event-dispatch-is-total
   * spec: gate-event-compatibility — Property: recognised-names-never-fall-back
   * spec: gate-event-compatibility — Contract: classify
   */
  def classify(name: String): EventDispatch =
    recognisedTable.find((p: (String, GateEvent)) => p._1 == name) match
      case Some((_, event)) => EventDispatch.Tier(event)
      case None             => EventDispatch.Injection(name)

end EventDispatch
