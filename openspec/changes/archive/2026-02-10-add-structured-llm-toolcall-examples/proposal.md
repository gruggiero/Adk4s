## Why

The existing adk4s-examples demonstrate core framework capabilities but lack examples showing how to leverage StructuredLLM and StructuredToolCall for type-safe, schema-validated LLM interactions. Currently, 10 examples manually parse LLM text responses using string matching and regex, 5 examples could benefit from structured outputs but don't currently parse responses, and 3 examples manually parse tool arguments using ujson. Additionally, the existing ToolSchemaExample demonstrates schema inference but lacks complete StructuredToolCall execution patterns. These examples should showcase best practices using the structured-llm library and StructuredToolCall for type-safe parsing, automatic schema validation, and error recovery.

## What Changes

- Add new example suite in `adk4s-examples/src/main/scala/org/adk4s/examples/structured/`
- Create 19 new examples demonstrating StructuredLLM and StructuredToolCall patterns
- Add corresponding Smithy schema definitions for structured output types
- Update examples README with new structured examples section
- Add runner script entries for all new examples
- Enhance existing ToolSchemaExample to demonstrate complete StructuredToolCall execution patterns
- Preserve all other existing examples unchanged (no modifications to current examples)

New examples to be added:
- **StructuredLLM examples (15)**:
  - 10 excellent candidates: Classification, plan parsing, multi-agent routing, role detection, chain routing, tool call simulation
  - 5 good candidates: Workflow completion parsing, async transformations, agent response structuring
- **StructuredToolCall examples (4)**:
  - 3 new examples: Type-safe ReAct agent tools, dynamic tool registry with typed tools, WIOGraph tool integration
  - 1 enhancement: Extend ToolSchemaExample with complete StructuredToolCall execution patterns

## Capabilities

### New Capabilities

- `structured-llm-examples`: Examples demonstrating StructuredLLM for type-safe LLM response parsing with schema validation, covering:
  - Classification patterns (category routing, role detection, query classification)
  - Extraction patterns (plan parsing, step extraction, numbered list parsing)
  - Multi-agent routing (host/specialist delegation)
  - Chain composition (structured intermediate outputs)
  - Workflow integration (typed completions in graphs and workflows)
- `structured-toolcall-examples`: Examples demonstrating StructuredToolCall for type-safe tool argument parsing and result decoding, covering:
  - ReAct agent patterns with typed tool arguments and results
  - Dynamic tool registry with compile-time type safety
  - WIOGraph tool integration with structured execution
  - Complete tool lifecycle: schema inference → typed execution → result decoding

### Modified Capabilities

None - this change only adds new examples without modifying existing functionality.

## Impact

**New Files:**
- `adk4s-examples/src/main/scala/org/adk4s/examples/structured/llm/` (15 example files)
- `adk4s-examples/src/main/scala/org/adk4s/examples/structured/toolcall/` (3 new example files)
- `adk4s-examples/src/main/scala/org/adk4s/examples/structured/schemas/` (Smithy schema definitions for all examples)
- Updates to `adk4s-examples/README.md` (new structured examples section)
- Updates to `adk4s-examples/run-example.sh` (19 new example entries)

**Modified Files:**
- `adk4s-examples/src/main/scala/org/adk4s/examples/eino/components/ToolSchemaExample.scala` (enhanced to show StructuredToolCall execution)

**Dependencies:**
- Requires `structured-llm` module (already exists)
- Requires `adk4s-core` for StructuredToolCall (already exists)
- No new external dependencies

**Build System:**
- No changes to build.sbt required
- Examples follow existing project structure and conventions

**Documentation:**
- Provides concrete examples for users migrating from manual parsing to structured approaches
- Demonstrates Schema[A] typeclass usage patterns with Smithy IDL definitions
- Shows StructuredToolCall integration with existing tool infrastructure
- Illustrates SAP (Schema-Aligned Parser) error recovery capabilities
- Covers progression from excellent candidates (clear parsing needs) to good candidates (adding structure to unstructured outputs)
- Documents best practices for schema design, prompt engineering for structured outputs, and error handling
