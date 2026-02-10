# Change: Add Streaming Integration

## Why
ADK4S needs streaming infrastructure to bridge LLM4S Iterator-based streaming with fs2.Stream for functional composition, resource safety, and backpressure handling. This enables real-time streaming responses and efficient concurrent operations.

## What Changes
- Add StreamConverter for Iterator to fs2.Stream conversion
- Add ChunkAccumulator for accumulating streaming chunks into complete responses
- Add MessageStream for stream operations matching Eino's Box, Concatenate, Merge, Copy
- Add StreamingLLM wrapper for fs2.Stream-based LLM client interface
- Add StreamOps for timeout, retry, buffering, and rate limiting utilities
- Add streaming package object with exports and type aliases

## Impact
- New capabilities: streaming
- Affected code: adk4s-core module (new streaming package)
- New dependencies: fs2-core 3.9.x, fs2-io 3.9.x
- Breaking changes: None (new functionality)
