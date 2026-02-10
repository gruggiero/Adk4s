package org.adk4s.examples.structured

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import java.util.UUID

/**
 * Unified mock LLM client for all StructuredLLM and StructuredToolCall examples.
 * 
 * This client returns deterministic, schema-compliant JSON responses based on
 * pattern matching against the user's last message content. It supports:
 * 
 * 1. StructuredLLM classification responses (category, role, query, route)
 * 2. StructuredLLM extraction responses (plan, steps, list, schema)
 * 3. StructuredLLM multi-agent responses (host routing, specialist delegation)
 * 4. StructuredLLM chain responses (typed intermediates, composition)
 * 5. StructuredLLM workflow responses (graph completion, async transformations)
 * 6. StructuredLLM SAP error recovery (malformed JSON, fences, trailing commas, single quotes)
 * 7. StructuredToolCall tool call responses (calculator, search, weather)
 * 
 * Supports both complete() and streamComplete() methods (streaming returns
 * the full response as a single chunk for deterministic behavior).
 * 
 * SAP Error Recovery:
 * - Trailing commas: {"category": "math", } -> {"category": "math"}
 * - Single quotes: {'category': 'science'} -> {"category": "science"}
 * - Markdown fences: ```json{"category": "math"}``` -> {"category": "math"}
 */
