## ADDED Requirements

### Requirement: Real LLM Integration Example
The system SHALL provide an example application demonstrating real LLM calls through the StructuredLLM wrapper using llm4s client with actual API providers.

#### Scenario: Successful resume extraction with OpenAI
- **GIVEN** OPENAI_API_KEY environment variable is set
- **GIVEN** LLM_MODEL is set to "openai/gpt-4o"
- **WHEN** RealLlmExample is executed with sample resume text
- **THEN** Application successfully creates LLM client from environment
- **AND** Sends request to OpenAI API with Smithy schema
- **AND** Parses response into Resume type
- **AND** Displays extracted resume information

#### Scenario: Error handling for missing API key
- **GIVEN** OPENAI_API_KEY environment variable is not set
- **WHEN** RealLlmExample is executed
- **THEN** Application fails at startup with clear error message
- **AND** Error message indicates required environment variable

#### Scenario: Error handling for invalid API key
- **GIVEN** OPENAI_API_KEY is set to invalid value
- **WHEN** RealLlmExample is executed
- **THEN** Application catches LLMError.AuthenticationError
- **AND** Displays formatted error with recovery guidance

#### Scenario: Multiple provider support
- **GIVEN** LLM_MODEL is set to "anthropic/claude-3-7-sonnet-latest"
- **GIVEN** ANTHROPIC_API_KEY is set
- **WHEN** RealLlmExample is executed
- **THEN** Application creates correct provider client
- **AND** Successfully completes resume extraction
