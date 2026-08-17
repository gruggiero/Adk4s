$version: "2.0"

namespace org.adk4s.record

use org.adk4s.record.canonical#CallKind

/// Data-classification marker traveling with every record (REC-23).
/// Travels with the data rather than being a property of where it
/// happens to be stored.
enum Classification {
    PUBLIC
    INTERNAL
    CONFIDENTIAL
    RESTRICTED
}

/// First-class record of a call outcome. A union of success and failure
/// variants so that failures are recorded as first-class outcomes (REC-21).
/// Generated from Smithy IDL via smithy4s codegen, with Schema[CallRecord]
/// for JSONL serialization via smithy4s.json.Json.
union CallRecord {
    succeeded: SucceededRecord
    failed: FailedRecord
}

/// A successful call record.
structure SucceededRecord {
    @required
    key: String

    @required
    seq: Long

    @required
    kind: CallKind

    @required
    payload: RecordPayload

    @required
    classification: Classification
}

/// A failed call record.
structure FailedRecord {
    @required
    key: String

    @required
    seq: Long

    @required
    kind: CallKind

    @required
    error: RecordedError

    @required
    classification: Classification
}

/// Typed payload per call kind. Replaces untyped JsonValue for type-safe
/// redaction and stable serialization. Flexible content (tool results)
/// uses smithy4s.Document.
union RecordPayload {
    model: ModelPayload
    tool: ToolPayload
    embedding: EmbeddingPayload
}

/// Model call response: content, tool calls, finish reason, token usage.
/// Converted from llm4s Completion at the boundary. Stores ALL Completion
/// fields so the cached result can be fully reconstructed on a hit.
structure ModelPayload {
    @required
    content: String

    @required
    id: String

    @required
    created: Long

    @required
    model: String

    finishReason: String

    toolCalls: ModelToolCallList

    promptTokens: Integer

    completionTokens: Integer

    totalTokens: Integer

    thinkingTokens: Integer

    cachedTokens: Integer

    cacheCreationTokens: Integer

    thinking: String

    estimatedCost: Double

    messageContent: String
}

/// A tool call within a model response.
structure ModelToolCall {
    @required
    id: String

    @required
    name: String

    @required
    arguments: Document
}

/// Tool call response: name, result (Document), callId, isError.
/// Converted from ToolOutput at the boundary.
structure ToolPayload {
    @required
    name: String

    @required
    result: Document

    @required
    callId: String

    @required
    isError: Boolean
}

/// Embedding response: vector, model, token count.
structure EmbeddingPayload {
    @required
    model: String

    tokenCount: Integer

    embedding: DoubleList
}

/// Captured failure detail for replay fidelity: error type, message,
/// optional cause.
structure RecordedError {
    @required
    errorType: String

    @required
    message: String

    cause: String
}

/// List of model tool calls.
list ModelToolCallList {
    member: ModelToolCall
}

/// List of doubles (embedding vector).
list DoubleList {
    member: Double
}

/// Reference to CallKind enum from canonical_form.smithy.
/// In Smithy, cross-namespace references use the full namespace prefix.
/// CallKind is defined in org.adk4s.record.canonical.CallKind.

