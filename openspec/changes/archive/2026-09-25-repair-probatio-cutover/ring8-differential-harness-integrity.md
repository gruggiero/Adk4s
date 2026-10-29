Ring 8: Adversarial Spec-Compliance Review — differential-harness-integrity

Fresh context: **yes** — reviewed by an isolated read-only agent (session
`devin-subagent-d19f4f3f`) whose only inputs were the spec, the typed contract,
and the implementation diff vs `d1cf98a3`. The reviewer had no access to the
implementing conversation. A prior author-session review (same inputs,
`fresh-context: no`) is superseded by this one; its residuals were largely
confirmed and remediated below.

Baseline: d1cf98a3dbb37e13f20d9b6e4fc2044ad3a5e1a9
Diff reviewed: full spec-1 diff bundle (`/tmp/spec1-r8/implementation.diff`,
~2,676 lines): `ArmTypes.scala`, `DifferentialHarness.scala`, `SeamTypes.scala`,
`SwapOrder.scala`, `DifferentialResult.scala`, `DifferentialHarnessSpec.scala`,
`DifferentialHarnessCompileNegative.scala`, `DifferentialHarnessIntegrityTypeContract.scala`,
`CutoverGate.scala`, `CutoverGateSpec.scala`, `CutoverBridgeSpec.scala`,
`OracleDiffRunner.scala`, `OracleGreenCheck.scala`, cli-side
`MigrationTypes.scala`/`SwapOrderSpec.scala`/`DifferentialHarnessCliTypeContract.scala`,
`verified/probatio/.../CutoverKernel.scala`, `workflow-hygiene.bats`,
`fixtures/predecessor-control.json`, live shims and `*.predecessor.bak`
counterparts, `ProbatioMain`/`Subcommand` dispatch, `build.sbt`, `stryker4s.conf`.

Dangerous patterns found: **5 unmitigated** (fresh review) → **all remediated or
re-verified** (see Findings); **13 justified** sites accepted (`require` spec-mandated
guards; `.headOption` first-mismatch; `.getOrElse(0)` behind `isComplete` gate;
`Try(...).toOption` git/path failures → `Undetermined`; `sys.error` decoder guards;
shape-mismatch fallbacks unreachable under equal-length `require`s, each carrying
`danger-scan:allow`).

Oracle tampering: none blocking. Findings on generator coverage were verified —
two were corrected on re-inspection (see Findings 9, 11–12 disposition below).

Requirements: **10 PASS, 4 PARTIAL, 0 FAIL** at review time → **all PARTIALs
remediated or dispositioned** (see Requirement verdicts). Compile-negatives:
6/6 PASS (`ArmTree(Nil)`, `ArmDivergence.Identical(List("a.sh"))`,
`SeamConfiguration(Set("ledger"))`, `MigrationState(Set("ledger"))`,
new-variant exhaustiveness, signature pins).

---

## Requirement-by-requirement verdicts (fresh-context)

### The comparison resolves each arm to a materialised tree — PASS
`ArmTree.materialise` resolves the repo root, validates the baseline commit,
creates a detached `git worktree` at the baseline, resolves all seven seams, and
maps non-repo / invalid-baseline / worktree-failure / unresolved-seam to
`Outcome.Undetermined`. **Residual fixed:** a `Left` from `resolveSeams` previously
leaked the just-added worktree; `materialise` now `git worktree remove --force`s it
before returning `Undetermined`.

### The predecessor arm contains the predecessor implementation at every seam — PASS
`.bak` copies for the five swapped seams; `liveIsPredecessor` keeps the baseline's
committed file for `Ledger`/`Checkpoint` (the pre-port implementation IS the
baseline script). Digest is taken post-write, so the recorded resolution binds
actual arm content.

### The ported arm contains the ported implementation at every seam — PASS
(strengthened). The ported resolution now **always writes the canonical
`exec <bin/probatio> <sub>` shim** rather than keeping the baseline file —
byte-identical at a swapped baseline, and correct at a pre-swap baseline where
the committed file is still the predecessor script (the reviewer's finding 6:
the ported arm could silently hold predecessor bytes).

