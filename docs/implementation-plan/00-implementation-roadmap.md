# ADK4S Implementation Roadmap

## Overview

This document outlines the implementation plan for ADK4S (Agent Development Kit for Scala 3), inspired by the Eino framework from CloudWeGo. The implementation leverages three key dependencies:

- **LLM4S** - LLM client abstraction, tool calling, and agent orchestration
- **Workflows4s** - Event-sourced workflow orchestration with WIO monads
- **structured-llm** - Type-safe structured outputs with Smithy schemas (already implemented)

## Feature Dependency Graph

```
                                    Phase 1: Foundation
                    ┌─────────────────────────────────────────┐
                    │                                         │
                    │  ┌─────────────┐    ┌──────────────┐   │
                    │  │ Core Types  │    │   Streaming  │   │
                    │  │ (01)        │    │ Integration  │   │
                    │  │             │    │ (02)         │   │
                    │  └──────┬──────┘    └──────┬───────┘   │
                    │         │                  │           │
                    └─────────┼──────────────────┼───────────┘
                              │                  │
                              ▼                  ▼
                    ┌─────────────────────────────────────────┐
                    │           Phase 2: Components           │
                    │                                         │
                    │  ┌─────────────┐    ┌──────────────┐   │
                    │  │ Component   │    │   Lambda &   │   │
                    │  │ Abstractions│───▶│   Runnable   │   │
                    │  │ (03)        │    │   (04)       │   │
                    │  └──────┬──────┘    └──────┬───────┘   │
                    │         │                  │           │
                    │         ▼                  │           │
                    │  ┌─────────────┐           │           │
                    │  │    Tools    │◀──────────┘           │
                    │  │    Node     │                       │
                    │  │    (05)     │                       │
                    │  └──────┬──────┘                       │
                    │         │                              │
                    └─────────┼──────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────────────────────────────┐
                    │         Phase 3: Orchestration          │
                    │                                         │
                    │  ┌─────────────┐    ┌──────────────┐   │
                    │  │    State    │    │   Branching  │   │
                    │  │ Management  │───▶│  & Routing   │   │
                    │  │ (06)        │    │  (07)        │   │
                    │  └──────┬──────┘    └──────┬───────┘   │
                    │         │                  │           │
                    │         ▼                  ▼           │
                    │  ┌─────────────────────────────────┐   │
                    │  │      Graph/Chain/Workflow       │   │
                    │  │          Builders (08)          │   │
                    │  └──────────────┬──────────────────┘   │
                    │                 │                      │
                    └─────────────────┼──────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────────┐
                    │         Phase 4: Agent Patterns         │
                    │                                         │
                    │  ┌─────────────┐    ┌──────────────┐   │
                    │  │   ReAct     │    │ Multi-Agent  │   │
                    │  │   Agent     │───▶│ Coordination │   │
                    │  │   (09)      │    │ (10)         │   │
                    │  └─────────────┘    └──────────────┘   │
                    │                                         │
                    └─────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────────┐
                    │        Phase 5: Advanced Features       │
                    │                                         │
                    │  ┌─────────────┐    ┌──────────────┐   │
                    │  │ Callbacks & │    │ Checkpointing│   │
                    │  │Observability│    │  & Resume    │   │
                    │  │ (11)        │    │  (12)        │   │
                    │  └─────────────┘    └──────────────┘   │
                    │                                         │
                    └─────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Foundation (Documents 01-02)
**Goal**: Establish core type system and streaming infrastructure

| Feature | Document | Prerequisites | Primary Dependency |
|---------|----------|---------------|-------------------|
| Core Types & Schema System | 01 | None | structured-llm (exists) |
| Streaming Integration | 02 | Core Types | fs2, LLM4S |

**Deliverables**:
- ADK4S core type definitions aligned with LLM4S
- fs2.Stream integration utilities
- Stream conversion helpers for LLM4S Iterator types

### Phase 2: Components (Documents 03-05)
**Goal**: Define component abstractions and tool execution

| Feature | Document | Prerequisites | Primary Dependency |
|---------|----------|---------------|-------------------|
| Component Abstractions | 03 | Core Types | LLM4S, structured-llm |
| Lambda & Runnable | 04 | Components, Streaming | Cats Effect |
| Tools Node | 05 | Lambda, Components | LLM4S ToolRegistry |

**Deliverables**:
- ChatModel/Tool/Retriever trait hierarchy
- Lambda ADT with automatic paradigm derivation
- Runnable trait with 4 paradigms (invoke/stream/collect/transform)
- ToolsNode for executing tool calls

### Phase 3: Orchestration (Documents 06-08)
**Goal**: Build graph-based workflow composition

| Feature | Document | Prerequisites | Primary Dependency |
|---------|----------|---------------|-------------------|
| State Management | 06 | Core Types | Workflows4s, Cats Effect Ref |
| Branching & Routing | 07 | State | Workflows4s WIO.fork |
| Graph/Chain/Workflow | 08 | All Phase 2-3 | Workflows4s WIO |

**Deliverables**:
- Event-sourced state management via Workflows4s
- Branching patterns (InvokeBranch, StreamBranch)
- Graph builder with type-safe edges
- Chain builder for linear flows
- Workflow builder with field mapping

### Phase 4: Agent Patterns (Documents 09-10)
**Goal**: Implement high-level agent patterns

| Feature | Document | Prerequisites | Primary Dependency |
|---------|----------|---------------|-------------------|
| ReAct Agent | 09 | Orchestration, Tools | LLM4S Agent |
| Multi-Agent Coordination | 10 | ReAct Agent | Workflows4s WIO |

**Deliverables**:
- ADK4S ReAct agent wrapper
- Multi-agent composition patterns
- Agent handoff protocols
- Supervisor/worker patterns

### Phase 5: Advanced Features (Documents 11-12)
**Goal**: Production-ready observability and durability

| Feature | Document | Prerequisites | Primary Dependency |
|---------|----------|---------------|-------------------|
| Callbacks & Observability | 11 | All previous | Cats Effect, natchez |
| Checkpointing & Resume | 12 | Orchestration | Workflows4s events |

**Deliverables**:
- CallbackHandler trait and builder
- Global/local handler registration
- Integration with OpenTelemetry/natchez
- Checkpoint serialization
- Resume from checkpoint

## Module Structure (Target)

```
adk4s/
├── structured-llm/              # EXISTS - Type-safe structured outputs
│   ├── core/                    # StructuredLLM, Prompt, Schema
│   ├── sap/                     # Schema-Aligned Parser
│   └── template/                # Prompt templates
│
├── adk4s-core/                  # NEW - Core abstractions
│   ├── types/                   # Core type definitions
│   ├── component/               # Component abstractions
│   ├── lambda/                  # Lambda and Runnable
│   └── streaming/               # fs2 integration
│
├── adk4s-orchestration/         # NEW - Workflow orchestration
│   ├── state/                   # State management
│   ├── graph/                   # Graph builder
│   ├── chain/                   # Chain builder
│   ├── workflow/                # Workflow with field mapping
│   └── branch/                  # Branching patterns
│
├── adk4s-agent/                 # NEW - Agent patterns
│   ├── react/                   # ReAct agent
│   ├── multi/                   # Multi-agent patterns
│   └── tools/                   # ToolsNode
│
├── adk4s-observability/         # NEW - Callbacks and tracing
│   ├── callback/                # CallbackHandler
│   ├── tracing/                 # Distributed tracing
│   └── metrics/                 # Metrics collection
│
└── adk4s-persistence/           # NEW - Checkpointing
    ├── checkpoint/              # Checkpoint management
    └── resume/                  # Resume logic
