# Change: Add real LLM call example application

## Why
The current structured-llm-test-models module contains only mock LLM client examples. Adding a real LLM call example will demonstrate the end-to-end usage of the StructuredLLM wrapper with actual LLM providers (OpenAI, Anthropic, etc.), validating that the integration works correctly with real API calls.

## What Changes
- Add a new main application `RealLlmExample` in `structured-llm-test-models/src/main/scala/org/adk4s/structured/example/`
- Demonstrate real LLM client creation using llm4s environment variables (`LLM_MODEL`, `OPENAI_API_KEY`, etc.)
- Use existing Smithy-generated Resume model for structured output parsing
- Include error handling for real-world scenarios (authentication, rate limits, network errors)
- Provide clear documentation on required environment variables
- Show example usage with different LLM providers (OpenAI, Anthropic, Azure, OpenRouter)

## Impact
- Affected code: `structured-llm-test-models/` module only
- No breaking changes to existing APIs
- Adds new example application as entry point
- Depends on: llm4s, structured-llm modules
