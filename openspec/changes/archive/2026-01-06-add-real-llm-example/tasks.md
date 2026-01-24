## 1. Implementation
- [x] 1.1 Create `RealLlmExample.scala` in `structured-llm-test-models/src/main/scala/org/adk4s/structured/example/`
- [x] 1.2 Implement real LLM client creation using `LLM.client()` from llm4s
- [x] 1.3 Create Schema instance using generated Resume model and Smithy definition
- [x] 1.4 Implement PromptTemplate for resume extraction
- [x] 1.5 Add error handling for LLMError types (authentication, rate limits, network errors)
- [x] 1.6 Add environment variable validation at startup
- [x] 1.7 Add logging for request/response cycle
- [x] 1.8 Update sbt configuration to allow running the example as `sbt "project structured-llm-test-models" "runMain org.adk4s.structured.example.RealLlmExample"`

## 2. Documentation
- [x] 2.1 Add README in `structured-llm-test-models/` explaining how to run the example
- [x] 2.2 Document required environment variables with examples
- [x] 2.3 Add inline code comments explaining each step
- [x] 2.4 Update project README with link to example

## 3. Testing
- [ ] 3.1 Manual test with OpenAI provider
- [ ] 3.2 Manual test with Anthropic provider (if available)
- [ ] 3.3 Verify error handling with missing API key
- [ ] 3.4 Verify error handling with invalid API key
- [ ] 3.5 Verify successful resume parsing
