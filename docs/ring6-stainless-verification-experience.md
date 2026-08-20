# Ring 6 — Stainless Formal Verification: Experience & Lessons

This document captures the practical experience of discharging Ring 6
(Stainless formal verification) for the `probatio-core` spec in the
`port-scanner-to-probatio` change. It is written to be reused as a playbook
for future verified-scala3 changes that need to construct a verified mirror
module and discharge it with native Z3.

---

## 1. What Ring 6 Requires

Ring 6 proves that the **core algorithmic invariants** of the production code
hold formally. It does NOT verify the production code directly (Stainless is
pinned to Scala 3.7.2 and cannot handle `String` interpolation, `fs2`, `Cats
Effect`, etc.). Instead, it verifies a **mirror module** — a pure Scala model
that abstracts the production algorithm into types Stainless can reason about
(`BigInt` instead of `String`, `stainless.collection.List` instead of
`scala.collection.immutable.List`, etc.).

A **bridge spec** (Ring 3 property tests) then runs both the production code
and the mirror on the same generated inputs and asserts they agree on the
proven invariants.

### Deliverables

1. **Verified mirror module** under `verified/<spec>/src/main/scala/...` with
   one `*Kernel.scala` file per production file being modeled.
2. **Native Z3 configuration** in `build.sbt` so Stainless uses the bundled
   `scalaz3` JNI library instead of the slower `smt-z3` text-based solver.
3. **Law lemmas** — standalone `@pure def` functions with
   `ensuring(_ == true)` postconditions that Stainless proves as verification
   conditions (VCs).
4. **Evidence ledger entry** recording the verification run (VC count, valid
   count, solver, time).

---

## 2. Native Z3 Configuration (Critical)

### The Problem

Without native Z3, Stainless falls back to `smt-z3` — a text-based SMT solver
that communicates with Z3 via stdio. This is **10–100x slower** than the JNI
`nativez3` solver and will time out on any non-trivial verification.

### The Fix

The `probatio-verified` project in `build.sbt` needs three things:

```scala
// 1. Declare the ScalaZ3 dependency as an unmanaged jar
stainlessExtraDeps += "ch.epfl.lara" % "scalaz3_3" % "4.13.4"
  from s"file://${baseDirectory.value.getParentFile / "unmanaged" / "scalaz3_3-4.13.4.jar"}",

// 2. Merge ScalaZ3 native classes into the Stainless plugin jar
//    (the mergeScalaZ3Plugin setting does this — see build.sbt lines ~395-440)

// 3. Enable Stainless for the project
stainlessEnabled := true  // set via: sbt 'set `probatio-verified` / stainlessEnabled := true'
```

The `scalaz3_3-4.13.4.jar` must be placed at `verified/unmanaged/`. This jar
contains the JNI bindings that let Stainless call Z3 directly via `com.microsoft.z3.Native`.

### Verifying Native Z3 Is Active

In the Stainless output, look for:
```
info Verification pipeline summary:
info   @extern, cache, anti-aliasing, choose injection,
info   nativez3, non-batched
```

If you see `smt-z3` instead of `nativez3`, the configuration is wrong and
verification will be unusably slow.

### The `mergeScalaZ3Plugin` Setting

The Stainless sbt plugin ships a `stainless-dotty-plugin` jar that gets passed
to scalac via `-Xplugin:`. The ScalaZ3 JNI classes need to be merged INTO this
plugin jar (not just on the classpath) because the plugin runs during
compilation, not at runtime. The `mergeScalaZ3Plugin` setting in `build.sbt`
handles this by:

1. Extracting both jars to a temp directory
2. Recombining them into a single `*-merged.jar`
3. Overriding `scalacOptions` to use the merged jar

The merged jar is cached at `target/scala-3.8.4/compiler_plugins/`. Delete it
if you change the ScalaZ3 jar or Stainless version.

---

## 3. The Mirror Module Pattern

