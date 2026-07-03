# Spec: Tool Abstraction Deduplication

<!-- This is a DELTA spec. It collapses ToolWrapper's dual storage and
     fixes the silent-drop bug for StructuredToolFunction-derived tools. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ToolWrapper` | case class | `org.adk4s.core.tools` |
| `ToolFunctionAdapter[T, R]` | opaque type | `org.adk4s.core.tools` |
| `SafeToolExecutable` | trait | `org.adk4s.core.tools` |
| `ToolFunction[T, R]` (llm4s) | trait | `org.llm4s.toolapi` |
| `ToolRegistry` (llm4s) | case class | `org.llm4s.toolapi` |
| `StructuredToolFunction[I, O]` | case class | `org.adk4s.core.tools` |
| `ToolSchema[A]` | opaque type | `org.adk4s.core.tools` |
| `ToolSchemaError` | sealed trait | `org.adk4s.core.tools` |
| `ToolsNodeConfig` | case class | `org.adk4s.core.tools` |
| `ToolsNodeConfigBuilder` | case class | `org.adk4s.core.tools` |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` |
| `ToolCallError` (llm4s) | sealed trait | `org.llm4s.toolapi` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ToolWrapper(toolFunction: ToolFunction[?, ?])` | case class (refactored) | `ToolWrapper` now stores a single `toolFunction` field; `originalToolFunction` and `executable` are removed. The executable is derived via `ToolFunctionAdapter` |
| `StructuredToolFunction.toToolFunction` | method | Synthesizes a `ToolFunction[Any, Any]` from the `inputSchema`/`outputSchema`/`handler` so `StructuredToolFunction`-derived tools appear in `ToolRegistry` |

## ADDED Requirements

### Requirement: ToolWrapper stores single ToolFunction

The system SHALL refactor `ToolWrapper` to store a single `toolFunction: ToolFunction[?, ?]` field, removing the `originalToolFunction: Option[ToolFunction[?, ?]]` and `executable: SafeToolExecutable` fields. The executable SHALL be derived via `ToolFunctionAdapter` on demand.

**Given** a `ToolWrapper` created from a `ToolFunction[T, R]`
**When** `wrapper.toolFunction` is accessed
**Then** it returns the original `ToolFunction[T, R]`

**Rationale**: The dual storage (`originalToolFunction` + `executable`) exists because the reverse cast (`SafeToolExecutable` → `ToolFunction`) fails at runtime. By storing only `ToolFunction` and deriving `executable` forward, the workaround is eliminated.

#### Scenario: ToolWrapper from ToolFunction

**Given** a `ToolFunction[Request, Response]` with name "get-weather"
**When** `ToolWrapper(tf)` is called
**Then** `wrapper.toolFunction.name == "get-weather"` and `wrapper.execute(args)` delegates to the derived `SafeToolExecutable`

#### Scenario: ToolWrapper from StructuredToolFunction

**Given** a `StructuredToolFunction[AddRequest, AddResult]` with name "add"
**When** `stf.toToolWrapper` is called
**Then** `wrapper.toolFunction.name == "add"` and `wrapper.toolFunction` is a synthesized `ToolFunction` (not `None`)

### Requirement: StructuredToolFunction synthesizes ToolFunction

The system SHALL provide `StructuredToolFunction.toToolFunction` that synthesizes a `ToolFunction[Any, Any]` from the `inputSchema`, `outputSchema`, and `handler`, so that `StructuredToolFunction`-derived tools are visible in `ToolRegistry`.

**Given** a `StructuredToolFunction[I, O]` with `inputSchema: ToolSchema[I]`, `outputSchema: ToolSchema[O]`, and `handler: I => Either[ToolSchemaError, O]`
**When** `stf.toToolFunction` is called
**Then** the result is a `ToolFunction[Any, Any]` whose `name` and `description` match the `StructuredToolFunction`, and whose `execute` method parses arguments via `inputSchema.decoder`, calls the handler, and encodes the result via `outputSchema.encoder`

**Rationale**: Currently `StructuredToolFunction.toToolWrapper` sets `originalToolFunction = None`, causing `toToolRegistry` to silently drop these tools. The LLM never sees them.

#### Scenario: Synthesized ToolFunction executes correctly

**Given** a `StructuredToolFunction[AddRequest, AddResult]` with `handler = req => Right(AddResult(req.a + req.b))`
**When** `stf.toToolFunction.execute(ujson.Obj("a" -> 2, "b" -> 3))` is called
**Then** the result is `Right(ujson.Obj("sum" -> 5))`

#### Scenario: Synthesized ToolFunction surfaces handler errors

**Given** a `StructuredToolFunction` with a handler that returns `Left(ToolSchemaError.MissingRequiredField("x", "$"))`
**When** `stf.toToolFunction.execute(ujson.Obj())` is called
**Then** the result is `Left(ToolCallError.InvalidArguments(...))` with the field name "x"

### Requirement: toToolRegistry includes all ToolWrappers

The system SHALL simplify `ToolsNodeConfig.toToolRegistry` to include every `ToolWrapper` in `config.tools`, by mapping `_.toolFunction` directly — no `flatMap` on `Option`.

**Given** a `ToolsNodeConfig` with 3 `ToolWrapper`s (one from `ToolFunction`, one from `StructuredToolFunction`, one from `ToolFunction`)
**When** `config.toToolRegistry` is called
**Then** the resulting `ToolRegistry` contains exactly 3 `ToolFunction`s with the correct names

**Rationale**: Fixes the silent-drop bug where `StructuredToolFunction`-derived tools were excluded from the registry.

#### Scenario: All tools appear in registry

**Given** a `ToolsNodeConfig` with `Left(ToolWrapper(tf1))`, `Left(stf.toToolWrapper)`, `Left(ToolWrapper(tf3))`
**When** `config.toToolRegistry.tools` is inspected
**Then** the registry has 3 tools with names `tf1.name`, `stf.name`, `tf3.name`

#### Scenario: No StructuredToolFunction silently dropped

**Given** a `ToolsNodeConfig` with only `StructuredToolFunction`-derived `ToolWrapper`s
**When** `config.toToolRegistry.tools` is inspected
**Then** the registry is non-empty and contains all the structured tools

### Requirement: ToolWrapper.execute derives executable on demand

The system SHALL derive the `SafeToolExecutable` from `toolFunction` via `ToolFunctionAdapter` on each `execute` call (or cache it lazily), without storing it as a separate field.

**Given** a `ToolWrapper` with a `toolFunction`
**When** `wrapper.execute(args)` is called
**Then** the result is identical to calling `ToolFunctionAdapter(toolFunction).execute(args)`

**Rationale**: Eliminates the dual storage and the reverse-cast workaround.

#### Scenario: Execute delegates to derived executable

**Given** a `ToolWrapper(ToolFunction[Req, Resp](...))`
**When** `wrapper.execute(validArgs)` is called
**Then** the result is `Right(encodedResponse)` matching the `ToolFunction`'s handler output

## MODIFIED Requirements

### Requirement: ToolsNodeConfigBuilder.withStructuredTool produces visible tool

The system SHALL modify `ToolsNodeConfigBuilder.withStructuredTool` so that the resulting `ToolWrapper` contains a synthesized `ToolFunction` (via `toToolFunction`), making the tool visible in `toToolRegistry`.

**Given** `ToolsNodeConfig.builder.withStructuredTool(addTool)`
**When** `.build.toToolRegistry` is called
**Then** the registry contains a tool named "add"

**Rationale**: Currently this tool is silently dropped. This is a behavior fix (bug → feature).

## Properties (Ring 3)

### Property: All ToolWrappers appear in ToolRegistry

**Invariant**: For all `ToolsNodeConfig` values, `config.toToolRegistry.tools.size == config.tools.collect { case Left(tw) => tw }.size`.

**Generator strategy**: `genToolsNodeConfig` (constructive: `Gen.list(Range.linear(0, 10), genToolEntry)` where `genToolEntry` is a weighted choice of `genToolFunctionWrapper`, `genStructuredToolWrapper`, `genAdkTool`). Classify by tool source type (ToolFunction vs StructuredToolFunction vs AdkTool).

```
forAll { (config: ToolsNodeConfig) =>
  val leftCount = config.tools.collect { case Left(tw) => tw }.size
  config.toToolRegistry.tools.size == leftCount
}
```

### Property: ToolWrapper execute matches ToolFunctionAdapter

**Invariant**: For all `ToolWrapper` values and all `args: ujson.Value`, `wrapper.execute(args) == ToolFunctionAdapter(wrapper.toolFunction).execute(args).left.map(toRuntimeException)`.

**Generator strategy**: `genToolWrapper` (constructive: `genToolFunction.map(ToolWrapper(_))`), `genArgs` (constructive: `Gen.json(Range.linear(0, 5))`). Classify by tool name.

```
forAll { (wrapper: ToolWrapper, args: Value) =>
  wrapper.execute(args) == ToolFunctionAdapter(wrapper.toolFunction).execute(args).left.map(toRuntimeException)
}
```

### Property: Synthesized ToolFunction name matches StructuredToolFunction name

**Invariant**: For all `StructuredToolFunction[I, O]` values, `stf.toToolFunction.name == stf.name`.

**Generator strategy**: `genStructuredToolFunction` (constructive: `genName.flatMap(name => genHandler.map(h => StructuredToolFunction.pure(name, "desc", schema, schema, h))))`. Classify by name length.

```
forAll { (stf: StructuredToolFunction[?, ?]) =>
  stf.toToolFunction.name == stf.name
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ToolWrapper(originalToolFunction = None, executable = ..., name = "x", description = "y")` | The `originalToolFunction` and `executable` fields are removed | `assertDoesNotCompile("ToolWrapper(originalToolFunction = None, executable = exec, name = \"x\", description = \"y\")")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| ToolWrapper has single toolFunction field | Requirement: ToolWrapper stores single | Type system + compile-negative test | ToolAbstractionDedupSpec, TypeContract |
| StructuredToolFunction.toToolFunction produces ToolFunction | Requirement: Synthesize ToolFunction | Scenario test (execute returns Right) | ToolAbstractionDedupSpec |
| toToolRegistry includes all ToolWrappers | Requirement: toToolRegistry + Property | Hedgehog property + scenario test | ToolAbstractionDedupSpec |
| No StructuredToolFunction silently dropped | Requirement: toToolRegistry + Scenario | Scenario test (registry non-empty) | ToolAbstractionDedupSpec |
| Execute delegates to derived executable | Requirement: ToolWrapper.execute + Property | Hedgehog property | ToolAbstractionDedupSpec |
| Synthesized ToolFunction name matches | Property: name match | Hedgehog property | ToolAbstractionDedupSpec |
| originalToolFunction field removed | Compile-Negative | assertDoesNotCompile | TypeContract |
