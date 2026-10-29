# Ring 8 — Adversarial Spec-Compliance Review: delivery-verified

- **Reviewer**: fresh-context isolated read-only subagent (`d782de9d`)
- **Inputs**: `specs/delivery-verified/spec.md` + typed contract + `git diff 38bfb22` only — no implementation conversation
- **Baseline**: `38bfb2239c81a5cb7d1abe9c38e2e682d4fdc3d2`
- **Verdict**: **5 PASS / 1 PARTIAL / 0 FAIL** — oracle tampering: none — dangerous patterns: ~17 candidates, all justified (0 unjustified)

## Requirement verdicts

| Requirement | Verdict |
|---|---|
| MUST-CONFIRM (publish authorised, not inferred) | PASS — the diff performs no push; workflows only react to hosted events; the gate stays a manual/ledger obligation |
| CI job observed to run and pass | PASS (code half) — `verify.yml` triggers `push: main` + `pull_request`, no `paths:` filter, no job-level `if:`/`continue-on-error`; both neutraliser shapes are specifically linted |
| CI job observed to fail on a regression | PASS — manual hosted obligation; the lint requires every suite step unguarded (`missingCiSteps` rejects `if:`/`continue-on-error:` incl. marker-line and literal-block masking) |
| CI job runs the tools through the archive | **PARTIAL → remediated** (F1, below) |
| Delivered binary built with the tested toolchain | PASS — full chain: `parse` exact `<dist>/<ver>`; `readEmbedded` maps both observed markers (`GraalVM CE 21.0.2+13.1` modern, `GraalVM 22.3.1 Java 17 CE` legacy), both directly tested; `toolchainVerdict` is exact equality; `Rejected` names both identities, `Unreadable` names the binary, missing read is an issue; `release-probatio.yml` `TESTED_TOOLCHAIN` matches the matrix pin and `build.sbt` local pin; no public path omits the tested identity |
| Release candidate built and validated locally | PASS (code half) — checksums recomputed at manifest build, missing artifact named, `builtFromCI` fail-closed |

Property `toolchain-check-accepts-iff-identical`: PASS — asserts `accepted == candidate.identityOption.contains(tested)` (spec predicate) plus payload legs; generator constructive, no filtering.

Compile-negative: PASS — 3 `compileErrors` pins in `NativeGateDeliveryTypeContract` (1-arg `validateAll`, 3-arg `ReleaseCheck.run`, `Version("")`).

## PARTIAL F1 — archive-path check was job-boundary-blind

**Finding**: `archivePathIssues` computed "before" over every `- `-marked region in the file. An `assembly` step in a *different, parallel* job satisfied `buildsArchive` while the acceptance job never had the archive — artifacts do not cross jobs without upload/download. A `- ` item in a same-job non-steps list (e.g. `matrix.include`) was also a false positive.

**Why the tests missed it**: all four synthetic adversarial workflows were single-job.

**Remediation** (`d64afb7`): `jobStepsRegions` segments the document by job (two-space keys under `jobs:`) and restricts each job's step blocks to its own `steps:` list (runs to the next same-or-lesser-indent line or job end). `archivePathIssues` now requires the `assembly` needle before the acceptance step **inside the same job's steps**, and reports an acceptance step outside any job's steps list as unverifiable. +2 adversarial cases pinned: assembly-in-parallel-job reported; `assembly`-named matrix item reported. Suite: 17/17 green.

## Dangerous-pattern judgments (manual hunt — scanner compares worktree-vs-HEAD)

- `ToolchainIdentity.scala:85` `case _ => None` — malformed recorded form → explicit no-identity.
- `ToolchainIdentity.scala:139–142,167–174` `sys.error` in `ReadWriter` decoders — decode crashes; inside `fromDirectory` the `NonFatal` catch maps to a named `Left`, not a silent default.
- `ReleaseManifestIO.scala:8,97` outer `NonFatal` → named `Left`; `:120` per-binary `NonFatal` → `Unreadable` (validation finding naming the binary, never skipped); `:138` non-artifact filename → `None` (`probatio-windows-x86_64` correctly becomes a flagged unexpected artifact, not dropped).
- `ReleaseCheck.scala:71,75,79` — unparseable identity / env lookup / wrong arity → `sys.error`, fail-closed.
- `ReleaseArtifact.scala:50,69`; `SbomGenerate.scala:28,38` — type-rejection/decode/arity, justified.
- `ReleaseManifestIOSpec.scala:518` `case other => fail(...)` — generator-invariant, justified. Soft spot recorded: `binaryNames` Nil-fallback `Gen.constant("probatio-linux-x86_64")` (:500–502) reachable only if `committedNativePlatforms` were emptied — harmless (the `kind` arm, not the name, determines the verdict).
- Re-scan of the full baseline range: `danger-scan.sh 38bfb22` → OK, no unjustified patterns.

## Oracle-tampering verdict — commit `9e3fcb9`: NOT a weakening

The invariant predicate and the three 20% cover thresholds are unchanged; the assertion still compares against the spec predicate (`identityOption.contains(tested)`), not the implementation. Expected class rates are now 30% identical / 40% readable-differs / 30% unreadable vs 20% floors at n=300 — comfortable margins, whereas at n≈100 a 25%-probability class had ~10–13% odds of dipping under its floor (the observed flake). Net hardening.

## Could-not-verify items → resolved by the parent

1. `SubcommandEntrypoints.before.scala` hunks — verified byte-identical to live `GateCmd` (1656-line span diffed clean); verbatim mirror of the spec-6 body change, legitimate corpus repair.
2. danger-scan baseline range — re-run `danger-scan.sh 38bfb22` → clean.
3. Hosted-run proof obligations (green run, regression runs) — remain ledger-only obligations; nothing in code discharges them, and none are claimed.
