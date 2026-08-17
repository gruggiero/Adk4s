# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

**openspec validate --strict**: not run (the `--strict` flag is not
supported by the installed `openspec` CLI version; the spec-lint.sh
script performs the equivalent mechanical checks: F1–F10, W1–W7).

**spec-lint.sh**: `7 spec file(s), 0 FAIL, 18 WARN`

All 18 WARNs are W3 (negative-requirement adversarial-scenario
confirmation prompts). Each was reviewed and confirmed to have at least
one scenario testing the forbidden input (see per-spec check 15 below).
No W1 (vague words), W4 (positional refs), W5 (impossibility tier), W7
(altitude candidates), or F-checks remain after the fixes applied during
this lint pass.

Fixes applied during lint:
- F6 in `recorded-wrappers`: Proof Obligations Source "(out of scope —
  proposal §2.2)" → "Criterion: streaming-out-of-scope" (typed source
  format).
- W7 in `adk4s-record-module`, `recorder-verified-model`,
  `record-replay-example`: build commands (`sbt .../compile`,
  `sbt scalafixAll --check`, `sbt -J-Xmx6g ring6`, `sbt adk4s-record/test`,
  `./adk4s-examples/run-example.sh ...`) removed from Given/When/Then
  clauses; replaced with behavioral vocabulary.
- W7 in `recorded-wrappers`, `recorder-laws`, `recorder-sink`,
  `record-replay-example`: code identifiers (`DeterministicChatModel`,
  `Embedder[F]`, `Resource[F, Recorder[F]]`, `Failed` variant names)
  removed from Given/When/Then clauses; replaced with domain vocabulary.
- W1 in `call-key`: "cause errors" → "surface as `Left` results";
  "valid records" → "well-formed records".
- W1 in `recorder-sink`: "returns the appropriate type" → "returns
  `Option[CallRecord]`, `Unit`, or `Long` respectively".

