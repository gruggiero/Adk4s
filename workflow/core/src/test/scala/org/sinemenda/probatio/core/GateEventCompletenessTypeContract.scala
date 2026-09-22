package org.sinemenda.probatio.core

/**
 * Typed contract for the spec-8 gate core (gate-event-completeness, Step 1).
 *
 * Pins the public shapes the Step-2 oracle exercises — compiled under the
 * real probatio-core classpath, `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `GateEvent` gains `PostBash` as the SIXTH case — the exhaustiveness
 *    escalation forces every existing match to name it (Ring 0).
 *    `GateEvent.harnessName` is the total map to the harness's own event
 *    names (`PostToolUse` for both post-* events; the blocking events'
 *    names exist but are never emitted in an envelope — blocks use
 *    `decision:block`).
 *  - `ToolOutcome` is a sealed trait with `private[ToolOutcome]` case
 *    constructors — `Exit`/`Skip` remain pattern-matchable but are
 *    constructible ONLY through `classify`, so a fabricated exit code is
 *    unrepresentable (the compile-negative). `classify` is implemented at
 *    contract time (kernel-mirrored, like spec 7's `markerDecision`).
 *  - `HarnessPayload` carries the fields the tiers consume — tool name,
 *    tool input, tool response, `.cwd` (repo fallback), `stop_hook_active`
 *    — plus `interrupted`, DERIVED from the response by the `of` smart
 *    constructor (one fact, stated once). Constructor private.
 *  - `RefusalBudget` encodes the bound as a private `issued` count, not a
 *    resettable flag: `full`, `fromMarker`, `exhausted`, `issue` (None
 *    when spent — the second refusal is unrepresentable), and the
 *    kernel-mirrored `apply` fold (exactly one refusal, at the first
 *    blockable).
 *  - `GateDecisions` is the pure decision module: every input arrives as
 *    a value (no file I/O, no env reads — the compile-negative). Includes
 *    the ambient ring-shape table, the marker-name right-to-left parse
 *    (predecessor quirks included), and the R3-row polarity predicate
 *    with the git ancestry check injected.
 *  - `BlockReason` gains the three completion-tier refusals:
 *    `CompletionUnresolved`, `ChainStateUndetermined`, `Uncorroborated` —
 *    rendered with the predecessor's exact reason text.
 *  - `SpecPhase.fromStateFile` is the total state-file parse
 *    (unrecognised → Oracle, the predecessor `*)` arm); `asToken` is its
 *    inverse.
 *  - `SessionId` needs NO change — the spec-3 forward-referenced
 *    implementation already satisfies the injective-encoding contract
 *    (opaque type, `fromRaw`/`resolve` only).
 *  - `GateKernel` (verified mirror) models `refusalBudget` and
 *    `classifyOutcome` per the spec's Ring-6 contracts.
 *
 * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
 * spec: gate-event-completeness — Compile-Negative: A ToolOutcome.Exit constructed from a harness response classified as a refusal
 * spec: gate-event-completeness — Compile-Negative: A file read or environment-variable read inside probatio-core's gate decision functions
 * spec: gate-event-compatibility — Compile-Negative: An event classification that can fail
 * spec: gate-event-compatibility — Compile-Negative: An injection dispatch built without the supplied name
 */
