package org.adk4s.record

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite

/**
 * Test oracle for the call-key spec — RL2/RL3/RL4/RL6/RL12 + normalization.
 *
 * These properties are derived from the SPEC (not from the implementation).
 * Before implementation, all properties are RED (NotImplementedError).
 * After implementation, all properties should be GREEN.
 *
 * spec: add-adk4s-record/call-key — Property: key-determinism
 * spec: add-adk4s-record/call-key — Property: key-sensitivity
 * spec: add-adk4s-record/call-key — Property: key-insensitivity
 * spec: add-adk4s-record/call-key — Property: rollout-separation
 * spec: add-adk4s-record/call-key — Property: normalization-idempotence
 * spec: add-adk4s-record/call-key — Property: normalization-order-preservation
 * spec: add-adk4s-record/call-key — Property: version-isolation
 */
class CallKeySpec extends HedgehogSuite:

  import CallKeySpec.*

  private val config: PropertyConfig => PropertyConfig = _.copy(testLimit = SuccessCount(500))

  // ── RL2: key-determinism ────────────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: key-determinism
  property("RL2 — key-determinism: same request yields same key", config):
    for req <- genModelRequest.forAll
        .cover(80, "has_tools", (r: ModelCallRequest) => r.tools.nonEmpty)
        .cover(50, "has_rollout", (r: ModelCallRequest) => r.rollout.isDefined)
    yield
      val k1 = CallKey.fromCanonical(CanonicalFormOps.from(req))
      val k2 = CallKey.fromCanonical(CanonicalFormOps.from(req))
      Result.assert(k1 == k2).log("RL2-key-determinism")

  // ── RL3: key-sensitivity ────────────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: key-sensitivity
  property("RL3 — key-sensitivity: output-affecting mutation changes key", config):
    for pair <- genRequestMutationPair.forAll
    yield
      val (base, mutation) = pair
      val mutated          = mutation.apply(base)
      val kBase            = CallKey.fromCanonical(CanonicalFormOps.from(base))
      val kMutated         = CallKey.fromCanonical(CanonicalFormOps.from(mutated))
      Result.assert(kBase != kMutated).log("RL3-key-sensitivity")

  // ── RL4: key-insensitivity ──────────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: key-insensitivity
  property("RL4 — key-insensitivity: non-affecting mutation preserves key", config):
    for pair <- genNonAffectingMutationPair.forAll
    yield
      val (base, mutation) = pair
      val mutated          = mutation.apply(base)
      val kBase            = CallKey.fromCanonical(CanonicalFormOps.from(base))
      val kMutated         = CallKey.fromCanonical(CanonicalFormOps.from(mutated))
      Result.assert(kBase == kMutated).log("RL4-key-insensitivity")

  // ── RL6: rollout-separation ─────────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: rollout-separation
  property("RL6 — rollout-separation: distinct rollouts → distinct keys", config):
    for pair <- genRolloutPair.forAll
    yield
      val (req, r1, r2) = pair
      val req1          = req.copy(rollout = r1)
      val req2          = req.copy(rollout = r2)
      val k1            = CallKey.fromCanonical(CanonicalFormOps.from(req1))
      val k2            = CallKey.fromCanonical(CanonicalFormOps.from(req2))
      if r1 == r2 then Result.assert(k1 == k2).log("RL6-rollout-separation-equal")
      else Result.assert(k1 != k2).log("RL6-rollout-separation-distinct")

  // ── Normalization idempotence ───────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: normalization-idempotence
  property("normalization-idempotence: normalize(normalize(conv)) == normalize(conv)", config):
    for conv <- genConversationWithToolCalls.forAll
        .cover(
          30,
          "zero_tool_calls",
          (c: org.llm4s.llmconnect.model.Conversation) =>
            c.messages.flatMap {
              case a: org.llm4s.llmconnect.model.AssistantMessage => a.toolCalls.toList
              case _                                              => Nil
            }.isEmpty
        )
        .cover(
          30,
          "single_tool_call",
          (c: org.llm4s.llmconnect.model.Conversation) =>
            c.messages.flatMap {
              case a: org.llm4s.llmconnect.model.AssistantMessage => a.toolCalls.toList
              case _                                              => Nil
            }.length == 1
        )
    yield
      import org.adk4s.record.canonical.normalizeToolCallIds
      val normalizedOnce  = normalizeToolCallIds(conv)
      val normalizedTwice = normalizeToolCallIds(normalizedOnce)
      Result.assert(normalizedOnce == normalizedTwice).log("normalization-idempotence")

  // ── Normalization order preservation ────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: normalization-order-preservation
  property("normalization-order-preservation: positional ids preserve pairing", config):
    for conv <- genConversationWithToolCalls.forAll
    yield
      import org.adk4s.record.canonical.normalizeToolCallIds
      val normalized = normalizeToolCallIds(conv)
      val pairings   = extractToolCallPairings(normalized)
      val allPaired = pairings.zipWithIndex.forall { case ((asstIdx, toolIdx), i) =>
        (normalized.messages(asstIdx), normalized.messages(toolIdx)) match
          case (
                asstMsg: org.llm4s.llmconnect.model.AssistantMessage,
                toolMsg: org.llm4s.llmconnect.model.ToolMessage
              ) =>
            asstMsg.toolCalls.headOption.map(_.id).contains(s"call_$i") && toolMsg.toolCallId == s"call_$i"
          case _ => false
      }
      Result.assert(allPaired).log("normalization-order-preservation")

  // ── RL12: version-isolation ─────────────────────────────────────────
  // spec: add-adk4s-record/call-key — Property: version-isolation
  property("RL12 — version-isolation: different keyVersion → different key", config):
    for req <- genModelRequest.forAll
    yield
      val form1 = CanonicalFormOps.from(req)
      val form2 = form1.copy(keyVersion = form1.keyVersion + 1)
      val k1    = CallKey.fromCanonical(form1)
      val k2    = CallKey.fromCanonical(form2)
      Result.assert(k1 != k2).log("RL12-version-isolation")

  // ── Scenario: Canonical form is inspectable ─────────────────────────
  // spec: add-adk4s-record/call-key — Scenario: Canonical form is inspectable
  property("canonical form is inspectable: toJson round-trips", config):
    for req <- genModelRequest.forAll
    yield
      val form = CanonicalFormOps.from(req)
      val json = smithy4s.json.Json.writeBlob(form)(using org.adk4s.record.canonical.CanonicalForm.schema).toUTF8String
      val parsed = CanonicalFormOps.fromJson(json)
      Result
        .assert(parsed.isRight)
        .log("canonical-form-inspectable")
        .and(parsed match
          case Right(f) => Result.assert(f == form).log("canonical-form-round-trip")
          case Left(_)  => Result.failure.log("canonical-form-parse-failed")
        )

