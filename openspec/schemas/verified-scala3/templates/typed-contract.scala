package <target.package>.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  Typed Contract for spec: <spec-name>
//  Generated: <date>
//  Schema: verified-scala3
//
//  This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. All method bodies are ???.
//  Review types, signatures, error algebra, and properties before any
//  implementation is written.
//
//  PLACEMENT: this file MUST live in the owning module's TEST sources, e.g.
//    <module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala
//  so that `sbt <module>/Test/compile` genuinely compiles it against the
//  real project classpath. Files under openspec/changes/... are NOT part of
//  the sbt source graph and give FALSE confidence.
//
//  Status: [ ] Compiles via <module>/Test/compile  [ ] Human-approved
// ═══════════════════════════════════════════════════════════════════════════

// ── Concepts reused (from concept-inventory.md) ─────────────────────────
// import <package>.<Type>    // <kind> — <brief note>

// ── New opaque types + smart constructors ───────────────────────────────
//
// type <ConstraintAlias> = <constraint expression>
// opaque type <TypeName> = <Underlying> :| <ConstraintAlias>
// object <TypeName> extends RefinedTypeOps[<TypeName>]:
//   // smart constructor: the ONLY public way to build a <TypeName>
//   def parse(raw: <Underlying>): Either[<Error>, <TypeName>] = ???
//   extension (x: <TypeName>) def value: <Underlying> = x

// ── New ADTs / enums / GADTs ────────────────────────────────────────────
//
// Prefer NARROW algebras over broad enums validated downstream:
//   enum OrderOp:          // not one broad CmpOp reused everywhere
//     case LT, LTE, GT, GTE
// Indexed ADTs/GADTs where the spec demands sort safety:
//   sealed trait Expr[S <: Sort]
//
// If extending an EXISTING enum: state how every existing pattern match
// over that enum must behave for the new variants.

// ── Error algebra ───────────────────────────────────────────────────────
//
// enum <ErrorType>:
//   case <Variant1>(<field>: <Type>)
//   case <Variant2>(<field>: <Type>)
// Extensions to existing error enums: list the new variants and the
// boundaries that must surface them.

// ── New case classes ────────────────────────────────────────────────────
//
// case class <ValueType>(
//   <field1>: <Type1>,
//   <field2>: <Type2>
// )

// ── Service / actor command signatures ──────────────────────────────────
//
// Existing trait extension:
//   trait <ExistingTrait>[F[_]] already has: <method1>, <method2>
//     NEW: def <newMethod>(<param>: <Type>): F[Either[<E>, <R>]] = ???
//
// New trait:
// trait <NewTrait>[F[_]: Async]:
//   def <method1>(<param>: <Type>): F[Either[<E>, <R>]] = ???
//
// Actor protocol (if the detected stack has actors):
// enum <Command>:
//   case <Cmd1>(<field>: <Type>, replyTo: ActorRef[<Reply>])
// enum <Event>:
//   case <Evt1>(<field>: <Type>)

// ── Compile-negative obligations ────────────────────────────────────────
//
// What must NOT be constructible. Each becomes an assertDoesNotCompile
// test in the test oracle (use the detected test framework's facility):
//
// assertDoesNotCompile("""
//   <forbidden construction, e.g. Expr.Count(c, b, body, CmpOp.IN, n)>
// """)

// ── Property & generator obligations (become the Ring 3 test oracle) ────
//
// Property: <name>
//   Invariant: <English description>
//   Generator: <Gen name — constructive/filtered — edge cases — classify labels>
//   forAll { (<params>: <Types>) => <predicate> }
//
// Model-based obligations (where relevant):
//   replay(persistedEvents) == liveState
//   decode(encode(x)) == Right(x)
//   desugar(IN) == OR of equalities

// ── Formal contracts (annotate pure functions in Ring 6) ────────────────
//
// def <fn>(<param>: <Type>): <Return> = {
//   require(<precondition>)
//   ???
// }.ensuring(result => <postcondition>)

// ── Temporal properties (become Ring 9 monitors) ────────────────────────
//
// EARS: "When <trigger>, the system shall <response>"
// Trigger event: <name>   Response event: <name>
// Monitor:
//   always { case <Trigger>(params) => hot { case <Response>(params) => ok } }
