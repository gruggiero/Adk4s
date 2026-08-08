# Fixture — a prerequisite table that MUST fail Property 2

Committed fixture. Never edit it to make a test pass; it exists to prove the
property can fail. Two defects, one per row:

- `yq` is a SEVENTH tool, beyond the six the original hard-coded selector
  matched. A property whose domain lists tool names would not select this row
  at all and would pass vacuously — the defect the fresh-context Ring 8 pass
  found.
- `podman` has an EMPTY justification cell, the edge case the spec's Generator
  strategy names ("a prerequisite listed with an empty justification cell").
- `curl` has a one-word cell, which names no ring or function.

| Prerequisite | Required by |
|---|---|
| `bash` | every check and every hook — the interpreter they are written in |
| `yq` | |
| `podman` | |
| `curl` | downloads |
