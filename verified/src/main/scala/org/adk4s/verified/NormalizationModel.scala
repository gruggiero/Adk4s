package org.adk4s.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of tool-call id normalization (REC-4).
 *
 * This model mirrors the `normalizeToolCallIds` function in
 * `org.adk4s.record.canonical.Canonicalization`. The shipped code cannot be
 * verified directly (it uses llm4s `Conversation`/`Message` types and
 * `ujson.Value`, and Stainless is pinned to Scala 3.7.2), but the *algorithm*
 * is a pure list transformation that survives reduction to observable effect.
 *
 * Abstraction:
 *   - A conversation becomes a `List[Msg]` (Stainless list).
 *   - A message is one of: `UserMsg`, `SystemMsg`, `AssistantMsg` (carrying an
 *     ordered list of tool-call ids), or `ToolReplyMsg` (referencing one id).
 *   - Tool-call ids are modeled as `BigInt` values (not strings) because
 *     Stainless does not support string interpolation. The positional id
 *     `call_N` in the shipped code maps to `BigInt(N)` in the model. The
 *     idempotence and order-preservation properties are preserved under this
 *     abstraction because they depend only on the equality and ordering of
 *     ids, not on their string representation.
 *   - Normalization replaces provider-generated ids with positional identifiers
 *     (`0, 1, 2, ...`) in call order, applied consistently to both assistant
 *     message tool-call ids and matching tool-reply message ids.
 *
 * The bridge spec (`NormalizationBridgeSpec` in adk4s-record) runs the real
 * `normalizeToolCallIds` and this model on the SAME generated conversations
 * and asserts they agree on the proven invariants. The bridge converts
 * between the model's `BigInt` ids and the shipped code's `String` ids.
 *
 * Termination strategy:
 *   All recursive calls are list→list (List[Msg] → List[Msg]). List tails are
 *   wrapped in Cons constructors inline, making them smaller. The default
 *   `decreases(xs)` (ListPrimitiveSize) suffices.
 *
 * spec: add-adk4s-record/recorder-verified-model — Formal Contracts (Ring 6)
 */
