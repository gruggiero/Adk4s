# Implementation Progress: add-memory-api

## 0. Build wiring (prerequisite for spec 1)
- [x] Add `adk4s-memory-api` lazy val to `build.sbt`
- [x] Create empty source tree
- [x] Verify `sbt "adk4s-memory-api/compile"` succeeds
- [x] Verify `sbt "adk4s-memory-api/dependencyTree"` shows no heavy deps

## 1. agent-memory
- [x] Step 1 — typed contract (human gate)
- [x] Step 2 — test oracle (human gate)
- [x] Step 3 — implementation
- [x] Rings: R0 R1 R2 R3 R5 R8
  - R5 (mutation): initial run skipped (Scala 3 `-source:future` incompatibility).
    Re-run with `source:future` commented out: 15 mutants, 9 survived (40%).
    Root cause: Hedgehog's `assert`/`assertEquals` return `Result` objects that
    are silently discarded in `test` blocks — all 20 test-block assertions were
    no-ops. Fix: added `assertM`/`assertEqualsM` helpers that delegate to real
    munit assertions via `withMunitAssertions`. Added 3 edge-case tests (empty
    query, whitespace-only query, direct `naiveScore` tests). Final: **15/15
    killed, 100% mutation score**.
- [x] Concept-delta + checkpoint

## 2. memory-retriever-bridge
- [x] Step 1 — typed contract (human gate)
- [x] Step 2 — test oracle (human gate)
- [x] Step 3 — implementation
- [x] Rings: R0 R1 R2 R3 R5 R8
  - R5 (mutation): 13 mutants, all killed, **100% mutation score**.
    Added 2 targeted collision tests to kill `synthesizeId` string-literal
    mutants (None-vs-Some provenance, multi-vs-single payload entries).
- [x] Concept-delta + checkpoint

## 3. memory-testkit
- [ ] Step 1 — build wiring + typed contract (human gate)
- [ ] Step 2 — test oracle (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: R0 R1 R2 R3 R8
- [ ] Concept-delta + checkpoint
