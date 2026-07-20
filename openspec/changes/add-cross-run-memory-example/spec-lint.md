# Spec Lint

**Change**: `add-cross-run-memory-example`
**Specs linted**: 1 — `specs/cross-run-memory-example/spec.md`
**Mechanical lint** (`spec-lint.sh`): **0 FAIL, 5 WARN (W3 — all confirmed)**
**Judgment lint** (this artifact): **PASS**
**Overall**: **CLEAN — ready for design**

## Mechanical lint results

```
spec-lint: openspec/changes/add-cross-run-memory-example/specs/cross-run-memory-example/spec.md
  WARN W3 line 71:  Req 1 "File-backed memory persists episodes across process boundaries" — negative
  WARN W3 line 99:  Req 2 "File-backed memory satisfies the AgentMemory laws" — negative
  WARN W3 line 121: Req 3 "Cross-run recall via the memory-aware runner (A1 and A2)" — negative
  WARN W3 line 171: Req 5 "Observability — the recall run narrates its mechanism" — negative
  WARN W3 line 215: Req 7 "Build wiring — examples module depends on the memory testkit in test scope" — negative
spec-lint: 1 spec file(s), 0 FAIL, 5 WARN
```

### W3 confirmations (adversarial scenario inputs are genuinely forbidden)

| Req | Trigger word | Adversarial scenario | Forbidden input | Confirmed |
|-----|-------------|----------------------|-----------------|-----------|
| Req 1 | "not an error" (scenario 2) | "Empty file on first recall" | A missing episodes file must NOT raise an exception — it is treated as zero episodes. Scenario 2 asserts the empty-list, no-exception outcome. | ✅ |
| Req 2 | "not" in "not on the production double" (scenario 2) | "Laws suite red on a broken double" | A double whose `recall` returns `Nil` regardless of input is forbidden by the laws; scenario 2 asserts ≥ 1 property fails. | ✅ |
| Req 3 | "NOT" in "NOT a hardcoded canned answer" (rationale) + "not from a script" | "Recall without prior teach" | A recall run with empty storage must NOT produce "blue"; scenario 2 asserts the response does not contain "blue". | ✅ |
| Req 5 | "not just the final answer" (rationale) | "Observability with zero hits" | Zero-hit recall must still print the four sections (with empty hit/inject blocks); scenario 2 asserts the sections are present. | ✅ |
| Req 7 | "NOT a main-scope dependency" (requirement body) | "No main-scope leakage" | A main-scope import of the testkit is forbidden; scenario 2 asserts compilation fails. | ✅ |

All 5 W3 warnings are confirmed: each negative requirement has at least one scenario whose input the requirement forbids.

### F1–F5 checks (all pass)

- **F1 (SHALL/MUST before Given)**: all 8 requirements open with a normative SHALL statement before the first `**Given**`. ✅
- **F2 (adversarial scenario for "only"/"never"/"must not")**: covered by W3 above. ✅
- **F3 (Generator strategy per property)**: all 4 properties declare `**Generator strategy**`. ✅
- **F4 (Proof Obligations section present)**: present, 18 rows. ✅
- **F5 (Temporal blocks have trigger/response)**: no temporal properties (Ring 9 not checked). N/A. ✅

## Judgment lint

### Testability — every `Then` is observable

| Requirement | Then clause | Observable? | How |
|-------------|------------|-------------|-----|
| Req 1, sc 1 | hit list non-empty, first hit text contains "blue" | ✅ | assert on `List[MemoryHit]` returned by `recall` |
| Req 1, sc 2 | empty hit list, no exception | ✅ | assert `recall` returns `Nil` and `.attempt.isRight` |
| Req 1, sc 3 | "unspecified" | ✅ (documented non-goal) | no test — explicitly out of scope |
| Req 2, sc 1 | all laws properties pass | ✅ | run `AgentMemoryLaws(indexesContent = true).all` |
| Req 2, sc 2 | ≥ 1 property fails on broken double | ✅ | run laws against a `Nil`-returning double, assert failure |
| Req 3, sc 1 | A1: output contains "blue"; A2: injected block contains "blue" | ✅ | assert on `RunResult.Completed.output` + captured printed output |
| Req 3, sc 2 | injected block empty, response does NOT contain "blue" | ✅ | assert output does not contain "blue" (adversarial) |
| Req 3, sc 3 | episodes file deleted, recall returns empty | ✅ | assert file does not exist + `recall` returns `Nil` |
| Req 4, sc 1 | response contains "blue" from injected block | ✅ | assert response contains "blue" when context is injected |
| Req 4, sc 2 | response does NOT contain "blue" when no context | ✅ | assert response does not contain "blue" (adversarial) |
| Req 5, sc 1 | four labeled sections in order, hit with positive score, "Relevant memory:" present, write count ≥ 1 | ✅ | assert on captured printed output (string contains checks) |
| Req 5, sc 2 | four sections present (with empty hit/inject) | ✅ | assert on captured printed output |
| Req 6, sc 1 | ≤ 2 documents, top doc contains "favorite color" | ✅ | assert on `List[Document]` |
| Req 6, sc 2 | empty document list, no exception | ✅ | assert `retrieve` returns `Nil` and no exception |
| Req 7, sc 1 | test compilation succeeds with laws import | ✅ | `sbt adk4s-examples/Test/compile` |
| Req 7, sc 2 | main-scope compilation FAILS | ✅ | compile-negative test or `sbt adk4s-examples/compile` fails |
| Req 8, sc 1 | README has Memory category with 3 rows | ✅ | manual review (documented) |
| Req 8, sc 2 | README orchestration section has snippet | ✅ | manual review (documented) |

