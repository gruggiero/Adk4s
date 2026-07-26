# Ring 6 — the VERIFIED-MIRROR pattern

<!-- Copy the parts you need into a project. This is the reference for
     schema rule "Ring 6 applies by ALGORITHMIC purity". Extracted from a
     working implementation (accordant4s: `verified/OracleKernel.scala` +
     `core/.../OracleModelBridgeTests.scala`, and a second instance
     `temporal-verified/SoupKernel.scala` — the pattern is repeatable). -->

## The problem it solves

Ring 6 is skipped in most projects for two reasons that sound decisive and
are not:

1. *“Our production code uses Iron / cats / opaque types / `Mirror` /
   `inline` / `ujson` / `IO` — Stainless cannot model it.”*
2. *“Stainless pins Scala 3.7.2 and we are on 3.8.4.”*

Both are statements about the **shipped code's types**, not about the
**algorithm**. Almost every interesting algorithm has a pure kernel that can
be expressed over verifiable types once you reduce its inputs to their
observable effect. The mirror pattern verifies that kernel and then binds the
shipped code to it mechanically.

> **Applicability rule.** Ring 6 applies when the algorithm has a pure kernel
> expressible in PureScala **at some abstraction**. Unverifiable production
> types and a Scala-version mismatch are NOT grounds to skip — they are the
> reasons the mirror exists.

## The four parts

| Part | Purpose | Without it |
|---|---|---|
| 1 · Triage table | decide what has a pure kernel | Ring 6 gets skipped by reflex |
| 2 · Mirror module | a PureScala model Stainless can prove | nothing to verify |
| 3 · **Bridge test** | binds shipped code to the model on shared inputs | you proved a program you do not ship |
| 4 · Scope note | records what was NOT proven, and who covers it | silent over-claiming |

Part 3 is the load-bearing one. A proof about a model nobody runs is a
document; a proof plus a bridge test is evidence about the shipped system.

---

## 1 · Triage — the design artifact's Ring 6 table

List every candidate and decide explicitly. Skipping is fine; skipping
*silently* is not.

```markdown
### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `core.Soup.step` | the state-fold kernel | Yes — `SoupKernel` mirror |
| `domain.ProfileEval.allows` | per-candidate branch evaluation | Yes — already covered by the `OracleKernel` mirror |
| `stream` (fs2 pipe) | effect wiring | No — effectful; TP1–TP5 enforced by Ring 3 |
```

Ask, in order:

1. Is there a **decision or fold** at the centre of this code — something
   that maps inputs to a verdict, a set, an ordering, a fold result?
2. Can its inputs be reduced to **observable effect** — a `Boolean`, an
   identity, a `BigInt` index — discarding the types Stainless cannot model?
3. Is there a **law** worth stating: conservation, monotonicity, idempotence,
   an iff-conformance, an ordering invariant?

Two “yes” answers mean a mirror is worth writing.

---

## 2 · The mirror module (build wiring)

```scala
// A LEAF module pinned to the Scala version Stainless's bundled frontend
// supports. It depends on NOTHING project-local: TASTy is only backward
// compatible, so a newer module may read this older artifact — never the
// reverse. Not aggregated by root, so ordinary builds skip it.
lazy val verified = (project in file("verified"))
  .enablePlugins(StainlessPlugin)
  .settings(
    name         := "<project>-verified",
    scalaVersion := "3.7.2",              // ← the Stainless frontend version
    // Stainless injects its own library sources; silence warnings we do not
    // own, while keeping full warnings on our model.
    scalacOptions := Seq(
      "-deprecation", "-feature",
      "-Wconf:src=.*stainless-library.*:silent"
    ),
    wartremoverErrors := Seq.empty,
    semanticdbEnabled := false,
    // Default OFF: the module then compiles as an ordinary fast module, which
    // is what the bridge test needs. Verification is an explicit step.
    stainlessEnabled := false,
    publish / skip   := true
  )

// The production module takes the mirror as a TEST-scope dependency so the
// bridge test can import the model. Test scope keeps it out of the artifact.
lazy val core = (project in file("core"))
  .dependsOn(verified % Test)
  .settings(/* … */)

// Ring 6 — verification is a separate, explicit command (z3 is
// single-threaded and wants a big heap), so it never slows `core/test`.
addCommandAlias("ring6", "; set verified / stainlessEnabled := true ; verified / compile")
```

Why `stainlessEnabled := false` by default matters: the bridge test compiles
the mirror on every `core/test` run. If verification were on by default,
every test run would pay for a solver.

---

## 3 · The model — an abstraction, not a copy

The work is finding the level at which the algorithm survives but the
unverifiable types do not.