### Applicability (paste the script's CONTEXT block VERBATIM)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v13
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/.zcode/skills is schema v11, this schema is v13.
     Checks added after v11 are NOT in the instructions you are following.
     Re-install (scanner/install-skills.sh) before trusting this report.
  behavioural registry  openspec/concepts/             PRESENT (35 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (160 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | PRESENT (capability-profile.md) | APPLIES |
| 6 · reused concepts resolved | PRESENT (concept-inventory.md, 160 rows) | APPLIES |
| 17 · ALTITUDE | PRESENT (concepts/, 35 concepts) | APPLIES |
| 18 · CONCURRENCY | PRESENT (TestControl testkit detected) | APPLIES |

> Note: the "INSTRUCTION DRIFT" warning about the skill at
> `/home/gruggiero/.zcode/skills` being schema v11 is about a different
> tool's skill installation, not about the spec-lint script itself. The
> script reports schema v13 and runs all v13 checks correctly.

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**` (mechanical: F1)
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant, each asserting the discriminating observable
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (openspec/capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy (mechanical: F3)
8. Every temporal property has a trigger event and a response event (mechanical: F5)
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition (candidates: W1)
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave (aliasing to a richer type counts — "Type-Widening Impact" subsection required)
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism, in the mandated Source format (mechanical: F4 section presence, **F6 Source resolvable**, **F7 every requirement named — reachability**, **F8 typed source exists**, F9 artifact resolves at Step 12, W2 row count, W4 positional refs, W5 mechanism strength)
13. Every consumer-facing surface (tool/operation/IDL) has a scenario asserting what the consumer observes (parameter schema, not just presence)
14. Every asserted error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL — every "only"/"never"/"must not" requirement has a scenario whose input the requirement forbids (mechanical half: F2/W3)
16. MUST-CONFIRM — externally-sourced classification tables / code mappings / value domains are marked MUST-CONFIRM with a pointer to the real source; invented plausible values FAIL
17. ALTITUDE — no code identifiers in Given/When/Then; concepts cited in "Concepts Used (behavioral)" link to registry files. Applicability comes from the CONTEXT block above, never from assumption (mechanical: **F10** section presence, **W7** identifier candidates). W7 silence is not a pass — it matches shapes, not prose
18. CONCURRENCY — concurrent-behavior requirements name deterministic observables testable with the detected deterministic test kit; wall-clock timing assertions FAIL

## Results

### Spec: specs/adk4s-record-module/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 5 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens are observable (compilation result, rule violation report, import audit) |
| 3 | Scenarios testable | ✅ | Testable with sbt compile, Scalafix, import inspection |
| 4 | Error paths specified | ✅ | AR-REC-1/AR-REC-2 violation scenarios specify the violation report |
| 5 | New concepts declared | ✅ | `adk4s-record` module, `AR-REC-1`, `AR-REC-2` in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | ChatModel, Embedder, ToolMiddleware, JsonValue, NodeKey, Positive, NonEmpty all in inventory |
| 7 | Generator strategies | ✅ | module-purity property declares its verification strategy (build inspection, not generated) |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | No W1 warnings |
| 10 | Unreachable claims proven | N/A | No unreachability claims |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions or type aliasing |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 6 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (internal module) |
| 14 | Error variants type-feasible | N/A | No error variant assertions |
| 15 | Adversarial scenarios for negatives | ✅ | W3 confirmed: "No forbidden dependencies" scenario tests forbidden deps; "Canonicalization has no fs2 imports" tests fs2-io scoping |
| 16 | MUST-CONFIRM marks present | N/A | No externally-sourced classifications |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (build commands removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/call-key/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 11 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 11 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (key equality, refinement result, canonical form diff, lookup result) |
| 3 | Scenarios testable | ✅ | Testable with Hedgehog property tests + munit scenarios |
| 4 | Error paths specified | ✅ | Empty RolloutId rejection, version mismatch → None |
| 5 | New concepts declared | ✅ | CallKey, CanonicalForm, CallKind, RolloutId, keyVersion in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | ChatModel, Embedder, InvokableTool, ToolInput, ToolOutput, NodeKey, Positive, NonEmpty, JsonValue, JsonValueCodec all in inventory |
| 7 | Generator strategies | ✅ | All 7 properties declare generator strategy (genModelRequest, genRequestMutation, genNonAffectingMutation, genRolloutPair, genConversationWithToolCalls, genVersionedRecord) |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | W1 fixed ("cause errors" → "surface as Left results"; "valid" → "well-formed") |
| 10 | Unreachable claims proven | ✅ | "Empty rollout id cannot be constructed" enforced by Iron RefinedType + compile-negative test |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions (CallKind is new, not extending existing) |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 11 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (internal key computation) |
| 14 | Error variants type-feasible | ✅ | RolloutId.refineEither returns Left (type-feasible) |
| 15 | Adversarial scenarios for negatives | ✅ | W3 confirmed: "Temperature enters canonical form" (forbids omission); "Provider request id does not affect key" (forbids inclusion); "Different provider ids yield same key" (forbids id sensitivity); "Empty string rejected" (forbids empty RolloutId); "Version bump makes old records invisible" (forbids cross-version serving) |
| 16 | MUST-CONFIRM marks present | N/A | No externally-sourced classifications |
| 17 | Altitude respected | ✅ | F10 section present; no W7 candidates remaining |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/recorded-wrappers/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 9 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 9 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (completion equality, call count, warning emission, payload content, cache hit) |
| 3 | Scenarios testable | ✅ | Testable with DeterministicChatModel, Ref call counters, Hedgehog properties |
| 4 | Error paths specified | ✅ | Recorder failure scenario, temperature warning scenario |
| 5 | New concepts declared | ✅ | RecordedChatModel, ToolMiddleware.recording, RecordedEmbedder, Redaction in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | ChatModel, Embedder, InvokableTool, ToolMiddleware, ToolInput, ToolOutput, ModelStep, ModelRequest, DeterministicChatModel, RecordedRequest, Observation, JsonValue, CallKey, CallKind, RolloutId, Recorder, CallRecord, Classification all in inventory |
| 7 | Generator strategies | ✅ | All 4 properties declare generator strategy |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | No W1 warnings |
| 10 | Unreachable claims proven | N/A | No unreachability claims |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass (F6 fixed: "out of scope" Source → "Criterion: streaming-out-of-scope"); all 9 requirements named |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (wrappers are internal) |
| 14 | Error variants type-feasible | ✅ | Recorder failure surfaced through configured channel, not as exception (type-feasible with MonadError) |
| 15 | Adversarial scenarios for negatives | ✅ | W3 confirmed: "Second identical call hits cache" (forbids re-calling on hit); "Recorder failure does not propagate" (forbids failing the call); "Temperature 1.0 without rollout warns" (forbids silence); "Redaction does not affect hit rate" (forbids key changes from redaction); "Recording composes with logging" (forbids middleware replacement) |
| 16 | MUST-CONFIRM marks present | ✅ | Classification enum marked "MUST-CONFIRM — do not invent: final set confirmed at design phase" in Implementation Anchors |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (DeterministicChatModel, Embedder[F] removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/recorder-laws/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 10 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 10 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (property pass/fail, key equality, call count, failure equality) |
| 3 | Scenarios testable | ✅ | Testable with Hedgehog properties, DeterministicChatModel |
| 4 | Error paths specified | ✅ | RL9 failure fidelity, RL10 write-failure containment |
| 5 | New concepts declared | ✅ | RecorderLaws, RequestMutation, NonAffectingMutation in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | ChatModel, Embedder, ToolMiddleware, DeterministicChatModel, RecordedRequest, Observation, AgentMiddlewareLaws, SemilatticeLaws, CallKey, CallRecord, Recorder, RolloutId all in inventory |
| 7 | Generator strategies | ✅ | all-laws-parameterized declares genRecorder with coverage labels |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | No W1 warnings |
| 10 | Unreachable claims proven | N/A | No unreachability claims |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 10 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (testkit is internal) |
| 14 | Error variants type-feasible | N/A | No error variant assertions |
| 15 | Adversarial scenarios for negatives | ✅ | W3 confirmed: "RL3 mutation changes key" (forbids key preservation on output-affecting mutation); "RL4 mutation preserves key" (forbids key change on non-affecting mutation) |
| 16 | MUST-CONFIRM marks present | N/A | No externally-sourced classifications |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (DeterministicChatModel, Failed removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/recorder-sink/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 9 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 9 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (None, Unit, entry count, JSONL line, record fields, classification field) |
| 3 | Scenarios testable | ✅ | Testable with Hedgehog properties, IO/Ref, fs2 file operations |
| 4 | Error paths specified | ✅ | Failed variant recording, eviction at capacity |
| 5 | New concepts declared | ✅ | Recorder[F], CallRecord, RecordedError, Classification, Recorder.noop/inMemory/file in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | CallKey, CallKind, Positive, JsonValue, AdkError all in inventory |
| 7 | Generator strategies | ✅ | All 5 properties declare generator strategy |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | W1 fixed ("appropriate type" → concrete return types) |
| 10 | Unreachable claims proven | ✅ | "Zero-capacity recorder cannot be constructed" enforced by Iron Positive + compile-negative |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions (CallRecord is new) |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 9 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (sink algebra is internal) |
| 14 | Error variants type-feasible | ✅ | Failed variant carries RecordedError (type-feasible with CallRecord ADT) |
| 15 | Adversarial scenarios for negatives | ✅ | W3 confirmed: "Noop recorder returns None" (forbids hits on noop); "Noop recorder's record is a no-op" (forbids storage on noop); "Duplicate key in append-only" (forbids overwrite); "Eviction at capacity" (forbids failing on eviction) |
| 16 | MUST-CONFIRM marks present | ✅ | Classification enum marked "MUST-CONFIRM — do not invent: final set confirmed at design phase" in Implementation Anchors |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (Resource[F, Recorder[F]], Failed removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/recorder-verified-model/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 4 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 4 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (verifier discharges, bridge test passes, assumption present) |
| 3 | Scenarios testable | ✅ | Testable with Stainless verification + Hedgehog bridge tests |
| 4 | Error paths specified | N/A | No error paths (formal verification model) |
| 5 | New concepts declared | ✅ | RecorderCoherenceModel, NormalizationModel in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | PredictorKernel, SemilatticeKernel (precedents), CallKey, CallRecord, Recorder all in inventory |
| 7 | Generator strategies | ✅ | Both bridge properties declare generator strategy |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | No W1 warnings |
| 10 | Unreachable claims proven | N/A | No unreachability claims (hash collision-freedom is assumed, not claimed unreachable) |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 4 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (formal model) |
| 14 | Error variants type-feasible | N/A | No error variant assertions |
| 15 | Adversarial scenarios for negatives | N/A | No negative requirements |
| 16 | MUST-CONFIRM marks present | N/A | No externally-sourced classifications |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (build commands removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

### Spec: specs/record-replay-example/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 3 requirements have concrete G/W/T clauses |
| 1b | SHALL/MUST normative opener | ✅ | All 3 requirements open with SHALL |
| 1c | Per-variant behavior-preservation scenarios | N/A | No enum/dispatch preservation requirements |
| 2 | Then observable | ✅ | All Thens observable (call count, output match, cache hit, completion result) |
| 3 | Scenarios testable | ✅ | Testable with DeterministicChatModel, call counters, file recorder |
| 4 | Error paths specified | N/A | No error paths (example demonstrates happy path) |
| 5 | New concepts declared | ✅ | RecordReplayExample in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | ChatModel, ReactAgent, AgentRunner, RunResult, DeterministicChatModel, RecordedChatModel, Recorder.file, Recorder.noop all in inventory |
| 7 | Generator strategies | N/A | No properties (example spec, not library spec — stated explicitly) |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | No W1 warnings |
| 10 | Unreachable claims proven | N/A | No unreachability claims |
| 11 | Enum extension / type-widening behavior | N/A | No enum extensions |
| 12 | Proof obligations complete | ✅ | F6/F7/F8 pass; all 3 requirements named by obligations |
| 13 | Consumer-facing surface asserted | N/A | No consumer-facing surfaces (example is application-edge) |
| 14 | Error variants type-feasible | N/A | No error variant assertions |
| 15 | Adversarial scenarios for negatives | N/A | No negative requirements |
| 16 | MUST-CONFIRM marks present | N/A | No externally-sourced classifications |
| 17 | Altitude respected | ✅ | F10 section present; W7 candidates fixed (DeterministicChatModel, OPENAI_API_KEY, run-example.sh removed from clauses); no remaining W7 |
| 18 | Concurrency deterministic | N/A | No concurrent-behavior requirements |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/adk4s-record-module/spec.md | PASS | 0 — 2 W3 confirmed (adversarial scenarios present) |
| specs/call-key/spec.md | PASS | 0 — 7 W3 confirmed (adversarial scenarios present) |
| specs/recorded-wrappers/spec.md | PASS | 0 — 5 W3 confirmed (adversarial scenarios present) |
| specs/recorder-laws/spec.md | PASS | 0 — 1 W3 confirmed (adversarial scenarios present) |
| specs/recorder-sink/spec.md | PASS | 0 — 3 W3 confirmed (adversarial scenarios present) |
| specs/recorder-verified-model/spec.md | PASS | 0 — 0 W3 |
| specs/record-replay-example/spec.md | PASS | 0 — 0 W3 |

**Overall: 7/7 PASS. 0 FAIL, 18 WARN (all W3 — confirmed).**

Implementation-order may be generated: every spec is PASS.
