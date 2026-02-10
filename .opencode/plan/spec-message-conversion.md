# Spec: message-conversion

## Purpose

Provides bidirectional conversion between structured-llm message types (Message, Role, Prompt) and LLM4S message types (Message subtypes, Conversation). This enables ADK4S to leverage existing structured-llm abstractions while communicating with LLM4S clients.

## Requirements

### Requirement: Message Converter

The system SHALL provide a MessageConverter object with bidirectional conversion methods between `org.adk4s.structured.core.Message` and `org.llm4s.llmconnect.model.Message`.

#### Scenario: Convert User role message to LLM4S
- **GIVEN** an ADK4S Message with Role.User
- **WHEN** MessageConverter.toLlm4s is called
- **THEN** a LLM4S UserMessage is returned
- **AND** the content is preserved

#### Scenario: Convert System role message to LLM4S
- **GIVEN** an ADK4S Message with Role.System
- **WHEN** MessageConverter.toLlm4s is called
- **THEN** a LLM4S SystemMessage is returned
- **AND** the content is preserved

#### Scenario: Convert Assistant role message to LLM4S
- **GIVEN** an ADK4S Message with Role.Assistant
- **WHEN** MessageConverter.toLlm4s is called
- **THEN** a LLM4S AssistantMessage is returned
- **AND** the content is Some(value) matching the ADK message content
- **AND** toolCalls is empty List

#### Scenario: Convert Tool role message to LLM4S
- **GIVEN** an ADK4S Message with Role.Tool
- **WHEN** MessageConverter.toLlm4s is called
- **THEN** a LLM4S ToolMessage is returned
- **AND** the content is preserved
- **AND** toolCallId is embedded in the message content (parsing required)

#### Scenario: Convert LLM4S UserMessage to ADK4S
- **GIVEN** a LLM4S UserMessage with content
- **WHEN** MessageConverter.fromLlm4s is called
- **THEN** an ADK4S Message with Role.User is returned
- **AND** the content is preserved

#### Scenario: Convert LLM4S SystemMessage to ADK4S
- **GIVEN** a LLM4S SystemMessage with content
- **WHEN** MessageConverter.fromLlm4s is called
- **THEN** an ADK4S Message with Role.System is returned
- **AND** the content is preserved

#### Scenario: Convert LLM4S AssistantMessage to ADK4S
- **GIVEN** a LLM4S AssistantMessage with Some(content) and empty toolCalls
- **WHEN** MessageConverter.fromLlm4s is called
- **THEN** an ADK4S Message with Role.Assistant is returned
- **AND** the content matches the LLM4S content

#### Scenario: Round-trip conversion preserves User message
- **GIVEN** an ADK4S Message with Role.User and content "Hello"
- **WHEN** converted to LLM4S and back to ADK4S
- **THEN** the final ADK4S message has Role.User
- **AND** the content is "Hello"

#### Scenario: Round-trip conversion preserves System message
- **GIVEN** an ADK4S Message with Role.System and content "You are helpful"
- **WHEN** converted to LLM4S and back to ADK4S
- **THEN** the final ADK4S message has Role.System
- **AND** the content is "You are helpful"

#### Scenario: Round-trip conversion preserves Assistant message
- **GIVEN** an ADK4S Message with Role.Assistant and content "Response"
- **WHEN** converted to LLM4S and back to ADK4S
- **THEN** the final ADK4S message has Role.Assistant
- **AND** the content is "Response"

### Requirement: Extension Methods for Message Conversion

The system SHALL provide extension methods for convenient message conversion.

#### Scenario: Use asLlm4s extension method
- **GIVEN** an ADK4S Message
- **WHEN** the asLlm4s extension method is called
- **THEN** the result equals MessageConverter.toLlm4s(message)

#### Scenario: Use asAdk extension method
- **GIVEN** a LLM4S Message
- **WHEN** the asAdk extension method is called
- **THEN** the result equals MessageConverter.fromLlm4s(message)

### Requirement: Conversation Converter

The system SHALL provide a ConversationConverter object with bidirectional conversion methods between `org.adk4s.structured.core.Prompt` and `org.llm4s.llmconnect.model.Conversation`.

#### Scenario: Convert empty Prompt to Conversation
- **GIVEN** an ADK4S Prompt with empty messages Vector
- **WHEN** ConversationConverter.toConversation is called
- **THEN** a LLM4S Conversation with empty messages Vector is returned

#### Scenario: Convert single-message Prompt to Conversation
- **GIVEN** an ADK4S Prompt with one Message
- **WHEN** ConversationConverter.toConversation is called
- **THEN** a LLM4S Conversation with one Message is returned
- **AND** the message is correctly converted using MessageConverter

#### Scenario: Convert multi-message Prompt to Conversation
- **GIVEN** an ADK4S Prompt with three Messages (System, User, Assistant)
- **WHEN** ConversationConverter.toConversation is called
- **THEN** a LLM4S Conversation with three Messages is returned
- **AND** the message order is preserved (System, User, Assistant)
- **AND** each message is correctly converted

#### Scenario: Convert empty Conversation to Prompt
- **GIVEN** a LLM4S Conversation with empty messages Vector
- **WHEN** ConversationConverter.fromConversation is called
- **THEN** an ADK4S Prompt with empty messages Vector is returned

#### Scenario: Convert single-message Conversation to Prompt
- **GIVEN** a LLM4S Conversation with one Message
- **WHEN** ConversationConverter.fromConversation is called
- **THEN** an ADK4S Prompt with one Message is returned
- **AND** the message is correctly converted using MessageConverter

#### Scenario: Convert multi-message Conversation to Prompt
- **GIVEN** a LLM4S Conversation with three Messages (UserMessage, AssistantMessage, UserMessage)
- **WHEN** ConversationConverter.fromConversation is called
- **THEN** an ADK4S Prompt with three Messages is returned
- **AND** the message order is preserved
- **AND** each message is correctly converted

#### Scenario: Round-trip conversion preserves empty Prompt
- **GIVEN** an ADK4S Prompt with empty messages
- **WHEN** converted to Conversation and back to Prompt
- **THEN** the final Prompt has empty messages

#### Scenario: Round-trip conversion preserves multi-message Prompt
- **GIVEN** an ADK4S Prompt with messages [System, User, Assistant, User]
- **WHEN** converted to Conversation and back to Prompt
- **THEN** the final Prompt has four messages
- **AND** the order is [System, User, Assistant, User]
- **AND** each message content is preserved

### Requirement: Extension Methods for Conversation Conversion

The system SHALL provide extension methods for convenient conversation conversion.

#### Scenario: Use asConversation extension method
- **GIVEN** an ADK4S Prompt
- **WHEN** the asConversation extension method is called
- **THEN** the result equals ConversationConverter.toConversation(prompt)

#### Scenario: Use asPrompt extension method
- **GIVEN** a LLM4S Conversation
- **WHEN** the asPrompt extension method is called
- **THEN** the result equals ConversationConverter.fromConversation(conv)
