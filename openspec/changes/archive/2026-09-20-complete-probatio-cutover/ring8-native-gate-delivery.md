Ring 8: Adversarial Spec-Compliance Review — native-gate-delivery

Fresh context: yes (read-only subagent; inputs limited to spec + diff + predecessors + supporting types)
Diff reviewed: ProbatioPlugin.scala, InstallResolver.scala, ShimGenerator.scala,
packaging/{BudgetVerdict,LatencyMeasurement,ReleaseCheck,ReleaseManifestIO,ReleaseValidator}.scala,
SubcommandWiring.scala (forgivePredicate batching), release-probatio.yml + spec-9 test/contract files
Dangerous patterns found: audited; 2 justified same-line (ReleaseCheck arity-rejection,
ReleaseManifestIO type-rejection + typed-catch); `AtomicReference` memoization justified in-line
Requirements: findings remediated and re-verified green; residual edge concerns dispositioned below

## DANGEROUS findings — fixed

1. **Fictional shim target.** `probatioGateShim` generated a shim that execs the
   *model* path (`/usr/local/bin/probatio`, `/usr/local/bin/probatio-jar-launcher.sh`)
   while `probatioInstall` writes under `~/.probatio/bin` — the installed shim
   would exec a file nothing ever creates. Fix: the task binds the resolution to
   the actual installed `File` (`resolution.copy(path = Some(installed.getAbsolutePath))`),
   and `resolveForShim` runs BEFORE install side-effects so a blocked resolution
   never produces a partial install + shim. `InstallResolver` model paths were
   then made real — they name `~/.probatio/bin/...`, the same locations the
   install writes.

2. **`forgivePredicate` batching diverged from the predecessor oracle.** The
   batched `git diff --name-only` (a) reported the NEW path on renames while the
   predecessor's per-row `git diff --quiet <baseline> HEAD -- <artifact>` pathspec
   sees the deleted OLD path, and (b) C-quoted non-ASCII filenames so literal
   membership failed. Fix: `--no-renames` (deletion reports the old name),
   `-z` NUL-separated output (no C-quoting), trailing `--`, directory-boundary
   matching, and literal per-row fallback for pathspecs the membership rule
   cannot model (empty, absolute, `.`/`./`, `..`, trailing slash, magic chars
   `* ? [ : !`). Git failure → "changed", matching the predecessor. Empirical
   parity: rename old/new path, modified non-ASCII path, directory, trailing
   slash, dot, empty, glob, absolute — all agree with the per-row oracle.

## PARTIAL / edge findings — fixed

3. **`resolveForShim` warnings never reached the log.** The resolution produced
   a warn line but the task only `sys.error`'d on `Left`; a resolved-with-warning
   result was silent. Fix: `emitLogLines` emits every recorded line at its tag
   level (`[warn]` → warn, `[info]`/untagged → info) — exactly once per ring.

4. **`probatioInstall` assumed a present binary was valid.** `detectScenario`
   now requires `probatioExpectedSha256` to match the binary's SHA-256;
   a mismatch (or absent setting) is `PrebuiltChecksumInvalid`, never assumed.

5. **`$PROBATIO_JAR` env-var launcher replaced.** `probatioAssemblyJar`
   (Option[File]) binds the launcher to a concrete artifact; the task fails
   naming the setting when unset.

6. **`ReleaseManifestIO` trusted sidecars.** Now: `Files.list` closed via
   `Using.resource`; the recorded token must be 64-char hex; when the content
   file is present its recomputed SHA-256 must equal the recorded digest — a
   stale/corrupt sidecar is `Left`, not a manifest that cannot detect the
   mismatch. Direct suite `ReleaseManifestIOSpec` covers discovery, missing
   artifacts, malformed/mismatched sidecars, SBOM parse, and `ReleaseCheck`
   exit codes.

7. **`ReleaseCheck` hardcoded `builtFromCI = true`.** Now derived from the
   invocation environment (CI presence) — the validator's provenance check is
   meaningful.

8. **`probatioUninstall` left the launcher.** `uninstallArtifacts` now removes
   `launcherScriptPath` alongside the shim and binary.

9. **`runDelegatingTask` leaked resources on spawn failure.** The `PrintWriter`
   is closed in `finally`; the stdout `Source` is closed after `mkString`. A
   spawn exception still fails the task, now without a dangling handle.

10. **Unquotable shim target emitted a corrupt script.** `generateShim` rejects
    a path containing `"` `\` `$` `` ` `` or newline with `Left` naming the
    cause — a corrupt shim is never emitted.

11. **Spec-9 artifacts untracked** (spec-lint F9): `inventory-snapshots/
    native-gate-delivery-before.md`, the new packaging sources/tests, and the
    plugin contract test staged.

## Reviewer observations — dispositioned, not defects

- **Duplicate `perTurnSubcommands`** in `InstallResolver` — intentional; the
  plugin cannot import packaging code (R-S1). Both copies named in comments.
- **Launcher under `java -jar`** — the multicall contract resolves from the
  invocation name and falls back to first-arg dispatch; the launcher passes
  `<subcommand>` as the first user arg. Exercised end-to-end during the
  latency measurement (all nested calls ran through the launcher path).
- **NaN / non-finite latency** — `BudgetVerdict.evaluate` compares
  `median < budget`; NaN fails the comparison → `Exceeded` — the safe
  direction (blocks delivery rather than silently meeting budget).
- **`writeShim` parent-guard** — removed after Stryker proved `IO.write`
  already creates parents (the `→false` mutant survived because the guard
  was unreachable-in-practice).

## Ring 5 (recorded alongside, see tasks.md)

- PASS A1 `ProbatioPlugin.scala` (StringLiteral excluded — sbt macro rejects
  mutated key descriptions): **96%**; the one survivor is an equivalent mutant
  (`[info]` guard → `true` — `stripPrefix` on a non-matching line is identity).
- PASS A2 `ShimGenerator.scala` + `InstallResolver.scala`: **100%**
  (33 generated, 27 testable, all killed — after strengthening exact path /
  warning-text / Left-reason assertions).
- PASS B (cli packaging files + `SubcommandWiring.scala`): **95.92%** of total,
  96.91% of covered (199 generated, 3 compiler-error `Files.forall` statics,
  188 detected, 8 undetected). All 8 undetected are pre-existing
  `SubcommandWiring` adapter code outside the spec-9 diff (lines 112, 187,
  224, 359, 391, 428) plus one equivalent mutant (`exists`→`forall` on
  `Option[Byte]`, line 192 — single-element semantics are identical). Zero
  undetected in `packaging/` or in the `forgivePredicate` batching region —
  the `/`→`""` boundary mutant, the unreadable-directory `Left`, and the
  `"\n"` issue-join were killed by targeted tests.
