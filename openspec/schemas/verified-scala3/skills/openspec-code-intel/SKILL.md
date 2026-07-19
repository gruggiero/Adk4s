---
name: openspec-code-intel
description: >
  Semantic code-intelligence for the verified-scala3 workflow via a headless
  Metals MCP endpoint, driven from scripts (no agent-side MCP registration
  needed): compiler-resolved find-usages, symbol inspection, and the
  impact-scan / removal-audit recipes used by apply Steps 0 and 12. Falls
  back to git grep deterministically when the endpoint is down. Use whenever
  you need "who uses X", "what is X", or the public-type-change impact scan
  — before reaching for grep.
metadata:
  generatedBy: verified-scala3-schema/7.0.0
---

# Code Intelligence Skill (Metals MCP, option B)

## Why

The workflow's recurring searches — find-references, symbol inspection,
catch-all impact scans, orphan audits — are exactly what text search does
worst: comment/string false positives (~30% measured on this repo), missed
re-exports, conflated same-named symbols (e.g. adk4s's and llm4s's
`AgentEvent`), and a second phase of file-reading to classify every hit.
The scripts below ask the compiler instead (Metals MCP endpoint), and hand
back a digest ~5–10× smaller than the grep pipeline. Scripts — not raw MCP
tools — so the recipes are deterministic, versioned with the schema, and
identical across agents (installed by `install-skills.sh`).

## Setup (once per machine, then once per project)

```bash
cs install metals-mcp                              # once per machine
openspec/schemas/verified-scala3/scanner/metals-start.sh    # once per project
openspec/schemas/verified-scala3/scanner/metals-start.sh stop   # when done
```

PER-PROJECT: Metals is workspace-scoped — one instance serves ONE build.
`metals-start.sh` picks a free port (8394 upward), finds a JDK 17+
(JAVA_HOME or the PATH java), starts the server detached, and writes the
endpoint to `<repo-root>/.metals/mcp.url`. The scripts discover the
endpoint in this order:

1. `METALS_MCP_URL` env var (explicit override)
2. the current repo's `.metals/mcp.url` — so with parallel projects each
   repo's recipes talk to THEIR index, never another project's
3. default `http://localhost:8394/mcp`

First start imports the build and indexes (seconds with warm Bloop state,
minutes cold; the endpoint answers before indexing finishes — early symbol
queries can be incomplete). RAM: ~1.5–2 GB resident PER instance — stop
instances you are not actively using. Record endpoint + Metals version in
`openspec/capability-profile.md` ("Code Intelligence" section);
metals-start.sh prints the line to paste.

## The scripts (in `openspec/schemas/verified-scala3/scanner/`)

### metals-call.sh — generic bridge

```bash
metals-call.sh probe                          # endpoint up? (exit 2 = use grep)
metals-call.sh list                           # available tools
metals-call.sh resolve <Name|fq.Name>         # canonical FQCN(s) — ALWAYS
                                              # resolve before get-usages
metals-call.sh <tool> '<json-args>'           # raw tool call
```

Useful raw calls (fqcn = canonical name from `resolve`):

| Question | Call |
|---|---|
| What members/signature does X have? | `metals-call.sh inspect '{"fqcn":"..."}'` |
| Who uses X? (file:line list) | `metals-call.sh get-usages '{"fqcn":"..."}'` |
| Scaladoc for X? | `metals-call.sh get-docs '{"fqcn":"..."}'` |
| Skeleton source of X (bodies elided)? | `metals-call.sh get-source '{"fqcn":"..."}'` |
| Does this file compile? (fast Ring 0 loop) | `metals-call.sh compile-file '{"fileInFocus":"path"}'` |
| Find symbols by partial name | `metals-call.sh glob-search '{"query":"...","fileInFocus":"<any project file>"}'` |

### impact-scan.sh — apply Step 0 PUBLIC-TYPE-CHANGE IMPACT SCAN

```bash
impact-scan.sh org.pkg.TypeName [window]
```

Semantic reference set (get-usages) + syntactic catch-all detection in
referencing files only. Output: the candidate `case _`/`case other` arms
near a usage, each to be resolved (exhaustive | explicit reject | justified)
in the spec's Proof Obligations.

### removal-audit.sh — apply Step 12 REMOVAL AUDIT

```bash
removal-audit.sh --suggest <baseline-SHA>     # what was removed; suspect pool
removal-audit.sh <fqcn> [<fqcn>...]           # who still uses each suspect
```

Verdict `ORPHAN-CANDIDATE` when all references are self-references in the
defining file — delete in this change or retain with written rationale.
Exit 1 when orphan candidates exist.

## Rules

1. **Freshness**: semantic answers come from an index — trustworthy only
   after a successful compile (post-Ring 0). During red phases (typed
   contract drafting, pre-implementation oracle), use syntactic/textual
   tools; after edits, `compile-file` before trusting `get-usages`.
2. **Resolve first**: `get-usages` on a wrong FQCN silently degrades to
   PACKAGE usages (a flood of line-1 refs). Nested symbols are
   `Outer.Inner` (e.g. `RunnableOps.FallbackSemantic`); top-level opaque
   types live in a synthetic `package` object. The recipes resolve
   automatically; do the same for raw calls.
3. **Fallback is part of the contract**: every script degrades to git grep
   (broader, noisier) and says so in its output. Restricted CI keeps using
   registry-check/spec-lint/danger-scan — bash + git grep only; the Metals
   endpoint is a workstation/agent capability, never a CI dependency.
4. **Known limits** (Metals 1.6.7): `glob-search` needs a `fileInFocus`
   (pass any project file); `inspect` on a sealed trait lists members, not
   variants (use the companion or the concept inventory); opaque-type
   companions sometimes return empty usages (scripts fall back textually).
   Pin the Metals version in the capability profile — MCP tool names are
   not yet a stable contract.

## Where the apply phase uses this

- Step 0 impact scan → `impact-scan.sh <widened-type>` (primary method)
- Step 0 concept check → `inspect` / `get-docs` to verify a concept's
  members instead of reading whole files
- Step 2/3 contract & implementation → `compile-file` for tight loops;
  `get-source` to read a dependency API skeleton
- Step 8 adversarial review → `get-usages` for direct-construction probing
- Step 12 removal audit → `removal-audit.sh --suggest <baseline>` then
  `removal-audit.sh <suspects...>`
