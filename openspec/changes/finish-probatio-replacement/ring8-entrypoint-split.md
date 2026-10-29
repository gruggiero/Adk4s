Ring 8: Adversarial Spec-Compliance Review — entrypoint-split

Fresh context: yes (isolated read-only subagent `a44334b2`; inputs were the
spec, the typed contract, and the baseline diff only)
Baseline: `556c881520f5bd5ae254c1f6e10ba67dfb331bae`
Diff reviewed: 11 new `*Cmd.scala` mains, `SubcommandEntrypoints.scala`
deleted, `EntrypointSplitOracle`/`CliSurfaceSpec`/`EntrypointContractSpec`/
`SubprocessConformanceSpec`/`CliParseErrorSpec`/`EntrypointSplitTypeContract`,
`RenameDeferral.scala`, progress + spec-cite bookkeeping; fixtures read
directly from the tree.
Dangerous patterns found: 3 (0 fixed / 3 justified-with-notes — R2, R3, R4)
Oracle tampering: none — oracle/test diffs vs baseline are append-only; the
single post-approval edit (`objectSpan` fallback `decl - 1` → `decl`) is a
strictness correction that reports *more* divergence, not less.
Requirements: 3 PASS, 0 PARTIAL, 0 FAIL

## Verdict per requirement

- Each subcommand's entrypoint resides in its own source file — **PASS.**
  Exactly one top-level object per new file; `SubcommandEntrypoints.scala`
  deleted; `layoutViolations` covers crowded/missing/split-brain and names
  file + both entrypoints.
- Every subcommand behaves exactly as before — **PASS.** 66-row corpus
  replays byte-identically through native + archive under the hermetic base
  (16/16); bats before/after recorded identical (279 tests, same 17
  pre-existing failures, same files). Reservations R1–R3.
- Moved code is moved, not edited — **PASS.** `objectSpan` covers doc block
  + object to last non-blank line; spans tile the before-file minus
  package/import/header; exact `Vector[String]` equality; independently
  spot-verified (fixture == `git show 556c881:…` blob — verified by the
  parent, empty diff). Reservations R4–R6.
- Compile-Negative Obligations — **PASS (n/a, none stated).** A compile-
  checked type contract was added anyway (11 `run` signatures + 8
  cross-entrypoint `private[cli]` pins).

## Proof-obligation rows

| Obligation | Artifact | Verdict |
|---|---|---|
| Every subcommand has its own file | `CliSurfaceSpec` | PASS — real-tree scan, 14/14 green |
| A file holding two entrypoints is reported | `CliSurfaceSpec` | PASS — synthetic-decls variant; R6 closed by a real temp-dir scanDir test added during remediation |
| The acceptance suite is unchanged | `hook-tiers.bats` via `probatioOracleDiff` | PASS — recorded before/after, per-file identical (durable pair: `entrypoint-split-bats-{before,after}.txt`) |
| A rejected invocation is rejected identically | `CliParseErrorSpec` | PASS — 11 rejections × 2 artifacts, exact (exit, out, err) |
| Every observable is preserved | `SubprocessConformanceSpec` | PASS — property + full 66-row enumeration; corpus-scope caveat R1 |
| A moved body compares identical | `EntrypointContractSpec` | PASS — all 11 green; fixture provenance verified against the baseline blob |
| An edit hidden in the move is detected | `EntrypointContractSpec` | PASS — planted substitution detected and named |

## Reservations and remediation

**R1 — Corpus narrower than the property's stated domain.** The recorded
corpus is help/rejection forms only; zero successful business-logic
invocations. Combined enforcement remains adequate (byte-identical moved
bodies + identical acceptance suite), recorded here rather than amended —
the corpus was recorded and gate-approved at Step 0/2; success-path
behaviour is covered by the moved-body comparison and the bats pair.

**R2 — `danger-scan --also` row is worktree-state-coupled.** Its recorded
stdout depends on `git diff HEAD`, not only the hermetic env; it replays
identically on a clean tracked tree (the committed state) and flags
divergence while the split is uncommitted — the divergence observed
mid-verification was this mechanism working as designed, not a behaviour
change. Documented in the R3 ledger row.

**R3 — `replay` artifact fallback** — FIXED during remediation:
`case _ => native` replaced with `case "native" =>` plus a `sys.error` on
unknown artifact names (`SubprocessConformanceSpec.scala:189-192`).

**R4 — `movedBodyDiffs` residual blind spots** — partially fixed: line
loading now reads raw bytes and splits on `\n` (`readLines`), so an
LF→CRLF edit inside a moved body is caught. Verified absent regardless:
no file-level declarations outside spans; no extra top-level objects;
all new files' imports are strict subsets of the original 13 with no
silent rebinding (`-Werror/-Wunused` enforces).

**R5 — Fixture provenance** — VERIFIED by the parent:
`git show 556c881:…/SubcommandEntrypoints.scala` diffs empty against the
recorded fixture; git renders it as a pure rename. Spec anchor line cites
corrected (4,701 → 4,704; +3 from spec-2 defect fix `192961d`).

**R6 — Synthetic-only crowded-file adversarial** — FIXED: added an
end-to-end test planting a real `Merged.scala` holding `GateCmd` +
`SpecLintCmd` declarations in a temp dir; `scanDir` → `layoutViolations`
reports it naming both (`CliSurfaceSpec`).

**Shared-helper decision** — justified conflict, spec anchor amended:
the 8 `private[cli]` cross-entrypoint members stay in their owning
objects; the anchor's "move to a shared file" instruction is incompatible
with "no line inside a moved body MAY change" (call-site qualifiers sit
inside moved bodies). Resolution recorded on the anchor row.

**RenameDeferral 19→20** — assessed in-scope: a recorded value kept in
sync with the on-disk pin count; the drift was introduced by this
change's own planning commit (`cae8f0e`). Repaired at `7ba16ee`, spec
cite synced, `MigrationProtocolSpec` green 21/21.

Post-remediation oracle re-run: `CliSurfaceSpec` 15/15,
`EntrypointContractSpec` 36/36, `SubprocessConformanceSpec` 16/16,
`CliParseErrorSpec` 8/8 — 75/75 green.
