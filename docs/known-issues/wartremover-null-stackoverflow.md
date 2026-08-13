# WartRemover `Null` Wart StackOverflowError

## Status

Open — workaround in place (`-Xss4m` in `.jvmopts`). Upstream bug not yet
reported.

## Summary

WartRemover's `Null` wart tree traverser (`org.wartremover.warts.Null`) uses
direct recursion through `super.traverseTree` → `traverseTreeChildren` →
`traverseTree` for each child node. For sufficiently deep ASTs the recursion
depth exceeds the default JVM thread stack (1 MB), causing a
`java.lang.StackOverflowError` during the `wartremover` compiler phase.

## Affected versions

- WartRemover **3.5.8** and **3.6.1** (both reproduce)
- Scala **3.8.4**
- sbt **1.12.12** with `fork := false` (compiler runs in-process; `.jvmopts`
  applies)

## Triggering file

`adk4s-examples/src/main/scala/org/adk4s/examples/eino/agent/AgentToolAdvancedExample.scala`
(228 lines). The file has a ~45-step `for`-comprehension over `IO` where each
step constructs nested `ujson.Obj` literals and traverses them with
`.traverse` lambdas containing `.obj.get(...).map(_.str).getOrElse(...)` chains.
The desugared AST (nested `flatMap`/`map` calls with deeply nested argument
trees) is deep enough that the `Null` wart's non-tail-recursive traverser
overflows the stack.

A standalone minimal reproduction with the same structural pattern (deep
for-comprehension + nested `ujson.Obj` + `.traverse` lambdas) does **not**
trigger the overflow in isolation — the issue depends on the full compilation
context (the `adk4s-examples` module compiles 59 files together, and the typed
AST includes inlined implicits, extension method expansions, and type
resolution trees from the full classpath). The overflow is reproducible only
within the `adk4s-examples` build.

## Root cause

The `Null` wart's `traverseTree` method (line 60 of `Null.scala` in
wartremover 3.6.1) is the catch-all case:

```scala
// org.wartremover.warts.Null, line 60 (wartremover 3.6.1)
case _ =>
  super.traverseTree(tree)(owner)
```

`super.traverseTree` (from `scala.quoted.Quotes$reflectModule$TreeTraverser`)
calls `foldTree` → `traverseTreeChildren` → `traverseTree` for each child
tree. This is direct recursion with no trampolining or stack-safety. Each AST
level consumes ~5 stack frames. For a deeply nested AST (e.g., a 45-step
for-comprehension desugared to nested `flatMap` calls, with complex
expressions at each level), the recursion depth exceeds the default 1 MB
thread stack.

The stack trace is a pure `Null.traverseTree` recursion:

```
[error] java.lang.StackOverflowError
[error] org.wartremover.warts.Null$$anon$1.traverseTree(Null.scala:60)
[error] org.wartremover.warts.Null$$anon$1.traverseTree(Null.scala:60)
[error] org.wartremover.warts.Null$$anon$1.traverseTree(Null.scala:60)
... (hundreds of frames)
[error] (adk4s-examples / Compile / compileIncremental) java.lang.StackOverflowError
```

## Workaround

Add `-Xss4m` to `.jvmopts`:

```
-Xmx4g
-Xss4m
-XX:+UseG1GC
-XX:-DoEscapeAnalysis
```

Since `fork := false` in the build, the Scala compiler runs in-process and
inherits the JVM thread stack size from `.jvmopts`. Raising the stack to 4 MB
gives the `Null` wart's traverser enough headroom for the deepest ASTs in the
codebase. This is the workaround currently applied in this repo.

## Alternatives considered

1. **Refactor the triggering file** to reduce AST depth (extract the
   for-comprehension steps into helper methods). Rejected: the file is an
   example and the overflow could recur with any sufficiently complex file.
2. **Disable the `Null` wart** (`wartremoverErrors := Warts.unsafe.filterNot(_
   == Wart.Null)`). Rejected: the `Null` wart is part of the project's Ring 1
   static analysis and catches real `null` usage.
3. **Exclude the triggering file** via `wartremoverExcluded`. Rejected: same
   reason as (2) — the file should be checked like any other.
4. **Set `fork := true` for `adk4s-examples`** and pass `-Xss4m` via
   `javaOptions`. Rejected: forking slows compilation and changes the
   development workflow; the in-process `.jvmopts` approach is simpler and
   applies to all modules uniformly.

## Upstream fix

The proper fix is for WartRemover's `Null` wart traverser to be stack-safe
(e.g., via trampolining or an explicit work queue). This is an upstream
WartRemover issue, not an ADK4S issue. Worth reporting at
https://github.com/wartremover/wartremover/issues with the reproduction steps
below.

## Reproduction

### Within the ADK4S repo (reliable)

```bash
# Remove the workaround
sed -i '/-Xss4m/d' .jvmopts

# Clean compile adk4s-examples — overflows during wartremover phase
sbt "adk4s-examples/clean" "adk4s-examples/compile"
# → java.lang.StackOverflowError
# → org.wartremover.warts.Null$$anon$1.traverseTree(Null.scala:60)

# Restore the workaround
git checkout .jvmopts
```

### Standalone minimal repro (does NOT trigger in isolation)

A standalone project with the same structural pattern (deep for-comprehension
+ nested `ujson.Obj` + `.traverse` lambdas) compiles fine at the default 1 MB
stack. The overflow depends on the full `adk4s-examples` compilation context
(59 files, complex classpath with implicit resolution and extension method
expansions). See the "Triggering file" section above for why.

### Key diagnostic findings

- A 100-step `for`-comprehension with simple `IO.unit` steps compiles fine
  with WartRemover at the default 1 MB stack — AST depth alone is not
  sufficient.
- A 41-step `for`-comprehension with nested `ujson.Obj` constructors and
  `.traverse` lambdas compiles fine in a standalone project — the full
  classpath context matters.
- The overflow is in the `wartremover` compiler phase, not in the compiler's
  own `Typer` phase — the stack trace is exclusively
  `Null.traverseTree` frames.
- The overflow reproduces with both WartRemover 3.5.8 and 3.6.1.
