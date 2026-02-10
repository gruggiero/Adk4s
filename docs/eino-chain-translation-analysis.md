# Eino compose/chain → ADK4S translation analysis

## Scope
This report analyzes the practical difficulties encountered when translating the Eino `compose/chain` example into the current ADK4S orchestration framework. It focuses on API mismatches, graph validation constraints, and workflow execution gaps.

## Summary of translation frictions
1. **Fan‑in constraints make Eino-style “parallel then merge” hard**
   - Eino’s `Parallel` node emits a composite object built from multiple sub‑nodes, then downstream nodes read the combined fields. In ADK4S, graph validation forbids fan‑in to any node except a `MergeNode` (and even then only if the node type is `MergeNode`) [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162].
   - The Eino chain uses parallel composition to build `{role, input}` and then feeds those into a prompt/LLM node. Modeling this in ADK4S requires a `MergeNode` whose input type is `(A, B)`, but the two incoming nodes must produce *exactly* the two tuple elements and must be the only fan‑in allowed. The mismatch between Eino’s map-based aggregation and ADK4S tuple-based merge requires extra adapter nodes or refactoring.

2. **Branch targets must match outgoing edges exactly**
   - ADK4S validates that branch target nodes are the same as the outgoing edges from the branching node, otherwise it fails validation [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#164-179].
   - The Eino chain uses a branching lambda that returns branch keys and assumes the composition runtime routes based on that key. In ADK4S, you must wire edges to each branch target *and* supply a `Branch` containing those same target keys. This makes “dynamic” branch routing more rigid and forces explicit edge wiring for all branch cases.

3. **Entry/end nodes are mandatory and validation is strict**
   - ADK4S rejects graphs without an explicit entry and at least one end node [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#29-43].
   - Eino examples often rely on implicit starts/ends based on chain structure, whereas ADK4S requires explicit markers. Translating the example requires identifying the logical terminal node and wiring it as an end node even when the chain visually ends at the LLM output step.

4. **Workflow compilation constraints vs runtime execution**
   - `WIOExecutor.toWIO` compiles the graph into Workflows4s WIO by walking edges and expanding branches into forks [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#10-167].
   - `StreamBranch` is not supported in WIO compilation and raises an exception, which matters if the original Eino chain is adapted to streaming semantics [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#128-137].
   - `WIOExecutor` currently executes branching by eagerly evaluating branch conditions via `unsafeRunSync` during compilation, which is a mismatch for effectful branch conditions or those that require runtime context (like randomness) [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#126-156].

5. **Graph execution and compile are separate paths**
   - The `Graph` itself still has a stubbed execution path (`identityStub`) and relies on `WIOExecutor.execute` for runtime traversal [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#115-137; @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala#37-178].
   - Translating the Eino example can work using `WIOExecutor.execute`, but an end‑to‑end workflow example (`InMemorySyncRuntime`) requires a fully valid WIO and a correct workflow runtime setup.

6. **Node API ergonomics vs chain DSL**
   - Eino’s chain DSL is fluent and implicit; ADK4S graph construction is explicit and type‑level. Each node addition returns `NodeAddition` (graph + ref) that must be threaded manually [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#33-152].
   - This makes large examples verbose and error‑prone, particularly when translating a chain that uses multiple lambdas, branches, parallel fan‑outs, and prompt composition.

7. **Model integration mismatch**
   - Eino’s example uses OpenAI chat model directly. ADK4S expects a `ChatModel[IO]` and uses `Conversation`/`Completion` from `llm4s` [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphNode.scala#32-40].
   - Without a resolved `openai-client` dependency in this repo, a mock model is required to keep the example runnable. This creates extra scaffolding not present in the original Eino example.

## Translation-specific pain points (example mapping)

### Eino branch → ADK4S branch
- **Eino**: `branchCond` returns branch key, then `NewChainBranch` wires lambdas by name.
- **ADK4S**: `Branch.pure` must be provided with explicit target `NodeKey`s. Every target must be reachable via an outgoing edge from the branching node or validation fails [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#164-179].
- **Impact**: You must declare the branch nodes *before* wiring, and then explicitly add edges from the branch selector to each target, which is more rigid than Eino’s “branch container”.

### Eino parallel → ADK4S merge
- **Eino**: `NewParallel().AddLambda("role", ...).AddLambda("input", ...)` produces a map with both fields.
- **ADK4S**: Fan‑in is only permitted into `MergeNode`, which expects a tuple `(A, B)` and requires explicit edges from both producers [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#74-167; @adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala#147-162].
- **Impact**: You must introduce adapter lambdas to align producer output types and shape the tuple. There is no built‑in “parallel map” or “fan‑out + join” API at the graph level.

### Eino prompt templating → ADK4S
- **Eino**: Prompt template node supports templated messages with named fields.
- **ADK4S**: You must build a `Conversation` manually before the `ChatModelNode`, or use `StructuredModelNode` with a `PromptTemplate` and schema [@adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala#53-60].
- **Impact**: There is no direct equivalent of Eino’s prompt template composition in the graph builder; it requires manual bridging code.

## Mitigations and recommended adjustments

1. **Provide higher-level graph builders**
   - A `GraphBuilder` DSL that hides `NodeAddition` plumbing and builds edges declaratively would drastically simplify translation and reduce error‑prone wiring.

2. **Parallel/fan‑in helper nodes**
   - Add a `ParallelNode` or `JoinNode` that accepts multiple upstream nodes and produces a typed record / map, avoiding the strict `(A, B)` tuple constraint in `MergeNode`.

3. **Branch and edge synthesis**
   - Provide a helper that, given `Branch` and targets, automatically adds edges and enforces ordering to make branch wiring less verbose and less error‑prone.

4. **Prompt template support**
   - Add a `PromptTemplateNode` that transforms typed input into `Conversation` using a template DSL similar to Eino’s `prompt.FromMessages`.

5. **Improve WIO compilation safety**
   - Avoid `unsafeRunSync` during compilation for branch evaluation by deferring branch decision to runtime. This would align better with effectful branch conditions and randomness.

## Conclusion
The main translation difficulties stem from strict validation rules (fan‑in, branch targets, explicit entry/end nodes), lack of high‑level composition helpers (parallel aggregation, prompt templating), and workflow compilation limitations (stream branches, effectful branch evaluation). Translating the Eino `compose/chain` example is feasible but requires restructuring the data flow and adding adapter nodes that are not present in Eino’s fluent DSL. The gaps are concrete and can be addressed by higher‑level graph builders, richer merge/parallel primitives, and safer WIO compilation semantics.