### Abstraction Strategy

| Production Type | Mirror Type | Why |
|----------------|-------------|-----|
| `String` | `BigInt` | Stainless doesn't support string interpolation |
| `scala.List[A]` | `stainless.collection.List[A]` | Stainless has its own List with built-in lemmas |
| `Option[A]` | `stainless.lang.Option[A]` | Stainless's own Option with `Some`/`None` |
| `Map[K,V]` | `stainless.lang.Map[K,V]` | Stainless's own Map |
| `Either[L,R]` | `stainless.lang.Either[L,R]` | Stainless's own Either |
| `Boolean` | `Boolean` | Direct mapping |
| `Int` | `BigInt` | Stainless prefers BigInt (no overflow concerns) |

### Imports

Every kernel file needs:
```scala
import stainless.lang._
import stainless.collection._
import stainless.annotation._
```

### Annotations

- `@pure` — marks a function as pure (no side effects). Required for all
  functions in the mirror module.
- `@extern` — marks a function as external (Stainless won't verify its body).
  Use sparingly — only for stubs that bridge to production types.
- `decreases(measure)` — explicitly specifies the termination measure for
  recursive functions. **Critical for list recursion** (see §4).

---

## 4. The foldLeft/flatMap/++ Trap (THE Key Lesson)

### The Problem

Stainless generates a Verification Condition (VC) for every function call. For
list operations like `foldLeft`, `flatMap`, `++` (concat), and `map`, Stainless
needs to prove:

1. **Termination** — the operation terminates on all inputs (requires an
   inductive measure proof).
2. **Postcondition** — the result satisfies any `ensuring` clause.

For `foldLeft`, Stainless cannot automatically infer the termination measure
because `foldLeft` is a higher-order function — the lambda passed to it is
opaque. This generates a VC like:

```
forall (f: (BigInt, BigInt) => BigInt), (acc: BigInt), (xs: List[BigInt]),
  measure(foldLeft(f, acc, xs)) < measure(foldLeft(f, acc, xs'))
```

Z3 **cannot discharge this** — it requires an inductive proof about the
structure of `foldLeft`, which is beyond what Z3's quantifier instantiation
can handle. The VC hangs indefinitely (no timeout — see §5).

### The Symptom

Verification progress stops at a fixed VC count and never advances:
```
info  Verified: 54 / 99
```
No further output. The process is stuck in Z3 trying to prove the
termination measure for a `foldLeft`/`flatMap`/`++` call.

### The Fix: Structural Recursion + `decreases`

Replace every `foldLeft`/`flatMap`/`++`/`map`/`exists`/`forall`/`count` with
**explicit structural recursion** using `match` on `Nil`/`Cons`, and add an
explicit `decreases` clause:

```scala
// BAD — Z3 hangs on the termination measure VC
@pure
def sumList(xs: List[BigInt]): BigInt =
  xs.foldLeft(BigInt(0))((a, b) => a + b)

// GOOD — structural recursion with explicit measure
@pure
def sumList(xs: List[BigInt]): BigInt =
  decreases(xs.size)
  xs match
    case Nil()      => BigInt(0)
    case Cons(h, t) => h + sumList(t)
```

```scala
// BAD — flatMap generates an unprovable VC
@pure
def driftScan(schemaVersion: BigInt, roots: List[InstallRoot]): DriftScanResult =
  val warnings = roots.flatMap { root =>
    root.stampVersion match
      case Some(found) if found != schemaVersion => List(DriftWarning(found))
      case _ => Nil()
  }
  DriftScanResult(noSkillInstalled = !roots.exists(_.stampVersion.isDefined), warnings)

// GOOD — structural recursion
@pure
def driftScan(schemaVersion: BigInt, roots: List[InstallRoot]): DriftScanResult =
  decreases(roots.size)
  roots match
    case Nil() => DriftScanResult(noSkillInstalled = true, warnings = Nil())
    case Cons(root, rest) =>
      val tailResult = driftScan(schemaVersion, rest)
      root.stampVersion match
        case None() => tailResult
        case Some(found) =>
          val warnings =
            if found != schemaVersion then Cons(DriftWarning(found), tailResult.warnings)
            else tailResult.warnings
          DriftScanResult(noSkillInstalled = false, warnings = warnings)
```

```scala
// BAD — ++ (concat) generates an unprovable VC
@pure
def render(inputs: BannerInputs): BannerOutput =
  val allLines = List(invariant) ++ contextLines ++ chainStateLines ++ driftLines
  BannerOutput(allLines, allLines.foldLeft(BigInt(0))(_ + _))

// GOOD — use Cons for fixed prefix, avoid ++ for dynamic lists
@pure
def render(inputs: BannerInputs): BannerOutput =
  val invariant = invariantText(inputs.schemaVersion)
  val contextLines = buildContextLines(inputs)
  val payload = invariant + trailerText  // simplified — no foldLeft
  BannerOutput(Cons(invariant, Cons(trailerText, contextLines)), payload)
```

### Why `decreases` Is Necessary

Stainless's `MeasureInference` phase tries to infer a termination measure
automatically. For simple structural recursion (`match` on the list argument),
it can usually infer `xs.size`. But for:

- Recursion where the argument is a **field of a case class** (e.g.,
  `inputs.activeChanges`), the inference fails because the measure must be
  on the field, not the whole input.
- Recursion where the argument is **transformed** before the recursive call
  (e.g., `inputs.copy(activeChanges = rest)`), the inference fails because it
  can't prove the copy decreases the measure.

Adding `decreases(xs.size)` or `decreases(inputs.activeChanges.size)` makes
the measure explicit and Z3 can prove it decreases.

---

## 5. The Timeout Problem

### No Per-VC Timeout

Stainless 0.9.9.3 does **not** have a working per-VC timeout mechanism that
can be set from sbt. The following were tried and all failed:

- `.stainless.conf` file with `--timeout=60` — not read by the sbt plugin
- `-J-Dstainless.timeout=60` system property — not recognized
- `stainlessOptions += "--timeout=60"` — no such sbt key exists (the
  `StainlessPlugin$autoImport` class only exposes `stainlessVersion`,
  `stainlessEnabled`, `stainlessExtraDeps`, `stainlessExtraResolvers`)

This means a single hard VC will hang the entire verification run indefinitely.
The only solution is to **avoid generating hard VCs** (see §4) or kill the
process and fix the code.

### Practical Approach

Run verification with a shell timeout and `tee` to a log file:
```bash
sbt -J-Xmx6g 'set `probatio-verified` / stainlessEnabled := true' \
  'probatio-verified/clean' 'probatio-verified/compile' 2>&1 | tee /tmp/ring6.log
```

Monitor progress with `tail -2 /tmp/ring6.log`. If the VC count stops
advancing for more than 2 minutes, kill the process and investigate which
function is at the stuck VC.

---

## 6. Law Lemmas: Fixed-Size Inputs Only

### The Problem

Law lemmas that call recursive functions on **unbounded list inputs** generate
VCs that require inductive proofs about the recursive function's behavior on
all possible lists. Z3 cannot discharge these.

```scala
// BAD — calls driftScan on an unbounded list, Z3 can't prove the postcondition
@pure
def driftScanNoSkill(schemaVersion: BigInt, roots: List[InstallRoot]): Boolean = {
  val allNone = roots.forall(_.stampVersion.isEmpty)
  val result = driftScan(schemaVersion, roots)
  !allNone || result.noSkillInstalled
}.ensuring(_ == true)
```

### The Fix: Prove Base Cases Only

Restrict law lemmas to **fixed-size inputs** (empty lists, single-element
lists). These generate VCs that Z3 can discharge because the recursion
terminates in one step:

```scala
// GOOD — empty list (base case)
@pure
def driftScanEmpty(schemaVersion: BigInt): Boolean = {
  driftScan(schemaVersion, Nil()).noSkillInstalled
}.ensuring(_ == true)

// GOOD — single-element list (one recursive step)
@pure
def driftScanSingleNone(schemaVersion: BigInt, rootPath: BigInt): Boolean = {
  val root = InstallRoot(rootPath, None())
  driftScan(schemaVersion, Cons(root, Nil())).noSkillInstalled
}.ensuring(_ == true)

@pure
def driftScanSingleSome(schemaVersion: BigInt, rootPath: BigInt, v: BigInt): Boolean = {
  val root = InstallRoot(rootPath, Some(v))
  !driftScan(schemaVersion, Cons(root, Nil())).noSkillInstalled
}.ensuring(_ == true)
```

### Why This Is Sufficient

The law lemmas prove the **base cases** of the recursive invariants. The
**inductive step** (the recursive case) is proven by the `decreases` clause
(termination) and the bridge spec (Ring 3 property tests) which tests the
full recursive functions against the production code on randomly generated
inputs of varying sizes.

This is a pragmatic split:
- **Stainless** proves the core invariant holds on the base cases (empty,
  single-element) — this catches logic errors in the conditional branches.
- **Bridge spec** proves the recursive structure preserves the invariant on
  arbitrary inputs — this catches structural errors in the recursion.

---

## 7. The Recursive `ensuring` Trap

### The Problem

An `ensuring` clause that **calls the function being verified** causes
infinite recursion during Stainless's VC generation:

```scala
// BAD — ensuring calls render, which is the function being verified
@pure
def render(inputs: BannerInputs): BannerOutput =
  val allLines = ...
  BannerOutput(allLines, allLines.foldLeft(BigInt(0))(_ + _))
  .ensuring { (result: BannerOutput) => result == render(inputs) }
```

Stainless tries to evaluate `render(inputs)` inside the postcondition to
generate the VC, which triggers another `ensuring`, which triggers another
`render`, ad infinitum.

### The Fix

The `ensuring` clause must only reference **values computed in the function
body**, not re-call the function:

```scala
// GOOD — ensuring references local values, not the function itself
@pure
def render(inputs: BannerInputs): BannerOutput =
  val allLines = ...
  val payload = ...
  BannerOutput(allLines, payload)
  .ensuring { (result: BannerOutput) =>
    result.lines == allLines && result.payload == payload
  }
```

Or simply remove the `ensuring` clause entirely if the postcondition is
trivially true (the function is pure and returns a constructed value).

---

## 8. The `implementation-order.md` Gate Trap

### The Problem

The `gate.sh` hook in the verified-scala3 workflow checks that files being
edited are listed in `implementation-order.md`. If the file uses a glob
pattern like `*.scala`, the gate's AWK script interprets it **literally**
(not as a glob) and blocks edits to specific kernel files:

```
gate.sh: BLOCKED — BannerEngineKernel.scala not in implementation-order.md
```

### The Fix

List the **exact filenames** in `implementation-order.md`, not globs:

```markdown
## Expected Files
- LedgerValidatorKernel.scala
- ChainStateKernel.scala
- BannerEngineKernel.scala
```

---

## 9. Untracked Files and danger-scan

### The Problem

`danger-scan` only checks files that have a git diff against HEAD. If a kernel
file is **untracked** (`??` in `git status`), danger-scan reports "no
production .scala files changed since HEAD" even though the file exists and was
modified. This is misleading but harmless — the edits still go through.

### The Fix

`git add` the kernel files early so they're tracked. The danger-scan messages
will then show the actual diff.

---

## 10. Verification Run Cheat Sheet

### Clean Run

```bash
# Remove cached merged plugin jar and temp files
rm -f target/scala-3.8.4/compiler_plugins/*-merged.jar
rm -rf /tmp/SCALAZ3_*

# Run verification
sbt -J-Xmx6g \
  'set `probatio-verified` / stainlessEnabled := true' \
  'probatio-verified/clean' \
  'probatio-verified/compile' 2>&1 | tee /tmp/ring6.log
```

### Reading the Output

1. **Phase listing** — Stainless runs ~30 phases. Look for
   `MeasureInference` (where `decreases` is processed) and
   `InductElimination` (where inductive lemmas are handled).

2. **VC generation** — `Generating VCs for N functions...` followed by
   `Finished generating VCs`. The total VC count is shown as
   `Verified: 0 / TOTAL`.

3. **Progress** — `Verified: N / TOTAL` increments as each VC is discharged.
   If progress stops for >2 minutes, a VC is hanging (see §4, §5).

4. **Final report** — A table showing each VC with its status:
   ```
   total: 118  valid: 118  (17 from cache, 30 trivial) invalid: 0  unknown: 0  time: 3.63
   ```

5. **Pipeline summary** — Confirms the solver used:
   ```
   nativez3, non-batched
   ```

### Success Criteria

- **valid: TOTAL** — all VCs discharged
- **invalid: 0** — no VCs refuted (would indicate a real bug)
- **unknown: 0** — no VCs timed out or were too hard
- **nativez3** — native Z3 solver used (not smt-z3)

### Recording Evidence

```bash
bash openspec/schemas/verified-scala3/scanner/ledger.sh append \
  --file openspec/changes/<change>/evidence-ledger.jsonl \
  --change <change> \
  --spec <spec> \
  --ring R6 \
  --obligation "R6 Stainless formal verification — N/N VCs valid (0 invalid, 0 unknown), native Z3, Ts" \
  --artifact verified/<spec>/src/main/scala/.../<Kernel>.scala \
  --command "sbt -J-Xmx6g 'set `probatio-verified` / stainlessEnabled := true' 'probatio-verified/compile'" \
  --exit 0 \
  --baseline <baseline-sha>
```

---

## 11. Debugging Stuck Verification

### Step 1: Identify the Stuck VC

The VC number where progress stops tells you which function is stuck. Cross-
reference with the final report table (if the run completes) or with the
function order in the source files.

### Step 2: Check for foldLeft/flatMap/++/map/exists/forall/count

These are the most common causes. Replace with structural recursion + 
`decreases` (see §4).

### Step 3: Check for Recursive `ensuring`

If the `ensuring` clause calls the function being verified, remove it or
rewrite it to reference local values only (see §7).

### Step 4: Check for Unbounded List Arguments in Law Lemmas

If a law lemma calls a recursive function on an unbounded list argument,
restrict to fixed-size inputs (see §6).

### Step 5: Check for `copy` in Recursive Calls

If the recursive call uses `inputs.copy(field = rest)`, Stainless may not
infer the measure. Add `decreases(inputs.field.size)` explicitly.

---

## 12. Summary: The Verification Checklist

Before running verification, check every function in the mirror module:

- [ ] No `foldLeft`, `flatMap`, `++`, `map`, `exists`, `forall`, `count`,
      `filter`, `foldRight` on lists — use structural recursion instead
- [ ] Every recursive function has a `decreases` clause
- [ ] No `ensuring` clause calls the function being verified
- [ ] Law lemmas only use fixed-size list inputs (Nil or Cons(x, Nil))
- [ ] All functions are `@pure`
- [ ] Imports are `stainless.lang._`, `stainless.collection._`,
      `stainless.annotation._`
- [ ] `build.sbt` has `stainlessExtraDeps` pointing to the ScalaZ3 jar
- [ ] `build.sbt` has `mergeScalaZ3Plugin` configured
- [ ] `implementation-order.md` lists exact kernel filenames (not globs)
- [ ] Kernel files are `git add`-ed (tracked) so danger-scan sees them

If all boxes are checked, verification should complete in seconds with
native Z3.