### A missing predecessor implementation is could-not-determine — PASS
Short-circuit `Left` naming the seam → `Undetermined`; no `ArmTree` is produced,
so no comparison is attempted.

### A comparison side that was not materialised cannot be constructed — was PARTIAL, now PASS
`final case class ArmTree private` emitted a **public `copy`** — a construction
backdoor (`arm.copy(resolutions = forged)`). **Remediated:** `ArmTree` is now a
`final class` with a private constructor — no `copy`, no `unapply`, no `apply`.
`ArmTree(Nil)` remains a compile error; construction is `new ArmTree` inside the
companion only.

### A comparison whose arms resolve identically is refused — PASS
`compare` returns `Left(ArmDivergence.Identical)` — a type disjoint from
`DifferentialResult`/`CutoverVerdict`; `OracleDiffRunner` prints `REFUSED` and
fails, never mapping to PROCEED/REVERT. Property covers all-ported /
all-predecessor / mixed configs at 200 iterations.

### Arm identity is content-based, not path-based — PASS
`divergence` compares `implementationDigest` pairs only; `sourcePath` never
enters the verdict. Same-bytes-different-paths → `Identical` (test + property).
`Identical` from strings is a compile-negative.

### The seam set covers the full swap order — PASS
Seven `ToolId` cases, exhaustive `seamPath`/`predecessorSource`, seven-position
`SwapOrder` ending `GateLast`; `resolveSeams` iterates `ported ++ (swapOrder --
ported)` — total bijection. CLI contract asserts same-name/same-path conformance
between the two `ToolId` enums.

### Suite files exercising tools outside the seam set are reported as not-compared — PASS (caveat recorded)
`unseamedToolPaths` reports file→unseamed-paths hits and `OracleDiffRunner`
prints them as not-compared. Caveat (finding 8, accepted): detection is substring
search — comments count (conservative over-report); invocations via alternate
spellings are missed (under-report). The real suite names paths literally, so
live behavior is correct.

### The comparison reproduces the recorded predecessor control — was PARTIAL, now PASS
Baseline mismatch → `Undetermined`; absent file → `Undetermined`; per-file count
equality → `Ran`, mismatches → `Finding` naming files. **Remediated (finding 4):**
malformed JSON or mistyped fields previously threw; the whole control access is
now wrapped in `Try → Undetermined` (`checkAgainstControl` inside
`checkPredecessorControl`).

### Retargeted source-inspection acceptance tests — PASS
D7 greps `$root/workflow/**/*.scala` for the dangling `sync-skills.sh` name and
requires `install-skills.sh`; D8 greps `HarnessPayloadReader.scala` for
`ujson`/`str("cwd")` and rejects regex/sed extraction. Meta-tests pin the bats
bodies; arm-level tests prove pass-on-good / fail-on-dangling /
fail-on-sed-style.

### The worse-file fold is exact — PARTIAL (dispositioned)
`isWorse` adds `predecessorPresent && portedPresent` conjuncts the spec's literal
postcondition omits. On a file absent from one run the literal contract counts it
worse; shipped code does not — but `isComplete` gates that edge before any
verdict, and `DifferentialResult` is spec-anchored "reused unchanged"
(byte-identical to baseline). The model's `worseIndices` matches the spec's
literal contract; the shipped fold narrows it on unreachable input. Disposition:
documented narrowing; the new bridge property (below) binds shipped and model on
complete comparisons where the semantics coincide.

### New ToolId variants are exhaustive — PASS
`-Wconf:name=PatternMatchExhaustivity:e` active; all match sites widened; cli
contract asserts conformance; `MigrationProtocolSpec` exercises all 2^7 subsets.

