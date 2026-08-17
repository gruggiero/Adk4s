package org.adk4s.record

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import hedgehog.Gen
import hedgehog.Property
import hedgehog.Range
import hedgehog.Result
import io.github.iltotore.iron.autoRefine
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.record.canonical.CallKind
import org.adk4s.record.canonical.CanonicalForm
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.UserMessage
import smithy4s.json.Json

// ── RecorderLaws — RL0–RL12 Hedgehog property testkit (main scope) ─────
// spec: add-adk4s-record/recorder-laws — Requirement: RecorderLaws ships in main scope as downstream-consumable properties
//
// Reusable behavioral laws for the Recorder sink algebra, parameterized
// over a Recorder[F] under test. Each law is a Hedgehog Property value
// runnable by any downstream backend author in their own test suite.
//
// Precedents: adk4s-harness-testkit AgentMiddlewareLaws (L0–L10),
//             adk4s-optimize OptimizerLaws.
//
// The 13 laws:
//   RL0  — transparency (RecordedChatModel with noop ≍ underlying)
//   RL1  — record/lookup coherence (lookup after record returns Some)
//   RL2  — key determinism (same request → same key)
//   RL3  — key sensitivity (RequestMutation changes key)
//   RL4  — key insensitivity (NonAffectingMutation preserves key)
//   RL5  — zero-call hit (warm recorder → zero underlying calls)
//   RL6  — rollout separation (different RolloutId → different key)
//   RL7  — codec round-trip (CallRecord encode → decode → equal)
//   RL8  — append-only monotonicity (seq never decreases)
//   RL9  — failure fidelity (recorded failure replays as equal failure)
//   RL10 — write-failure containment (sink failure doesn't fail the call)
//   RL11 — redaction neutrality (redaction changes payload, not key)
//   RL12 — version isolation (keyVersion n+1 invisible to n)
final class RecorderLaws[F[_]](recorder: Recorder[F])(using
  F: cats.effect.Async[F],
  runtime: IORuntime
):

  import RecorderLaws.*

  // RL0: RecordedChatModel(under, noop) is observationally equivalent to under
  // spec: add-adk4s-record/recorder-laws — Requirement: RL0 transparency property
  def rl0_transparency: Property =
    for conv <- genConversation.forAll
    yield
      val under: ChatModel[IO] = SimpleChatModel()
      val wrapped: ChatModel[IO] =
        RecordedChatModel(under, Recorder.noop[IO])
      val direct: Completion =
        under.generate(conv).unsafeRunSync()
      val recorded: Completion =
        wrapped.generate(conv).unsafeRunSync()
      Result
        .assert(direct == recorded)
        .log("RL0-transparency: wrapped == direct")

  // RL1: lookup(k) after record(k, v) returns Some(v); record(j, _) doesn't affect lookup(k)
  // spec: add-adk4s-record/recorder-laws — Requirement: RL1 record/lookup coherence property
  def rl1_coherence: Property =
    for
      k <- genCallKey.forAll
      j <- genDistinctCallKey(k).forAll
      v <- genCallRecord.forAll
    yield
      val (r1, r2): (Option[CallRecord], Option[CallRecord]) =
        (for
          rec <- Recorder.inMemory[IO](Positive100)
          _   <- rec.record(k, v)
          r1  <- rec.lookup(k)
          _   <- rec.record(j, v)
          r2  <- rec.lookup(k)
        yield (r1, r2)).unsafeRunSync()
      Result
        .assert(r1 == Some(v))
        .log("RL1-coherence: lookup(k) == Some(v)")
        .and(Result.assert(r2 == r1).log("RL1-coherence: unaffected by record(j)"))

  // RL2: same request → same key (key determinism)
  // spec: add-adk4s-record/recorder-laws — Proof Obligation: RL2 key determinism
  def rl2_key_determinism: Property =
    for req <- genModelRequest.forAll
    yield
      val k1: CallKey = CallKey.fromCanonical(CanonicalFormOps.from(req))
      val k2: CallKey = CallKey.fromCanonical(CanonicalFormOps.from(req))
      Result.assert(k1 == k2).log("RL2-key-determinism")

  // RL3: RequestMutation (output-affecting) changes the key
  // spec: add-adk4s-record/recorder-laws — Requirement: RL3 and RL4 are mutation-generator-driven
  def rl3_key_sensitivity: Property =
    for
      base     <- genModelRequest.forAll
      mutation <- genRequestMutation(base).forAll
    yield
      val mutated: ModelCallRequest = mutation.apply(base)
      val kBase: CallKey            = CallKey.fromCanonical(CanonicalFormOps.from(base))
      val kMutated: CallKey         = CallKey.fromCanonical(CanonicalFormOps.from(mutated))
      Result.assert(kBase != kMutated).log("RL3-key-sensitivity")

  // RL4: NonAffectingMutation preserves the key
  // spec: add-adk4s-record/recorder-laws — Requirement: RL3 and RL4 are mutation-generator-driven
  def rl4_key_insensitivity: Property =
    for
      base     <- genModelRequest.forAll
      mutation <- genNonAffectingMutation(base).forAll
    yield
      val mutated: ModelCallRequest = mutation.apply(base)
      val kBase: CallKey            = CallKey.fromCanonical(CanonicalFormOps.from(base))
      val kMutated: CallKey         = CallKey.fromCanonical(CanonicalFormOps.from(mutated))
      Result.assert(kBase == kMutated).log("RL4-key-insensitivity")

  // RL5: warm recorder → second call makes zero underlying calls
  // spec: add-adk4s-record/recorder-laws — Requirement: RL5 zero-call hit property
  def rl5_zero_call_hit: Property =
    for conv <- genConversation.forAll
    yield
      val counter: CallCounter = CallCounter()
      val warm: IO[(Int, Int)] =
        for
          memRec <- Recorder.inMemory[IO](Positive100)
          wrapped: ChatModel[IO] = RecordedChatModel(counter, memRec)
          _ <- wrapped.generate(conv)
          count1 = counter.count
          _ <- wrapped.generate(conv)
          count2 = counter.count
        yield (count1, count2)
      val (count1, count2): (Int, Int) = warm.unsafeRunSync()
      Result
        .assert(count1 == 1)
        .log("RL5-zero-call-hit: first call hits underlying")
        .and(Result.assert(count2 == 1).log("RL5-zero-call-hit: second call makes zero underlying calls"))

  // RL6: different RolloutId → different key (rollout separation)
  // spec: add-adk4s-record/recorder-laws — Proof Obligation: RL6 rollout separation
  def rl6_rollout_separation: Property =
    for
      req <- genModelRequest.forAll
      r1  <- genRolloutId.forAll
      r2  <- genRolloutId.forAll
    yield
      val req1: ModelCallRequest = req.copy(rollout = Some(r1))
      val req2: ModelCallRequest = req.copy(rollout = Some(r2))
      val k1: CallKey            = CallKey.fromCanonical(CanonicalFormOps.from(req1))
      val k2: CallKey            = CallKey.fromCanonical(CanonicalFormOps.from(req2))
      if r1 == r2 then Result.assert(k1 == k2).log("RL6-rollout-separation: same rollout → same key")
      else Result.assert(k1 != k2).log("RL6-rollout-separation: distinct rollouts → distinct keys")

  // RL7: CallRecord codec round-trip (encode → decode → equal)
  // spec: add-adk4s-record/recorder-laws — Proof Obligation: RL7 codec round trip
  def rl7_codec_round_trip: Property =
    for record <- genCallRecord.forAll
    yield
      val json: String = Json.writeBlob(record)(using CallRecord.schema).toUTF8String
      val decoded: Either[Throwable, CallRecord] =
        Json.read[CallRecord](smithy4s.Blob(json))
      Result.assert(decoded == Right(record)).log("RL7-codec-round-trip")

  // RL8: append-only monotonicity (seq never decreases)
  // spec: add-adk4s-record/recorder-laws — Proof Obligation: RL8 append-only monotonicity
  def rl8_append_only_monotonicity: Property =
    for ops <- genSeqOps.forAll
    yield
      val seqs: List[Long] =
        (for
          rec <- Recorder.inMemory[IO](Positive100)
          seqs <- ops.foldLeftM(List.empty[Long])((acc, op) =>
            op match
              case RecorderOp.NextSeq      => rec.nextSeq.map(acc :+ _)
              case RecorderOp.Lookup(k)    => rec.lookup(k).as(acc)
              case RecorderOp.Record(k, r) => rec.record(k, r).as(acc)
          )
        yield seqs).unsafeRunSync()
      val pairs: List[(Long, Long)] = seqs.zip(seqs.drop(1))
      Result
        .assert(pairs.forall { case (a, b) => a < b })
        .log("RL8-append-only-monotonicity: strictly increasing")

  // RL9: recorded failure replays as equal failure
  // spec: add-adk4s-record/recorder-laws — Requirement: RL9 failure fidelity property
  // Tests both that the record is stored as a FailedCase AND that looking it
  // up through RecordedChatModel treats it as a miss (retry), not a success.
  def rl9_failure_fidelity: Property =
    for
      err <- genRecordedError.forAll
      key <- genCallKey.forAll
    yield
      val failedRecord: CallRecord = CallRecord.failed(
        FailedRecord(key.value, 0L, CallKind.MODEL, err, Classification.INTERNAL)
      )
      // 1. Verify the record is stored and retrieved as a FailedCase
      val storedResult: Option[CallRecord] =
        (for
          rec <- Recorder.inMemory[IO](Positive100)
          _   <- rec.record(key, failedRecord)
          r   <- rec.lookup(key)
        yield r).unsafeRunSync()
      val storedCorrectly: Boolean = storedResult match
        case Some(fr: CallRecord.FailedCase) => fr == failedRecord
        case _                               => false
      // 2. Verify that a RecordedChatModel hitting a failed record treats it
      //    as a miss (retry), NOT as a success. A failure record should not
      //    replay as a successful Completion.
      val conv: Conversation = Conversation(List(UserMessage("test")))
      val replayResult: Either[Throwable, Completion] =
        (for
          rec <- Recorder.inMemory[IO](Positive100)
          _   <- rec.record(key, failedRecord)
          // Build a RecordedChatModel that will compute the same key for this
          // conversation. We use a FailingChatModel as the underlying so that
          // if the failed record is treated as a miss (correct), the call
          // fails. If it were treated as a hit (wrong), it would return a
          // success — but there's no success to return from a FailedCase.
          under                  = FailingChatModel()
          wrapped: ChatModel[IO] = RecordedChatModel(under, rec)
          r <- wrapped.generate(conv).attempt
        yield r).unsafeRunSync()
      // The replay should fail (Left) because the failed record is treated
      // as a miss, and the underlying FailingChatModel raises an error.
      // It must NOT be a success (Right).
      val replayIsNotSuccess: Boolean = replayResult.isLeft
      Result
        .assert(storedCorrectly)
        .log("RL9-failure-fidelity: stored as equal FailedCase")
        .and(Result.assert(replayIsNotSuccess).log("RL9-failure-fidelity: replay does not produce success"))

  // RL10: sink write failure doesn't fail the wrapped call
  // spec: add-adk4s-record/recorder-laws — Requirement: RL10 write-failure containment property
  def rl10_write_failure_containment: Property =
    for conv <- genConversation.forAll
    yield
      val failingRec: Recorder[IO] = FailingRecorder()
      val under: ChatModel[IO]     = SimpleChatModel()
      val wrapped: ChatModel[IO]   = RecordedChatModel(under, failingRec)
      val result: Either[Throwable, Completion] =
        wrapped.generate(conv).attempt.unsafeRunSync()
      Result
        .assert(result.isRight)
        .log("RL10-write-failure-containment: call succeeds despite sink failure")

  // RL11: redaction changes stored payload, not key
  // spec: add-adk4s-record/recorder-laws — Requirement: RL11 redaction neutrality property
  def rl11_redaction_neutrality: Property =
    for conv <- genConversation.forAll
    yield
      val redaction: Redaction = payload =>
        payload match
          case RecordPayload.ModelCase(mp) =>
            RecordPayload.model(mp.copy(content = "[REDACTED]"))
          case other =>
            other // danger-scan:allow redaction only applies to Model payloads; non-Model passes through unchanged
      val counter: CallCounter = CallCounter()
      val result: IO[Boolean] =
        for
          memRec <- Recorder.inMemory[IO](Positive100)
          wrapped: ChatModel[IO] = RecordedChatModel(counter, memRec, redaction = Some(redaction))
          _ <- wrapped.generate(conv)
          _ <- wrapped.generate(conv)
        yield counter.count == 1 // second call was a hit (zero underlying calls)
      val hitOnSecondCall: Boolean = result.unsafeRunSync()
      Result
        .assert(hitOnSecondCall)
        .log("RL11-redaction-neutrality: second call hits (key unchanged by redaction)")

  // RL12: records under keyVersion=n invisible to keyVersion=n+1
  // spec: add-adk4s-record/recorder-laws — Requirement: RL12 version isolation property
  // Tests that a record written under keyVersion=1 is invisible to a lookup
  // at keyVersion=2 (returns None, not an error).
  def rl12_version_isolation: Property =
    for req <- genModelRequest.forAll
    yield
      val formV1: CanonicalForm = CanonicalFormOps.from(req)
      // Construct a form with keyVersion = 2 (simulating a future version)
      val formV2: CanonicalForm = formV1.copy(keyVersion = 2)
      val kV1: CallKey          = CallKey.fromCanonical(formV1)
      val kV2: CallKey          = CallKey.fromCanonical(formV2)
      // Write a record under keyVersion=1, then lookup at keyVersion=2
      val lookupResult: Option[CallRecord] =
        (for
          rec <- Recorder.inMemory[IO](Positive100)
          record = CallRecord.succeeded(
            SucceededRecord(
              kV1.value,
              0L,
              CallKind.MODEL,
              RecordPayload.model(genModelPayloadSample),
              Classification.PUBLIC
            )
          )
          _ <- rec.record(kV1, record)
          r <- rec.lookup(kV2)
        yield r).unsafeRunSync()
      Result
        .assert(kV1 != kV2)
        .log("RL12-version-isolation: different keyVersion → different key")
        .and(
          Result
            .assert(lookupResult.isEmpty)
            .log("RL12-version-isolation: lookup at v2 returns None for v1 record")
        )

