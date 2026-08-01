# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     The apply phase tracks detailed state in implementation-progress.md;
     tasks.md is regenerated from it at each checkpoint (Step 13). -->

## 1. eval-core

- [ ] Step 0 — baseline SHA + inventory snapshot; add `adk4s-eval` module to `build.sbt` + `project/Dependencies.scala` (dependsOn structured-llm; deps catsEffect, fs2Core, munitMain, munitCatsEffect, hedgehogMunit + testDeps; scalacOptions ++= scala3Options; aggregated by root); verify `sbt adk4s-eval/compile` with empty package object
- [ ] Step 1 — typed contract: `Example[I, O]`, `Score`, `TraceEntry`, `Trace`, `Metric[F, I, O]` trait + `fromPredicate`/`fromDouble` helpers, `EvalConfig`, `EvalOutcome[+O]` enum, `EvalRow[I, O]`, `EvaluationResult[I, O]` with `toJson`/`fromJson`/`toCsv` signatures, `EvalError` enum (extends Throwable), `Evaluate` object factory signature, `Dataset` object `fromJsonl` signature, `Metrics` object `exactMatch`/`containsAll` signatures (compiles in test sources, human gate)
- [ ] Step 2 — test oracle: 9 Hedgehog properties (devset-order-under-parallelism, failure-score-substitution, mean-aggregate, empty-devset-score-zero, feedback-inert, metric-called-once-per-example, determinism-under-parallelism, json-round-trip, trace-is-none-in-eval-mode) + scenarios (parallelism, poisoned example, metric raises, maxErrors cancel via Deferred+TestControl, no cap, empty devset, all-failure, counting metric trace, feedback inert, round-trip, formatVersion, parallelism sweep, CSV mixed outcomes, JSONL valid/malformed/empty, exact match miss, contains-all partial) + 2 compile-negative stubs (EvalOutcome non-exhaustive, EvalError non-exhaustive) — run ORACLE POLARITY (red / green-by-design) (human gate)
- [ ] Step 3 — implementation: data types (Example, Score, TraceEntry, Trace, EvalConfig, EvalOutcome, EvalRow, EvalError), EvaluationResult with toJson/fromJson/toCsv (upickle + ujson, formatVersion=1), Metric trait + helpers, Metrics.exactMatch/containsAll (pure Applicative), Evaluate harness (fs2 parEvalMap ordered + interruptWhen for maxErrors + Ref counter), Dataset.fromJsonl (JSONL reader, line-number error)
- [ ] Ring R0 — `sbt adk4s-eval/compile` + `sbt adk4s-eval/Test/compile` pass; exhaustiveness escalation verified for EvalOutcome/EvalError
- [ ] Ring R1 — `sbt scalafixAll --check` + `sbt scalafmtCheck` pass; WartRemover (Throw active — EvalError via F.raiseError; Any excluded — ujson.Value in export; Var active — no mutable state)
- [ ] Ring R2 — import audit: org.adk4s.eval imports only cats-effect, fs2-core, structured-llm, ujson; no workflows4s, llm4s client, adk4s-core, adk4s-orchestration, adk4s-optimize
- [ ] Ring R3 — all 9 properties pass; concurrent scenarios pass via TestControl (deterministic, no wall-clock sleeps)
- [ ] Ring R4 — json-round-trip property passes (formatVersion=1, round-trip equality); CSV column contract scenario passes
- [ ] Ring R8 — adversarial review (fresh context): check for parEvalMapUnordered (R1.1 violation), feedback leaking into aggregate (R1.6), metric retry around metric call (R1.7), trace=Some in harness (R1.5), EvalError extending AdkError (R1 spec violation)
- [ ] Step 12 — concept-delta check (scanner diff); update openspec/concept-inventory.md with 14 new concepts; update openspec/capability-profile.md (adk4s-eval module row); create openspec/concepts/eval-harness.md + openspec/concepts/metric-contract.md
- [ ] Step 13 — regenerate tasks.md from implementation-progress.md; commit checkpoint

## 2. llm-judges

- [ ] Step 0 — baseline SHA + inventory snapshot; verify adk4s-eval module compiles (from spec 1)
- [ ] Step 1 — typed contract: `Judges.semanticF1[F]` / `Judges.completeAndGrounded[F]` factory signatures returning `Metric[F, String, String]`; `SemanticF1Judge` / `CompleteAndGroundedJudge` case classes + hand-written `Schema.instance` definitions (Smithy IDL + smithy4s Schema via implicit derivation); verify `sbt adk4s-eval/compile` compiles without smithy4s-sbt-codegen plugin (human gate)
- [ ] Step 2 — test oracle: 4 Hedgehog properties (semantic-f1-eval-mode, semantic-f1-optimization-mode-binarized, out-of-range-clamped, complete-and-grounded-eval-mode) + scenarios (eval mode, optimization mode pass/fail, schema compiles without codegen, unparseable judge completion, precision above 1.0, recall below 0.0) using mock StructuredLLM — run ORACLE POLARITY (human gate)
- [ ] Step 3 — implementation: Judges object with semanticF1/completeAndGrounded factory methods; judge schemas via Schema.instance (hand-written Smithy IDL + smithy4s Schema derivation); binarize toggle on trace.isDefined; Constraint.check for out-of-range clamping + feedback flag; parse failure propagates as raise (caught by harness)
- [ ] Ring R0 — `sbt adk4s-eval/compile` + `sbt adk4s-eval/Test/compile` pass; judge schemas compile without codegen plugin
- [ ] Ring R1 — `sbt scalafixAll --check` + `sbt scalafmtCheck` pass; WartRemover (Throw active — judge parse failure via F.raiseError; Any excluded)
- [ ] Ring R2 — import audit: Judges.scala imports only structured-llm (StructuredLLM, Schema, Prompt, Constraint), cats-effect, adk4s.eval (Metric, Score, Trace); no forbidden imports
- [ ] Ring R3 — all 4 properties pass; mock StructuredLLM scenarios pass
- [ ] Ring R8 — adversarial review (fresh context): check for judge schemas in structured-llm-test-models (R1.11 violation), manual if/else instead of Constraint.check (R1.13 violation), binarize missing on trace.isDefined (R1.10 violation), harness crash on parse failure (R1.12 violation)
- [ ] Step 12 — concept-delta check (scanner diff); update openspec/concept-inventory.md with 4 new concepts (Judges.semanticF1, Judges.completeAndGrounded, 2 judge schemas)
- [ ] Step 13 — regenerate tasks.md from implementation-progress.md; commit checkpoint