### Divergence and worse-file decisions are verified and bridged — was PARTIAL (Ring-6 obligation unmet), now PASS
**Finding 1 (MEDIUM):** `CutoverBridgeSpec` bridged only `cutoverDecision`;
the spec's contract requires the shipped `divergence`/`worseFiles` bound to
`divergenceDecision`/`worseIndices`. **Remediated:** two new bridge properties —
`bridge-divergence` (shipped verdict ⟺ model verdict on generated 7-seam
resolution pairs; `ArmsDiverged(i)` must name the shipped first differing seam's
position) and `bridge-worse-indices` (shipped `isWorse` positions == model
`worseIndices` on complete comparisons). Both green.

---

## Findings — disposition

1. **MEDIUM — Ring-6 bridging obligation unmet** → **FIXED** (two bridge
   properties added to `CutoverBridgeSpec`, green).
2. **MEDIUM — `worseFiles` narrows the written contract** → **DISPOSITIONED**:
   presence conjuncts are spec-anchored unchanged code; unreachable under the
   `isComplete` gate; bridge binds on complete inputs. Documented, not altered.
3. **MEDIUM — `ArmTree` forgeable via public `copy`** → **FIXED**: converted to
   `final class` with private ctor; the codebase's own "copy is sealed shut"
   convention (per `ChainStateAttributionSpec`).
4. **LOW-MEDIUM — control-file parsing throws** → **FIXED**: `Try → Undetermined`.
5. **LOW — leaked worktree on failed materialisation** → **FIXED**: `git worktree
   remove --force` on the `resolveSeams` failure path.
6. **LOW — ported resolution can install predecessor bytes** → **FIXED**: the
   ported resolution now always writes the canonical shim (byte-identical to the
   committed shim at swapped baselines; verified against the live shims).
7. **LOW — silent drop in CLI `MigrationState` decoder** → **FIXED**: `case _ =>
   None` → `sys.error`, matching the spec-sanctioned decoder guards in
   `SeamTypes`.
8. **LOW — `exercisedToolPaths` substring heuristic** → **ACCEPTED**: over-report
   is conservative; the real suite names paths literally.
9. **LOW — mixed-config generators covered only prefix subsets** → **FIXED**:
   `genMixedSeamConfiguration` (both copies) now draws a bitmask over all 126
   non-empty proper subsets.
10. **LOW — spec prose claimed concepts unaltered while the diff extended the
    concept's State** → **FIXED**: spec's Concepts-Used note corrected.
11. **INFO — alleged empty-list model divergence** → **NOT CONFIRMED**:
    `isComplete` is vacuously true on an empty file list, so the shipped gate
    Proceeds on empty — matching the model (`bridge-cutover-empty` test already
    asserts this). No divergence exists.
12. **INFO — alleged missing diverged-direction coverage** → **NOT CONFIRMED**:
    `genIndependentArmPair` (weight 4/10) generates both arms' contents
    independently, so diverged pairs are produced; the identical-heavy branches
    exist to reach the ≥20%-identical cover floor, not to exclude divergence.

## Residuals remaining (accepted, none contradict a spec clause)

- A bats file crashing mid-run after partial TAP output counts as present for
  that file (spec mandates only the zero-TAP case → absent). Direction is safe:
  a crash *understates* predecessor quality, it cannot fabricate parity.
- `runSuite`/`exercisedToolPaths` throw if a baseline lacks `tests/` — a crash,
  never a wrong verdict; all real baselines carry the suite.
- `OracleDiffRunner`'s `worktree remove` failure is swallowed (cleanup
  best-effort; a leaked worktree is cosmetic, not a verdict risk).
- `probatioOracleDiff`/`probatioOracleControl` print SKIPPED and pass vacuously
  when their fixtures are absent — the fixtures are tracked and present; noted
  for the record.

## Post-remediation verification

- `probatio-core` test suite: **64 tests green** (`DifferentialHarnessSpec` 28,
  `CutoverBridgeSpec` 8 incl. both new bridges, `CutoverGateSpec` 20,
  `SwapOrderSpec` 4, `DifferentialHarnessCompileNegative` 4)
- `probatio-cli`: `MigrationProtocolSpec` + `SwapOrderSpec` green
- scalafix clean on probatio-core; cli failures confined to pre-existing
  baseline-identical violations in `packaging/`/`cli/` files outside this spec
- scalafmt applied to all touched files