```

## Dependency Mapping (Eino to ADK4S)

| Eino Concept | ADK4S Implementation | Dependency |
|--------------|---------------------|------------|
| `BaseChatModel` | Use `LLMClient` directly | LLM4S |
| `ChatModel.BindTools` | `CompletionOptions(tools = ...)` | LLM4S |
| `ToolsNode` | ADK4S `ToolsNode` wrapping `ToolRegistry` | LLM4S |
| `Graph[I, O]` | ADK4S `Graph[I, O]` using `WIO` | Workflows4s |
| `Chain[I, O]` | ADK4S `Chain[I, O]` using `WIO.flatMap` | Workflows4s |
| `Workflow[I, O]` | ADK4S `Workflow[I, O]` with Smithy mapping | Workflows4s |
| `StreamReader[T]` | `fs2.Stream[F, T]` | fs2 |
| `Runnable.Invoke/Stream/Collect/Transform` | ADK4S `Runnable` trait | Cats Effect |
| `Lambda` | ADK4S `Lambda` ADT | Native Scala 3 |
| `GraphBranch` | ADK4S `Branch` + `WIO.fork` | Workflows4s |
| `ProcessState` | `Ref[F, S]` or Workflows4s events | Cats Effect / Workflows4s |
| `Handler/Callbacks` | ADK4S `CallbackHandler` | Cats Effect |
| `ReAct Agent` | Wrap LLM4S `Agent` | LLM4S |
| `ChatTemplate` | `PromptTemplate[I]` | structured-llm |
| `Schema (output)` | `Schema[A]` typeclass | structured-llm |

## Document Index

| # | Document | Phase | Status |
|---|----------|-------|--------|
| 00 | Implementation Roadmap | Overview | Complete |
| 01 | Core Types & Schema System | 1 | Pending |
| 02 | Streaming Integration | 1 | Pending |
| 03 | Component Abstractions | 2 | Pending |
| 04 | Lambda & Runnable | 2 | Pending |
| 05 | Tools Node | 2 | Pending |
| 06 | State Management | 3 | Pending |
| 07 | Branching & Routing | 3 | Pending |
| 08 | Graph/Chain/Workflow | 3 | Pending |
| 09 | ReAct Agent | 4 | Pending |
| 10 | Multi-Agent Coordination | 4 | Pending |
| 11 | Callbacks & Observability | 5 | Pending |
| 12 | Checkpointing & Resume | 5 | Pending |

## Implementation Guidelines

### General Principles

1. **Type Safety First**: All types should be checked at compile time where possible
2. **Immutability**: All data structures must be immutable
3. **Effect System**: All side effects wrapped in `IO[A]` (Cats Effect)
4. **Functional Composition**: Prefer composition over inheritance
5. **Smithy Integration**: Use Smithy for schema definitions where applicable

### Code Conventions

- Use opaque types for type-safe wrappers
- Use sealed trait hierarchies for ADTs
- Use extension methods for typeclass syntax
- Use ValidatedNec for error accumulation
- Use fs2.Stream for all streaming operations

### Testing Strategy

- Unit tests with mock LLM clients
- Integration tests with real LLM (optional, via env vars)
- Property-based tests for parsers
- Workflow tests using Workflows4s test utilities

### Documentation Requirements

Each implementation document should include:
1. **Prerequisites** - What must be implemented first
2. **Dependencies** - Which libraries/modules are needed
3. **API Design** - Trait/class definitions
4. **Implementation Tasks** - Detailed task list
5. **Testing Plan** - How to verify correctness
6. **Examples** - Usage examples

## Getting Started

To begin implementation:

1. Read this roadmap document
2. Ensure LLM4S and Workflows4s are available locally
3. Start with Phase 1, Document 01 (Core Types)
4. Follow prerequisites strictly - do not skip ahead
5. Complete each phase before moving to the next
