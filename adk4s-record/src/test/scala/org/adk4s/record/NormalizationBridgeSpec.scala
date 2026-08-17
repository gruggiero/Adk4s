package org.adk4s.record

import hedgehog.*
import hedgehog.munit.HedgehogSuite
import org.adk4s.record.canonical.normalizeToolCallIds
import org.adk4s.verified.NormalizationModel
import org.adk4s.verified.NormalizationModel.*
import org.llm4s.llmconnect.model.AssistantMessage as LlmAssistantMessage
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.SystemMessage as LlmSystemMessage
import org.llm4s.llmconnect.model.ToolCall as LlmToolCall
import org.llm4s.llmconnect.model.ToolMessage as LlmToolMessage
import org.llm4s.llmconnect.model.UserMessage as LlmUserMessage

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge spec — binds the shipped `normalizeToolCallIds` to the
 * `NormalizationModel` PureScala model.
 *
 * The model and shipped code operate on different id spaces: the model uses
 * `BigInt` positional ids (0, 1, 2, ...) because Stainless does not support
 * string interpolation, while the shipped code uses `String` ids (`call_0`,
 * `call_1`, ...). The bridge therefore compares STRUCTURAL properties that
 * are invariant under the id-space abstraction, not exact equality.
 *
 * Properties verified:
 * 1. Idempotence: normalize(normalize(conv)) == normalize(conv) — both in
 *    the model and in the shipped code.
 * 2. Order preservation: after normalization, tool-call ids are positional
 *    (call_0, call_1, ...) in call order, and tool replies reference the
 *    matching positional id.
 *
 * spec: add-adk4s-record/recorder-verified-model — bridge properties
 */
