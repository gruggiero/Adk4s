#!/usr/bin/env python3
"""openspec-graph.py — the workflow's ARTIFACT TRACEABILITY GRAPH (prototype).

The verified-scala3 workflow already *is* a graph, maintained as markdown
tables: concepts declare actions and syncs; concept files bind actions to
code; the inventory catalogs types with provenance; specs cite concepts,
use/introduce types, carry requirements; proof obligations bind requirements
to enforcement artifacts. registry-check.sh verifies those edges PAIRWISE.
This tool makes the graph explicit so it can be queried TRANSITIVELY —
the one thing neither grep nor Metals can do, because the nodes are our
artifacts, not code.

Nodes:  concept, action, sync, type, spec, req, oblig, artifact, code
Edges:  declares, defines-sync, implemented-by, cites, uses, introduces,
        has-req, enforced-by, verified-by

Subcommands:
  export [--output F]      nodes+edges as JSON (derived — regenerate freely)
  stats                    node/edge counts by kind
  impact <Concept[/action]>  what an actions/state change reaches:
                           citing specs -> their requirements -> obligations
                           -> enforcing artifacts
  obligations [<change>]   REACHABILITY AUDIT: every requirement must reach
                           an enforcement artifact. Reports unenforced
                           requirements and obligations with no artifact —
                           spec-lint check 12 as reachability, not row count.
  concept-code <Concept>   the code a concept's implementation map binds

Sources (all repo-local, all already required by the schema):
  openspec/concepts/*.md               behavioral registry
  openspec/concept-inventory.md        project type inventory
  openspec/changes/*/specs/*/spec.md   active change specs (archive excluded)

FRESHNESS: this is DERIVED output, like the inventory snapshots — regenerate
per baseline/checkpoint; never hand-maintain. Unlinkable rows are REPORTED,
never silently dropped (a graph that hides what it could not parse is the
confidently-wrong failure mode this workflow exists to avoid).
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections import defaultdict

# ---------------------------------------------------------------------------
# model
# ---------------------------------------------------------------------------


class Graph:
    def __init__(self) -> None:
        self.nodes: dict[str, dict] = {}
        self.edges: list[dict] = []
        self.warnings: list[str] = []

    def node(self, nid: str, kind: str, **attrs) -> str:
        if nid not in self.nodes:
            self.nodes[nid] = {"id": nid, "kind": kind, **attrs}
        else:
            self.nodes[nid].update({k: v for k, v in attrs.items() if v})
        return nid

    def edge(self, src: str, rel: str, dst: str, **attrs) -> None:
        self.edges.append({"from": src, "rel": rel, "to": dst, **attrs})

    def out(self, nid: str, rel: str | None = None) -> list[dict]:
        return [e for e in self.edges if e["from"] == nid and (rel is None or e["rel"] == rel)]

    def inc(self, nid: str, rel: str | None = None) -> list[dict]:
        return [e for e in self.edges if e["to"] == nid and (rel is None or e["rel"] == rel)]


# ---------------------------------------------------------------------------
# parsing helpers
# ---------------------------------------------------------------------------

BACKTICK = re.compile(r"`([^`]+)`")
MDLINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")


def table_rows(lines: list[str], start: int) -> list[list[str]]:
    """Consume a markdown table starting at/after `start`; return data rows."""
    rows = []
    for line in lines[start:]:
        s = line.strip()
        if not s.startswith("|"):
            if rows:
                break
            continue
        cells = [c.strip() for c in s.strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
            continue
        rows.append(cells)
    return rows[1:] if rows else []  # drop header row


def section_bounds(lines: list[str], heading_re: str) -> tuple[int, int] | None:
    rx = re.compile(heading_re)
    for i, line in enumerate(lines):
        if rx.match(line):
            for j in range(i + 1, len(lines)):
                if lines[j].startswith("## "):
                    return (i, j)
            return (i, len(lines))
    return None


STOPWORDS = {
    "the", "a", "an", "and", "or", "in", "on", "for", "to", "of", "is", "are",
    "with", "from", "by", "into", "that", "this", "its", "it", "not", "no",
    "returns", "return", "must", "shall", "when", "then", "given", "spec",
}


def _tokens(text: str) -> set[str]:
    """Distinctive lowercase tokens of a heading/cell, for inferred linking."""
    raw = re.findall(r"[A-Za-z][A-Za-z0-9/_-]{2,}", text.lower())
    return {t for t in raw if t not in STOPWORDS}


def first_symbol(cell: str) -> str | None:
    """Concept name from a table cell: markdown link text or backticked name."""
    m = MDLINK.search(cell)
    if m and m.group(1)[:1].isupper():
        return m.group(1)
    m = BACKTICK.search(cell)
    if m:
        tok = m.group(1).strip()
        if tok[:1].isupper():
            return tok
    m = re.match(r"\s*([A-Z][A-Za-z0-9]*(?:/[A-Za-z][A-Za-z0-9]*)?)", cell)
    return m.group(1) if m else None


# ---------------------------------------------------------------------------
# builders
# ---------------------------------------------------------------------------


def build_concepts(g: Graph, root: str) -> None:
    cdir = os.path.join(root, "openspec", "concepts")
    if not os.path.isdir(cdir):
        return
    for fname in sorted(os.listdir(cdir)):
        if not fname.endswith(".md") or fname == "README.md":
            continue
        path = os.path.join(cdir, fname)
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()

        names = []
        for line in lines:
            m = re.match(r"^#+ Concept: ([A-Za-z][A-Za-z0-9]*)", line) or re.match(
                r"^concept ([A-Za-z][A-Za-z0-9]*)", line
            )
            if m:
                names.append(m.group(1))
        if not names:
            g.warnings.append(f"concept file declares no concept: {fname}")
            continue
        primary = names[0]
        cid = g.node(f"concept:{primary}", "concept", file=f"openspec/concepts/{fname}")

        # actions block (inside the spec fence, between `actions` and
        # `operational principle`)
        in_actions = False
        for line in lines:
            if re.match(r"^actions\s*$", line):
                in_actions = True
                continue
            if re.match(r"^(operational principle|synchronizations|state|purpose)", line) or line.startswith("```"):
                in_actions = False
            if in_actions:
                m = re.match(r"^\s{0,8}([a-z][A-Za-z0-9]*)\s*\[", line)
                if m:
                    aid = g.node(f"action:{primary}/{m.group(1)}", "action", concept=primary)
                    g.edge(cid, "declares", aid)

        # synchronizations: fenced `synchronizations` block or ## section
        for i, line in enumerate(lines):
            if re.match(r"^\s*(synchronizations|## Synchronizations)", line):
                for line2 in lines[i + 1 : i + 60]:
                    if line2.startswith("## ") and "Synchron" not in line2:
                        break
                    m = re.match(r"^\s*(?:sync\s+)?([A-Z][A-Za-z0-9]*):?\s*$", line2)
                    if m:
                        sid = g.node(f"sync:{primary}/{m.group(1)}", "sync", concept=primary)
                        g.edge(cid, "defines-sync", sid)

        # implementation map -> code files
        b = section_bounds(lines, r"^## Implementation map")
        if b:
            for row in table_rows(lines, b[0] + 1):
                for cell in row[1:]:
                    for tok in BACKTICK.findall(cell):
                        if "/" in tok and "." in tok and " " not in tok:
                            fid = g.node(f"code:{tok}", "code")
                            g.edge(cid, "implemented-by", fid)


def build_inventory(g: Graph, root: str) -> None:
    path = os.path.join(root, "openspec", "concept-inventory.md")
    if not os.path.isfile(path):
        return
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    section = None
    for i, line in enumerate(lines):
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        if not line.strip().startswith("|") or section is None:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
            continue
        name = first_symbol(cells[0]) or cells[0].strip("`")
        if not name or not name[:1].isupper() or name.startswith("<!--"):
            continue
        tid = g.node(f"type:{name}", "type", section=section, provenance=cells[-1])
        # service-trait Implementations column (scanner-linked, cross-file)
        if section.startswith("Service Traits") and len(cells) >= 4:
            for impl in [x.strip(" `") for x in cells[3].split(",")]:
                if impl and impl != "—":
                    iid = g.node(f"type:{impl}", "type", section="implementation")
                    g.edge(tid, "implemented-by", iid)


REQ_RE = re.compile(r"^### Requirement:\s*(.+?)\s*$")


def build_specs(g: Graph, root: str) -> None:
    cdir = os.path.join(root, "openspec", "changes")
    if not os.path.isdir(cdir):
        return
    for change in sorted(os.listdir(cdir)):
        if change == "archive":
            continue
        sdir = os.path.join(cdir, change, "specs")
        if not os.path.isdir(sdir):
            continue
        for cap in sorted(os.listdir(sdir)):
            path = os.path.join(sdir, cap, "spec.md")
            if not os.path.isfile(path):
                continue
            with open(path, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
            sid = g.node(
                f"spec:{change}/{cap}",
                "spec",
                change=change,
                file=f"openspec/changes/{change}/specs/{cap}/spec.md",
            )

            # cited behavioral concepts
            b = section_bounds(lines, r"^## Concepts Used \(behavioral\)")
            if b:
                for row in table_rows(lines, b[0] + 1):
                    ref = first_symbol(row[0])
                    if not ref or "<!--" in row[0]:
                        continue
                    new = re.search(r"\((new|created by)", row[0], re.I)
                    if "/" in ref:
                        cname = ref.split("/")[0]
                        nid = g.node(f"action:{ref}", "action", concept=cname)
                    else:
                        nid = g.node(f"concept:{ref}", "concept")
                    g.edge(sid, "cites", nid, planned=bool(new))

            # used / introduced types
            for heading, rel in (
                (r"^## Concepts Used \(from inventory\)", "uses"),
                (r"^## Concepts Introduced", "introduces"),
            ):
                b = section_bounds(lines, heading)
                if not b:
                    continue
                for row in table_rows(lines, b[0] + 1):
                    name = first_symbol(row[0])
                    if not name or "<!--" in row[0]:
                        continue
                    g.edge(sid, rel, g.node(f"type:{name}", "type"))

            # requirements (ordinal matters: obligations cite "Requirement N")
            reqs: list[str] = []
            for line in lines:
                m = REQ_RE.match(line)
                if m:
                    reqs.append(m.group(1))
            for idx, rname in enumerate(reqs, start=1):
                rid = g.node(
                    f"req:{change}/{cap}#{idx}",
                    "req",
                    title=rname,
                    ordinal=idx,
                    spec=f"{change}/{cap}",
                )
                g.edge(sid, "has-req", rid)

            # proof obligations -> requirements (by ordinal or title) + artifacts
            b = section_bounds(lines, r"^## Proof Obligations")
            if b:
                for n, row in enumerate(table_rows(lines, b[0] + 1), start=1):
                    if len(row) < 4 or "<!--" in row[0]:
                        continue
                    oid = g.node(
                        f"oblig:{change}/{cap}#{n}",
                        "oblig",
                        title=row[0].strip("`"),
                        enforcement=row[2],
                        spec=f"{change}/{cap}",
                    )
                    linked = False
                    # (1) explicit ordinal: "Requirement 3" / "R3" (shorthand)
                    for m in re.finditer(r"(?:Requirement\s+|\bR)(\d+)\b", row[1]):
                        i = int(m.group(1))
                        if 1 <= i <= len(reqs):
                            g.edge(f"req:{change}/{cap}#{i}", "enforced-by", oid, link="explicit")
                            linked = True
                    # (2) requirement title quoted in the Source cell
                    if not linked:
                        for i, rname in enumerate(reqs, start=1):
                            key = rname.lower()[:40]
                            if key and key in row[1].lower():
                                g.edge(f"req:{change}/{cap}#{i}", "enforced-by", oid, link="title")
                                linked = True
                                break
                    # (3) INFERRED: distinctive-token overlap between the
                    # obligation title and a requirement title. Tagged so the
                    # audit can separate proven links from inferred ones —
                    # an inferred link is a lint finding, not a pass.
                    if not linked:
                        otoks = _tokens(row[0])
                        best, score = None, 0
                        for i, rname in enumerate(reqs, start=1):
                            common = otoks & _tokens(rname)
                            if len(common) > score:
                                best, score = i, len(common)
                        if best and score >= 2:
                            g.edge(f"req:{change}/{cap}#{best}", "enforced-by", oid, link="inferred")
                            linked = True
                            g.warnings.append(
                                f"{change}/{cap}: obligation #{n} Source={row[1][:44]!r} names no "
                                f"requirement — INFERRED -> R{best} by title overlap"
                            )
                    if not linked:
                        # A TYPED non-requirement source (Property/Scenario/
                        # Invariant/...) is legitimate per the v8 mandate: the
                        # obligation comes from a property, not a requirement.
                        # Only an untyped cell names nothing.
                        typed = re.search(
                            r"(^|[^A-Za-z])(Property|Properties|Scenario|Scenarios|Invariant|"
                            r"Compile-Negative|Temporal|Criterion|Type-Constraint|MUST-CONFIRM|"
                            r"Design|Non-goal)\s*(:|\d)",
                            row[1],
                        )
                        if typed:
                            g.nodes[oid]["source_kind"] = typed.group(2)
                        else:
                            g.warnings.append(
                                f"{change}/{cap}: obligation #{n} names NOTHING resolvable "
                                f"(Source={row[1][:60]!r})"
                            )
                    for art in BACKTICK.findall(row[3]) or [row[3]]:
                        art = art.strip()
                        if art and art not in ("—", "-"):
                            g.edge(oid, "verified-by", g.node(f"artifact:{art}", "artifact"))


def build(root: str) -> Graph:
    g = Graph()
    build_concepts(g, root)
    build_inventory(g, root)
    build_specs(g, root)
    return g


# ---------------------------------------------------------------------------
# queries
# ---------------------------------------------------------------------------


def cmd_stats(g: Graph) -> int:
    kinds = defaultdict(int)
    for n in g.nodes.values():
        kinds[n["kind"]] += 1
    rels = defaultdict(int)
    for e in g.edges:
        rels[e["rel"]] += 1
    print("nodes:", ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())), f"(total {len(g.nodes)})")
    print("edges:", ", ".join(f"{k}={v}" for k, v in sorted(rels.items())), f"(total {len(g.edges)})")
    if g.warnings:
        print(f"\nunlinked rows ({len(g.warnings)}) — reported, not dropped:")
        for w in g.warnings:
            print("  ", w)
    return 0


def cmd_impact(g: Graph, target: str) -> int:
    """Concept or Concept/action -> citing specs -> requirements -> obligations
    -> artifacts. The question Step 0 asks at the SPEC level."""
    kind = "action" if "/" in target else "concept"
    nid = f"{kind}:{target}"
    if nid not in g.nodes:
        alt = [n for n in g.nodes if n.startswith(f"{kind}:{target}") or n.endswith(f"/{target}")]
        print(f"impact: unknown {kind} '{target}'" + (f"; did you mean: {', '.join(alt[:5])}" if alt else ""))
        return 1

    print(f"impact: {target}")
    related = {nid}
    if kind == "concept":
        related |= {e["to"] for e in g.out(nid, "declares")}
        syncs = [e["to"] for e in g.out(nid, "defines-sync")]
        code = [e["to"] for e in g.out(nid, "implemented-by")]
        if syncs:
            print(f"  syncs defined: {', '.join(s.split(':', 1)[1] for s in syncs)}")
        if code:
            print(f"  implementation map binds {len(code)} file(s)")

    citing = sorted({e["from"] for r in related for e in g.inc(r, "cites")})
    if not citing:
        print("  no ACTIVE spec cites this concept — safe from the spec side")
        return 0

    total_reqs = total_obl = 0
    unenforced: list[str] = []
    artifacts: set[str] = set()
    for sid in citing:
        planned = any(e.get("planned") for r in related for e in g.inc(r, "cites") if e["from"] == sid)
        print(f"\n  {sid.split(':', 1)[1]}{'  [cites as NEW/created]' if planned else ''}")
        for e in g.out(sid, "has-req"):
            rid = e["to"]
            req = g.nodes[rid]
            obls = g.out(rid, "enforced-by")
            total_reqs += 1
            total_obl += len(obls)
            arts = {a["to"].split(":", 1)[1] for o in obls for a in g.out(o["to"], "verified-by")}
            artifacts |= arts
            mark = "✓" if obls else "✗"
            print(f"    {mark} R{req['ordinal']}: {req['title'][:72]}")
            if obls:
                print(f"        {len(obls)} obligation(s) -> {', '.join(sorted(arts)[:4]) or '(no artifact)'}")
            else:
                unenforced.append(f"{sid.split(':', 1)[1]} R{req['ordinal']}")

    print(
        f"\n  reach: {len(citing)} spec(s), {total_reqs} requirement(s), "
        f"{total_obl} obligation(s), {len(artifacts)} enforcing artifact(s)"
    )
    if unenforced:
        print(f"  UNENFORCED requirements ({len(unenforced)}): {', '.join(unenforced)}")
    return 0


def cmd_obligations(g: Graph, change: str | None) -> int:
    """Reachability audit: every requirement must reach an artifact."""
    specs = [n for n, d in g.nodes.items() if d["kind"] == "spec" and (not change or d["change"] == change)]
    if not specs:
        print(f"obligations: no active spec found{' for ' + change if change else ''}")
        return 1
    bad = 0
    for sid in sorted(specs):
        print(f"\n{sid.split(':', 1)[1]}")
        reqs = [e["to"] for e in g.out(sid, "has-req")]
        orphan_reqs = []
        for rid in reqs:
            obls = [e["to"] for e in g.out(rid, "enforced-by")]
            arts = {a["to"] for o in obls for a in g.out(o, "verified-by")}
            if not obls:
                orphan_reqs.append(g.nodes[rid])
            elif not arts:
                bad += 1
                print(f"  ✗ R{g.nodes[rid]['ordinal']} reaches obligations but NO artifact")
        all_obls = [n for n, d in g.nodes.items() if d["kind"] == "oblig" and d.get("spec") == sid.split(":", 1)[1]]
        dangling = [o for o in all_obls if not g.out(o, "verified-by")]
        manual = [
            o
            for o in all_obls
            if re.search(r"manual review", g.nodes[o].get("enforcement", ""), re.I)
        ]
        links = defaultdict(int)
        for rid in reqs:
            for e in g.out(rid, "enforced-by"):
                links[e.get("link", "?")] += 1
        print(f"  requirements: {len(reqs)}   obligations: {len(all_obls)}   "
              f"artifacts: {len({a['to'] for o in all_obls for a in g.out(o, 'verified-by')})}")
        kind_sourced = [o for o in all_obls if g.nodes[o].get("source_kind")]
        print(f"  obligation links: " + ", ".join(f"{k}={v}" for k, v in sorted(links.items())) or "  (none)")
        if kind_sourced:
            kinds = defaultdict(int)
            for o in kind_sourced:
                kinds[g.nodes[o]["source_kind"]] += 1
            print(f"  non-requirement sources (legitimate): "
                  + ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())))
        if links.get("inferred"):
            print(f"  ⚠ {links['inferred']} link(s) INFERRED by title overlap — the Source cell names "
                  f"no requirement; make it 'Requirement N' or quote the title")
        if orphan_reqs:
            bad += len(orphan_reqs)
            print(f"  ✗ UNENFORCED requirement(s) — no obligation cites them:")
            for r in orphan_reqs:
                print(f"      R{r['ordinal']}: {r['title'][:70]}")
        if dangling:
            print(f"  ⚠ {len(dangling)} obligation(s) with no artifact cell")
        if manual:
            print(f"  ℹ {len(manual)} manually-reviewed obligation(s) (allowed, must be explicit)")
    if g.warnings:
        print(f"\nunlinked rows ({len(g.warnings)}):")
        for w in g.warnings:
            print("  ", w)
    print(f"\nverdict: {'FAIL — ' + str(bad) + ' unenforced/unreachable' if bad else 'PASS — every requirement reaches an artifact'}")
    return 1 if bad else 0


def cmd_concept_code(g: Graph, name: str) -> int:
    nid = f"concept:{name}"
    if nid not in g.nodes:
        print(f"concept-code: unknown concept '{name}'")
        return 1
    files = sorted({e["to"].split(":", 1)[1] for e in g.out(nid, "implemented-by")})
    actions = sorted({e["to"].split("/", 1)[1] for e in g.out(nid, "declares")})
    print(f"{name}: {len(actions)} action(s), {len(files)} bound file(s)")
    for a in actions:
        print(f"  action {a}")
    for f in files:
        print(f"  code   {f}")
    return 0


# ---------------------------------------------------------------------------
# cli
# ---------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    root = os.environ.get("OPENSPEC_ROOT") or os.getcwd()
    if not os.path.isdir(os.path.join(root, "openspec")):
        print(f"openspec-graph: no openspec/ directory under {root}", file=sys.stderr)
        return 2
    cmd = argv[1] if len(argv) > 1 else "stats"
    g = build(root)

    if cmd == "export":
        payload = {"nodes": list(g.nodes.values()), "edges": g.edges, "warnings": g.warnings}
        out = None
        if "--output" in argv:
            out = argv[argv.index("--output") + 1]
        text = json.dumps(payload, indent=2)
        if out:
            with open(out, "w", encoding="utf-8") as fh:
                fh.write(text + "\n")
            print(f"openspec-graph: {len(g.nodes)} nodes, {len(g.edges)} edges -> {out}", file=sys.stderr)
        else:
            print(text)
        return 0
    if cmd == "stats":
        return cmd_stats(g)
    if cmd == "impact":
        if len(argv) < 3:
            print("usage: openspec-graph.py impact <Concept[/action]>", file=sys.stderr)
            return 2
        return cmd_impact(g, argv[2])
    if cmd == "obligations":
        return cmd_obligations(g, argv[2] if len(argv) > 2 else None)
    if cmd == "concept-code":
        if len(argv) < 3:
            print("usage: openspec-graph.py concept-code <Concept>", file=sys.stderr)
            return 2
        return cmd_concept_code(g, argv[2])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
