package org.adk4s.orchestration.memory

import hedgehog.Gen
import hedgehog.Range
import java.time.Instant
import org.adk4s.core.error.{AdkError, GenericError}
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.memory.{MemoryHit, TemporalScope}
import org.adk4s.orchestration.agent.RunResult
import org.llm4s.llmconnect.model.{Message, UserMessage}

/**
 * Hedgehog generators for spec:memory-orchestration-hook property tests.
 *
 * All generators are CONSTRUCTIVE (no `suchThat` filtering). Edge cases are
 * covered by the range bounds and `Gen.element1` choices.
 *
 * Reuses `org.adk4s.memory.Generators` generators where applicable via
 * direct composition (genContent, genInstant, genScope, genHit).
 */
object Generators:

  // ── Reused from org.adk4s.memory.Generators (same constructive definitions) ──

  /** Generates an alpha-numeric string of 1..20 chars. */
  val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 20))

  /** Generates an alpha-numeric string of 0..20 chars (includes empty). */
  val genContentOrEmpty: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(0, 20))

  /** Generates an `Instant` from a bounded epoch-millis range. */
  val genInstant: Gen[Instant] =
    Gen.long(Range.linear(0L, 4102444800000L)).map(Instant.ofEpochMilli)

  /** Generates a `TemporalScope` from a bounded instant. */
  val genScope: Gen[TemporalScope] =
    genInstant.map(TemporalScope.apply)

  /** Generates an optional `TemporalScope` (50% Some, 50% None). */
  val genOptScope: Gen[Option[TemporalScope]] =
    Gen.choice1(genScope.map(Some(_)), Gen.constant(Option.empty[TemporalScope]))

  // ── MemoryPolicy generators ──────────────────────────────────────────────

  /** Generates `recallK` from 0..5 (spec: Range.linear 0 5). */
  val genRecallK: Gen[Int] =
    Gen.int(Range.linear(0, 5))

  /** Generates `recallK` from 0..10 (wider range for the recall-count property). */
  val genRecallKWide: Gen[Int] =
    Gen.int(Range.linear(0, 10))

  /** Generates a boolean for write flags. */
  val genWriteFlag: Gen[Boolean] =
    Gen.boolean

  /** Generates a `MemoryPolicy` with constructive fields.
    * Uses the smart constructor (recallK >= 0 enforced by `require`).
    */
  val genPolicy: Gen[MemoryPolicy] =
    for
      recallK            <- genRecallK
      scope              <- genOptScope
      writeUserInput     <- genWriteFlag
      writeAssistantOutput <- genWriteFlag
    yield MemoryPolicy(
      recallK = recallK,
      scope = scope,
      writeUserInput = writeUserInput,
      writeAssistantOutput = writeAssistantOutput
    )

  // ── MemoryHit generators ─────────────────────────────────────────────────

  /** Generates a `MemoryHit` with constructive fields. */
  val genHit: Gen[MemoryHit] =
    for
      text  <- genContent
      score <- Gen.double(Range.linearFrac(0.0, 1.0))
    yield MemoryHit(text, score)

  /** Generates a list of 0..6 `MemoryHit`s (spec: Range.linear 0 6). */
  val genHitList: Gen[List[MemoryHit]] =
    Gen.list(genHit, Range.linear(0, 6))

  // ── Message generators ───────────────────────────────────────────────────

  /** Generates a `UserMessage` with constructive content. */
  val genUserMessage: Gen[UserMessage] =
    genContent.map(UserMessage.apply)

  /** Generates a non-empty list of `Message`s (1..8 messages, all `UserMessage`s).
    * spec: Range.linear 1 8
    */
  val genMessages: Gen[List[Message]] =
    Gen.list(genUserMessage, Range.linear(1, 8)).map((msgs: List[UserMessage]) => msgs.map((m: UserMessage) => (m: Message)))

  // ── RunResult generators ─────────────────────────────────────────────────

  /** Generates a checkpoint ID string. */
  val genCheckpointId: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(4, 12))

  /** Generates an `InterruptSignal` (simple, with constructive info). */
  val genSignal: Gen[InterruptSignal] =
    genContent.map(InterruptSignal.simple)

  /** Generates a `GenericError` with constructive message. */
  val genAdkError: Gen[AdkError] =
    genContent.map(GenericError.apply)

  /** Generates a `RunResult` from the three variants (Completed/Interrupted/Failed).
    * spec: genRunResult — one of Completed(genContent, genMessages),
    * Interrupted(genCheckpointId, genSignal), Failed(genAdkError)
    */
  val genRunResult: Gen[RunResult] =
    Gen.choice1(
      for
        output   <- genContent
        messages <- genMessages
      yield RunResult.Completed(output, messages),
      for
        checkpointId <- genCheckpointId
        signal       <- genSignal
      yield RunResult.Interrupted(checkpointId, signal),
      genAdkError.map(RunResult.Failed.apply)
    )

  /** Generates a `RunResult.Completed` specifically. */
  val genCompleted: Gen[RunResult.Completed] =
    for
      output   <- genContent
      messages <- genMessages
    yield RunResult.Completed(output, messages)

  /** Generates a `RunResult.Interrupted` specifically. */
  val genInterrupted: Gen[RunResult.Interrupted] =
    for
      checkpointId <- genCheckpointId
      signal       <- genSignal
    yield RunResult.Interrupted(checkpointId, signal)

  /** Generates a `RunResult.Failed` specifically. */
  val genFailed: Gen[RunResult.Failed] =
    genAdkError.map(RunResult.Failed.apply)
