package org.adk4s.record

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.autoRefine
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.core.component.Embedder
import org.adk4s.core.component.EmbeddingResult
import org.adk4s.core.tools.ToolInput
import org.adk4s.core.tools.ToolMiddleware
import org.adk4s.core.tools.ToolOutput
import org.adk4s.harness.testkit.DeterministicChatModel
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.UserMessage

/**
 * Test oracle for the recorded-wrappers spec — 4 properties + 11 scenarios.
 *
 * These properties are derived from the SPEC (not from the implementation).
 * Before implementation, all properties are RED (NotImplementedError from
 * the typed contract's `???` bodies).
 * After implementation, all properties should be GREEN.
 *
 * spec: add-adk4s-record/recorded-wrappers — Property: transparency-noop
 * spec: add-adk4s-record/recorded-wrappers — Property: zero-call-hit
 * spec: add-adk4s-record/recorded-wrappers — Property: write-failure-containment
 * spec: add-adk4s-record/recorded-wrappers — Property: redaction-neutrality
 */
class RecordedWrappersSpec extends HedgehogSuite:

  import RecordedWrappersSpec.*

  private val config: PropertyConfig => PropertyConfig = _.copy(testLimit = SuccessCount(100))

  // ── RL0: transparency-noop ──────────────────────────────────────────
  // spec: add-adk4s-record/recorded-wrappers — Property: transparency-noop
  // RecordedChatModel(under, noop) is observationally equivalent to under
  // across generated conversations.
  property("RL0 — transparency-noop: noop recorder is equivalent to underlying", config):
    for
      seed <- Gen.long(Range.linear(1, 100000)).forAll
      conv <- genConversation.forAll
    yield
      val result: Boolean = (for
        under1 <- DeterministicChatModel(seed, Nil)
        under2 <- DeterministicChatModel(seed, Nil)
        noopRec  = Recorder.noop[IO]
        recorded = RecordedChatModel[IO](under2, noopRec)
        r1 <- under1.generate(conv)
        r2 <- recorded.generate(conv)
      yield r1 == r2).unsafeRunSync()
      Result.assert(result).log("RL0-transparency-noop")

  // ── RL5: zero-call-hit ──────────────────────────────────────────────
  // spec: add-adk4s-record/recorded-wrappers — Property: zero-call-hit
  // With a warm recorder and a call-counting ChatModel double, a hit
  // performs exactly zero underlying calls and returns the recorded
  // completion.
  property("RL5 — zero-call-hit: hit performs 0 underlying calls", config):
    for
      seed <- Gen.long(Range.linear(1, 100000)).forAll
      conv <- genConversation.forAll
    yield
      val (callCount, result): (Int, Boolean) = (for
        under   <- DeterministicChatModel(seed, Nil)
        warmRec <- Recorder.inMemory[IO](10)
        // Warm the cache: first call records
        warmRecorded = RecordedChatModel[IO](under, warmRec)
        _ <- warmRecorded.generate(conv)
        // Count underlying calls on second call via a counting wrapper
        callRef <- Ref.of[IO, Int](0)
        countingUnder = new ChatModel[IO]:
          def generate(conversation: Conversation): IO[Completion] =
            callRef.update(_ + 1) *> under.generate(conversation)
          def stream(conversation: Conversation): Stream[IO, StreamedChunk] = Stream.empty
          def streamContent(conversation: Conversation): Stream[IO, String] = Stream.empty
          def withConfig(c: ChatModelConfig): ChatModel[IO]                 = this
        hitRecorded = RecordedChatModel[IO](countingUnder, warmRec)
        r1    <- warmRecorded.generate(conv)
        r2    <- hitRecorded.generate(conv)
        count <- callRef.get
      yield (count, r1 == r2)).unsafeRunSync()
      Result
        .assert(callCount == 0)
        .log("RL5-zero-call-hit-count")
        .and(Result.assert(result).log("RL5-zero-call-hit-result"))

  // ── RL10: write-failure-containment ─────────────────────────────────
  // spec: add-adk4s-record/recorded-wrappers — Property: write-failure-containment
  // When the sink throws on record, the wrapped call's result still
  // reaches the caller.
  property("RL10 — write-failure-containment: sink failure doesn't fail call", config):
    for
      seed <- Gen.long(Range.linear(1, 100000)).forAll
      conv <- genConversation.forAll
    yield
      val failingRec: Recorder[IO] = new Recorder[IO]:
        def lookup(key: CallKey): IO[Option[CallRecord]] = IO.pure(None)
        def record(key: CallKey, outcome: CallRecord): IO[Unit] =
          IO.raiseError(new RuntimeException("sink write failed"))
        def nextSeq: IO[Long] = IO.pure(0L)
      val result: Option[Completion] = (for
        under <- DeterministicChatModel(seed, Nil)
        recorded = RecordedChatModel[IO](under, failingRec)
        r <- recorded.generate(conv).map(Some(_)).handleError(_ => None)
      yield r).unsafeRunSync()
      Result.assert(result.isDefined).log("RL10-write-failure-containment")

  // ── RL11: redaction-neutrality ──────────────────────────────────────
  // spec: add-adk4s-record/recorded-wrappers — Property: redaction-neutrality
  // Redaction changes the stored payload and does not change the key.
  property("RL11 — redaction-neutrality: redaction changes payload not key", config):
    for
      seed <- Gen.long(Range.linear(1, 100000)).forAll
      conv <- genConversation.forAll
    yield
      val redaction: Redaction = payload =>
        payload match
          case RecordPayload.ModelCase(m) =>
            RecordPayload.model(m.copy(content = "[REDACTED]"))
          case other => other
      val storedContent: Option[String] = (for
        under <- DeterministicChatModel(seed, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, redaction = Some(redaction))
        _ <- recorded.generate(conv)
        // Compute the key the same way the wrapper does
        key = CallKey.fromCanonical(
          CanonicalFormOps.from(
            ModelCallRequest(
              provider = "recorded",
              model = "recorded-model",
              conversation = conv,
              tools = Nil,
              systemPrompt = "",
              options = CompletionOptions()
            )
          )
        )
        stored <- rec.lookup(key)
      yield stored.flatMap(_.project.succeeded.map(_.payload)).flatMap {
        case RecordPayload.ModelCase(m) => Some(m.content)
        case _                          => None
      }).unsafeRunSync()
      Result.assert(storedContent.contains("[REDACTED]")).log("RL11-redaction-neutrality")

  // ── Scenarios ─────────────────────────────────────────────────────────
  // stryker4s 0.21.0 detects Hedgehog property failures but not munit test
  // failures, so these are written as properties with Gen.constant inputs.

  property("Noop-wrapped ChatModel is transparent", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Noop-wrapped ChatModel is transparent
    for _ <- Gen.constant(()).forAll
    yield
      val result: Boolean = (for
        under1 <- DeterministicChatModel(42L, Nil)
        under2 <- DeterministicChatModel(42L, Nil)
        noopRec  = Recorder.noop[IO]
        recorded = RecordedChatModel[IO](under2, noopRec)
        conv     = Conversation(List(UserMessage("hello")))
        r1 <- under1.generate(conv)
        r2 <- recorded.generate(conv)
      yield r1 == r2).unsafeRunSync()
      Result.assert(result).log("Noop-wrapped should be equivalent to underlying")

  property("Second identical call hits cache", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Second identical call hits cache
    for _ <- Gen.constant(()).forAll
    yield
      val (underlyingCalls, resultsMatch): (Int, Boolean) = (for
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec)
        conv     = Conversation(List(UserMessage("hello")))
        r1        <- recorded.generate(conv)
        reqCount1 <- under.capturedRequests.get.map(_.length)
        r2        <- recorded.generate(conv)
        reqCount2 <- under.capturedRequests.get.map(_.length)
      yield (reqCount2 - reqCount1, r1 == r2)).unsafeRunSync()
      Result
        .assert(underlyingCalls == 0)
        .log("Second call should hit cache (0 underlying calls)")
        .and(Result.assert(resultsMatch).log("Results should match"))

  property("First call misses and records", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: First call misses and records
    for _ <- Gen.constant(()).forAll
    yield
      val (underlyingCalls, completed): (Int, Boolean) = (for
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec)
        conv     = Conversation(List(UserMessage("hello")))
        _        <- recorded.generate(conv)
        reqCount <- under.capturedRequests.get.map(_.length)
      yield (reqCount, true)).unsafeRunSync()
      Result
        .assert(underlyingCalls == 1)
        .log("First call should miss and call underlying")
        .and(Result.assert(completed).log("First call should complete"))

  property("Recorder failure does not propagate", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Recorder failure does not propagate
    for _ <- Gen.constant(()).forAll
    yield
      val failingRec: Recorder[IO] = new Recorder[IO]:
        def lookup(key: CallKey): IO[Option[CallRecord]] = IO.pure(None)
        def record(key: CallKey, outcome: CallRecord): IO[Unit] =
          IO.raiseError(new RuntimeException("sink failed"))
        def nextSeq: IO[Long] = IO.pure(0L)
      val result: Option[Completion] = (for
        under <- DeterministicChatModel(42L, Nil)
        recorded = RecordedChatModel[IO](under, failingRec)
        conv     = Conversation(List(UserMessage("hello")))
        r <- recorded.generate(conv).map(Some(_)).handleError(_ => None)
      yield r).unsafeRunSync()
      Result.assert(result.isDefined).log("Call should succeed despite recorder failure")

  property("Temperature 1.0 without rollout warns", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Temperature 1.0 without rollout warns
    for _ <- Gen.constant(()).forAll
    yield
      val result: Boolean = (for
        warnings <- Ref.of[IO, List[String]](Nil)
        warnChannel = (msg: String) => warnings.update(_ :+ msg)
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, warningChannel = Some(warnChannel))
        conv     = Conversation(List(UserMessage("hello")))
        opts     = CompletionOptions(temperature = 1.0)
        _     <- recorded.generate(conv, opts)
        warns <- warnings.get
      yield warns.nonEmpty).unsafeRunSync()
      Result.assert(result).log("Should emit warning for nonzero temperature without rollout")

  property("Temperature 0.0 without rollout does not warn", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Temperature 0.0 without rollout does not warn
    for _ <- Gen.constant(()).forAll
    yield
      val result: Boolean = (for
        warnings <- Ref.of[IO, List[String]](Nil)
        warnChannel = (msg: String) => warnings.update(_ :+ msg)
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, warningChannel = Some(warnChannel))
        conv     = Conversation(List(UserMessage("hello")))
        opts     = CompletionOptions(temperature = 0.0)
        _     <- recorded.generate(conv, opts)
        warns <- warnings.get
      yield warns.isEmpty).unsafeRunSync()
      Result.assert(result).log("Should NOT emit warning for zero temperature without rollout")

  property("Temperature 1.0 with RolloutId does not warn", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Temperature 1.0 with RolloutId does not warn
    for _ <- Gen.constant(()).forAll
    yield
      val rollout: RolloutId =
        RolloutId
          .refineEither("run-1")
          .toOption
          .getOrElse(
            RolloutId
              .refineEither("x")
              .toOption
              .getOrElse(
                sys.error("unreachable: non-empty string rejected by RolloutId")
              )
          )
      val result: Boolean = (for
        warnings <- Ref.of[IO, List[String]](Nil)
        warnChannel = (msg: String) => warnings.update(_ :+ msg)
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, rollout = Some(rollout), warningChannel = Some(warnChannel))
        conv     = Conversation(List(UserMessage("hello")))
        opts     = CompletionOptions(temperature = 1.0)
        _     <- recorded.generate(conv, opts)
        warns <- warnings.get
      yield warns.isEmpty).unsafeRunSync()
      Result.assert(result).log("Should NOT emit warning for nonzero temperature WITH rollout")

  property("Redacted payload, unredacted key", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Redacted payload, unredacted key
    for _ <- Gen.constant(()).forAll
    yield
      val redaction: Redaction = payload =>
        payload match
          case RecordPayload.ModelCase(m) =>
            RecordPayload.model(m.copy(content = "[REDACTED]"))
          case other => other
      val storedContent: Option[String] = (for
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, redaction = Some(redaction))
        conv     = Conversation(List(UserMessage("hello")))
        _ <- recorded.generate(conv)
        key = CallKey.fromCanonical(
          CanonicalFormOps.from(
            ModelCallRequest(
              provider = "recorded",
              model = "recorded-model",
              conversation = conv,
              tools = Nil,
              systemPrompt = "",
              options = CompletionOptions()
            )
          )
        )
        stored <- rec.lookup(key)
      yield stored.flatMap(_.project.succeeded.map(_.payload)).flatMap {
        case RecordPayload.ModelCase(m) => Some(m.content)
        case _                          => None
      }).unsafeRunSync()
      Result.assert(storedContent.contains("[REDACTED]")).log("Stored payload should be redacted")

  property("Redaction does not affect hit rate", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Redaction does not affect hit rate
    for _ <- Gen.constant(()).forAll
    yield
      val redaction: Redaction = payload =>
        payload match
          case RecordPayload.ModelCase(m) =>
            RecordPayload.model(m.copy(content = "[REDACTED]"))
          case other => other
      val (underlyingCalls, _): (Int, Boolean) = (for
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, redaction = Some(redaction))
        conv     = Conversation(List(UserMessage("hello")))
        _         <- recorded.generate(conv) // miss + record (redacted)
        reqCount1 <- under.capturedRequests.get.map(_.length)
        _         <- recorded.generate(conv) // should hit (key unaffected by redaction)
        reqCount2 <- under.capturedRequests.get.map(_.length)
      yield (reqCount2 - reqCount1, true)).unsafeRunSync()
      Result.assert(underlyingCalls == 0).log("Second call should hit despite redaction")

  property("Recording composes with logging", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Recording composes with logging
    for _ <- Gen.constant(()).forAll
    yield
      val (result, logs): (ToolOutput, List[String]) = (for
        logRef <- Ref.of[IO, List[String]](Nil)
        loggingMw: ToolMiddleware = org.adk4s.core.tools.ToolMiddleware.logging(msg => logRef.update(_ :+ msg))
        rec <- Recorder.inMemory[IO](10)
        recordingMw: ToolMiddleware = RecordingToolMiddleware.recording(rec)
        composed: ToolMiddleware    = ToolMiddleware.compose(List(loggingMw, recordingMw))
        endpoint                    = Kleisli((in: ToolInput) => IO.pure(ToolOutput(in.name, "result", in.callId)))
        wrapped                     = composed(endpoint)
        out  <- wrapped.run(ToolInput("echo", "{}", "call-1"))
        logs <- logRef.get
      yield (out, logs)).unsafeRunSync()
      Result
        .assert(result.result == "result")
        .log("Tool result should be returned")
        .and(
          Result.assert(logs.nonEmpty).log("Logging middleware should have logged")
        )

  property("Embedding cache hit", config):
    // spec: add-adk4s-record/recorded-wrappers — Scenario: Embedding cache hit
    for _ <- Gen.constant(()).forAll
    yield
      val (callCount, _): (Int, Boolean) = (for
        callRef <- Ref.of[IO, Int](0)
        under <- IO.delay(new Embedder[IO] {
          def embed(text: String): IO[org.adk4s.core.component.Embedding] =
            callRef.update(_ + 1) *> IO.pure(Vector.fill(4)(0.0))
          def embedBatch(texts: List[String]): IO[EmbeddingResult] =
            callRef.update(_ + 1) *> IO.pure(EmbeddingResult(Nil, None))
          def dimension: IO[Int] = IO.pure(4)
        })
        rec <- Recorder.inMemory[IO](10)
        recorded = RecordedEmbedder[IO](under, rec)
        _     <- recorded.embed("hello") // miss + record
        _     <- callRef.set(0)          // reset counter between miss and hit
        _     <- recorded.embed("hello") // should hit
        count <- callRef.get
      yield (count, true)).unsafeRunSync()
      Result.assert(callCount == 0).log("Second embed should hit cache (0 underlying calls)")

  property("Recorded embedding payload has model name", config):
    // spec: add-adk4s-record/recorded-wrappers — Requirement: RecordedEmbedder wraps Embedder with recording and caching
    // Kills "recorded-embedder" → "" string mutants in RecordedEmbedder
    for _ <- Gen.constant(()).forAll
    yield
      val under: Embedder[IO] = new Embedder[IO] {
        def embed(text: String): IO[org.adk4s.core.component.Embedding] =
          IO.pure(Vector.fill(4)(0.0))
        def embedBatch(texts: List[String]): IO[EmbeddingResult] =
          IO.pure(EmbeddingResult(Nil, None))
        def dimension: IO[Int] = IO.pure(4)
      }
      val modelInPayload: String = (for
        rec <- Recorder.inMemory[IO](10)
        recorded = RecordedEmbedder[IO](under, rec)
        _ <- recorded.embed("hello")
        // Look up the recorded payload and check the model field
        key = CallKey.fromCanonical(CanonicalFormOps.fromEmbedding("hello", "recorded-embedder"))
        stored <- rec.lookup(key)
      yield stored
        .flatMap(_.project.succeeded.map(_.payload))
        .flatMap {
          case RecordPayload.EmbeddingCase(p) => Some(p.model)
          case _                              => None
        }
        .getOrElse("")).unsafeRunSync()
      Result
        .assert(modelInPayload == "recorded-embedder")
        .log(s"Payload model should be 'recorded-embedder', got: $modelInPayload")

  property("Cache hit preserves tool calls and token usage", config):
    // spec: add-adk4s-record/recorded-wrappers — Requirement: Hit returns recorded result with zero underlying calls
    // Kills mutants in completionToPayload/reconstructCompletion (toolCalls.isEmpty, usage condition)
    for _ <- Gen.constant(()).forAll
    yield
      val script: List[Completion] = List(
        Completion(
          id = "comp-1",
          created = 12345L,
          content = "response text",
          model = "test-model",
          message = AssistantMessage(Some("response text"), List(ToolCall("call-1", "echo", ujson.Obj()))),
          toolCalls = List(ToolCall("call-1", "echo", ujson.Obj())),
          usage = Some(TokenUsage(10, 20, 30))
        )
      )
      val (r1Id, r1Content, r1ToolCalls, r1Usage, r2Id, r2Content, r2ToolCalls, r2Usage): (
        String,
        String,
        Int,
        Int,
        String,
        String,
        Int,
        Int
      ) = (for
        under <- DeterministicChatModel(42L, script)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec)
        conv     = Conversation(List(UserMessage("hello")))
        r1 <- recorded.generate(conv)
        r2 <- recorded.generate(conv)
      yield (
        r1.id,
        r1.content,
        r1.toolCalls.length,
        r1.usage.map(_.totalTokens).getOrElse(0),
        r2.id,
        r2.content,
        r2.toolCalls.length,
        r2.usage.map(_.totalTokens).getOrElse(0)
      )).unsafeRunSync()
      Result
        .assert(r1ToolCalls == 1)
        .log("First call should have 1 tool call")
        .and(Result.assert(r1Usage == 30).log("First call should have totalTokens=30"))
        .and(Result.assert(r2ToolCalls == 1).log("Cache hit should preserve tool calls"))
        .and(Result.assert(r2Usage == 30).log("Cache hit should preserve token usage"))
        .and(Result.assert(r2Id == r1Id).log("Cache hit should preserve id"))
        .and(Result.assert(r2Content == r1Content).log("Cache hit should preserve content"))

  property("Warning message contains temperature and RolloutId mention", config):
    // spec: add-adk4s-record/recorded-wrappers — Requirement: Nonzero temperature without RolloutId emits a diagnostic warning
    // Kills string-literal mutants that empty any part of the warning message
    for _ <- Gen.constant(()).forAll
    yield
      val result: List[String] = (for
        warnings <- Ref.of[IO, List[String]](Nil)
        warnChannel = (msg: String) => warnings.update(_ :+ msg)
        under <- DeterministicChatModel(42L, Nil)
        rec   <- Recorder.inMemory[IO](10)
        recorded = RecordedChatModel[IO](under, rec, warningChannel = Some(warnChannel))
        conv     = Conversation(List(UserMessage("hello")))
        opts     = CompletionOptions(temperature = 1.0)
        _     <- recorded.generate(conv, opts)
        warns <- warnings.get
      yield warns).unsafeRunSync()
      val msg: String = result.headOption.getOrElse("")
      Result
        .assert(result.nonEmpty)
        .log("Should emit warning")
        .and(Result.assert(msg.contains("temperature")).log(s"Warning should mention temperature, got: $msg"))
        .and(Result.assert(msg.contains("RolloutId")).log(s"Warning should mention RolloutId, got: $msg"))
        .and(Result.assert(msg.contains("cached deterministically")).log(s"Warning should mention caching, got: $msg"))
        .and(Result.assert(msg.contains("deliberate resampling")).log(s"Warning should mention resampling, got: $msg"))
        .and(
          Result.assert(msg.contains("Identical requests")).log(s"Warning should mention identical requests, got: $msg")
        )

object RecordedWrappersSpec:

  // ── Generators ──────────────────────────────────────────────────────

  def genConversation: Gen[Conversation] =
    for
      msgCount <- Gen.int(Range.linear(1, 5))
      messages <- Gen.list(genMessage, Range.linear(msgCount, msgCount))
    yield Conversation(messages)

  def genMessage: Gen[Message] =
    Gen.choice1(
      genUserMessage,
      genSystemMessage,
      genAssistantMessageNoTools
    )

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