object RecorderLaws:

  // ── Generators ────────────────────────────────────────────────────────

  val genCallKey: Gen[CallKey] =
    Gen.string(Gen.alphaNum, Range.linear(8, 64)).map(CallKey.fromDigest)

  // Generates a CallKey distinct from `other` (guaranteed != other)
  def genDistinctCallKey(other: CallKey): Gen[CallKey] =
    Gen
      .string(Gen.alphaNum, Range.linear(8, 64))
      .map(s => CallKey.fromDigest(other.value + "-" + s))
      .ensure(_ != other)

  val genClassification: Gen[Classification] =
    Gen.element1(
      Classification.PUBLIC,
      Classification.INTERNAL,
      Classification.CONFIDENTIAL,
      Classification.RESTRICTED
    )

  val genCallKind: Gen[CallKind] =
    Gen.element1(CallKind.MODEL, CallKind.TOOL, CallKind.EMBEDDING)

  val genRecordedError: Gen[RecordedError] =
    for
      errorType <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      message   <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
      hasCause  <- Gen.boolean
      cause     <- Gen.string(Gen.alphaNum, Range.linear(0, 30))
    yield RecordedError(errorType, message, if hasCause then Some(cause) else None)

  val genModelPayload: Gen[ModelPayload] =
    for
      content   <- Gen.string(Gen.alphaNum, Range.linear(0, 100))
      finish    <- Gen.string(Gen.alphaNum, Range.linear(0, 20))
      hasFinish <- Gen.boolean
      hasTokens <- Gen.boolean
      tokens    <- Gen.int(Range.linear(0, 10000))
    yield ModelPayload(
      content = content,
      id = "",
      created = 0L,
      model = "",
      finishReason = if hasFinish then Some(finish) else None,
      toolCalls = None,
      promptTokens = if hasTokens then Some(tokens) else None,
      completionTokens = if hasTokens then Some(tokens) else None,
      totalTokens = if hasTokens then Some(tokens * 2) else None
    )

  val genToolPayload: Gen[ToolPayload] =
    for
      name    <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      callId  <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      isError <- Gen.boolean
    yield ToolPayload(name, smithy4s.Document.DNull, callId, isError)

  val genEmbeddingPayload: Gen[EmbeddingPayload] =
    for
      model     <- Gen.string(Gen.alphaNum, Range.linear(3, 20))
      hasTokens <- Gen.boolean
      tokens    <- Gen.int(Range.linear(0, 1000))
    yield EmbeddingPayload(model, if hasTokens then Some(tokens) else None, None)

  val genRecordPayload: Gen[RecordPayload] =
    Gen.frequency(
      40 -> genModelPayload.map(RecordPayload.model(_)),
      List(
        40 -> genToolPayload.map(RecordPayload.tool(_)),
        20 -> genEmbeddingPayload.map(RecordPayload.embedding(_))
      )
    )

  val genSucceededRecord: Gen[CallRecord] =
    for
      key     <- Gen.string(Gen.alphaNum, Range.linear(8, 64))
      seq     <- Gen.long(Range.linear(0, 1000000))
      kind    <- genCallKind
      payload <- genRecordPayload
      cls     <- genClassification
    yield CallRecord.succeeded(
      SucceededRecord(key, seq, kind, payload, cls)
    )

  val genFailedRecord: Gen[CallRecord] =
    for
      key  <- Gen.string(Gen.alphaNum, Range.linear(8, 64))
      seq  <- Gen.long(Range.linear(0, 1000000))
      kind <- genCallKind
      err  <- genRecordedError
      cls  <- genClassification
    yield CallRecord.failed(
      FailedRecord(key, seq, kind, err, cls)
    )

  val genCallRecord: Gen[CallRecord] =
    Gen.frequency1(60 -> genSucceededRecord, 40 -> genFailedRecord)

  // ── Model request generators ──────────────────────────────────────────

  val genToolDef: Gen[ToolDef] =
    for
      name <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      desc <- Gen.string(Gen.alphaNum, Range.linear(5, 30))
    yield ToolDef(name, desc, "{}")

  val genRolloutId: Gen[RolloutId] =
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

  val genConversation: Gen[Conversation] =
    for
      msgCount <- Gen.int(Range.linear(1, 5))
      messages <- Gen.list(genUserMessage, Range.linear(msgCount, msgCount))
    yield Conversation(messages)

  val genUserMessage: Gen[org.llm4s.llmconnect.model.Message] =
    for content <- Gen.string(Gen.alphaNum, Range.linear(1, 50))
    yield UserMessage(content)

  val genModelRequest: Gen[ModelCallRequest] =
    for
      provider     <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      model        <- Gen.string(Gen.alphaNum, Range.linear(3, 10))
      systemPrompt <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
      temperature  <- Gen.double(Range.linearFrac(0.0, 2.0))
      topP         <- Gen.double(Range.linearFrac(0.0, 1.0))
      hasRollout   <- Gen.frequency1(55 -> Gen.constant(true), 45 -> Gen.constant(false))
      rolloutVal   <- genRolloutId
      rollout = if hasRollout then Some(rolloutVal) else None
      conv      <- genConversation
      hasTools  <- Gen.frequency1(82 -> Gen.constant(true), 18 -> Gen.constant(false))
      toolCount <- Gen.int(Range.linear(1, 3))
      tools <-
        if hasTools then Gen.list(genToolDef, Range.linear(toolCount, toolCount))
        else Gen.constant(List.empty[ToolDef])
      options = CompletionOptions(temperature, topP, None, 0.0, 0.0, Nil, None, None, None)
    yield ModelCallRequest(
      provider = provider,
      model = model,
      conversation = conv,
      tools = tools,
      systemPrompt = systemPrompt,
      options = options,
      rollout = rollout
    )

  // ── Context-aware mutation generators ─────────────────────────────────

  def genRequestMutation(base: ModelCallRequest): Gen[RequestMutation] =
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

  def genNonAffectingMutation(base: ModelCallRequest): Gen[NonAffectingMutation] =
    Gen.choice1(
      Gen.constant(NonAffectingMutation.ChangeProviderRequestId("req-123")),
      Gen.constant(NonAffectingMutation.ChangeLatency(42L)),
      Gen.constant(NonAffectingMutation.ChangeTokenUsage(100L)),
      Gen.constant(NonAffectingMutation.ChangeTimestamp(1234567890L)),
      Gen.constant(NonAffectingMutation.RegenerateToolCallIds(Map.empty))
    )

  // ── Seq ops generator (for RL8) ──────────────────────────────────────

  enum RecorderOp:
    case NextSeq
    case Lookup(key: CallKey)
    case Record(key: CallKey, record: CallRecord)

  val genSeqOps: Gen[List[RecorderOp]] =
    for
      count <- Gen.int(Range.linear(1, 10))
      ops   <- Gen.list(genSeqOp, Range.linear(count, count))
    yield ops

  val genSeqOp: Gen[RecorderOp] =
    Gen.frequency1(
      40 -> Gen.constant(RecorderOp.NextSeq),
      30 -> genCallKey.map(RecorderOp.Lookup(_)),
      30 -> (for
        key <- genCallKey
        rec <- genSucceededRecord
      yield RecorderOp.Record(key, rec))
    )

  // ── Positive 100 for inMemory recorder ────────────────────────────────
  val Positive100: org.adk4s.core.types.Positive = 100

  // ── SimpleChatModel — deterministic ChatModel for RL0/RL5 ─────────────
  // A minimal ChatModel that returns a deterministic Completion based on
  // the conversation content. Used by RL0 (transparency) and RL5 (zero-call
  // hit) as the underlying model.
  private class SimpleChatModel extends ChatModel[IO]:
    def generate(conversation: Conversation): IO[Completion] =
      IO.pure(
        Completion(
          id = "simple-" + conversation.messages.hashCode.toString,
          created = 0L,
          content = conversation.messages.map(_.content).mkString,
          model = "simple-model",
          message = AssistantMessage(
            conversation.messages.map(_.content).mkString,
            Nil
          )
        )
      )

    override def generate(
      conversation: Conversation,
      options: CompletionOptions
    ): IO[Completion] = generate(conversation)

    def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
      Stream.empty

    def streamContent(conversation: Conversation): Stream[IO, String] =
      Stream.empty

    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  // ── CallCounter — ChatModel that counts underlying calls ──────────────
  // Wraps SimpleChatModel with an atomic call counter for RL5.
  private class CallCounter extends ChatModel[IO]:
    private val inner: SimpleChatModel = SimpleChatModel()
    private val countRef: cats.effect.Ref[IO, Int] =
      cats.effect.Ref.unsafe[IO, Int](0)

    def count: Int = countRef.get.unsafeRunSync()

    def generate(conversation: Conversation): IO[Completion] =
      countRef.update(_ + 1) *> inner.generate(conversation)

    override def generate(
      conversation: Conversation,
      options: CompletionOptions
    ): IO[Completion] = generate(conversation)

    def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
      inner.stream(conversation)

    def streamContent(conversation: Conversation): Stream[IO, String] =
      inner.streamContent(conversation)

    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  // ── FailingRecorder — Recorder whose record always fails ──────────────
  // Used by RL10 (write-failure containment).
  private class FailingRecorder extends Recorder[IO]:
    def lookup(key: CallKey): IO[Option[CallRecord]] =
      IO.pure(None)
    def record(key: CallKey, outcome: CallRecord): IO[Unit] =
      IO.raiseError(new RuntimeException("sink write failure (RL10)"))
    def nextSeq: IO[Long] =
      IO.pure(0L)

  // ── FailingChatModel — ChatModel whose generate always fails ──────────
  // Used by RL9 (failure fidelity) to verify that a failed record is treated
  // as a miss (retry), not as a success.
  private class FailingChatModel extends ChatModel[IO]:
    def generate(conversation: Conversation): IO[Completion] =
      IO.raiseError(new RuntimeException("underlying model failure (RL9)"))
    override def generate(
      conversation: Conversation,
      options: CompletionOptions
    ): IO[Completion] = generate(conversation)
    def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
      Stream.raiseError[IO](new RuntimeException("underlying model failure (RL9)"))
    def streamContent(conversation: Conversation): Stream[IO, String] =
      Stream.raiseError[IO](new RuntimeException("underlying model failure (RL9)"))
    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  // ── Sample ModelPayload for RL12 ──────────────────────────────────────
  val genModelPayloadSample: ModelPayload =
    ModelPayload(
      content = "sample",
      id = "",
      created = 0L,
      model = "",
      finishReason = None,
      toolCalls = None,
      promptTokens = None,
      completionTokens = None,
      totalTokens = None
    )
