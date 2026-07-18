# Concept: Prompt

## Concept specification

```
concept Prompt
purpose
    An immutable conversation ready to send to an LLM, wrapping a llm4s
    Conversation and supporting schema injection into the last user
    message.
state
    conversation: Prompt -> Conversation
actions
    addMessage [ message: Message ]
        => [ prompt: Prompt ]
    addSystemMessage [ content: String ]
        => [ prompt: Prompt ]
    addUserMessage [ content: String ]
        => [ prompt: Prompt ]
    addAssistantMessage [ content: String ]
        => [ prompt: Prompt ]
    appendToLast [ content: String ]
        => [ prompt: Prompt ]   # appends to last UserMessage, or adds new user message
    withOutputFormat [ A: Schema ]
        => [ prompt: Prompt ]   # appends Schema[A].outputFormatBlock to last user message
    ++ [ other: Prompt ]
        => [ prompt: Prompt ]   # concatenates conversations
operational principle
    A caller builds a Prompt via the factories (empty, user, system, simple)
    and add* methods. withOutputFormat appends the Smithy IDL block to the
    last user message, producing a prompt that instructs the LLM to reply
    in the schema's shape.
```

## Implementation map

| Element | Code |
|---|---|
| class `Prompt` | `final case class Prompt(conversation: Conversation)` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `addMessage` | `Prompt.addMessage(message: Message): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `addSystemMessage` | `Prompt.addSystemMessage(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `addUserMessage` | `Prompt.addUserMessage(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `addAssistantMessage` | `Prompt.addAssistantMessage(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `appendToLast` | `Prompt.appendToLast(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `withOutputFormat` | `Prompt.withOutputFormat[A: Schema]: Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| action `++` | `Prompt.++(other: Prompt): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| factory `empty` | `Prompt.empty: Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| factory `user` | `Prompt.user(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| factory `system` | `Prompt.system(content): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| factory `simple` | `Prompt.simple(systemPrompt, userMessage): Prompt` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| type `PromptTemplate` | `trait PromptTemplate[-I]` (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`) |
| runtime host | `org.adk4s.structured.core` |

## Deviations from the pattern

- `withOutputFormat[A: Schema]` directly summons `Schema[A]` and calls `outputFormatBlock`, tightly coupling Prompt to the Schema typeclass — Prompt cannot be schema-agnostic (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`).
- `appendToLast` has three branches (last is `UserMessage` → append; otherwise → add new user message; empty → add new user message) — the behavior depends on conversation state in a non-obvious way (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`).
- `Prompt` is a thin wrapper over `Conversation`; all message-type knowledge lives in llm4s, so Prompt's behavior is coupled to llm4s message variants (`structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`).