All `Then` clauses are observable. The two manual-review obligations (Req 8 sc 1, sc 2) are explicitly marked as manual in the Proof Obligations table.

### Concept resolution — every cited concept exists

All 23 concepts in "Concepts Used (from inventory)" verified against `openspec/concept-inventory.md` in the inventory-check artifact. All 5 concepts in "Concepts Introduced" are new commitments consistent with the proposal. No concept is invented or misreferenced.

### Generator strategies — all declared, all constructive

| Property | Generator | Constructive? | Edge cases |
|----------|-----------|---------------|------------|
| Property 1 (round-trip) | `genEpisodes` (existing) | ✅ constructive, `Range.linear(1, 20)` | empty content, long content, special chars (covered by existing gen) |
| Property 2 (reload equivalence) | `genEpisode` + `genQuery` + `genK` (existing) | ✅ constructive | empty query, matching query, non-matching query (cover labels) |
| Property 3 (scoring delegates) | `genContent` + `genQuery` (existing) | ✅ constructive | query is substring of content (cover label) |
| Property 4 (empty storage) | `genQuery` + `genK` (existing) | ✅ constructive | directory may not exist |

All 4 properties use existing generators from `adk4s-memory-api/src/test/scala/org/adk4s/memory/Generators.scala` (verified in inventory-check). No new generators needed. All are constructive (no `suchThat` filtering). Coverage labels declared where the framework supports assertions (Hedgehog `cover`).

### Proof-obligation completeness — every requirement mapped

18 proof obligations in the table, covering all 8 requirements, all 4 properties, all edge/adversarial scenarios, and the compile-negative obligation. No obligation lacks a declared enforcement mechanism. Two obligations are explicitly manual review (README checks).

### Type-feasibility — every error variant is reachable via the producing API

| Requirement | Asserted outcome | Producing API | Feasible? |
|-------------|-----------------|---------------|-----------|
| Req 1 sc 2 | empty hit list, no exception | `recall: F[List[MemoryHit]]` | ✅ — `Nil` is a valid `List[MemoryHit]` |
| Req 3 sc 1 | `RunResult.Completed.output` contains "blue" | `runWithEvents: (IO[RunResult], Stream[...])` — `RunResult.Completed(output, messages)` | ✅ — pattern match on `Completed` |
| Req 6 sc 2 | empty document list | `retrieve: F[List[Document]]` | ✅ — `Nil` is a valid `List[Document]` |
| Req 7 sc 2 | compilation fails | sbt compile exit code | ✅ — compile-negative is a build-level check |

No requirement asserts an error variant that the producing API cannot carry. The spec does not introduce new error variants — `FileBackedAgentMemory` returns `F[EpisodeOutcome]` and `F[List[MemoryHit]]` per the `AgentMemory` trait, with no new error algebra.

### Altitude rule — behavioral vocabulary in requirements, code identifiers in Implementation Anchors

The spec uses a concept registry (`openspec/concepts/`). Requirements and scenarios use behavioral vocabulary ("memory-aware runner", "injected context block", "recall hit count", "teach run", "recall run") and concrete test vectors ("blue", "favorite color", "Acme Corp"). Code identifiers (`FileBackedAgentMemory`, `MemoryAwareRunner`, `MemoryPolicy.default`, `runWithEvents`, `naiveScore`) appear ONLY in:
- The "Concepts Used (from inventory)" table (type-level references — allowed)
- The "Concepts Introduced" table (commitments — allowed)
- The "Implementation Anchors" section (the designated place for code identifiers — allowed)
- The Properties section `forAll` pseudocode (necessary for property precision — allowed)

No code identifier appears inside a `Given`/`When`/`Then` clause. ✅

### Concurrency rule — not applicable

No requirement involves concurrent execution, timeouts, cancellation, or interruption. The spec explicitly documents (Req 1 sc 3) that concurrent writers are out of scope and the double is single-writer/single-reader by design. `TestControl` is not required.

### MUST-CONFIRM rule — no external authoritative sources

No classification table, code mapping, or value domain in this spec is sourced from outside the repo. All signatures were verified against source in the inventory-check artifact. The `"Relevant memory:"` render prefix was confirmed against `MemoryPolicy.scala:46`. No invented values.

## Verdict

**CLEAN** — the spec is unambiguous, testable, concept-resolved, and proof-obligation-complete. It is ready for the design artifact.
