# Ring 8: Adversarial Spec-Compliance Review — jar-launcher-dispatch

**Fresh context**: yes — isolated read-only subagent; inputs were the spec,
the typed-contract summary, and `git diff f11a390` only. No implementation
conversation was visible to it.

**Baseline**: `f11a390d35415178a0a9d2f9ca24664185123a82`

## Verdicts

Requirements: **4 PASS, 0 PARTIAL, 0 FAIL** — every requirement and scenario
verified branch-by-branch. Properties: 3/3 PASS. Compile-negatives: 2/2 PASS.
Ring-6 contract (`resolveSource` + `archiveEqualsGeneric` + `foreignNameRejected`
+ bridge totality): PASS. Oracle tampering: **none**.

Proof obligations: 15 PASS, **1 PARTIAL**.

### The PARTIAL — "the acceptance suite passes in an archive-only tree"

Only `gate-payload.bats` gained an explicit archive-only-tree test; the other
two named suites exercise the archive path only when the native binary is
ambiently absent. **Remediation taken**: the three named suites were run in a
materially archive-only tree (native binary moved aside): gate-payload 25/25,
hook-tiers 28/28, evidence-ledger 23/23 — all green through `java -jar`.
Recorded in the evidence ledger. The reviewer's fix class 3 ("a note in the
evidence ledger recording the run") is satisfied; a permanent archive-only CI
mode belongs to the delivery-verified spec, which owns CI.

## Dangerous patterns

7 found: 5 justified with `danger-scan:allow` (DispatchKernel non-Sep head,
source predicates; MulticallDispatch total `tail`s; ProbatioMain guarded
`get`/Throwable catches — all verified accurate). 2 unjustified, both
fail-closed and remediated or accepted:

- `SubprocessConformanceSpec.archivePath` swallowed a directory-listing
  `IOException` and reported "archive missing" — **fixed**: listing failures
  now `fail` naming the real error.
- `EntrypointBridgeSpec` `(none)`-sentinel conflation — over-strict, can only
  produce a phantom divergence on ungenerated input. Accepted residual.

## Remediation applied during review

- `bin/probatio` hardcoded `probatio-cli-assembly-0.1.0-SNAPSHOT.jar` while
  every other consumer globs `probatio-cli-assembly-*.jar` — a version bump
  would falsely report "no built tool". **Fixed**: the launcher now globs the
  assembly directory; the could-not-determine message names the glob.

## Accepted residuals (recorded, not blocking)

- `classify`'s `.jar` test is case-sensitive and literal — `java -jar FOO.JAR`
  classifies as a foreign named executable and is rejected. Consistent with
  the spec's literal wording; noted as a spec-level gap.
- An executable literally named `foo.jar` classifies as `Archive` — a literal
  exception to foreign-name rejection, mandated by the spec's own contract.
- `NamedExecutable` accepts a path payload; the basename invariant is
  conventional, not enforced — unreachable through `classify` (all shipped
  callers classify at the boundary).
- `extractInvocationName` splits `sun.java.command` on space — an archive path
  containing a space misclassifies. Pre-existing, not introduced by this spec.
- Bridge `sourceToModel`/`isForeign` re-derive classification via the shipped
  `genericNames`/`Subcommand.fromString` — co-dependent oracle; `MulticallDispatchSpec`
  covers "prob" directly, and Stainless verifies the model independently.
- Bridge `genBridgeArgs` samples the generic kind as "probatio" only; "prob"
  is covered by scenario tests.
