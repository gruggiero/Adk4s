# ADK4S

## Structured LLM

This repository includes a *BAML-inspired* “Structured LLM” wrapper around [LLM4s](https://github.com/llm4s/llm4s) focused on **type-safe structured outputs**.

The core idea is:
- **Define an output schema in Smithy** (human-readable and token-efficient)
- **Inject that schema into the prompt** as the required output format
- **Parse the model output into Scala types** using Schema-Aligned Parsing (SAP), a lenient JSON parser designed for real-world LLM output

For the full design document, see: [docs/structured-llm-design.md](docs/structured-llm-design.md).

### Key Features

- **Type-safe structured outputs**: LLM responses decoded into Scala data types.
- **Smithy-based schemas**: output types described in Smithy IDL (compact, documentation-friendly).
- **Schema-Aligned Parsing (SAP)**: best-effort parsing that can recover from common formatting issues (code fences, trailing commas, etc.).
- **Composable prompt templates**: Scala 3 string-interpolator based prompt/templates with structured sections.
- **Cats Effect integration**: a composable `Prompt => F[A]` style API.

### Architecture (High Level)

User code calls `StructuredLLM.complete[A](prompt)`.

`StructuredLLM`:
- Injects the Smithy schema/output format into the prompt
- Calls an underlying llm4s client (OpenAI, Anthropic, Azure, etc.)
- Parses and decodes the response using SAP

## Modules

### structured-llm

The core library providing the `StructuredLLM` abstraction, schema support, prompt/template DSL, and SAP parser.

See: [docs/structured-llm-design.md](docs/structured-llm-design.md).

### structured-llm-test-models
Contains example applications demonstrating `StructuredLLM` usage against real providers and models.

Use this module to test the application end-to-end locally by configuring a provider/model and running the example main.
- **RealLlmExample** - Demonstrates real LLM calls through the StructuredLLM wrapper using llm4s client with actual API providers

See [structured-llm-test-models/README.md](structured-llm-test-models/README.md) for detailed instructions.
