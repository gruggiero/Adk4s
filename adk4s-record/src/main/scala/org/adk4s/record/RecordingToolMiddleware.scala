package org.adk4s.record

import cats.data.Kleisli
import cats.effect.IO
import org.adk4s.core.tools.ToolInput
import org.adk4s.core.tools.ToolMiddleware
import org.adk4s.record.canonical.CallKind
import smithy4s.Document

// ── RecordingToolMiddleware — recording ToolMiddleware factory ─────────
// spec: add-adk4s-record/recorded-wrappers — Requirement: Recording ToolMiddleware composes with existing middleware
//
// A recording ToolMiddleware that composes with the existing
// logging/timing/validation middleware set. Tool call results are recorded
// under their computed CallKey. The middleware is a ToolMiddleware
// (Kleisli endomorphism) so it composes via `ToolMiddleware.compose` or
// the `>>` operator.
object RecordingToolMiddleware:

  /**
   * Create a recording ToolMiddleware.
   *
   * @param recorder
   *   The Recorder to store tool call records in.
   * @param redaction
   *   Optional payload redaction function, applied after key computation.
   * @return
   *   A ToolMiddleware that records tool call results.
   */
  def recording(
    recorder: Recorder[IO],
    redaction: Option[Redaction] = None
  ): ToolMiddleware =
    endpoint =>
      Kleisli { (input: ToolInput) =>
        val form = CanonicalFormOps.fromToolCall(input)
        val key  = CallKey.fromCanonical(form)
        for
          result <- endpoint.run(input)
          payload = ToolPayload(
            name = result.name,
            result = Document.fromString(result.result),
            callId = result.callId,
            isError = result.isError
          )
          redacted = redaction.fold(RecordPayload.tool(payload))(red => red(RecordPayload.tool(payload)))
          record = CallRecord.succeeded(
            SucceededRecord(
              key = key.value,
              seq = 0L,
              kind = CallKind.TOOL,
              payload = redacted,
              classification = Classification.PUBLIC
            )
          )
          // Record without failing the call on write failure
          _ <- recorder.record(key, record).attempt.void
        yield result
      }