class NormalizationBridgeSpec extends HedgehogSuite:

  property("bridge-normalization-idempotence — shipped and model agree"):
    for conv <- genConversationWithToolCalls.forAll
    yield
      // Shipped idempotence: normalize(normalize(conv)) == normalize(conv)
      val shippedOnce: Conversation  = normalizeToolCallIds(conv)
      val shippedTwice: Conversation = normalizeToolCallIds(shippedOnce)
      val shippedIdempotent: Boolean = conversationsEqual(shippedOnce, shippedTwice)

      // Model idempotence: normalize(normalize(model)) == normalize(model)
      val modelConv: stainless.collection.List[Msg]  = toModel(conv)
      val modelOnce: stainless.collection.List[Msg]  = NormalizationModel.normalize(modelConv)
      val modelTwice: stainless.collection.List[Msg] = NormalizationModel.normalize(modelOnce)
      val modelIdempotent: Boolean                   = modelTwice == modelOnce

      (shippedIdempotent ==== true)
        .and(modelIdempotent ==== true)

  property("bridge-normalization-order-preservation — pairing relation preserved"):
    for conv <- genConversationWithToolCalls.forAll
    yield
      val shipped: Conversation = normalizeToolCallIds(conv)
      // Extract all tool call ids from assistant messages, in order
      val toolCallIds: List[String] =
        shipped.messages.toList.flatMap {
          case a: LlmAssistantMessage => a.toolCalls.toList.map(_.id)
          case _                      => Nil
        }
      // Extract all tool reply ids, in order
      val replyIds: List[String] =
        shipped.messages.toList.flatMap {
          case t: LlmToolMessage => List(t.toolCallId)
          case _                 => Nil
        }
      // Tool call ids should be positional: call_0, call_1, ...
      val expectedCallIds: List[String] = toolCallIds.indices.map(i => s"call_$i").toList
      // Reply ids should match the corresponding tool call ids
      val allPositional: Boolean = toolCallIds.forall { id =>
        id.startsWith("call_") && id.stripPrefix("call_").toIntOption.isDefined
      }
      val callsMatchExpected: Boolean = toolCallIds == expectedCallIds
      val repliesMatchCalls: Boolean  = replyIds == toolCallIds.take(replyIds.length)

      // Model order preservation: after normalization, ids are 0, 1, 2, ...
      val modelConv: stainless.collection.List[Msg] = toModel(conv)
      val modelNorm: stainless.collection.List[Msg] = NormalizationModel.normalize(modelConv)
      val modelToolCallIds: List[BigInt] =
        stainlessListToScala(modelNorm).flatMap {
          case AssistantMsg(_, ids) => stainlessListToScala(ids)
          case _                    => Nil
        }
      val modelExpectedIds: List[BigInt] = modelToolCallIds.indices.map(BigInt(_)).toList
      val modelOrderPreserved: Boolean   = modelToolCallIds == modelExpectedIds

      (allPositional ==== true)
        .and(callsMatchExpected ==== true)
        .and(repliesMatchCalls ==== true)
        .and(modelOrderPreserved ==== true)

  // ── Conversion: llm4s Conversation → model List[Msg] ───────────────────
  // The model uses BigInt ids because Stainless does not support string
  // interpolation. We convert String ids to BigInt via a deterministic
  // hash (String.hashCode). This preserves equality: two equal strings
  // produce equal BigInts, and the normalization properties (idempotence,
  // order preservation) depend only on equality, not on the id values.

  /** Convert an llm4s Conversation to the model's List[Msg]. */
  def toModel(conv: Conversation): stainless.collection.List[Msg] =
    val scalaList: ScalaList[Msg] = conv.messages.toList.map(msgToModel)
    scalaToStainless(scalaList)

  /** Convert a single llm4s Message to the model's Msg. */
  def msgToModel(msg: Message): Msg =
    msg match
      case u: LlmUserMessage =>
        UserMsg(u.content)
      case s: LlmSystemMessage =>
        SystemMsg(s.content)
      case a: LlmAssistantMessage =>
        val toolCallIds: stainless.collection.List[BigInt] =
          scalaToStainless(a.toolCalls.toList.map(tc => BigInt(tc.id.hashCode)))
        AssistantMsg(a.contentOpt.getOrElse(""), toolCallIds)
      case t: LlmToolMessage =>
        ToolReplyMsg(t.content, BigInt(t.toolCallId.hashCode))

  /** Convert a Scala List to a Stainless List. */
  def scalaToStainless[A](xs: ScalaList[A]): stainless.collection.List[A] =
    xs match
      case Nil          => stainless.collection.Nil()
      case head :: tail => stainless.collection.Cons(head, scalaToStainless(tail))

  /** Convert a Stainless List to a Scala List. */
  def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => Nil
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  /** Check if two Conversations are equal (by messages). */
  def conversationsEqual(a: Conversation, b: Conversation): Boolean =
    a.messages.toList == b.messages.toList

  // ── Generators ─────────────────────────────────────────────────────────

  def genConversationWithToolCalls: Gen[Conversation] =
    for
      turnCount <- Gen.int(Range.linear(1, 3))
      turns     <- Gen.list(genTurn, Range.linear(turnCount, turnCount))
      messages = turns.flatten
    yield Conversation(messages)

  def genTurn: Gen[ScalaList[Message]] =
    for
      toolCount <- Gen.frequency1(40 -> Gen.constant(0), 40 -> Gen.constant(1), 20 -> Gen.constant(2))
      userMsg   <- genUserMessage
      toolCalls <- Gen.list(genToolCall, Range.linear(toolCount, toolCount))
      asstMsg = LlmAssistantMessage("", toolCalls)
      toolReplies <- Gen.list(genContent, Range.linear(toolCount, toolCount))
    yield
      if toolCount == 0 then ScalaList(userMsg, asstMsg)
      else
        val replies = toolCalls.zip(toolReplies).map { case (tc, content) =>
          LlmToolMessage(content, tc.id)
        }
        ScalaList(userMsg, asstMsg) ++ replies

  def genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(5, 50))

  def genUserMessage: Gen[Message] =
    for content <- genContent
    yield LlmUserMessage(content)

  def genToolCall: Gen[LlmToolCall] =
    for
      id   <- Gen.string(Gen.alphaNum, Range.linear(5, 20))
      name <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      args = ujson.Obj()
    yield LlmToolCall(id, name, args)
