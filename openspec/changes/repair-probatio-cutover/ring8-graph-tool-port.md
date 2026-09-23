# Ring 8: Adversarial Spec-Compliance Review — graph-tool-port

- **Fresh context**: yes — isolated read-only subagent, inputs limited to the spec
  (`specs/graph-tool-port/spec.md`), the typed contract
  (`GraphToolPortTypeContract.scala`), and the implementation diff
  (38 files, +6151, new files intent-to-added so the diff covered them).
  No implementation conversation, no progress tracker, no prior review.
- **Dangerous patterns**: 19 candidates in the new graph sources — all reviewed,
  `case _: X` arms rewritten as destructuring patterns (`GraphNode.kindName`,
  `GraphWire` artifact/code cases), the rest carry same-line
  `danger-scan:allow` justifications; mechanical scan: **OK, no unjustified
  dangerous patterns since HEAD**
- **Oracle tampering**: none — the retargeted bats assertions are stricter than
  the originals (JSON line isolated before `jq` instead of silently failing on
  merged output)

## Verdicts

No requirement FAILs at review. Three real defects found and remediated:

- **F1 — `GraphAudit.reaches` fuel abort dropped pending artifacts** (found:
  diverged from the verified kernel on follow-edges-only graphs). The loop
  checked fuel before inspecting the remainder of the queue, so a resolving
  artifact already enqueued when fuel hit 0 was never seen — the kernel checks
  `artifacts.contains` *before* the fuel test. **Fixed**: fuel now bounds
  edge-follows only; when fuel exhausts, already-enqueued nodes are still
  inspected for a resolving artifact (`rest.exists`). Regression test added:
  "an enqueued resolving artifact still counts when fuel exhausts" — the
  enqueue-fork shape makes the pending artifact the unique decider.
- **F2 — conservation property was self-referential; `rowCount` never asserted
  and buggy**. `SourceCorpus.rowCount` used
  `lines.drop(start+1).takeWhile(startsWith("|"))`, which hits the blank line
  after every heading and returns 0 for registry and spec rows — the property
  asserted only `rowsRead == rowsBound + unlinkable` (both sides derived from
  the same parser output). **Fixed**: the counters now replicate the parser's
  read-set independently (`tableRows` scan shape — skip non-`|` lines to the
  first row, contiguous block, separators and header dropped; marker
  first-cells exempt in the three concept tables; inventory counts all
  non-separator `|` rows inside `## ` sections, header quirk reproduced), and
  the property asserts `corpus.rowCount == build.rowsRead` as well as
  `rowsRead == rowsBound + unlinkable`.
- **F3 — `diffExports` substring masking**. `strictBindingExplained` accepted a
  predecessor-only `code:`/`artifact:` element whenever any reported text
  *contained* the path as a substring — an unrelated row mentioning a
  superstring could mask a genuinely dropped binding. **Fixed**: path-shaped
  tokens (the parser's own `/`+`.`+no-space shape) are extracted from reported
  texts and exact membership is required.

## Observations resolved (no defect)

- **O3 — `Files.isRegularFile` vs tracked-file resolution**: correct as-is.
  The predecessor resolves artifacts with `os.path.isfile`
  (`openspec-graph.py:205,246`); `isRegularFile` is the exact parity equivalent
  (both follow symlinks). Resolution is deliberately not git-tracking-scoped —
  that is the predecessor's semantics.
- **O6 — vacuous bats assertion**: fixed earlier — the `jq` pipeline in the
  happy-path checks now filters the JSON line out of `run`'s merged
  stdout+stderr before asserting.

## Post-remediation evidence

- `SpecLintEngineSpec` 116/116 (incl. the F1 regression test and the wired
  conservation property — the independent `rowCount` empirically matches
  `rowsRead` across the generated corpus space)
- `ConformanceSpec` 13/13 (stricter `diffExports` still agrees with the
  predecessor modulo the two sanctioned strict-binding divergence classes)
- `VerifiedKernelBridgeSpec` 17/17
- `fact-extraction.bats` 10/10; full CLI suite 695 green
- danger-scan: OK — no unjustified dangerous patterns since HEAD