```scala
package <pkg>.verified

import stainless.collection._
import stainless.lang._

/**
 * Ring 6 — a PureScala MIRROR of <algorithm>.
 *
 * The real implementation (`<pkg>.<Real>`) uses <Iron / cats / opaque types /
 * function-typed checks> that Stainless cannot model, so it cannot be
 * verified directly. Here <what> is reduced to its observable effect —
 * `EvalBranch` = (did the check pass?, resulting state identity) — over
 * `BigInt` identities. The real implementation is pinned to THIS algorithm by
 * the bridge property in `<BridgeSpec>`.
 */
object <Name>Kernel {

  /** A branch reduced to its effect against a fixed response. */
  final case class EvalBranch(passed: Boolean, next: BigInt)

  def passingNexts(bs: List[EvalBranch]): List[BigInt] =
    bs match {
      case Nil()      => Nil[BigInt]()
      case Cons(b, t) => if (b.passed) b.next :: passingNexts(t) else passingNexts(t)
    }

  def distinct(xs: List[BigInt]): List[BigInt] = {
    decreases(xs.size)                       // termination measure
    xs match {
      case Nil()      => Nil[BigInt]()
      case Cons(h, t) => h :: distinct(t.filter(_ != h))
    }
  }

  def survivors(bs: List[EvalBranch]): List[BigInt] =
    distinct(passingNexts(bs))

  // The law worth proving, as a quantifier-free VC z3 discharges instantly.
  def conformance(bs: List[EvalBranch]): Boolean = {
    survivors(bs).isEmpty == !bs.exists(_.passed)
  }.holds
}
```

**Reduction recipes that work.** Replace a domain value with a `BigInt`
identity (index into the distinct values seen); replace a
function-typed check with the `Boolean` it produced; replace a typeclass
result with its outcome; replace an effect with its returned value. Keep
the control flow — that is the thing under proof.

**Solver pragmatics.** Prefer quantifier-free invariants:
`forall(s => exists(...))` VCs can diverge in z3 with no timeout. When one
does, do not weaken the code — state the law in the scope note and delegate
it to a named Ring 3 property (see part 4).

---

## 4 · The bridge test — the part that makes it evidence

Run the real implementation and the model on the **same generated inputs**
and assert they agree on **exactly the invariants Stainless proves**.

```scala
// Ring 6 bridge — MECHANICAL link between the shipped kernel and the
// Stainless-verified model. If EITHER drifts, this property fails.
// The model is COMPILED (not re-verified) here: `stainlessEnabled` is off by
// default, so this adds no solver cost to the ordinary test run.
final class <Name>ModelBridgeTests extends HedgehogSuite:

  property("real <Real> agrees with the Stainless model on <invariants>") {
    for
      input <- genInput.forAll
      res   <- genResponse.forAll
    yield
      val real = <Real>.<method>(input, res)

      // Reduce the real evaluation to the model's abstraction.
      val flat: List[(Boolean, S)] = /* (did it pass?, next state) */
      def enc(s: S): BigInt        = /* stable identity */
      val model = <Name>Kernel.survivors(toStainlessList(flat.map((p, n) => EvalBranch(p, enc(n)))))

      real match
        case Verdict.Conformant(p) => Result.assert(!model.isEmpty && model.size.toInt == p.size)
        case Verdict.Deviant(_)    => Result.assert(model.isEmpty)
  }
```

Requirements:

- **Property-based**, not example-based — the binding must hold over a domain.
- Assert the *same* invariants the model proves, not weaker ones.
- Live in the production module's test sources (mirror is `% Test`).
- Cite it in the proof-obligations table as the artifact for the
  “model conforms to production” obligation.

---

## 5 · The scope note — record what you did NOT prove

Every mirror carries, in its own doc comment, the honest scope:

```
 * Scope (best-effort): Stainless proves the two load-bearing invariants
 *   1. SOUNDNESS — every surviving next-state comes from a branch that passed;
 *   2. CONFORMANCE — "some state survives" ⇔ "some branch passed".
 * Dedup correctness is left to the Ring-3 property "<name>" — proving it here
 * needs an unbounded inductive subset lemma that adds no assurance the
 * property test does not already give.
```

An unproven law is not a hole if it is **named and delegated**. It becomes a
hole the moment it is silently omitted — which is the same rule the
proof-obligations ledger applies everywhere else.

---

## Obligations this pattern produces

| Obligation | Source | Enforcement | Artifact |
|---|---|---|---|
| `<law>` holds for all inputs | Requirement: `<title>` + Invariant: `<name>` | formal contract (Ring 6) | `<Name>Kernel` |
| Shipped code conforms to the verified model | Requirement: `<title>` | bridge property (Ring 3 + Ring 6) | `<Name>ModelBridgeTests` |
| `<delegated law>` | Invariant: `<name>` | property test (delegated from Ring 6 — see mirror scope note) | `<Spec>` |

## Checklist

- [ ] Design artifact has a “Pure Code (Ring 6 candidates)” table, with the
      *no* rows justified.
- [ ] Mirror module: leaf, pinned Scala version, no project-local deps,
      `stainlessEnabled := false`, `ring6` alias, not aggregated.
- [ ] Production module depends on the mirror `% Test`.
- [ ] Model reduces inputs to observable effect; termination measures where
      recursion is not structural.
- [ ] Bridge property test exists and asserts the proven invariants.
- [ ] Mirror doc comment records proven laws AND delegated ones by name.
- [ ] Obligations table cites the mirror and the bridge test as artifacts.
