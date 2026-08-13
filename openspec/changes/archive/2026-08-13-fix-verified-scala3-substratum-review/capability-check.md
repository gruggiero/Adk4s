# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-12
**Verification result**: CLEAN (profile matches build; no Scala stack changes from this change)

## Corrections applied to the project profile

None. This change modifies the verified-scala3 workflow's own bash/jq/python
tooling in `openspec/schemas/verified-scala3/` — no `build.sbt`,
`project/Versions.scala`, `project/Dependencies.scala`, or Scala source
changes. The project profile's Scala stack description is unaffected.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| python3 (runtime) | declared prerequisite | proposal §fact-extraction-unification (D5); added to the workflow's declared prerequisite set alongside bash, git, jq, shellcheck, bats, shfmt |
| `openspec-graph.py export` | new Python subcommand | proposal §New Concepts; JSON export mode for chain-state consumption |
| `spec-lint.sh --format json` | new bash output mode | proposal §New Concepts; machine interface for chain-state consumption |
| `ledger.sh run` / `ledger.sh verify` | new bash subcommands | proposal §New Concepts; capture + replay modes for evidence integrity |

These are workflow-tooling capabilities, not Scala project capabilities. They
do not appear in `build.sbt` or `project/Dependencies.scala`. They are
declared in the workflow's own prerequisite mechanism (`schema.yaml` +
`scanner/install-skills.sh --check-installed`) and CI templates.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes (bats) | bats tests run; `shellcheck` + `shfmt` pass on modified scripts. No sbt compile (no Scala code changed). |
| R1 lint | yes (shellcheck + shfmt) | `shellcheck` (declared prerequisite, CI-enforced); `shfmt -d` (declared prerequisite, CI-enforced). Scalafix/WartRemover N/A (no Scala code). |
| R2 architecture | no | No Scala module dependencies; this change touches only `openspec/schemas/verified-scala3/` tooling. Skip impact: none — the workflow's own layering is not Scala module layering. |
| R3 property/scenario | yes (bats) | bats scenario tests in `openspec/schemas/verified-scala3/tests/` — MANDATORY. Each defect fix gets a bats case. No CONCURRENT behavior (bash scripts are sequential). |
| R4 wire/persistence | yes (ledger JSONL compat) | New ledger fields (`sha256`, `digest`, `wallTime`, `failed` reason-code) must not break existing readers. Backward-compat test with archived `evidence-ledger.jsonl` fixtures. |
| R5 mutation | no | Stryker4s targets Scala only; no bash mutation tooling in the declared prerequisite set. Skip impact: bats scenario tests provide the equivalent coverage for bash logic — each fix has a case that would fail before the fix. |
| R6 formal | no | No PureScala kernel; bash/jq logic is tested via bats scenarios, not Stainless. Skip impact: the jq contract files (`*.jq`) are deterministic, total programs — bats tests exercising them are the verification. |
| R7 model checking | no | No distributed/event-driven invariants. |
| R8 adversarial review | yes | MANDATORY — fresh-context reviewer checks each fix against the review document's defect description. |
| R9 telemetry | no | No telemetry stack detected (otel4s NOT PRESENT per capability-profile). |
| Concurrency kit | N/A | No concurrent behavior (bash scripts are sequential). |
| Code intelligence | N/A | No Scala code to semantically analyze; git grep is sufficient for bash/jq/python tooling. |