final class GateEventCompletenessTypeContract extends ProbatioSuite:

  // ── GateEvent — six cases + the harness-name map ────────────────────
  val eventSig1: GateEvent = GateEvent.SessionStart
  val eventSig2: GateEvent = GateEvent.PromptSubmit
  val eventSig3: GateEvent = GateEvent.PostEdit
  val eventSig4: GateEvent = GateEvent.ToolCall
  val eventSig5: GateEvent = GateEvent.PostBash
  val eventSig6: GateEvent = GateEvent.Completion

  val harnessNameSig: GateEvent => String = GateEvent.harnessName

  // ── ToolOutcome — classification-only construction ──────────────────
  val classifySig: ujson.Value => ToolOutcome = ToolOutcome.classify

  // Exit/Skip are matchable (not constructible outside the companion):
  def outcomeMatch(o: ToolOutcome): Int = o match
    case ToolOutcome.Exit(code)   => code
    case ToolOutcome.Skip(reason) => reason.length

  // ── HarnessPayload — smart-constructed, interruption derived ────────
  val payloadOfSig: (
    String,
    ujson.Value,
    ujson.Value,
    String,
    ujson.Value
  ) => HarnessPayload = HarnessPayload.of

  def payloadAccessors(p: HarnessPayload): (String, ujson.Value, ujson.Value, String, ujson.Value, Boolean) =
    (p.toolName, p.toolInput, p.toolResponse, p.cwd, p.stopHookActive, p.interrupted)

  // ── RefusalBudget — bounded, non-resettable ─────────────────────────
  val budgetFullSig: RefusalBudget                  = RefusalBudget.full
  val budgetFromSig: Boolean => RefusalBudget       = RefusalBudget.fromMarker
  val budgetFoldSig: List[Boolean] => List[Boolean] = RefusalBudget.apply

  def budgetOps(b: RefusalBudget): (Boolean, Option[RefusalBudget]) =
    (b.exhausted, b.issue)

  // ── GateDecisions — pure decision functions ─────────────────────────
  val readOnlySig: Set[String]                                              = GateDecisions.readOnlyTools
  val isReadOnlySig: String => Boolean                                      = GateDecisions.isReadOnlyTool
  val isProdEditSig: String => Boolean                                      = GateDecisions.isProductionEdit
  val isSpecEditSig: String => Boolean                                      = GateDecisions.isSpecEdit
  val specOrderSig: String => List[String]                                  = GateDecisions.specOrder
  val owningSpecSig: (String, String, String) => Option[String]             = GateDecisions.owningSpec
  val advancePhaseSig: (SpecPhase, Boolean, Boolean) => SpecPhase           = GateDecisions.advancePhase
  val ambientSig: String => Option[GateDecisions.AmbientMatch]              = GateDecisions.ambientRingMatch
  val step0Sig: (String, String) => Option[GateDecisions.Step0Target]       = GateDecisions.step0Target
  val markerTripleSig: (String, String) => Option[(String, String, String)] = GateDecisions.markerTriple
  val unresolvedBlockSig: List[(String, List[String])] => String            = GateDecisions.unresolvedBlock

  val polaritySig: (
    List[ujson.Value],
    String,
    String,
    GateDecisions.Polarity,
    String => Boolean
  ) => Boolean = GateDecisions.hasRing3Row

  // ── BlockReason — the three completion refusals ─────────────────────
  val unresolvedSig: String => BlockReason     = BlockReason.CompletionUnresolved.apply
  val undeterminedSig: BlockReason             = BlockReason.ChainStateUndetermined
  val uncorroboratedSig: String => BlockReason = BlockReason.Uncorroborated.apply

  // ── SpecPhase — state-file tokens ───────────────────────────────────
  val fromStateFileSig: String => SpecPhase = SpecPhase.fromStateFile
  val asTokenSig: SpecPhase => String       = SpecPhase.asToken

  // ── SessionId — unchanged contract (already conformant) ─────────────
  val sessionResolveSig: (
    Option[String],
    Option[String],
    Option[String],
    Long
  ) => SessionId = SessionId.resolve

  def sessionOps(s: SessionId): (String, String) = (s.raw, s.encoded)

  // ── EventDispatch — spec: gate-event-compatibility (Step 1) ────────
  //
  // The total classification of a supplied `--event` name: `Tier` for a
  // recognised name, `Injection` carrying the supplied name for every
  // other. The classification is a total function INTO `EventDispatch`
  // — never an optional, never an either — so a failable parse (the
  // shape that produced the error status this spec removes) is
  // unconstructible.
  val dispatchClassifySig: String => EventDispatch =
    EventDispatch.classify

  val dispatchTierSig: GateEvent => EventDispatch.Tier =
    EventDispatch.Tier.apply

  val dispatchInjectionSig: String => EventDispatch.Injection =
    EventDispatch.Injection.apply

  val dispatchRecognisedSig: List[String] =
    EventDispatch.recognisedNames

  val dispatchProbesSig: EventDispatch => (Boolean, Boolean) =
    (d: EventDispatch) => (d.isTier, d.isInjection)

  test("six GateEvent cases are distinct"):
    val events: List[GateEvent] =
      List(eventSig1, eventSig2, eventSig3, eventSig4, eventSig5, eventSig6)
    assertEquals(events.distinct.length, 6)

  test("harnessName is total and names the harness's own names"):
    assertEquals(harnessNameSig(GateEvent.PostBash), "PostToolUse")
    assertEquals(harnessNameSig(GateEvent.PromptSubmit), "UserPromptSubmit")

  test("classify produces Exit only for genuine outcomes"):
    assertEquals(outcomeMatch(classifySig(ujson.Obj("a" -> 1))), 0)
    assertEquals(outcomeMatch(classifySig(ujson.Str("Error: Exit code 7"))), 7)
    assert(outcomeMatch(classifySig(ujson.Str("Error: refused"))) > 0)

  test("RefusalBudget is bounded — the second refusal is unrepresentable"):
    val spent: RefusalBudget = budgetFromSig(true)
    assertEquals(budgetOps(spent), (true, None))
    assertEquals(budgetOps(budgetFullSig)._2.isDefined, true)

  test("the refusal fold produces exactly one refusal, at the first blockable"):
    assertEquals(
      budgetFoldSig(List(false, true, true)),
      List(false, true, false)
    )

  test("classify is total and lands every name in exactly one arm"):
    EventDispatch.recognisedNames.foreach { (name: String) =>
      assert(dispatchClassifySig(name).isTier, s"recognised name '$name' must dispatch to a tier")
    }
    val injected: EventDispatch = dispatchClassifySig("no-such-event")
    assert(injected.isInjection)
    injected match
      case EventDispatch.Injection(supplied) => assertEquals(supplied, "no-such-event")
      case EventDispatch.Tier(_)             => fail("an unrecognised name must not reach a tier")

  test("the recognised name set is closed and injective"):
    assertEquals(dispatchRecognisedSig.length, 6)
    assertEquals(dispatchRecognisedSig.distinct.length, 6)
    val tiers: List[GateEvent] =
      dispatchRecognisedSig.map(dispatchClassifySig).collect { case EventDispatch.Tier(e) => e }
    assertEquals(tiers.distinct.length, 6, "no two recognised names may reach the same tier")

  // spec: gate-event-compatibility — Compile-Negative: An event classification that can fail
  // A failable parse is what produces the error status this spec
  // removes — `classify` returns `EventDispatch`, not an optional or
  // an either, so assigning it to `Option[EventDispatch]` must not
  // compile.
  test("a failable event classification does not compile"):
    val err: String = compileErrors(
      """val e: Option[EventDispatch] = EventDispatch.classify("x")"""
    )
    assert(
      err.nonEmpty,
      "classify must return EventDispatch, not Option[EventDispatch] — the parse cannot fail"
    )

  // spec: gate-event-compatibility — Compile-Negative: An injection dispatch built without the supplied name
  // A fallback that discards what it fell back from is the silent
  // drift this spec keeps visible — `Injection` requires the supplied
  // name.
  test("an injection dispatch without the supplied name does not compile"):
    val err: String = compileErrors(
      """EventDispatch.Injection()"""
    )
    assert(
      err.nonEmpty,
      "EventDispatch.Injection must require the supplied name — the fallback names what it fell back from"
    )

end GateEventCompletenessTypeContract
