$version: "2.0"

namespace org.adk4s.record.canonical

/// Call type discriminator for key-space isolation.
/// Each variant produces a separate key namespace, so a model call
/// and a tool call with the same content get different keys.
enum CallKind {
    MODEL
    TOOL
    EMBEDDING
}

/// Inspectable canonical form of a call request.
/// Serialized to stable JSON for SHA-256 hashing.
///
/// The body is a union of per-kind structures. The kind field is
/// redundant with the union variant but kept explicit for inspectability
/// and spec compatibility. The Canonicalization functions enforce
/// consistency at construction time.
structure CanonicalForm {
    @required
    keyVersion: Integer

    @required
    kind: CallKind

    @required
    body: CanonicalBody
}

/// Union of canonical body structures per call kind.
union CanonicalBody {
    model: ModelBody
    tool: ToolBody
    embedding: EmbeddingBody
}

/// Canonical body for a model call request.
/// Includes all output-affecting fields. Excludes non-output-affecting
/// fields (providerRequestId, latencyMs, tokenUsage, timestamp).
structure ModelBody {
    @required
    provider: String

    @required
    model: String

    @required
    systemPrompt: String

    @required
    messages: CanonicalMessageList

    @required
    tools: CanonicalToolDefList

    @required
    temperature: Double

    @required
    topP: Double

    maxTokens: Integer

    @required
    presencePenalty: Double

    @required
    frequencyPenalty: Double

    reasoning: String

    budgetTokens: Integer

    responseFormat: String

    @required
    stopSequences: StringList

    outputSchema: String

    rollout: String
}

/// Canonical body for a tool call.
structure ToolBody {
    @required
    name: String

    @required
    arguments: String

    @required
    callId: String
}

/// Canonical body for an embedding request.
structure EmbeddingBody {
    @required
    text: String

    @required
    model: String
}

/// Union of canonical message types.
/// Each variant corresponds to a message role in the conversation.
union CanonicalMessage {
    user: UserMessage
    system: SystemMessage
    assistant: AssistantMessage
    tool: ToolMessage
}

/// A user message in the canonical conversation.
structure UserMessage {
    @required
    content: String
}

/// A system message in the canonical conversation.
structure SystemMessage {
    @required
    content: String
}

/// An assistant message in the canonical conversation.
/// May contain tool calls (with normalized positional ids).
structure AssistantMessage {
    content: String
    toolCalls: CanonicalToolCallList
}

/// A tool reply message in the canonical conversation.
structure ToolMessage {
    @required
    content: String

    @required
    toolCallId: String
}

/// A tool call within an assistant message.
/// The arguments field is a JSON document (smithy4s.Document) to
/// preserve the exact structure of the tool call arguments.
structure CanonicalToolCall {
    @required
    id: String

    @required
    name: String

    @required
    arguments: Document
}

/// A tool definition in the canonical form.
/// The schema field is a JSON document preserving the exact tool schema.
structure CanonicalToolDef {
    @required
    name: String

    @required
    description: String

    @required
    schema: Document
}

/// List of canonical messages.
list CanonicalMessageList {
    member: CanonicalMessage
}

/// List of canonical tool calls.
list CanonicalToolCallList {
    member: CanonicalToolCall
}

/// List of canonical tool definitions.
list CanonicalToolDefList {
    member: CanonicalToolDef
}

/// List of strings.
list StringList {
    member: String
}
