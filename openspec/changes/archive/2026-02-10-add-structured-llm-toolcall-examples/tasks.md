## 1. Smithy Schema Setup

- [x] 1.1 Create examples.smithy file in structured-llm-test-models/src/main/smithy/ (using existing infrastructure)
- [x] 1.2 Define namespace org.adk4s.structured.test in Smithy file
- [x] 1.3 Add Smithy schema for CategoryClassification (category: String, confidence: Double)
- [x] 1.4 Add Smithy schema for RoleDetection (role: String, confidence: Double)
- [x] 1.5 Add Smithy schema for QueryClassification (queryType: String, intent: String)
- [x] 1.6 Add Smithy schema for ChainRoute (chainName: String, reason: String)
- [x] 1.7 Add Smithy schema for PlanExtraction (steps: List[PlanStep], totalDuration: Int)
- [x] 1.8 Add Smithy schema for PlanStep (index: Int, description: String, duration: Int)
- [x] 1.9 Add Smithy schema for StepList (items: List[StepItem])
- [x] 1.10 Add Smithy schema for StepItem (index: Int, description: String, duration: Option[Int])
- [x] 1.11 Add Smithy schema for ListParsing (items: List[ListItem])
- [x] 1.12 Add Smithy schema for ListItem (index: Int, content: String)
- [x] 1.13 Add Smithy schema for SchemaExtraction (appropriate complex structure)
- [x] 1.14 Add Smithy schema for SpecialistDelegation (specialist: String, rationale: String)
- [x] 1.15 Add Smithy schema for TypedIntermediate (appropriate chain output structure)
- [x] 1.16 Add Smithy schema for GraphCompletion (appropriate graph node output structure)
- [x] 1.17 Compile Smithy schemas and verify generated Scala code (added dependency to adk4s-examples)

## 2. Directory Structure Setup

- [x] 2.1 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/
- [x] 2.2 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/
- [x] 2.3 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/classification/
- [x] 2.4 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/extraction/
- [x] 2.5 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/multiagent/
- [x] 2.6 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/chain/
- [x] 2.7 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/workflow/
- [x] 2.8 Create adk4s-examples/src/main/scala/org/adk4s/examples/structured/toolcall/

## 3. StructuredLLM Classification Examples

- [x] 3.1 Implement CategoryClassificationStructuredExample with mock responses for math/science/history
- [x] 3.2 Implement RoleDetectionStructuredExample with mock responses for customer/support/manager roles
- [x] 3.3 Implement QueryClassificationStructuredExample with mock responses for question/command/statement
- [x] 3.4 Implement ChainRouteStructuredExample with mock chain routing decisions
- [x] 3.5 Add Schema[A] instances wrapping Smithy schemas for all classification examples
- [x] 3.6 Add environment variable detection (OPENAI_API_KEY) to all classification examples
- [x] 3.7 Test all classification examples compile and run with MockChatModel

## 4. StructuredLLM Extraction Examples

- [x] 4.1 Implement PlanExecuteStructuredExample extracting typed plan with numbered steps
- [x] 4.2 Implement StepsExtractionStructuredExample extracting step list with metadata
- [x] 4.3 Implement ListParsingStructuredExample parsing numbered/bulleted lists
- [x] 4.4 Implement SchemaExtractionStructuredExample demonstrating complex schema extraction
- [x] 4.5 Add Schema[A] instances for all extraction output types
- [x] 4.6 Add environment variable detection to all extraction examples
- [x] 4.7 Test all extraction examples compile and run with MockChatModel

## 5. StructuredLLM Multi-Agent Examples

- [x] 5.1 Implement MultiAgentHostStructuredExample with host routing to specialists
- [x] 5.2 Implement SpecialistDelegationStructuredExample with specialist selection and rationale
- [x] 5.3 Add Schema[A] instances for multi-agent coordination types
- [x] 5.4 Add environment variable detection to multi-agent examples
- [x] 5.5 Test multi-agent examples compile and run with MockChatModel

## 6. StructuredLLM Chain Examples

- [x] 6.1 Implement TypedIntermediatesStructuredExample chaining StructuredLLM calls with typed results
- [x] 6.2 Implement ChainCompositionStructuredExample composing multiple parsers
- [x] 6.3 Implement TransformChainStructuredExample with staged transformations
- [x] 6.4 Add Schema[A] instances for chain intermediate types
- [x] 6.5 Add environment variable detection to chain examples
- [x] 6.6 Test chain examples compile and run with MockChatModel

## 7. StructuredLLM Workflow Examples

