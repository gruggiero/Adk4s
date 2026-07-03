# Escape Analysis: code-review-llm4s-middleware-and-dedup

- **Change:** `llm4s-middleware-and-dedup` (archived: `openspec/changes/archive/2026-07-02-llm4s-middleware-and-dedup/`)
- **Schema:** verified-scala3 (verified against `openspec/schemas/verified-scala3/schema.yaml`)
- **Review file:** `docs/code-review-llm4s-middleware-and-dedup.md` (produced by another agent)
- **Artifacts read:** proposal, capability-profile, concept-inventory, specs/* (4), spec-lint, design, implementation-order, implementation-progress, tasks; plus the cited production code and the two test oracles (`MiddlewareAdoptionSpec`, `ToolAbstractionDedupSpec`)
- **Scope:** the 4 specs of the change (`tool-abstraction-dedup`, `error-hierarchy-dedup`, `message-type-dedup`, `llm4s-middleware-adoption`) and the code they touched/dominated.

> Method: each finding is classified with the verified-scala3-escape-analysis
> taxonomy, attributed to the workflow stage whose *catchment* covers its error
> class, and given a concrete escape reason backed by artifact/code evidence.
> Codes/levers follow `references/workflow-catchment.md`.

## Summary

| # | Finding (short) | Error class | Responsible stage | Escape reason | Lever |
|---|-----------------|-------------|-------------------|---------------|-------|
| 1 | `RetryTrigger.LLMError` wrongly maps to `ParseRetryTrigger.ParseFailed` | E-SPEC / E-TEST | Step 2 test oracle (+Ring 8) | Behavior-preservation req tested on ONE enum variant only | 6, 1 |
| 2 | `MessageStream` / `PromptSyntax` map `Tool`→`UserMessage` | E-ARCH / E-LINT | spec-lint (ck 11) + Ring 1/8 (diff-scoped) + **blind spot** | Type-alias widening to a richer enum never audited for downstream matches; files out of diff | 8, 2, 3 |
| 3 | `ChatTemplate.substituteMessageContent` skips `ToolMessage` | E-ARCH / E-LINT | (same as #2) | (same as #2 — `case other => other` no-op) | 8, 2 |
| 4 | Dead code: `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable` | E-SCOPE / E-LINT | Ring 1 (RemoveUnused) + Step 12 | RemoveUnused blind to public/opaque members; concept-delta is additive-only | 1, 3 |
| 5 | `toToolFunction` exposes empty parameter schema | E-SPEC / E-TEST | Step 2 test oracle (+spec author) | "Visible" underspecified = name+presence, not parameter schema; no test on `tf.schema` | 1, 6 |
| 6 | `toToolFunction` flattens errors to `String` (spec required `InvalidArguments`) | E-SPEC / E-PROCESS | Ring 3 (oracle corrupted) + spec-lint | Test oracle loosened (`contains(...)`) to match impl; spec requirement type-infeasible vs llm4s `Either[String,_]` | 6, 1, 5 |
| 7 | `ClientStrategy.executeFallback` name misalignment | E-TEST / E-SPEC | (pre-existing, out of diff) | Diff-scoped review never scans adjacent untouched coupled file | 1, 8 |

**Overall:** 7 findings. 6 escapable by an existing stage (all failed through weak/missing
oracles or diff-scoped review); 1 class (#2/#3 alias-widening impact) is a **structural
blind spot** — no current stage performs downstream-match impact analysis for a public
type alias that widens the reachable variant set.

**Cross-cutting themes (highest value):**
- **A. Diff-scoped rings miss behaviorally-coupled files** (#2, #3, #7): Ring 1's
  dangerous-pattern grep and Ring 8's adversarial review operate on the change's
  production-file diff. Files that merely *compile against* a changed public type
  (and inherit new semantics) are never scanned.
- **B. No "public-type-widening impact analysis" stage** (#2, #3): aliasing
  `Role`/`Message` to llm4s types silently introduced the `Tool` variant across the
  whole module graph; spec-lint explicitly mis-reasoned that "no new variants" were added.
- **C. Behavior-preservation requirements tested on a single variant** (#1): an
  enum-dispatch mapping with 4 branches had 1 branch tested.
- **D. Oracle corruption / loosening** (#6): a test was written with `|| contains(...)`
  fallbacks instead of asserting the spec's named error variant, masking a direct spec
  violation. Ring 3 cannot catch what a corrupted oracle encodes.
- **E. "Visible/usable" requirements underspecify the consumer-facing surface** (#5).

---

## Finding 1: `RetryTrigger.LLMError` incorrectly maps to `ParseRetryTrigger.ParseFailed`

- **Quote from review:** "When a user requests retry **only on LLM errors** … the inner `StructuredLLMImpl` gets `parseRetryTrigger = Some(ParseRetryTrigger.ParseFailed)`, which causes it to **also retry on parse failures**."
- **Error class:** E-SPEC (behavior-preservation requirement violated) / E-TEST
- **Where it bites:** `structured-llm/.../core/StructuredLLM.scala` — `fromClientWithRetry`, the `trigger match { case RetryTrigger.LLMError => ParseRetryTrigger.ParseFailed … }` block (review cites line 257). Confirmed in source: the non-`Option` `parseTrigger` is always wrapped in `Some(…)` and passed to `StructuredLLMImpl`, which routes to `completeRawWithRetry`. So an LLM-error-only request silently also retries on parse failures. (The `None` the review proposes can't even be expressed by the current shape.)

### Hypothesis — why the workflow missed it
The deprecated-factory requirement (`llm4s-middleware-adoption` → "fromClientWithRetry delegates to middleware factory … preserving identical observable behavior") and its companion MODIFIED requirement "Retry behavior preservation" were each backed by **a single scenario, both using `RetryTrigger.All`**. The mapping function dispatches over a 4-variant enum (`LLMError`, `ParseFailure`, `ValidationFailure`, `All`) but only the `All` branch was ever exercised, so the wrong `LLMError` branch shipped.

### Stage that should have caught it
**Step 2 — Test oracle** is the primary responsible stage (catchment: E-TEST — tests derived from spec). Defence-in-depth that also missed it: **Ring 8** (adversarial — should re-derive, per `RetryTrigger` variant, which error types the deprecated factory actually retries) and **spec-lint** (the "identical behavior" requirement was not decomposed into per-variant scenarios).

### Why that stage did not catch it
Failure mode (from the catchment map): *"a scenario has no test / generator never reaches the failing case."* Evidence: `MiddlewareAdoptionSpec.scala` test `"Deprecated fromClientWithRetry produces same retry count"` feeds `Left(UnknownError)` three times with `trigger = RetryTrigger.All` and asserts call count 3. No test constructs `trigger = RetryTrigger.LLMError`, and no property asserts "the set of retried error types for trigger T equals the legacy behavior." The spec-lint check for the requirement passed because the Then was "observable" (call count), not because coverage was exhaustive.

### Workflow improvement (lever)
- **Lever 6 — strengthen generator/coverage:** add a schema rule that any requirement asserting *behavior preservation over an enum/dispatch parameter* MUST be backed by one scenario per enum variant, each asserting the discriminating observable (here: *which error types trigger retry*, not just a call count).
- **Lever 1 — tighten spec-lint:** new check "behavior-preservation coverage": a requirement whose Then is "identical/same behavior" over a `match`/enum input must list one scenario per branch, or be explicitly flagged PARTIAL.

---

## Finding 2: `MessageStream.messageForRole` / `PromptSyntax` silently convert `ToolMessage` → `UserMessage`

- **Quote from review:** "The fallback case converts any unrecognized message type (including `ToolMessage`) to `UserMessage` … silently corrupting the conversation role."
- **Error class:** E-ARCH (invalid state silently mapped to valid — the design hierarchy marks this **FORBIDDEN**) / E-LINT (`case other =>` fallback)
- **Where it bites:** `adk4s-core/.../streaming/MessageStream.scala:37` (`case other => UserMessage(content)`) and `structured-llm/.../template/PromptSyntax.scala:137` (`case MessageRole.Tool => UserMessage(content)`). Confirmed in source. **Neither file is in the change's "Expected Changed Production Files"** (`implementation-order.md` lists only Prompt/StructuredLLM/MessageConverter/AgentTool for `message-type-dedup`).

### Hypothesis — why the workflow missed it
`message-type-dedup` aliased `type Role = org.llm4s.llmconnect.model.MessageRole` and `type Message = org.llm4s…Message`. The llm4s `MessageRole` carries a `Tool` variant that the legacy adk4s `Role` did not, so every `Role`/`Message` pattern match with a catch-all across the module graph silently widened its reachable input set. The workflow analysed the *edited* surface (`Prompt`, `MessageConverter`) but never the *consuming* surface.

### Stage that should have caught it
Three stages share responsibility, all of which failed:
1. **spec-lint, check 11 (enum/GADT extension)** — primary. Its catchment is exactly "enum extension states how existing pattern matches behave."
2. **Ring 1 (dangerous-pattern grep)** — catchment includes `case _`/`case other` mapping invalid→valid in domain logic.
3. **Ring 8 (adversarial)** — catchment includes "private/public helpers mapping invalid→valid."

### Why that stage did not catch it
- **spec-lint check 11 — reasoning error:** `spec-lint.md` row for `message-type-dedup` check 11 says *"Role is being removed (deprecated alias), not extended; no new variants."* That is the miss: aliasing to a *richer* enum is an effective extension of the reachable variant set for every consumer, even though no variant was *added in source*. The check treated "alias" as "non-extension."
- **Ring 1 / Ring 8 — diff-scoped (cross-cutting theme A):** both operate on the change's production-file diff. `MessageStream.scala` and `PromptSyntax.scala` were not edited by this change (they only compile against the new alias), so the dangerous-pattern grep and the adversarial diff never scanned them. A latent `case other => UserMessage` became a live bug with no review.

**Structural blind spot:** no current stage performs *public-type-widening impact analysis* — i.e., when a public type becomes an alias for (or is widened to) a type with more variants, find all match sites on that type and re-check exhaustiveness/fallback semantics across the module graph.

### Workflow improvement (lever)
- **Lever 8 — new stage/check (closes the blind spot):** add a mandatory **"public-type-change impact scan"** to the apply phase (Step 0 or a new sub-step of Ring 2): when a spec changes a public type to an alias or widens it, grep the whole module graph for matches on that type with a catch-all/default, and require each to be either made exhaustive, explicitly reject the new variant, or carry a justified `case` — recorded as a proof obligation.
- **Lever 2 — lint/architecture rule:** forbid `case (other|_) => <constructs a valid domain Message/value>` in `Role`/`Message` conversion code (must be exhaustive over `MessageRole`, or explicitly reject).
- **Lever 3 — broaden Ring 1 grep scope:** the dangerous-pattern grep must scan not only the diff but every file that *imports* a public type touched by the change (cheap via `rg` on import lines).

---

## Finding 3: `ChatTemplate.substituteMessageContent` skips `ToolMessage`

- **Quote from review:** "Variable substitution is skipped for `ToolMessage` … If a `ToolMessage` contains `{variable}` placeholders, they will remain unresolved."
- **Error class:** E-ARCH / E-LINT (`case other => other` no-op fallback)
- **Where it bites:** `adk4s-core/.../component/ChatTemplate.scala:56` (`case other => other`). Confirmed; also **not** in the change's expected-changed-files.

### Hypothesis — why the workflow missed it
Identical root cause to Finding 2: the `message-type-dedup` alias widening made `ToolMessage` a reachable arm of the `Llm4sMessage` match, and the `case other => other` catch-all silently no-ops substitution for it. The difference is the failure shape (silent no-op vs mislabel), not the cause.

### Stage that should have caught it / Why it did not
Same as Finding 2 (spec-lint check 11 reasoning error + diff-scoped Ring 1/Ring 8 + the alias-widening structural blind spot). No additional stage applies.

### Workflow improvement (lever)
Same Levers 8/2/3 as Finding 2 — a single impact-scan stage and an exhaustive-match rule on `Message`/`MessageRole` closes both #2 and #3 at once.

---

## Finding 4: Dead code — `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable`

- **Quote from review:** "After the `ToolWrapper` refactor … the following are no longer used in production code."
- **Error class:** E-SCOPE (orphaned public surface left behind) / E-LINT
- **Where it bites:** `adk4s-core/.../tools/ToolsNodeConfig.scala` (`trait SafeToolExecutable`, `opaque type ToolFunctionAdapter` + companion) and `StructuredToolFunction.scala` (`toSafeExecutable`). Confirmed: `ToolWrapper.execute` now calls `toolFunction.execute(args)` directly and does not touch `ToolFunctionAdapter`/`SafeToolExecutable`; `toToolWrapper` uses `toToolFunction`, not `toSafeExecutable`. A repo-wide `rg` finds these symbols only in their own definition files, the type-contract, and test scaffolding — **zero production references**.

### Hypothesis — why the workflow missed it
The refactor deleted `ToolWrapper.originalToolFunction`/`executable` and re-routed execution through `toolFunction.execute`, orphaning the adapter layer. The dead symbols survive because they are public/opaque API surface.

### Stage that should have caught it
**Ring 1 (RemoveUnused)** — catchment: unused code. **Step 12 (concept delta)** — catchment: public surface changed exactly as committed.

### Why that stage did not catch it
- **Ring 1 — blind spot:** Scalafix `RemoveUnused` does not flag public traits, opaque-type companions, or public extension methods (they look like exported API), so the orphans passed lint.
- **Step 12 — additive-only:** the concept-delta check verifies *introduced* concepts against the committed surface; it has no notion of *leftover* concepts that should have been removed when their only consumer (`ToolWrapper.executable`) disappeared.

### Workflow improvement (lever)
- **Lever 1 — tighten Step 12:** add a **"removal audit"** — for every field/type *removed* from a refactored concept (here `ToolWrapper.executable`, `originalToolFunction`), grep main sources for now-orphaned dependents and require each to be deleted or justified.
- **Lever 3 — Ring 1 grep:** after a field-removal refactor, grep the changed files' symbol graph for members whose only remaining references are their own definition/test (cheap heuristic).

*(Lower priority — code quality, no runtime failure.)*

---

## Finding 5: `StructuredToolFunction.toToolFunction` exposes an empty parameter schema

- **Quote from review:** "The synthesized `ToolFunction` exposes an empty parameter schema to the LLM … The LLM doesn't see what parameters the tool accepts."
- **Error class:** E-SPEC (underspecified) / E-TEST (missing)
- **Where it bites:** `adk4s-core/.../tools/StructuredToolFunction.scala:152-153` (`ObjectSchema[ujson.Value](stf.description, Seq.empty, false)`). Confirmed; the source comment admits "permissive ObjectSchema (the real validation is done by `inputSchema.decoder`)." `stf.inputSchema.jsonSchema` is available but unused.

### Hypothesis — why the workflow missed it
The change's entire rationale for synthesis was "StructuredToolFunction-derived tools are visible in ToolRegistry … the LLM never sees them." But "visible" was operationalised as *appearing in the registry with name+description* — not *callable with the correct parameter schema*. `design.md`'s "ToolFunction synthesis" decision explicitly punted: *"The `SchemaDefinition` can be built from `ToolSchema[I]`'s JSON schema representation if available (investigate during implementation; **fallback to a permissive schema**)."* The implementer took the fallback, so the tool is now technically visible but practically uncallable.

### Stage that should have caught it
**Step 2 — Test oracle** (primary) and **specs** (the requirement never constrained the LLM-facing schema). Defence-in-depth: **Ring 8** (should ask "does making it visible actually let the LLM call it correctly?") — but the spec didn't require it, so the oracle couldn't encode it.

### Why that stage did not catch it
The oracle tested: synthesized `ToolFunction` *name* matches (`property`), *execute* succeeds, errors surface, and *registry presence*. No test ever inspects `tf.schema` (its parameters). The spec's "Concepts Introduced" commits only to `name`/`description`/`execute` correctness. There is no obligation on the schema surface, so Ring 3 had nothing to falsify.

### Workflow improvement (lever)
- **Lever 1 — tighten specs:** any requirement introducing an **LLM-/consumer-facing surface** (tool, operation, IDL) MUST specify what the consumer observes (here: the parameter schema) and carry a scenario asserting the surface exposes all input parameters (non-empty, consistent with `inputSchema`).
- **Lever 6 — property:** synthesized `ToolFunction.schema` parameters must be non-empty and consistent with `inputSchema.jsonSchema` (when available).

---

## Finding 6: `toToolFunction` flattens errors to `String` (spec required `ToolCallError.InvalidArguments`)

- **Quote from review:** "Errors are flattened to plain strings … error diagnostics are less informative."
- **Error class:** E-SPEC (direct requirement violation) **+ E-PROCESS (oracle corruption)**
- **Where it bites:** `StructuredToolFunction.scala:160,163` (`case Left(err: ToolSchemaError) => Left(err.message)`). Confirmed. Root cause is structural: llm4s `ToolFunction`'s handler returns `Either[String, R]`, so a structured `ToolCallError.InvalidArguments` cannot be carried through `toToolFunction` at all — whereas `toSafeExecutable` (returns `Either[ToolCallError, Value]`) preserves it.

### Hypothesis — why the workflow missed it
The spec scenario **explicitly** required `Left(ToolCallError.InvalidArguments(...)) with the field name "x"`. The implementation cannot produce that through this path. Rather than fail the implementation against the spec, the test oracle was *loosened* to accommodate it. This is oracle corruption — the most damaging escape mode, because Ring 3 (run tests) is structurally unable to catch a bug the oracle has re-defined away.

### Stage that should have caught it
**Ring 3** is the intended backstop (a faithful oracle fails here), but it depends on Step 2. The actual failure is at **Step 2 (oracle authoring)** and **spec-lint** (the requirement was type-infeasible). **Ring 8** is the last defence.

### Why that stage did not catch it
- **Step 2 / Ring 3 — oracle corruption (cross-cutting theme D):** `ToolAbstractionDedupSpec.scala` test `"synthesized ToolFunction surfaces handler errors for missing field"` asserts:
  ```scala
  assert(err.toString.contains("a") || err.toString.contains("Missing") || err.toString.contains("Invalid"))
  ```
  The spec said `InvalidArguments(...)`. The test replaced that exact assertion with a three-way `|| contains(...)` substring check that passes for almost any error string — encoding the implementation's flattened `String` instead of the spec's structured variant. A faithful `assert(err.isInstanceOf[ToolCallError.InvalidArguments])` would have failed and exposed the bug at Ring 3.
- **spec-lint — type-feasibility gap:** the requirement "surfaces as `InvalidArguments`" is *unprovable* against `ToolFunction`'s `Either[String, R]` handler signature. No check verifies that a required error variant is actually returnable by the producing API.
- **Ring 8 — shallow:** accepted the loosened oracle instead of re-deriving the strict assertion from the scenario text.

### Workflow improvement (lever)
- **Lever 6 — faithful oracles:** add a rule that a test asserting a spec-named error variant MUST assert that variant exactly (no `|| contains(...)` loosening); any loosening requires an explicit spec amendment re-approved at the Step 2 gate. Treat `|| contains`/`toString.contains` in an error assertion as a lint smell.
- **Lever 1 — spec-lint type-feasibility:** new check — any requirement whose Then returns a specific error variant must be type-feasible against the producing API's return type; flag the contradiction (here: `Either[String,_]` cannot carry `ToolCallError`).
- **Lever 5 — design fix:** if structured errors are genuinely required on the `toToolWrapper` path, change the design to preserve them (map to `InvalidArguments` before the `String` boundary), exactly as `toSafeExecutable` already does.

---

## Finding 7: `ClientStrategy.executeFallback` — `clientNames` misalignment

- **Quote from review:** "If `clientNames` is shorter than `clients`, remaining clients get name `"unknown"` … misleading for debugging. This is pre-existing."
- **Error class:** E-TEST / E-SPEC (pre-existing, out of scope)
- **Where it bites:** `structured-llm/.../core/ClientStrategy.scala:69-71` (`clientNames.headOption.getOrElse("unknown")` + `clientNames.drop(1)`). Confirmed. `git log` shows the file was last touched by the **prior** change (`baml-gap-features`, commit `9574c86`), and it is **not** in this change's expected-changed-files or specs.

### Hypothesis — why the workflow missed it
This is a pre-existing defect in a file the change did not edit. The change's specs cover retry/middleware composition but say nothing about `AttemptRecord` name accuracy, and no scenario feeds a `clientNames` vector shorter than `clients`.

### Stage that should have caught it / Why it did not
No stage in *this* change's workflow is responsible for an unedited, behaviorally-coupled file. The escape reason is **diff-scoping (cross-cutting theme A)**: Ring 8 reviews the change's diff; `ClientStrategy` is invoked by the middleware/fallback path but was not itself edited, so it was never reviewed. (For a genuinely pre-existing bug this is arguably correct scoping, but the reviewer flagged it because it sits on the path this change re-architected.)

### Workflow improvement (lever)
- **Lever 1 — spec coverage:** specs touching a fallback/retry path MUST specify `AttemptRecord`/naming accuracy and carry a scenario for the misaligned-length case.
- **Lever 8 — coupled-file review:** broaden Ring 8 to also review *behaviorally-coupled* files (callers/callees on the re-architected path) even when not in the diff — at least for a HIGH-risk change (this change was rated **high** correctness risk).

*(Lowest priority — pre-existing, debugging-only impact.)*

---

## Aggregated workflow-improvement plan

| Lever | Workflow change | Catches findings # | Where to edit |
|-------|-----------------|--------------------|---------------|
| **8** | Add a **public-type-change impact scan** (Step 0 / Ring 2 sub-step): when a spec aliases/widens a public type, grep the module graph for catch-all matches on it; require exhaustiveness or justified reject. **Closes the only true structural blind spot.** | 2, 3 (+7 coupled-file) | `schema.yaml` `apply.instruction` (Step 0) + new Ring 2 obligation |
| **1** | **spec-lint hardening:** (a) behavior-preservation over enum ⇒ one scenario per variant; (b) "alias to richer type" ⇒ treated as enum extension (fixes check 11 mis-reasoning); (c) consumer-facing-surface specs must constrain what the consumer observes; (d) error-variant Then must be type-feasible vs producing API; (e) fallback-path specs must cover naming/records. | 1, 2, 5, 6, 7 | `schema.yaml` `spec-lint.instruction` |
| **6** | **Oracle-faithfulness rules:** per-variant coverage for enum-dispatch preservation; property on synthesized LLM-facing schema; ban `|| contains`/`toString.contains` loosening of a spec-named error variant without re-approval. | 1, 5, 6 | `schema.yaml` `apply.instruction` Step 2 + Step 6 |
| **2 / 3** | **Ring 1/Ring 2 rules:** architecture rule forbidding `case other/_ => <valid domain value>` in `Message`/`Role` conversion; broaden the Ring 1 dangerous-pattern grep from diff-only to *all files importing a changed public type*. | 2, 3, 4 | `schema.yaml` `apply.instruction` Step 4/5 |
| **1** | **Step 12 removal audit:** for each field/type removed from a refactored concept, grep for orphaned dependents. | 4 | `schema.yaml` `apply.instruction` Step 12 |
| **5** | **Design fix:** preserve structured `ToolCallError` on the `toToolWrapper` path (map before the `String` boundary), mirroring `toSafeExecutable`. | 6 | code/design (follow-up change) |

### Priority
1. **High (recurring/high-risk, low cost):**
   - **Lever 6 — ban loosened error assertions** (#6): one-line oracle rule, would have turned a silent spec-violation into a red Ring 3. Highest ROI.
   - **Lever 1 — behavior-preservation = one scenario per enum variant** (#1): cheap, catches the whole class of "tested All, shipped LLMError."
   - **Lever 1/2 — fix spec-lint check 11 + exhaustive-match rule on `Message`/`Role`** (#2, #3): the alias-widening class is silent and data-corrupting.
2. **Medium:**
   - **Lever 8 — public-type-change impact scan** (#2, #3): the only structural blind spot; heavier (cross-module grep) but high value for any API-refactor change.
   - **Lever 1 — consumer-facing-surface specs must constrain the schema** (#5).
3. **Low / needs design:**
   - **Lever 1/8 — Step 12 removal audit + coupled-file Ring 8** (#4, #7): code-quality and debugging-only.
   - **Lever 5 — preserve structured tool errors** (#6): needs a design decision on the `toToolWrapper` boundary.

### Open questions for the human
- Make `case other/_ => <valid domain value>` in `Message`/`Role` conversion a **fatal** Ring 2 architecture rule (currently no such rule exists), or keep it a Ring 1 grep? (Theme B / findings 2–3.)
- Should Ring 8 for a **HIGH**-risk change be broadened to behaviorally-coupled (caller/callee) files outside the diff? (Theme A / findings 2, 3, 7.)
- For `toToolFunction` (#5/#6): is the permissive-schema + flattened-error path acceptable for now (tools are "visible" if not "callable with structure"), or should we open a follow-up verified-scala3 change to surface `inputSchema.jsonSchema` and preserve `ToolCallError`? (Levers 1/5/6.)