object CallKeySpec:

  // ── Generators ──────────────────────────────────────────────────────

  import org.llm4s.llmconnect.model.{
    Conversation,
    Message,
    UserMessage,
    SystemMessage,
    AssistantMessage,
    ToolMessage,
    ToolCall,
    CompletionOptions
  }

  // ── genToolDef ──────────────────────────────────────────────────────
  def genToolDef: Gen[ToolDef] =
    for
      name <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      desc <- Gen.string(Gen.alphaNum, Range.linear(5, 30))
      schema = "{}"
    yield ToolDef(name, desc, schema)

  // ── genModelRequest ─────────────────────────────────────────────────
  def genModelRequest: Gen[ModelCallRequest] =
    for
      provider     <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      model        <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      systemPrompt <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
      temperature  <- Gen.double(Range.linearFrac(0.0, 2.0))
      hasMaxTokens <- Gen.frequency1(50 -> Gen.constant(true), 50 -> Gen.constant(false))
      maxTokensVal <- Gen.int(Range.linear(1, 4096))
      maxTokens = if hasMaxTokens then Some(maxTokensVal) else None
      topP       <- Gen.double(Range.linearFrac(0.0, 1.0))
      hasRollout <- Gen.frequency1(55 -> Gen.constant(true), 45 -> Gen.constant(false))
      rolloutVal <- genRolloutId
      rollout = if hasRollout then Some(rolloutVal) else None
      conv      <- genConversation
      hasTools  <- Gen.frequency1(82 -> Gen.constant(true), 18 -> Gen.constant(false))
      toolCount <- Gen.int(Range.linear(1, 3))
      tools <-
        if hasTools then Gen.list(genToolDef, Range.linear(toolCount, toolCount))
        else Gen.constant(List.empty[ToolDef])
      options = CompletionOptions(temperature, topP, maxTokens, 0.0, 0.0, Nil, None, None, None)
    yield ModelCallRequest(
      provider = provider,
      model = model,
      conversation = conv,
      tools = tools,
      systemPrompt = systemPrompt,
      options = options,
      rollout = rollout
    )

  // ── genRolloutId ────────────────────────────────────────────────────
  def genRolloutId: Gen[RolloutId] =
    // Gen.string with Range.linear(1, 20) guarantees non-empty, so refineEither always succeeds.
    // We use .toOption.getOrElse with a fallback that can never be reached.
    for s <- Gen.string(Gen.alphaNum, Range.linear(1, 20))
    yield RolloutId
      .refineEither(s)
      .toOption
      .getOrElse(
        RolloutId
          .refineEither("x")
          .toOption
          .getOrElse(
            sys.error("unreachable: non-empty string rejected by RolloutId")
          )
      )

  // ── genConversation ─────────────────────────────────────────────────
  def genConversation: Gen[Conversation] =
    for
      msgCount <- Gen.int(Range.linear(1, 5))
      messages <- Gen.list(genMessage, Range.linear(msgCount, msgCount))
    yield Conversation(messages)

  // ── genConversationWithToolCalls ────────────────────────────────────
  // Use 1-2 turns to ensure sufficient zero_tool_calls coverage
  // (with 1 turn and 50% zero, ~50% of conversations have 0 tool calls).
  def genConversationWithToolCalls: Gen[Conversation] =
    for
      turnCount <- Gen.int(Range.linear(1, 2))
      turns     <- Gen.list(genTurn, Range.linear(turnCount, turnCount))
      messages = turns.flatten
    yield Conversation(messages)

  // ── genMessage ──────────────────────────────────────────────────────
  def genMessage: Gen[Message] =
    Gen.choice1(
      genUserMessage,
      genSystemMessage,
      genAssistantMessageNoTools,
      genToolMessage
    )

  // ── genTurn: assistant with tool calls + tool replies ───────────────
  // Bias towards 0 or 1 tool calls for coverage. The spec's normalization
  // property model uses toolCalls.head, so we cap at 1 tool call per turn.
  def genTurn: Gen[List[Message]] =
    for
      toolCount <- Gen.frequency1(50 -> Gen.constant(0), 50 -> Gen.constant(1))
      userMsg   <- genUserMessage
      toolCalls <- Gen.list(genToolCall, Range.linear(toolCount, toolCount))
      asstMsg = AssistantMessage("", toolCalls)
      // Generate one tool reply per tool call, with matching ids, in order
      toolReplies <- Gen.list(genContent, Range.linear(toolCount, toolCount))
    yield
      if toolCount == 0 then List(userMsg, asstMsg)
      else
        val replies = toolCalls.zip(toolReplies).map { case (tc, content) =>
          ToolMessage(content, tc.id)
        }
        List(userMsg, asstMsg) ++ replies

  def genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(5, 50))

  def genUserMessage: Gen[Message] =
    for content <- genContent
    yield UserMessage(content)

  def genSystemMessage: Gen[Message] =
    for content <- genContent
    yield SystemMessage(content)

  def genAssistantMessageNoTools: Gen[Message] =
    for content <- genContent
    yield AssistantMessage(content)

  def genAssistantMessageWithTools(toolCount: Int): Gen[AssistantMessage] =
    for
      content   <- Gen.string(Gen.alphaNum, Range.linear(0, 30))
      toolCalls <- Gen.list(genToolCall, Range.linear(toolCount, toolCount))
    yield AssistantMessage(content, toolCalls)

  def genToolCall: Gen[ToolCall] =
    for
      id   <- Gen.string(Gen.alphaNum, Range.linear(5, 20))
      name <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      args = ujson.Obj()
    yield ToolCall(id, name, args)

  def genToolMessage: Gen[Message] =
    for
      content <- genContent
      callId  <- Gen.string(Gen.alphaNum, Range.linear(5, 20))
    yield ToolMessage(content, callId)

  // ── genRequestMutationPair ──────────────────────────────────────────
  def genRequestMutationPair: Gen[(ModelCallRequest, RequestMutation)] =
    for
      base     <- genModelRequest
      mutation <- genRequestMutation(base)
    yield (base, mutation)

  def genRequestMutation(base: ModelCallRequest): Gen[RequestMutation] =
    // Context-aware: RemoveTool and ChangeToolSchema pick names from the
    // base request's tools to ensure the mutation actually changes the request.
    val removeToolGen: Gen[RequestMutation] =
      base.tools match
        case first :: rest =>
          Gen.element(first, rest).map(t => RequestMutation.RemoveTool(t.name))
        case Nil =>
          Gen.constant(RequestMutation.AddTool("new-tool"))
    val changeSchemaGen: Gen[RequestMutation] =
      base.tools match
        case first :: rest =>
          Gen.element(first, rest).map(t => RequestMutation.ChangeToolSchema(t.name))
        case Nil =>
          Gen.constant(RequestMutation.AddTool("new-tool"))

    Gen.choice1(
      Gen.constant(RequestMutation.ChangeProvider("alt-provider")),
      Gen.constant(RequestMutation.ChangeModel("alt-model")),
      Gen.constant(RequestMutation.ReorderMessages),
      Gen.constant(RequestMutation.ChangeTemperature(0.5)),
      Gen.constant(RequestMutation.ChangeMaxTokens(Some(256))),
      Gen.constant(RequestMutation.ChangeTopP(0.3)),
      Gen.constant(RequestMutation.ChangeStopSequences(List("STOP"))),
      Gen.constant(RequestMutation.AddTool("new-tool")),
      removeToolGen,
      changeSchemaGen,
      Gen.constant(RequestMutation.ChangeSystemPrompt("alt-prompt")),
      genRolloutId.map(r => RequestMutation.ChangeRolloutId(Some(r)))
    )

  // ── genNonAffectingMutationPair ─────────────────────────────────────
  def genNonAffectingMutationPair: Gen[(ModelCallRequest, NonAffectingMutation)] =
    for
      base     <- genModelRequest
      mutation <- genNonAffectingMutation(base)
    yield (base, mutation)

  def genNonAffectingMutation(base: ModelCallRequest): Gen[NonAffectingMutation] =
    Gen.choice1(
      Gen.constant(NonAffectingMutation.ChangeProviderRequestId("req-123")),
      Gen.constant(NonAffectingMutation.ChangeLatency(42L)),
      Gen.constant(NonAffectingMutation.ChangeTokenUsage(100L)),
      Gen.constant(NonAffectingMutation.ChangeTimestamp(1234567890L)),
      // RegenerateToolCallIds: generate a map of old→new ids (empty if no tool calls)
      Gen.constant(
        NonAffectingMutation.RegenerateToolCallIds(
          Map.empty[String, String]
        )
      )
    )

  // ── genRolloutPair ──────────────────────────────────────────────────
  def genRolloutPair: Gen[(ModelCallRequest, Option[RolloutId], Option[RolloutId])] =
    for
      req   <- genModelRequest
      hasR1 <- Gen.frequency1(50 -> Gen.constant(true), 50 -> Gen.constant(false))
      r1Val <- genRolloutId
      r1 = if hasR1 then Some(r1Val) else None
      hasR2 <- Gen.frequency1(50 -> Gen.constant(true), 50 -> Gen.constant(false))
      r2Val <- genRolloutId
      r2 = if hasR2 then Some(r2Val) else None
    yield (req, r1, r2)

  // ── extractToolCallPairings ─────────────────────────────────────────
  /**
   * Extract (assistantIdx, toolReplyIdx) pairings from a conversation.
   * Each assistant with tool calls is paired with the corresponding
   * tool reply in order. Assumes 1 tool call per assistant message
   * (matching the spec's normalization property model).
   */
  def extractToolCallPairings(
    conv: Conversation
  ): List[(Int, Int)] =
    val messages = conv.messages
    val asstIndices = messages.zipWithIndex.collect {
      case (a: AssistantMessage, i) if a.toolCalls.nonEmpty => i
    }
    val toolIndices = messages.zipWithIndex.collect { case (_: ToolMessage, i) => i }
    asstIndices.zip(toolIndices).toList