- [x] 7.1 Implement GraphIntegrationStructuredExample using StructuredLLM in WIOGraph nodes
- [x] 7.2 Implement AsyncNodeStructuredExample with streaming StructuredLLM in async nodes
- [x] 7.3 Add Schema[A] instances for workflow output types
- [x] 7.4 Add environment variable detection to workflow examples
- [x] 7.5 Test workflow examples compile and run with MockChatModel
- [x] 7.6 Verify streaming example uses streamWithResult API

## 8. SAP Error Recovery Demonstrations

- [x] 8.1 Add SAPErrorRecoveryStructuredExample demonstrating markdown fence recovery
- [x] 8.2 Add mock responses with trailing commas to demonstrate SAP recovery
- [x] 8.3 Add mock responses with single quotes to demonstrate SAP recovery
- [x] 8.4 Add comments to examples explaining which SAP recovery features are demonstrated
- [x] 8.5 Test SAP recovery example with various malformed JSON inputs

## 9. StructuredToolCall ReAct Example

- [x] 9.1 Create ReactAgentStructuredExample.scala in toolcall/ directory
- [x] 9.2 Define tool input/output case classes with ToolSchema.derive
- [x] 9.3 Implement ReAct loop using StructuredToolCall.createTool for typed tools
- [x] 9.4 Add mock LLM responses returning tool calls with typed arguments
- [x] 9.5 Demonstrate ToolSchema encoder for tool results
- [x] 9.6 Add environment variable detection
- [x] 9.7 Test ReAct example compiles and executes complete loop

## 10. StructuredToolCall Dynamic Registry Example

- [x] 10.1 Create DynamicToolRegistryStructuredExample.scala in toolcall/ directory
- [x] 10.2 Define multiple tool types with ToolSchema.derive
- [x] 10.3 Implement dynamic tool registration using StructuredToolCall.createTool
- [x] 10.4 Demonstrate TypedTool[F, I, O] creation and asInvokableTool conversion
- [x] 10.5 Show tools can be added to ToolRegistry and executed
- [x] 10.6 Add environment variable detection
- [x] 10.7 Test dynamic registry example compiles and runs

## 11. StructuredToolCall WIOGraph Example

- [x] 11.1 Create WIOGraphToolStructuredExample.scala in toolcall/ directory
- [x] 11.2 Define tool types for graph node execution
- [x] 11.3 Implement WIOGraph with tool nodes using StructuredToolCall
- [x] 11.4 Demonstrate typed tool results flowing to subsequent graph nodes
- [x] 11.5 Show tool execution within graph context
- [x] 11.6 Add environment variable detection
- [x] 11.7 Test WIOGraph tool example compiles and executes graph

## 12. ToolSchemaExample Enhancement

- [x] 12.1 Read existing ToolSchemaExample to understand Scenarios 1-3
- [x] 12.2 Add new "Scenario 4: Execute with StructuredToolCall" section
- [x] 12.3 Demonstrate StructuredToolCall.createTool with BookingArgs/BookingResult
- [x] 12.4 Show typed tool execution with execute(args: BookingArgs): IO[BookingResult]
- [x] 12.5 Demonstrate asInvokableTool conversion for registry compatibility
- [x] 12.6 Verify existing Scenarios 1-3 remain unchanged
- [x] 12.7 Test enhanced ToolSchemaExample compiles and runs all 4 scenarios

## 13. Error Handling Demonstrations

- [ ] 13.1 Add error handling example to ReactAgentStructuredExample showing InvalidArguments
- [ ] 13.2 Add error handling to DynamicToolRegistryStructuredExample showing ExecutionFailed
- [ ] 13.3 Add error handling to WIOGraphToolStructuredExample showing ResultParsingFailed
- [ ] 13.4 Ensure all error examples use StructuredToolCallError sealed trait
- [ ] 13.5 Test error cases trigger appropriate error types

## 14. MockChatModel Implementation

- [x] 14.1 Create StructuredMockLLMClient extending MockChatModel
- [x] 14.2 Implement pattern matching on system prompts (contains "classifier", "planner", etc.)
- [x] 14.3 Add mock responses for all 15 StructuredLLM examples
- [x] 14.4 Add mock tool call responses for 3 StructuredToolCall examples
- [x] 14.5 Ensure all mock responses are schema-compliant JSON
- [x] 14.6 Add mock responses demonstrating SAP error recovery (fences, commas, quotes)
- [x] 14.7 Test StructuredMockChatModel returns appropriate response for each example

## 15. README Documentation

