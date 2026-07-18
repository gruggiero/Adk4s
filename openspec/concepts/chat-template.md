# Concept: ChatTemplate

## Concept specification

```
concept ChatTemplate[F[_], V]
purpose
    Prompt templates with variable substitution, producing a Prompt or
    Conversation from a variables value.
state
    template: ChatTemplate -> PromptTemplate[V] | List[Message]
    substitution: ChatTemplate -> (String, V) => String
actions
    format [ variables: V ]
        => [ prompt: F[Prompt] ]
    formatConversation [ variables: V ]
        => [ conversation: F[Conversation] ]
    fromPromptTemplate [ template: PromptTemplate[V] ]
        => [ ChatTemplate[F, V] ]
    fromMessages [ messages ; substitution ]
        => [ ChatTemplate[F, Map[String, String]] ]
    simple [ messages ]
        => [ ChatTemplate[F, Map[String, String]] ]   # {key} placeholder substitution
operational principle
    A caller builds a ChatTemplate from a PromptTemplate or a list of
    messages with a substitution function. format applies the
    substitution to each message's content and returns a Prompt. The
    `simple` factory uses `{key}` placeholders replaced via
    `String.replace`.
```

## Implementation map

| Element | Code |
|---|---|
| trait `ChatTemplate` | `trait ChatTemplate[F[_], V]` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| action `format` | `ChatTemplate.format(variables: V): F[Prompt]` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| action `formatConversation` | `ChatTemplate.formatConversation(variables: V): F[Conversation]` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| factory `fromPromptTemplate` | `ChatTemplate.fromPromptTemplate[F, V](template)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| factory `fromMessages` | `ChatTemplate.fromMessages[F](messages, substitution)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| factory `simple` | `ChatTemplate.simple[F](messages)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| helper `substituteMessageContent` | `ChatTemplate.substituteMessageContent(msg, variables, substitution)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`) |
| runtime host | `org.adk4s.core.component` |

## Deviations from the pattern

- The `simple` factory's `{key}` substitution silently leaves unreplaced placeholders in the output — there is no missing-variable error or warning (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`).
- `substituteMessageContent` only substitutes the `content` field; `AssistantMessage.toolCalls` are preserved without substitution, so any placeholders inside tool-call arguments are silently ignored (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`).
- `ChatTemplate` depends on `Prompt` and `PromptTemplate` from `structured-llm`, coupling a core component to a higher-level module (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`).
