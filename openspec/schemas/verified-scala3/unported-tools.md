# Unported Tool Register

Every executable tool in `scanner/` and `hooks/` is classified into
exactly one of two halves: **ported** (named on the `Subcommand` surface
of the probatio binary) or **registered** (recorded here). A tool in
neither is a check finding; a tool in both is a check finding. The check
is `UnportedToolRegister.checkSurface` (probatio-core); the executable
domain excludes `*.predecessor.bak` files — those are the swap protocol's
recorded revert targets, not tools.

A register entry names what blocks the port, drawn from the closed
`PortBlocker` set — `GatedOnSpike(name)`, `NotOnEnforcementPath`,
`SupersededByPortedTool(subcommand)` — and the workflow instructions that
still cite the tool, so the blast radius of a future port is visible.

| Tool | Path | Blocker | Cited by |
|------|------|---------|----------|
| concept-registry verifier | openspec/schemas/verified-scala3/scanner/registry-check.sh | GatedOnSpike(native-packaging V1 scalameta spike) | openspec/schemas/verified-scala3/schema.yaml, openspec/schemas/verified-scala3/ci/github-actions.yml, openspec/schemas/verified-scala3/templates/inventory-check.md |
| concept scanner (scan.sh + concept-scanner.scala) | openspec/schemas/verified-scala3/scanner/scan.sh | GatedOnSpike(native-packaging V1 scalameta spike) | openspec/schemas/verified-scala3/skills/openspec-scan-concepts/SKILL.md, openspec/schemas/verified-scala3/schema.yaml |
| impact-scan | openspec/schemas/verified-scala3/scanner/impact-scan.sh | NotOnEnforcementPath | openspec/schemas/verified-scala3/skills/openspec-code-intel/SKILL.md, openspec/schemas/verified-scala3/schema.yaml |
| removal-audit | openspec/schemas/verified-scala3/scanner/removal-audit.sh | NotOnEnforcementPath | openspec/schemas/verified-scala3/skills/openspec-code-intel/SKILL.md, openspec/schemas/verified-scala3/schema.yaml |
| metals-call | openspec/schemas/verified-scala3/scanner/metals-call.sh | NotOnEnforcementPath | openspec/schemas/verified-scala3/skills/openspec-code-intel/SKILL.md, openspec/schemas/verified-scala3/schema.yaml, openspec/capability-profile.md |

## Notes

- `scan.sh` is the executable wrapper; `concept-scanner.scala` is its
  implementation (non-executable — outside the tool domain, registered
  under the same entry).
- `metals-start.sh` is **not** registered: both of its operations are on
  the ported surface (`metals start`, `metals stop`), so it classifies
  `Ported("metals")`. `metals-call.sh`'s `call` operation is not ported —
  it is registered above.
- Caveat on that classification: `metals call` discovers the server via
  `.metals/mcp.url`, which only the predecessor `start` path writes — the
  ported `metals start` is a model stub (`MetalsClient.initialize`
  performs no launch I/O), so `metals call` has no reachable endpoint
  until `start` does real process work. The `NotOnEnforcementPath`
  blocker stands regardless.
- `openspec-graph.py` is **not** registered: it was ported by
  `graph-tool-port` and classifies `Ported("graph")`.