class StructuredMockLLMClient extends LLMClient:

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
      case msg: SystemMessage => msg.content
    }.lastOption.getOrElse("")
    
    val lastSystemPrompt: String = conversation.messages.collect {
      case msg: SystemMessage => msg.content
    }.lastOption.getOrElse("")
    
    val response: String = generateResponse(lastUserMessage, lastSystemPrompt)
    
    val assistantMessage: AssistantMessage = AssistantMessage(Some(response))
    Right(Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = response,
      model = "mock-structured-llm",
      message = assistantMessage,
      toolCalls = List.empty,
      usage = None,
      thinking = None
    ))

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Either[org.llm4s.error.LLMError, Completion] =
    complete(conversation, options)

  def getContextWindow(): Int = 8192
  def getReserveCompletion(): Int = 512

  private def generateResponse(userMessage: String, systemPrompt: String): String =
    val promptType: String = detectPromptType(systemPrompt)
    
    if containsSAPExample(userMessage, systemPrompt) then
      generateSAPErrorResponse(userMessage, systemPrompt)
    else if promptType == "classifier" then
      generateClassificationResponse(userMessage, systemPrompt)
    else if promptType == "planner" then
      generatePlanResponse(userMessage, systemPrompt)
    else if promptType == "extraction" then
      generateExtractionResponse(userMessage, systemPrompt)
    else if promptType == "multiagent" then
      generateMultiAgentResponse(userMessage, systemPrompt)
    else if promptType == "chain" then
      generateChainResponse(userMessage, systemPrompt)
    else if promptType == "workflow" then
      generateWorkflowResponse(userMessage, systemPrompt)
    else if promptType == "toolcall" then
      generateToolCallResponse(userMessage, systemPrompt)
    else
      generateGenericResponse(userMessage)

  private def detectPromptType(systemPrompt: String): String =
    if systemPrompt.contains("classif") || systemPrompt.contains("classifier") then
      "classifier"
    else if systemPrompt.contains("plan") || systemPrompt.contains("extract plan") then
      "planner"
    else if systemPrompt.contains("extract") || systemPrompt.contains("parse list") then
      "extraction"
    else if systemPrompt.contains("host") || systemPrompt.contains("specialist") then
      "multiagent"
    else if systemPrompt.contains("chain") || systemPrompt.contains("intermediate") then
      "chain"
    else if systemPrompt.contains("workflow") || systemPrompt.contains("graph") || systemPrompt.contains("async") then
      "workflow"
    else if systemPrompt.contains("ReAct") || systemPrompt.contains("tool") then
      "toolcall"
    else
      "generic"

  private def containsSAPExample(userMessage: String, systemPrompt: String): Boolean =
    userMessage.contains("SAP") || 
    userMessage.contains("malform") || 
    userMessage.contains("trailing comma") ||
    userMessage.contains("single quote") ||
    userMessage.contains("markdown fence") ||
    systemPrompt.contains("SAP") ||
    systemPrompt.contains("error recovery")

  private def generateSAPErrorResponse(userMessage: String, systemPrompt: String): String =
    if userMessage.contains("trailing comma") then
      """{"category": "math", "confidence": 0.95,}"""
    else if userMessage.contains("single quote") then
      """{'category': 'science', 'confidence': 0.92}"""
    else if userMessage.contains("markdown fence") then
      """```json
{"category": "history", "confidence": 0.90}
```"""
    else
      """{"category": "other", "confidence": 0.88,"""

  private def generateClassificationResponse(userMessage: String, systemPrompt: String): String =
    if userMessage.contains("square root") || userMessage.contains("144") then
      """{"category": "math", "confidence": 0.95}"""
    else if userMessage.contains("photosynthesis") || userMessage.contains("plants") then
      """{"category": "science", "confidence": 0.92}"""
    else if userMessage.contains("World War") || userMessage.contains("history") then
      """{"category": "history", "confidence": 0.90}"""
    else if userMessage.contains("weather") then
      """{"category": "other", "confidence": 0.88}"""
    else if userMessage.contains("customer") || userMessage.contains("support") then
      """{"role": "customer", "confidence": 0.91}"""
    else if userMessage.contains("manager") then
      """{"role": "manager", "confidence": 0.89}"""
    else if userMessage.contains("question") then
      """{"queryType": "question", "intent": "information seeking"}"""
    else if userMessage.contains("command") then
      """{"queryType": "command", "intent": "action request"}"""
    else if userMessage.contains("database") then
      """{"chainName": "database", "reason": "Requires data access"}"""
    else
      """{"category": "other", "confidence": 0.70}"""

  private def generatePlanResponse(userMessage: String, systemPrompt: String): String =
    if systemPrompt.contains("plan") then
      """{"steps": [{"index": 1, "description": "Research topic", "duration": 5}, {"index": 2, "description": "Draft outline", "duration": 10}, {"index": 3, "description": "Write content", "duration": 15}], "totalDuration": 30}"""
    else if systemPrompt.contains("extract list") || systemPrompt.contains("steps") then
      """{"items": [{"index": 1, "description": "Initialize project", "duration": 5}, {"index": 2, "description": "Set up development environment", "duration": 10}, {"index": 3, "description": "Implement core features", "duration": 20}]}"""
    else if systemPrompt.contains("numbered") || systemPrompt.contains("bulleted") then
      """{"items": [{"index": 1, "content": "Create project structure"}, {"index": 2, "content": "Install dependencies"}, {"index": 3, "content": "Write initial code"}]}"""
    else if systemPrompt.contains("schema") || systemPrompt.contains("complex structure") then
      """{"name": "ProjectSetup", "description": "Initial project configuration", "items": ["Create repo", "Install deps", "Configure CI"], "priority": "high"}"""
    else
      """{"steps": [{"index": 1, "description": "Analyze request"}], "totalDuration": 5}"""

  private def generateExtractionResponse(userMessage: String, systemPrompt: String): String =
    """{"items": [{"index": 1, "name": "Item 1", "description": "First item description"}, {"index": 2, "name": "Item 2", "description": "Second item description"}, {"index": 3, "name": "Item 3", "description": "Third item description"}]}"""

  private def generateMultiAgentResponse(userMessage: String, systemPrompt: String): String =
    if userMessage.contains("query") || userMessage.contains("database") then
      """{"specialist": "database-agent", "rationale": "Request involves data storage and retrieval"}"""
    else if userMessage.contains("calculation") || userMessage.contains("math") then
      """{"specialist": "calculation-agent", "rationale": "Request requires arithmetic operations"}"""
    else if systemPrompt.contains("host") then
      """{"specialist": "database-agent", "rationale": "Routing to appropriate specialist"}"""
    else
      """{"specialist": "general-agent", "rationale": "No specific domain identified"}"""

  private def generateChainResponse(userMessage: String, systemPrompt: String): String =
    if systemPrompt.contains("intermediate") || systemPrompt.contains("typed") then
      """{"step": "processed", "result": "Analysis complete", "nextAction": "generate response"}"""
    else if systemPrompt.contains("composition") || systemPrompt.contains("multiple parsers") then
      """{"classification": "category", "extraction": {"items": ["item1", "item2"]}, "combined": true}"""
    else if systemPrompt.contains("transform") || systemPrompt.contains("staged") then
      """{"raw": "unstructured text", "normalized": "normalized text", "extracted": {"key": "value"}}"""
    else
      """{"result": "intermediate output", "status": "success"}"""

  private def generateWorkflowResponse(userMessage: String, systemPrompt: String): String =
    if systemPrompt.contains("graph") || systemPrompt.contains("completion") then
      """{"nodeType": "decision", "action": "route_to_chain_a", "reason": "Matches criteria for chain A"}"""
    else if systemPrompt.contains("async") || systemPrompt.contains("streaming") then
      """{"nodeType": "transform", "input": "original_data", "output": "transformed_data"}"""
    else
      """{"nodeType": "process", "action": "continue", "status": "pending"}"""

  private def generateToolCallResponse(userMessage: String, systemPrompt: String): String =
    if userMessage.contains("calculate") || userMessage.contains("multiply") then
      "I need to use the calculator tool to perform this calculation."
    else if userMessage.contains("search") then
      "I'll use the search tool to find relevant information."
    else if userMessage.contains("weather") then
      "Let me check the weather using the weather tool."
    else if userMessage.contains("book") || userMessage.contains("trip") then
      """I'll help you book your trip. Let me gather the details."""
    else
      "I'll help you with that task."

  private def generateGenericResponse(userMessage: String): String =
    "I understand your request and can help with that task."
