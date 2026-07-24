# Artifact Traceability Graph — PROTOTYPE

Status: **prototype, branch `openspec-artifact-graph`** — not wired into
schema.yaml, not mirrored to other repos, not installed as a skill. Evaluate
before promoting.

## What it is

`scanner/openspec-graph.py` derives an explicit graph from artifacts the
workflow already maintains, so they can be queried **transitively**. The
workflow is already a graph — concepts declare actions and syncs, concept
files bind actions to code, the inventory catalogs types, specs cite
concepts and carry requirements, proof obligations bind requirements to
enforcement artifacts — but `registry-check.sh` verifies those edges
**pairwise**. Multi-hop questions ("what does changing this concept reach?",
"does every requirement reach an enforcing artifact?") had no mechanical
answer.

Nodes: `concept action sync type spec req oblig artifact code`
Edges: `declares defines-sync implemented-by cites uses introduces has-req
enforced-by verified-by`

Sources (all repo-local, all already required): `openspec/concepts/*.md`,
`openspec/concept-inventory.md`, `openspec/changes/*/specs/*/spec.md`
(archive excluded).

## Commands

```bash
scanner/openspec-graph.py stats                    # node/edge counts + unlinked rows
scanner/openspec-graph.py export --output g.json   # derived JSON
scanner/openspec-graph.py impact <Concept[/action]>  # citing specs -> reqs -> obligations -> artifacts
scanner/openspec-graph.py obligations [<change>]   # REACHABILITY AUDIT (exit 1 on unenforced)
scanner/openspec-graph.py concept-code <Concept>   # actions + bound files
```

## Measured on real corpora (2026-07-20)

| Repo | Nodes | Edges | Audit verdict |
|---|---|---|---|
| adk4s | 424 | 625 | FAIL — 4 candidate unenforced requirements in `add-optimizable-surface` |
| graphStore | 174 | ~140 | PASS — every requirement reaches an artifact |

## What it found (the point of the prototype)

1. **The Proof-Obligations `Source` column has no mandated format.** Three
   repos/changes invented three conventions: `Requirement 3` (adk4s
   cross-run-memory), `R1 scenario` / `R2 + Property 2` (graphStore), and
   `Requirement + Property: <name>` (adk4s optimizable-surface — names NO
   requirement at all). spec-lint check 12 counts rows; it cannot verify the
   link, so a table can look complete while the requirement→obligation
   binding is unverifiable. **This is a schema gap, and the cheapest fix is
   a mandated Source format** (`Requirement N` or the exact requirement
   title), which would also make check 12 mechanical.
2. **`add-optimizable-surface` has 4 candidate unenforced requirements**
   (R1 predictor-state is pure immutable data, R2 predictor path addressing,
   R6 updateEither typed errors, R13 derived via structural derivation).
   R1 was spot-checked by hand and genuinely has no dedicated obligation row.
   ⚠ CAVEAT: because that spec's Source cells name no requirement, links
   were INFERRED by title-token overlap and assigned greedily — an
   obligation meant for R13 may have attached elsewhere. Treat the list as
   **candidates for human review**, not proof.
3. Everything the tool cannot link is **reported, never dropped** — a graph
   that hides unparsed rows would be the confidently-wrong failure mode this
   workflow exists to avoid.

## Honest limitations

- **Python3 dependency.** The CI-portable checks (`registry-check.sh`,
  `spec-lint.sh`, `danger-scan.sh`) stay bash+git-grep. If this graduates,
  either keep it workstation-only (like the Metals recipes) or port the
  audit to bash.
- **Inferred links are a lint finding, not a pass.** Any spec whose audit
  shows `inferred=N` should fix its Source cells instead.
- **Requirement ordinals are positional** — reordering requirements silently
  re-points explicit `Requirement N` links. Title-based linking is more
  robust; a mandated format should prefer titles or stable IDs.
- **`Property N` / `Compile-Negative` sources are unlinkable** by design
  here: they cite artifacts other than requirements. Modelling
  properties/compile-negative obligations as first-class nodes is the
  obvious next step.
- Not yet covered: tests→requirements (Ring 3 cross-reference tables),
  per-spec commits as temporal versions, code edges from SemanticDB/SCIP
  (which would enable true call-path/closure queries).

## If it graduates

- Mandate the Source format in schema.yaml + spec template; make
  `obligations` a spec-lint mechanical check (it is stricter than check 12).
- Add tests→requirements edges from the Ring 3 cross-reference table so
  "which tests trace to sync X" becomes answerable.
- Consider graphStore as the store (dogfood): per-spec commits map onto
  temporal invalidation; SemanticDB-derived code edges could join the same
  graph, which is also the natural home for the one remaining ScalaSemantic
  question (`call_path` for sync verification).
