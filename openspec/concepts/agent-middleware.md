# Concept: AgentMiddleware

## Concept specification

```
concept AgentMiddleware[F[_]]
purpose
    Provide an effect-polymorphic extension point for the agent loop with
    four hooks (before-agent, after-agent, wrap-model-call, wrap-tool-call)
    and three contribution members (state cells, tools, state-aware prompt
    sections), so that middleware can declare state, contribute tools,
    inject prompt content, and intercept model/tool calls without
    modifying the loop.
state
    name: AgentMiddleware -> MiddlewareName
    stateCells: AgentMiddleware -> List[StateCell[?]]
    tools: AgentMiddleware -> List[InvokableTool[F]]
actions
    beforeAgent [ state: HarnessState ]
        => [ state: HarnessState ]
    afterAgent [ state: HarnessState ]
        => [ state: HarnessState ]
    wrapModelCall [ next: ModelStep[F] ]
        => [ step: ModelStep[F] ]
    wrapToolCall [ next: ToolStep[F] ]
        => [ step: ToolStep[F] ]
    promptSections [ state: HarnessState ]
        => [ sections: List[PromptSection] ]
operational principle
    A middleware author implements the trait, overriding only the hooks
    and contributions they need. The four hooks correspond one-to-one to
    deepagents' before_agent, after_agent, wrap_model_call, and
    wrap_tool_call. Each hook has a default no-op implementation
    (beforeAgent/afterAgent return the input state unchanged via
    Applicative[F].pure; wrapModelCall/wrapToolCall return the next step
    unchanged). The contribution members default to empty. The identity
    middleware (AgentMiddleware.id) overrides nothing and is the neutral
    element of middleware composition.
```

## Implementation map

| Element | Code |
|---|---|
| state `name` | `def name: MiddlewareName` (`adk4s-harness-api/src/main/scala/org/adk4s/harness/AgentMiddleware.scala`) |
| state `stateCells` | `def stateCells: List[StateCell[?]] = Nil` (`AgentMiddleware.scala`) |
| state `tools` | `def tools: List[InvokableTool[F]] = Nil` (`AgentMiddleware.scala`) |
| action `beforeAgent` | `def beforeAgent(state: HarnessState): F[HarnessState] = summon[Applicative[F]].pure(state)` (`AgentMiddleware.scala`) |
| action `afterAgent` | `def afterAgent(state: HarnessState): F[HarnessState] = summon[Applicative[F]].pure(state)` (`AgentMiddleware.scala`) |
| action `wrapModelCall` | `def wrapModelCall(next: ModelStep[F]): ModelStep[F] = next` (`AgentMiddleware.scala`) |
| action `wrapToolCall` | `def wrapToolCall(next: ToolStep[F]): ToolStep[F] = next` (`AgentMiddleware.scala`) |
| action `promptSections` | `def promptSections(state: HarnessState): List[PromptSection] = Nil` (`AgentMiddleware.scala`) |
| identity | `AgentMiddleware.id[F[_]: Applicative]: AgentMiddleware[F]` — name is `"identity"`, all defaults (`AgentMiddleware.scala`) |
| `ModelRequest[F]` | `final case class ModelRequest[F[_]](systemPrompt, messages, tools, options, state)` (`ModelRequest.scala`) |
| `ModelResponse` | `final case class ModelResponse(completion, state)` (`ModelRequest.scala`) |
| `ModelStep[F]` | `type ModelStep[F[_]] = Kleisli[F, ModelRequest[F], ModelResponse]` (`ModelStep.scala`) |
| `ToolCallCtx` | `final case class ToolCallCtx(input: ToolInput, state: HarnessState)` (`ToolCallCtx.scala`) |
| `ToolCallOut` | `final case class ToolCallOut(output: ToolOutput, state: HarnessState)` (`ToolCallCtx.scala`) |
| `ToolStep[F]` | `type ToolStep[F[_]] = Kleisli[F, ToolCallCtx, ToolCallOut]` (`ToolStep.scala`) |
| `ToolStep.passthrough` | `def passthrough[F[_]: Functor](ep: Kleisli[F, ToolInput, ToolOutput]): ToolStep[F]` — threads state through unchanged (`ToolStep.scala`) |
| Ring 6 mirror | `MiddlewareKernel` — PureScala model of `passthrough` state-preservation (`verified/src/main/scala/org/adk4s/verified/MiddlewareKernel.scala`) |
| runtime host | `org.adk4s.harness` |

## Deviations from the pattern

- `AgentMiddleware` requires `Applicative[F]` as a context bound, needed for the default `beforeAgent`/`afterAgent` implementations (`F.pure(state)`). The stack sequencing (fold-based hook composition) requires `Monad[F]`, but that constraint is on the `MiddlewareStack`, not on individual middlewares.
- `ToolStep.passthrough` requires `Functor[F]` for the `.map` on the endpoint's output. This is a minimal constraint — `Functor` is the weakest effect type that supports the lift.
- `SystemPrompt.render` concatenates base + section bodies with `\n` separator (no `## name` headers). The section names are preserved in the `PromptSection` values for inspection/auditing, but the rendered prompt is just the bodies.

## Synchronizations

- **AgentMiddleware ↔ HarnessState**: middleware declares `stateCells`; the loop builds `HarnessState.initial(stack.allCells)` and threads state through `ModelRequest`/`ModelResponse`/`ToolCallCtx`/`ToolCallOut`. `beforeAgent`/`afterAgent` transform the state; `wrapModelCall`/`wrapToolCall` observe and may transform the state in the response/output. (Future: `harness-agent` spec.)
- **AgentMiddleware ↔ MiddlewareStack**: middlewares compose into a `MiddlewareStack[F]` via `validated(List(m1, m2, ...))`. The stack folds hooks in order: `beforeAgent` forward, `afterAgent` reverse, `wrapModelCall`/`wrapToolCall` outermost-first (`m1(m2(base))`). (Future: `middleware-stack` spec.)
- **AgentMiddleware ↔ ChatModel**: `wrapModelCall` wraps the base model step that delegates to `ChatModel.generate`. The middleware can rewrite the `ModelRequest` (system prompt, tools, options) before delegating and transform the `ModelResponse` after. (Future: `harness-agent` spec.)
- **AgentMiddleware ↔ Tool**: `tools` contributes `InvokableTool[F]` values to the per-request tool list. `wrapToolCall` wraps the base tool step. `ToolStep.passthrough` lifts existing state-oblivious `ToolEndpoint` values into state-threading `ToolStep`s. (Future: `harness-agent` spec.)
