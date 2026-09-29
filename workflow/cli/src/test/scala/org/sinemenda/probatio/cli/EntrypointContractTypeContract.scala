package org.sinemenda.probatio.cli

/**
 * Typed contract for spec: cli-entrypoint-contract
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved public
 * signatures via eta-expanded references and asserts the compile-negative
 * obligations. Zero runtime cost; any later signature drift breaks
 * `probatio-cli/Test/compile`.
 *
 * spec: cli-entrypoint-contract — Step 1: typed contract (full)
 * spec: cli-entrypoint-contract — Concepts Introduced: ProgramArgs
 * spec: cli-entrypoint-contract — Concepts Introduced: InvocationName
 */
final class EntrypointContractTypeContract extends ProbatioCliSuite:

  // ── Signature pins (eta-expanded against the real implementation) ───────
  // These pins make "signatures stay as approved" compiler-checked.

  // MulticallDispatch.resolveAndSplit: (InvocationName, ProgramArgs) => Either[CliError, (Subcommand, ProgramArgs)]
  val resolveAndSplitSig: (InvocationName, ProgramArgs) => Either[CliError, (Subcommand, ProgramArgs)] =
    MulticallDispatch.resolveAndSplit

  // ProbatioMain.dispatch: (InvocationName, ProgramArgs) => Int
  val dispatchSig: (InvocationName, ProgramArgs) => Int =
    ProbatioMain.dispatch

  // ProgramArgs.fromRuntime: Array[String] => ProgramArgs
  val programArgsFromRuntimeSig: Array[String] => ProgramArgs =
    ProgramArgs.fromRuntime

  // ProgramArgs.fromFixture: List[String] => ProgramArgs
  val programArgsFromFixtureSig: List[String] => ProgramArgs =
    ProgramArgs.fromFixture

  // InvocationName.fromRuntime: InvocationSource => Either[String, InvocationName]
  // spec: jar-launcher-dispatch — InvocationName is constructed from an InvocationSource
  val invocationNameFromRuntimeSig: InvocationSource => Either[String, InvocationName] =
    InvocationName.fromRuntime

  // InvocationSource.classify: String => InvocationSource
  val invocationSourceClassifySig: String => InvocationSource =
    InvocationSource.classify

  // InvocationSource is a closed enum with exactly two cases.
  val invocationSourceVariants: List[InvocationSource] =
    List(InvocationSource.NamedExecutable("probatio"), InvocationSource.Archive("probatio-cli-assembly.jar"))

  // ── Compile-negative: ProgramArgs from a program-name-prefixed list ─────
  // spec: cli-entrypoint-contract — Compile-Negative: ProgramArgs from a program-name-prefixed list
  test("ProgramArgs cannot be directly constructed from a List"):
    val err: String = compileErrors("ProgramArgs(List(\"probatio\", \"gate\", \"--event\", \"x\"))")
    assert(err.nonEmpty, "ProgramArgs(List(...)) should not compile — no public apply")

  // ── Compile-negative: removed Subcommand cases ──────────────────────────
  // spec: cli-entrypoint-contract — Compile-Negative: Subcommand.RegistryCheck (and Scan, RemovalAudit, ImpactScan, ConceptScanner, Graph)
  test("Subcommand.RegistryCheck does not compile (unported tool removed)"):
    val err: String = compileErrors("Subcommand.RegistryCheck")
    assert(err.nonEmpty, "Subcommand.RegistryCheck should not exist — unported tool removed")

  test("Subcommand.Scan does not compile (unported tool removed)"):
    val err: String = compileErrors("Subcommand.Scan")
    assert(err.nonEmpty, "Subcommand.Scan should not exist — unported tool removed")

  test("Subcommand.RemovalAudit does not compile (unported tool removed)"):
    val err: String = compileErrors("Subcommand.RemovalAudit")
    assert(err.nonEmpty, "Subcommand.RemovalAudit should not exist — unported tool removed")

  test("Subcommand.ImpactScan does not compile (unported tool removed)"):
    val err: String = compileErrors("Subcommand.ImpactScan")
    assert(err.nonEmpty, "Subcommand.ImpactScan should not exist — unported tool removed")

  test("Subcommand.ConceptScanner does not compile (unported tool removed)"):
    val err: String = compileErrors("Subcommand.ConceptScanner")
    assert(err.nonEmpty, "Subcommand.ConceptScanner should not exist — unported tool removed")

  // spec: graph-tool-port — Type-Constraint: the subcommand enum gains the traceability-tool case
  // The cli-entrypoint-contract removal is inverted: graph-tool-port supplies
  // the implementation, so the case exists and must compile.
  test("Subcommand.Graph compiles and parses (ported tool)"):
    val sub: Subcommand = Subcommand.Graph
    assertEquals(Subcommand.cliName(sub), "graph")
    assertEquals(Subcommand.fromString("graph"), Right(Subcommand.Graph))

  // ── Compile-negative: the old two-argument resolve shape ────────────────
  // spec: cli-entrypoint-contract — Compile-Negative: MulticallDispatch.resolve(argv0, argv1)
  test("MulticallDispatch.resolve two-argument form does not compile"):
    val err: String = compileErrors("MulticallDispatch.resolve(\"probatio\", Some(\"gate\"))")
    assert(err.nonEmpty, "MulticallDispatch.resolve(argv0, argv1) should not exist — removed")

  // ── Compile-negative: InvocationName direct construction ────────────────
  test("InvocationName cannot be directly constructed from a String"):
    val err: String = compileErrors("InvocationName(\"probatio\")")
    assert(err.nonEmpty, "InvocationName(...) should not compile — no public apply")

  // spec: jar-launcher-dispatch — Compile-Negative: A program name built from an unclassified string
  test("InvocationName cannot be built from an unclassified archive path string"):
    val err: String = compileErrors("InvocationName(\"probatio-cli-assembly.jar\")")
    assert(
      err.nonEmpty,
      "InvocationName(\"...jar\") should not compile — construction requires an InvocationSource"
    )

  // spec: jar-launcher-dispatch — Compile-Negative: An archive source carrying a tool name
  test("InvocationSource.Archive cannot carry a tool name"):
    val err: String = compileErrors("InvocationSource.Archive(Subcommand.Gate)")
    assert(
      err.nonEmpty,
      "InvocationSource.Archive(Subcommand.Gate) should not compile — the variant takes a path"
    )

  // ── Property & generator obligations (become the Ring 3 test oracle) ───
  //
  // Property: argument-preservation
  //   Invariant: For every invocation name and every argument list, the
  //   argument list the selected tool receives equals the supplied list with
  //   at most its first element removed, and the first element is removed if
  //   and only if it named the selected tool.
  //   Generator: genInvocationName (constructive — tool names + generic names
  //   + non-tool name) × genArgList (constructive — flag/value/tool-name-
  //   shaped tokens; sizes 0–12). Hedgehog cover: named-tool-first ≥ 20%,
  //   symlink-no-tool-token ≥ 20%, tool-name-in-value-position ≥ 10%,
  //   empty-args ≥ 5%.
  //
  // Property: dispatch-equivalence-across-signals
  //   Invariant: For every tool and every argument list, invoking under the
  //   generic name with the tool named as the first argument selects the same
  //   tool and delivers the same remaining arguments as invoking under the
  //   tool's own name with no tool argument.
  //   Generator: genPortedTool (exhaustive enum) × genArgList (filtered to
  //   exclude lists whose first element is a tool name). Hedgehog cover: each
  //   tool ≥ 5%.
  //
  // Property: no-silent-selection
  //   Invariant: Selection never succeeds for a token that is not an exposed
  //   tool name, under any invocation name.
  //   Generator: genNonToolToken (constructive — alphanumeric/kebab tokens,
  //   rejection filter against exposed name set) × genInvocationName.
  //   Hedgehog cover: resembles-removed-tool ≥ 15%.
  //
  // Property: subprocess-agrees-with-in-process
  //   Invariant: For every exposed tool and every argument list in the
  //   fixture corpus, starting the built artifact as a separate process
  //   yields the same exit status as calling the selection-and-run path in
  //   process.
  //   Generator: genFixtureInvocation — constructive, 2 × |tools| corpus.
  //   Hedgehog cover: valid ≥ 40%, invalid ≥ 40%.
  //
  // spec: cli-entrypoint-contract — Property: argument-preservation
  // spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
  // spec: cli-entrypoint-contract — Property: no-silent-selection
  // spec: cli-entrypoint-contract — Property: subprocess-agrees-with-in-process

  // ── Formal contract (Ring 6) ────────────────────────────────────────────
  //
  // Contract: resolveAndSplit
  //   Precondition: none — the function is total over all inputs.
  //   Postcondition: when a tool is selected, the returned remainder is a
  //   suffix of the input whose dropped prefix has length 0 or 1, and has
  //   length 1 if and only if the dropped element classified as the selected
  //   tool.
  //
  //   def resolveAndSplit(nameTool: Option[BigInt], tokens: List[BigInt]):
  //       Option[(BigInt, List[BigInt])] = {
  //     // pure model
  //   }.ensuring { res => res match
  //     case None => true
  //     case Some((tool, rest)) =>
  //       (rest == tokens || (tokens.nonEmpty && rest == tokens.tail && tokens.head == tool)) &&
  //       rest.length >= tokens.length - 1
  //   }
  //
  // spec: cli-entrypoint-contract — Formal Contract: resolveAndSplit (Ring 6)
  // spec: cli-entrypoint-contract — Proof Obligation: The selection-and-split function is total and its remainder is a bounded suffix