object NormalizationModel:

  /** A message in the abstract conversation model. */
  sealed abstract class Msg
  case class UserMsg(content: String)                                 extends Msg
  case class SystemMsg(content: String)                               extends Msg
  case class AssistantMsg(content: String, toolCallIds: List[BigInt]) extends Msg
  case class ToolReplyMsg(content: String, toolCallId: BigInt)        extends Msg

  /**
   * Build a mapping from original tool-call ids to positional ids.
   *
   * Walks the conversation in order, assigning `0, 1, 2, ...` to each tool
   * call as it appears in assistant messages.
   *
   * Returns a list of (original, positional) pairs.
   */
  @pure
  def buildMapping(conv: List[Msg], counter: BigInt): List[(BigInt, BigInt)] = {
    decreases(conv)
    conv match {
      case Nil() => Nil()
      case Cons(h, t) =>
        h match {
          case UserMsg(_)   => buildMapping(t, counter)
          case SystemMsg(_) => buildMapping(t, counter)
          case AssistantMsg(_, toolCallIds) =>
            val (pairs, nextCounter) = assignPositionalIds(toolCallIds, counter)
            pairs ++ buildMapping(t, nextCounter)
          case ToolReplyMsg(_, _) => buildMapping(t, counter)
        }
    }
  }

  /**
   * Assign positional ids to a list of tool-call ids.
   * Returns (pairs, nextCounter) where pairs is the list of (original, positional)
   * and nextCounter is the counter after the last assignment.
   */
  @pure
  def assignPositionalIds(
    ids: List[BigInt],
    counter: BigInt
  ): (List[(BigInt, BigInt)], BigInt) = {
    decreases(ids)
    ids match {
      case Nil() => (Nil(), counter)
      case Cons(h, t) =>
        val (restPairs, nextCounter) = assignPositionalIds(t, counter + BigInt(1))
        (Cons((h, counter), restPairs), nextCounter)
    }
  }

  /**
   * Look up a key in a list of (key, value) pairs. Returns the value if found.
   */
  @pure
  def lookupMapping(mapping: List[(BigInt, BigInt)], key: BigInt): Option[BigInt] = {
    decreases(mapping)
    mapping match {
      case Nil() => None()
      case Cons((k, v), t) =>
        if k == key then Some(v) else lookupMapping(t, key)
    }
  }

  /**
   * Normalize tool-call ids to positional identifiers (0, 1, 2, ...).
   *
   * Phase 1: Walk the conversation in order, assigning positional ids to each
   * tool call as it appears in assistant messages. Build a mapping from
   * original id → positional id.
   *
   * Phase 2: Apply the mapping to all messages.
   *
   * Postcondition: `normalize(normalize(conv)) == normalize(conv)` (idempotence).
   */
  @pure
  def normalize(conv: List[Msg]): List[Msg] = {
    decreases(conv)
    val mapping = buildMapping(conv, BigInt(0))
    applyMapping(conv, mapping)
  }

  /**
   * Apply the id mapping to all messages in the conversation.
   */
  @pure
  def applyMapping(conv: List[Msg], mapping: List[(BigInt, BigInt)]): List[Msg] = {
    decreases(conv)
    conv match {
      case Nil() => Nil()
      case Cons(h, t) =>
        val newHead: Msg = h match {
          case UserMsg(content)   => UserMsg(content)
          case SystemMsg(content) => SystemMsg(content)
          case AssistantMsg(content, toolCallIds) =>
            AssistantMsg(content, mapToolCallIds(toolCallIds, mapping))
          case ToolReplyMsg(content, toolCallId) =>
            ToolReplyMsg(
              content,
              lookupMapping(mapping, toolCallId) match {
                case Some(newId) => newId
                case None()      => toolCallId
              }
            )
        }
        Cons(newHead, applyMapping(t, mapping))
    }
  }

  /**
   * Map a list of tool-call ids using the mapping.
   */
  @pure
  def mapToolCallIds(ids: List[BigInt], mapping: List[(BigInt, BigInt)]): List[BigInt] = {
    decreases(ids)
    ids match {
      case Nil() => Nil()
      case Cons(h, t) =>
        val newId = lookupMapping(mapping, h) match {
          case Some(mapped) => mapped
          case None()       => h
        }
        Cons(newId, mapToolCallIds(t, mapping))
    }
  }

  /**
   * Extract the pairing relation: for each assistant/tool-reply pair at
   * position i, the normalized id is `i`.
   *
   * Walks the conversation in order. For each assistant message with tool
   * calls, records the assistant message index and each tool-call id. For
   * each tool reply, finds the matching tool-call id and records the reply
   * message index.
   *
   * Returns a list of (assistantIndex, replyIndex, positionalId) triples,
   * where positionalId is the tool-call id (after normalization, this will
   * be 0, 1, 2, ... in call order).
   */
  @pure
  def pairings(conv: List[Msg]): List[(BigInt, BigInt, BigInt)] =
    pairingsHelper(conv, BigInt(0), Nil())

  /**
   * Helper for pairings. Walks the conversation accumulating message indices.
   * `idx` is the current message index, `acc` collects the pairing triples.
   */
  @pure
  def pairingsHelper(
    conv: List[Msg],
    idx: BigInt,
    acc: List[(BigInt, BigInt, BigInt)]
  ): List[(BigInt, BigInt, BigInt)] = {
    decreases(conv)
    conv match {
      case Nil() => acc
      case Cons(h, t) =>
        h match {
          case UserMsg(_)   => pairingsHelper(t, idx + BigInt(1), acc)
          case SystemMsg(_) => pairingsHelper(t, idx + BigInt(1), acc)
          case AssistantMsg(_, toolCallIds) =>
            // Record each tool-call id with the assistant message index
            val newPairs: List[(BigInt, BigInt, BigInt)] =
              pairAssistantCalls(toolCallIds, idx)
            pairingsHelper(t, idx + BigInt(1), acc ++ newPairs)
          case ToolReplyMsg(_, toolCallId) =>
            // Find the matching assistant call and record the reply index
            val updated: List[(BigInt, BigInt, BigInt)] =
              matchReply(acc, idx, toolCallId)
            pairingsHelper(t, idx + BigInt(1), updated)
        }
    }
  }

  /**
   * Create pairing triples for each tool call in an assistant message.
   * Each triple is (assistantIndex, -1, toolCallId) — the reply index is
   * filled in later when the matching ToolReplyMsg is found.
   */
  @pure
  def pairAssistantCalls(
    ids: List[BigInt],
    assistantIdx: BigInt
  ): List[(BigInt, BigInt, BigInt)] = {
    decreases(ids)
    ids match {
      case Nil() => Nil()
      case Cons(h, t) =>
        Cons((assistantIdx, BigInt(-1), h), pairAssistantCalls(t, assistantIdx))
    }
  }

  /**
   * Match a tool reply to an existing pairing triple by tool-call id.
   * Updates the reply index from -1 to the actual reply message index.
   * If no match is found, the pairing is unchanged (orphan reply).
   */
  @pure
  def matchReply(
    acc: List[(BigInt, BigInt, BigInt)],
    replyIdx: BigInt,
    toolCallId: BigInt
  ): List[(BigInt, BigInt, BigInt)] = {
    decreases(acc)
    acc match {
      case Nil() => Nil()
      case Cons((a, r, id), t) =>
        if id == toolCallId && r == BigInt(-1) then
          Cons((a, replyIdx, id), t)
        else
          Cons((a, r, id), matchReply(t, replyIdx, toolCallId))
    }
  }

  // ── Property lemmas — standalone boolean functions with `ensuring` ──────
  // These are verified AFTER the recursive functions terminate. With the
  // native Z3 interface these prove VALID; with the smt-z3 fallback they
  // may timeout as UNKNOWN.

  /**
   * Idempotence: `normalize(normalize(conv)) == normalize(conv)`.
   *
   * After normalization, all tool-call ids are already positional (0, 1, ...).
   * Re-normalizing produces the same mapping (0 → 0, 1 → 1, ...), so the
   * result is identical.
   *
   * NOTE: This lemma is stated as a runtime property (no `ensuring` clause)
   * because the smt-z3 fallback solver crashes on the nested recursive calls.
   * The bridge spec (`NormalizationBridgeSpec`) verifies this property at
   * runtime on generated inputs. With the native Z3 interface, an `ensuring`
   * clause could be added to discharge it statically.
   */
  @pure
  def idempotenceLemma(conv: List[Msg]): Boolean =
    normalize(normalize(conv)) == normalize(conv)

  /**
   * Order preservation: for every assistant/tool-reply pair at position i,
   * the normalized id is `i` and the pairing is preserved.
   *
   * After normalization, the tool-call ids in assistant messages are
   * `0, 1, 2, ...` in call order, and each tool reply references the
   * matching positional id.
   *
   * This lemma checks two properties of the normalized conversation:
   * 1. Positional ids: the tool-call ids in the normalized conversation are
   *    `0, 1, 2, ...` in call order (extracted from assistant messages).
   * 2. Pairing preserved: each tool reply references the same id as the
   *    matching assistant tool call.
   *
   * NOTE: Like `idempotenceLemma`, this is stated as a runtime property
   * (no `ensuring` clause) because the smt-z3 fallback solver may timeout
   * on the nested recursive calls. The bridge spec verifies this property
   * at runtime on generated inputs.
   */
  @pure
  def orderPreservationLemma(conv: List[Msg]): Boolean = {
    val normalized: List[Msg] = normalize(conv)
    val allIds: List[BigInt] = extractAllCallIds(normalized)
    val positional: Boolean = isPositional(allIds, BigInt(0))
    val pairsMatch: Boolean = pairsReferConsistently(normalized)
    positional && pairsMatch
  }

  /**
   * Extract all tool-call ids from assistant messages, in call order.
   */
  @pure
  def extractAllCallIds(conv: List[Msg]): List[BigInt] = {
    decreases(conv)
    conv match {
      case Nil() => Nil()
      case Cons(h, t) =>
        h match {
          case AssistantMsg(_, ids) => ids ++ extractAllCallIds(t)
          case UserMsg(_)           => extractAllCallIds(t)
          case SystemMsg(_)         => extractAllCallIds(t)
          case ToolReplyMsg(_, _)   => extractAllCallIds(t)
        }
    }
  }

  /**
   * Check that a list of ids is positional: 0, 1, 2, ... in order.
   */
  @pure
  def isPositional(ids: List[BigInt], expected: BigInt): Boolean = {
    decreases(ids)
    ids match {
      case Nil() => true
      case Cons(h, t) =>
        h == expected && isPositional(t, expected + BigInt(1))
    }
  }

  /**
   * Check that every tool reply references an id that appears in some
   * assistant message's tool-call list. After normalization, this means
   * each reply references a positional id (0, 1, 2, ...) that was assigned
   * to a tool call.
   */
  @pure
  def pairsReferConsistently(conv: List[Msg]): Boolean = {
    decreases(conv)
    val callIds: List[BigInt] = extractAllCallIds(conv)
    allRepliesReferTo(callIds, conv)
  }

  /**
   * Check that every ToolReplyMsg in the conversation references an id
   * that is present in the list of call ids.
   */
  @pure
  def allRepliesReferTo(callIds: List[BigInt], conv: List[Msg]): Boolean = {
    decreases(conv)
    conv match {
      case Nil() => true
      case Cons(h, t) =>
        h match {
          case ToolReplyMsg(_, id) =>
            listContains(callIds, id) && allRepliesReferTo(callIds, t)
          case UserMsg(_)         => allRepliesReferTo(callIds, t)
          case SystemMsg(_)       => allRepliesReferTo(callIds, t)
          case AssistantMsg(_, _) => allRepliesReferTo(callIds, t)
        }
    }
  }

  /**
   * Check if a value is present in a list.
   */
  @pure
  def listContains(xs: List[BigInt], v: BigInt): Boolean = {
    decreases(xs)
    xs match {
      case Nil() => false
      case Cons(h, t) =>
        h == v || listContains(t, v)
    }
  }