- [x] 15.1 Add "Structured Examples" top-level section to adk4s-examples/README.md
- [x] 15.2 Add "Structured LLM (Type-Safe Response Parsing)" subsection
- [x] 15.3 Document all 15 StructuredLLM examples organized by pattern (classification, extraction, etc.)
- [x] 15.4 Add "Structured ToolCall (Type-Safe Tool Execution)" subsection
- [x] 15.5 Document all 3 StructuredToolCall examples plus enhanced ToolSchemaExample
- [x] 15.6 Add explanation of StructuredToolCall.createTool and TypedTool API
- [x] 15.7 Add cross-references to original manual-parsing examples where applicable
- [x] 15.8 Add "Schema Design Best Practices" section
- [x] 15.9 Document environment variable usage (OPENAI_API_KEY, LLM_MODEL, OPENAI_BASE_URL)
- [x] 15.10 Add SAP error recovery capabilities section

## 16. run-example.sh Updates

- [x] 16.1 Add "Structured LLM Examples" section comment in run-example.sh
- [x] 16.2 Add entry for CategoryClassificationStructuredExample
- [x] 16.3 Add entry for RoleDetectionStructuredExample
- [x] 16.4 Add entry for QueryClassificationStructuredExample
- [x] 16.5 Add entry for ChainRouteStructuredExample
- [x] 16.6 Add entry for PlanExecuteStructuredExample
- [x] 16.7 Add entry for StepsExtractionStructuredExample
- [x] 16.8 Add entry for ListParsingStructuredExample
- [x] 16.9 Add entry for SchemaExtractionStructuredExample
- [x] 16.10 Add entry for MultiAgentHostStructuredExample
- [x] 16.11 Add entry for SpecialistDelegationStructuredExample
- [x] 16.12 Add entry for TypedIntermediatesStructuredExample
- [x] 16.13 Add entry for ChainCompositionStructuredExample
- [x] 16.14 Add entry for TransformChainStructuredExample
- [x] 16.15 Add entry for GraphIntegrationStructuredExample
- [x] 16.16 Add entry for AsyncNodeStructuredExample
- [x] 16.17 Add entry for SAPErrorRecoveryStructuredExample
- [x] 16.18 Add "Structured ToolCall Examples" section comment
- [x] 16.19 Add entry for ReactAgentStructuredExample
- [x] 16.20 Add entry for DynamicToolRegistryStructuredExample
- [x] 16.21 Add entry for WIOGraphToolStructuredExample
- [x] 16.22 Update entry for ToolSchemaExample noting enhanced version
- [x] 16.23 Test run-example.sh --list shows all new examples
- [x] 16.24 Test run-example.sh can execute each new example by name

## 17. Integration Testing

- [x] 17.1 Run sbt "adk4s-examples/compile" to verify all examples compile
- [x] 17.2 Test each StructuredLLM classification example runs without errors
- [x] 17.3 Test each StructuredLLM extraction example runs without errors
- [x] 17.4 Test each StructuredLLM multi-agent example runs without errors
- [x] 17.5 Test each StructuredLLM chain example runs without errors
- [x] 17.6 Test each StructuredLLM workflow example runs without errors
- [x] 17.7 Test SAP error recovery example runs without errors
- [x] 17.8 Test each StructuredToolCall example runs without errors
- [x] 17.9 Test enhanced ToolSchemaExample runs all 4 scenarios
- [x] 17.10 Verify all examples print expected output with MockChatModel

## 18. Real LLM Testing (Optional)

- [ ] 18.1 Set OPENAI_API_KEY environment variable for testing
- [ ] 18.2 Test 2-3 StructuredLLM examples with real LLM verify parsing works
- [ ] 18.3 Test 1 StructuredToolCall example with real LLM verify tool execution works
- [ ] 18.4 Verify examples print "[Using real LLM: model-name]" message
- [ ] 18.5 Verify SAP recovery works with actual LLM responses (may have malformed JSON)

## 19. Final Verification

- [x] 19.1 Verify all 19 example files exist in correct directories
- [x] 19.2 Verify examples.smithy compiles and generates expected types
- [x] 19.3 Verify README.md has complete structured examples documentation
- [x] 19.4 Verify run-example.sh includes all 19 new examples
- [x] 19.5 Verify no existing examples were modified (except ToolSchemaExample enhancement)
- [ ] 19.6 Run full test suite: sbt "adk4s-examples/test"
- [x] 19.7 Verify no compilation warnings in new example code
- [x] 19.8 Review all examples follow functional programming conventions
- [x] 19.9 Verify all examples use IO effects properly
- [x] 19.10 Final smoke test: run 3-4 examples end-to-end via run-example.sh
