# Change: Add State Management for Orchestration

## Why
ADK4S needs a state management system to support both lightweight in-memory state for simple workflows and durable event-sourced state for production use cases with Workflows4s integration.

## What Changes
- Add StateRef wrapper for Cats Effect Ref-based state management
- Add pre/post handler types for state modification during node execution
- Add Workflows4s integration with AdkWorkflowContext and AgentStateContext
- Add StatefulNode wrapper for state-aware Runnable instances
- Provide utilities for common state operations (accumulate, storeOutput, etc.)

## Impact
- New capability: `state-management`
- Affected code: `adk4s-orchestration` module
- Dependencies: Cats Effect 3.6.3, Workflows4s (local)
