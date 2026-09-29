package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Range
import org.sinemenda.probatio.core.EventDispatch
import org.sinemenda.probatio.core.GateEvent

/**
 * Shared generators and abstractions for the gate-event-compatibility
 * oracle (spec 4 of `repair-probatio-cutover`).
 *
 * `genEventName` follows the spec's generator strategy — a constructive
 * union of three closed alphabets (the six recognised names, the known
 * harness alternates, arbitrary strings) — so recognised names are hit
 * by construction, not by chance. `eventNameCode` is the abstraction the
 * Ring-6 bridge shares with `DispatchKernel`: `1..6` for the recognised
 * names (position in `EventDispatch.recognisedNames`), `0` otherwise.
 *
 * spec: gate-event-compatibility — Properties (Ring 3)
 * spec: gate-event-compatibility — Property: parity-with-predecessor-on-event-dispatch
 */
object EventDispatchFixtures:

  /**
   * The six (token, event) pairs — the oracle's own record of the
   * closed recognised set, in dispatch order. Deliberately independent
   * of `EventDispatch.recognisedNames`: a drift in the shipped table is
   * exactly what the scenario assertions must catch.
   */
  val recognisedPairs: List[(String, GateEvent)] = List(
    "session-start" -> GateEvent.SessionStart,
    "prompt-submit" -> GateEvent.PromptSubmit,
    "tool-call"     -> GateEvent.ToolCall,
    "post-edit"     -> GateEvent.PostEdit,
    "post-bash"     -> GateEvent.PostBash,
    "completion"    -> GateEvent.Completion
  )

  /** The `--event` token for a tier event — the oracle's own mapping. */
  def tokenOf(event: GateEvent): String = event match
    case GateEvent.SessionStart => "session-start"
    case GateEvent.PromptSubmit => "prompt-submit"
    case GateEvent.ToolCall     => "tool-call"
    case GateEvent.PostEdit     => "post-edit"
    case GateEvent.PostBash     => "post-bash"
    case GateEvent.Completion   => "completion"

  /**
   * The kernel's name-code abstraction: `1..6` for the recognised names
   * (position in `EventDispatch.recognisedNames`), `0` for every other
   * string — the mirror of `DispatchKernel.recognisedEventNames`.
   */
  def eventNameCode(name: String): BigInt =
    val idx: Int = EventDispatch.recognisedNames.indexOf(name)
    if idx >= 0 then BigInt(idx + 1) else BigInt(0)

  /**
   * The known harness alternates — spellings drawn from the
   * in-repository records only (the MUST-CONFIRM rule: no invented
   * names):
   *   - `user-prompt-submit` — the devin adapter's prompt-event name
   *     (`workflow-hygiene.bats` tests 5/6/9/10 invoke it);
   *   - `UserPromptSubmit`/`SessionStart`/`PostToolUse`/`PreToolUse`/`Stop`
   *     — the harness-side event names in the adapter JSON payloads and
   *     the executable envelope contract;
   *   - `before_agent_start`/`tool_call`/`tool_result` — the pi
   *     extension's own event names (`verified-scala3-gate.ts`).
   * None of these is a recognised gate name — every one exercises the
   * injection fallback.
   */
  private def genHarnessAlternateName: Gen[String] =
    Gen.element1(
      "user-prompt-submit",
      "UserPromptSubmit",
      "SessionStart",
      "PostToolUse",
      "PreToolUse",
      "Stop",
      "before_agent_start",
      "tool_call",
      "tool_result"
    )

  /**
   * Case and whitespace variants of a recognised name — the spec's
   * named edge cases. Every variant is still unrecognised (the match is
   * exact), so these exercise the fallback on near-misses.
   */
  private def genRecognisedVariant: Gen[String] =
    Gen
      .element1(
        "session-start",
        "prompt-submit",
        "tool-call",
        "post-edit",
        "post-bash",
        "completion"
      )
      .flatMap { (n: String) =>
        Gen.element1(
          n.toUpperCase(java.util.Locale.ROOT),
          n.capitalize,
          " " + n,
          n + " ",
          "  " + n + "\t"
        )
      }

  /**
   * Arbitrary names: `Gen.string` over a mixed alphabet including
   * hyphens and Unicode at lengths 0–40, plus very long names and the
   * empty string — the spec's edge cases.
   */
  private def genArbitraryName: Gen[String] =
    val mixedChar: Gen[Char] =
      Gen.choice1(
        Gen.alphaNum,
        Gen.element1('-', '_', ' ', '.', '/', 'é', '中', '☃')
      )
    Gen.frequency1(
      50 -> Gen.string(mixedChar, Range.linear(0, 40)),
      15 -> genRecognisedVariant,
      10 -> Gen.string(Gen.alphaNum, Range.linear(60, 160)),
      10 -> Gen.constant("")
    )

  /**
   * `genEventName` — constructive over a union of the three closed
   * alphabets: the six recognised names, the known harness alternates,
   * and arbitrary strings.
   */
  def genEventName: Gen[String] =
    Gen.frequency1(
      30 -> Gen.element1(
        "session-start",
        "prompt-submit",
        "tool-call",
        "post-edit",
        "post-bash",
        "completion"
      ),
      20 -> genHarnessAlternateName,
      50 -> genArbitraryName
    )

  /**
   * `genUnrecognisedName` — draws from `genEventName` and maps any
   * recognised draw onto a distinct unrecognised prefix, so the
   * generator produces unrecognised names by construction rather than
   * by discarding recognised ones.
   */
  def genUnrecognisedName: Gen[String] =
    genEventName.map { (name: String) =>
      if EventDispatch.recognisedNames.contains(name) then s"unrecognised-$name"
      else name
    }
