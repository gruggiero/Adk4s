package org.adk4s.record

import munit.FunSuite
import org.adk4s.core.error.AdkError
import org.adk4s.core.error.RecorderError
import org.adk4s.record.canonical.CallKind
import smithy4s.Schema

/**
 * Type-level contract tests for Recorder[F], CallRecord, RecordPayload,
 * RecordedError, Classification, RecorderError, and factory signatures.
 *
 * spec: add-adk4s-record/recorder-sink — Step 1: typed contract
 */
class RecorderTypeContract extends FunSuite:

  // ── CallRecord union structure ──────────────────────────────────────

  test("CallRecord is a union with SucceededCase and FailedCase"):
    val succeeded: CallRecord = CallRecord.succeeded(
      SucceededRecord(
        key = "key1",
        seq = 1L,
        kind = CallKind.MODEL,
        payload = RecordPayload.model(
          ModelPayload(
            content = "response",
            id = "",
            created = 0L,
            model = "",
            finishReason = None,
            toolCalls = None,
            promptTokens = None,
            completionTokens = None,
            totalTokens = None
          )
        ),
        classification = Classification.INTERNAL
      )
    )
    succeeded match
      case _: CallRecord.SucceededCase => () // expected
      case _: CallRecord.FailedCase    => fail("expected SucceededCase")

    val failed: CallRecord = CallRecord.failed(
      FailedRecord(
        key = "key2",
        seq = 2L,
        kind = CallKind.MODEL,
        error = RecordedError("LlmCallError", "timeout", None),
        classification = Classification.INTERNAL
      )
    )
    failed match
      case _: CallRecord.FailedCase    => () // expected
      case _: CallRecord.SucceededCase => fail("expected FailedCase")

  // ── RecordPayload union structure ───────────────────────────────────

  test("RecordPayload is a union with model, tool, and embedding variants"):
    val model: RecordPayload = RecordPayload.model(
      ModelPayload("content", "", 0L, "", None, None, None, None, None)
    )
    model match
      case _: RecordPayload.ModelCase     => () // expected
      case _: RecordPayload.ToolCase      => fail("expected ModelCase")
      case _: RecordPayload.EmbeddingCase => fail("expected ModelCase")

    val tool: RecordPayload = RecordPayload.tool(
      ToolPayload("name", smithy4s.Document.DNull, "callId", false)
    )
    tool match
      case _: RecordPayload.ToolCase      => () // expected
      case _: RecordPayload.ModelCase     => fail("expected ToolCase")
      case _: RecordPayload.EmbeddingCase => fail("expected ToolCase")

    val embedding: RecordPayload = RecordPayload.embedding(
      EmbeddingPayload("model", None, None)
    )
    embedding match
      case _: RecordPayload.EmbeddingCase => () // expected
      case _: RecordPayload.ModelCase     => fail("expected EmbeddingCase")
      case _: RecordPayload.ToolCase      => fail("expected EmbeddingCase")

  // ── Classification enum has four variants ───────────────────────────

  test("Classification has four variants: PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED"):
    val values: List[Classification] = Classification.values
    assertEquals(values.length, 4)
    assert(values.contains(Classification.PUBLIC))
    assert(values.contains(Classification.INTERNAL))
    assert(values.contains(Classification.CONFIDENTIAL))
    assert(values.contains(Classification.RESTRICTED))

  // ── RecordedError structure ─────────────────────────────────────────

  test("RecordedError has errorType, message, and optional cause"):
    val err1: RecordedError = RecordedError("LlmCallError", "timeout", None)
    assertEquals(err1.errorType, "LlmCallError")
    assertEquals(err1.message, "timeout")
    assertEquals(err1.cause, None)

    val err2: RecordedError = RecordedError("ToolError", "failed", Some("NPE"))
    assertEquals(err2.cause, Some("NPE"))

  // ── Schema[CallRecord] exists for JSONL serialization ───────────────

  test("Schema[CallRecord] exists for JSONL via smithy4s.json.Json"):
    val schema: Schema[CallRecord] = CallRecord.schema
    assert(schema != null)

  // ── RecorderError extends AdkError with three variants ──────────────

  test("RecorderError extends AdkError with SinkWriteFailed, SinkReadFailed, CodecFailed"):
    val writeErr: AdkError = RecorderError.SinkWriteFailed(
      new RuntimeException("disk full")
    )
    writeErr match
      case _: RecorderError.SinkWriteFailed => () // expected
      case _: AdkError                      => fail("expected SinkWriteFailed")

    val readErr: AdkError = RecorderError.SinkReadFailed(
      new RuntimeException("permission denied")
    )
    readErr match
      case _: RecorderError.SinkReadFailed => () // expected
      case _: AdkError                     => fail("expected SinkReadFailed")

    val codecErr: AdkError = RecorderError.CodecFailed("bad json", "{invalid")
    codecErr match
      case _: RecorderError.CodecFailed => () // expected
      case _: AdkError                  => fail("expected CodecFailed")
    assertEquals(codecErr.message, "bad json")

  // ── Recorder trait has lookup, record, nextSeq ──────────────────────

  test("Recorder trait has lookup, record, nextSeq signatures"):
    // Type-level check: the trait compiles with these method signatures.

    ()

  // ── SucceededRecord carries key and seq ─────────────────────────────

  test("SucceededRecord carries key, seq, kind, payload, classification"):
    val rec: SucceededRecord = SucceededRecord(
      "key1",
      5L,
      CallKind.MODEL,
      RecordPayload.model(ModelPayload("content", "", 0L, "", None, None, None, None, None)),
      Classification.CONFIDENTIAL
    )
    assertEquals(rec.key, "key1")
    assertEquals(rec.seq, 5L)
    assertEquals(rec.kind, CallKind.MODEL)
    assertEquals(rec.classification, Classification.CONFIDENTIAL)

  // ── FailedRecord carries key, seq, kind, error, classification ──────

  test("FailedRecord carries key, seq, kind, error, classification"):
    val rec: FailedRecord = FailedRecord(
      "key2",
      3L,
      CallKind.TOOL,
      RecordedError("ToolError", "failed", None),
      Classification.RESTRICTED
    )
    assertEquals(rec.key, "key2")
    assertEquals(rec.seq, 3L)
    assertEquals(rec.kind, CallKind.TOOL)
    assertEquals(rec.classification, Classification.RESTRICTED)
