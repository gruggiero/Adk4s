package org.adk4s.record

import munit.FunSuite
import org.adk4s.verified.NormalizationModel
import org.adk4s.verified.NormalizationModel.*
import org.adk4s.verified.RecorderCoherenceModel

/**
 * Type-level contract test for the Ring 6 PureScala models introduced by
 * spec 4 (recorder-verified-model).
 *
 * Verifies that the model signatures are accessible from the adk4s-record
 * test scope (via `verified % Test` dependency) and that the types have the
 * expected shape. After implementation (Step 3), the functions return real
 * values instead of throwing NotImplementedError.
 *
 * spec: add-adk4s-record/recorder-verified-model — typed contract
 */
class VerifiedModelTypeContract extends FunSuite:

  test("NormalizationModel.Msg has four variants"):
    val userMsg: Msg      = UserMsg("hello")
    val systemMsg: Msg    = SystemMsg("system")
    val assistantMsg: Msg = AssistantMsg("response", stainless.collection.List(BigInt(0)))
    val toolReplyMsg: Msg = ToolReplyMsg("result", BigInt(0))
    // Pattern match instead of isInstanceOf (WartRemover forbids isInstanceOf)
    userMsg match
      case _: UserMsg      => ()
      case _: SystemMsg    => fail("UserMsg matched as SystemMsg")
      case _: AssistantMsg => fail("UserMsg matched as AssistantMsg")
      case _: ToolReplyMsg => fail("UserMsg matched as ToolReplyMsg")
    systemMsg match
      case _: SystemMsg    => ()
      case _: UserMsg      => fail("SystemMsg matched as UserMsg")
      case _: AssistantMsg => fail("SystemMsg matched as AssistantMsg")
      case _: ToolReplyMsg => fail("SystemMsg matched as ToolReplyMsg")
    assistantMsg match
      case _: AssistantMsg => ()
      case _: UserMsg      => fail("AssistantMsg matched as UserMsg")
      case _: SystemMsg    => fail("AssistantMsg matched as SystemMsg")
      case _: ToolReplyMsg => fail("AssistantMsg matched as ToolReplyMsg")
    toolReplyMsg match
      case _: ToolReplyMsg => ()
      case _: UserMsg      => fail("ToolReplyMsg matched as UserMsg")
      case _: SystemMsg    => fail("ToolReplyMsg matched as SystemMsg")
      case _: AssistantMsg => fail("ToolReplyMsg matched as AssistantMsg")

  test("NormalizationModel.normalize returns a List[Msg]"):
    val conv: stainless.collection.List[Msg] =
      stainless.collection.Cons(UserMsg("hi"), stainless.collection.Nil())
    val result = NormalizationModel.normalize(conv)
    assert(result != null)

  test("NormalizationModel.pairings returns pairing triples"):
    val conv: stainless.collection.List[Msg] =
      stainless.collection.Cons(
        UserMsg("hi"),
        stainless.collection.Cons(
          AssistantMsg("resp", stainless.collection.List(BigInt(42))),
          stainless.collection.Cons(
            ToolReplyMsg("result", BigInt(42)),
            stainless.collection.Nil()
          )
        )
      )
    val result = NormalizationModel.pairings(conv)
    assert(result != null)
    // Should contain at least one pairing triple
    val scalaList: List[(BigInt, BigInt, BigInt)] = result match
      case stainless.collection.Nil() => Nil
      case stainless.collection.Cons(h, t) =>
        h :: (t match
          case stainless.collection.Nil()       => Nil
          case stainless.collection.Cons(h2, _) => h2 :: Nil // just check first 2
          case _                                => Nil
        )
    assert(scalaList.nonEmpty)

  test("NormalizationModel.idempotenceLemma returns a Boolean"):
    val conv: stainless.collection.List[Msg] =
      stainless.collection.Cons(UserMsg("hi"), stainless.collection.Nil())
    val result: Boolean = NormalizationModel.idempotenceLemma(conv)
    assert(result || !result) // compiles iff return type is Boolean

  test("NormalizationModel.orderPreservationLemma returns a Boolean"):
    val conv: stainless.collection.List[Msg] =
      stainless.collection.Cons(
        UserMsg("hi"),
        stainless.collection.Cons(
          AssistantMsg("resp", stainless.collection.List(BigInt(7))),
          stainless.collection.Cons(
            ToolReplyMsg("result", BigInt(7)),
            stainless.collection.Nil()
          )
        )
      )
    val result: Boolean = NormalizationModel.orderPreservationLemma(conv)
    assert(result || !result) // compiles iff return type is Boolean

  test("RecorderCoherenceModel.lookup signature compiles"):
    val lookupFn: (stainless.lang.Map[Int, Int], Int) => stainless.lang.Option[Int] =
      RecorderCoherenceModel.lookup
    assert(lookupFn != null)

  test("RecorderCoherenceModel.record signature compiles"):
    val recordFn: (stainless.lang.Map[Int, Int], Int, Int) => stainless.lang.Map[Int, Int] =
      RecorderCoherenceModel.record
    assert(recordFn != null)

  test("RecorderCoherenceModel.coherenceLemma signature compiles"):
    val lemmaFn: (stainless.lang.Map[Int, Int], Int, Int) => Boolean =
      RecorderCoherenceModel.coherenceLemma
    assert(lemmaFn != null)

  test("RecorderCoherenceModel.isolationLemma signature compiles"):
    val lemmaFn: (stainless.lang.Map[Int, Int], Int, Int, Int, Int) => Boolean =
      RecorderCoherenceModel.isolationLemma
    assert(lemmaFn != null)

  test("RecorderCoherenceModel.digest returns an Int"):
    val result: Int = RecorderCoherenceModel.digest(0)
    assert(result == 0) // identity function for runtime execution

  test("RecorderCoherenceModel.injectiveAssumption returns a Boolean"):
    val result: Boolean = RecorderCoherenceModel.injectiveAssumption(0, 1)
    assert(result || !result) // compiles iff return type is Boolean
