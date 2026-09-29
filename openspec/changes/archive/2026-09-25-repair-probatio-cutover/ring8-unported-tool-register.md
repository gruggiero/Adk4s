# Ring 8: Adversarial Spec-Compliance Review — unported-tool-register

Fresh context: yes (isolated read-only subagent `97e3e9ca`; inputs: spec + contract + changed files)
Baseline: `d22c33d59b625409770516a74b2a01846dd0fd79`
Oracle tampering: none in the spec-11 section (assertions match structured variants; generators match or exceed the spec's declared strategy)
Requirements at review: 2 PASS, 2 PARTIAL, 0 FAIL — **4 PASS / 0 PARTIAL / 0 FAIL after remediation** (one Req-2 residual presented to the human gate; see below)

## Verdicts and remediation

### Req 1 — every tool ported or registered: **PASS**
`classifyAll` is total (every non-`.predecessor.bak` path lands in `classified` XOR `unclassified`); `checkSurface` checks `Unreadable` before classifying, so the Undetermined/Finding boundary can't be crossed either direction. Real-tree completeness asserted non-vacuously.

### Req 2 — every entry states its blocker: **PARTIAL → remediated, one residual for the human gate**
Closed enum + all three compile-negatives pinned; `parseBlocker` rejects free text and empty payloads. **Residual (presented, not silently left):** the public constructors still admit `GatedOnSpike("")` / `SupersededByPortedTool("nonexistent")` — semantically forbidden states the parser rejects but the type permits. Not remediated because the approved contract pins `PortBlocker`'s `String` payloads and `Report`'s exact field set; a smart-constructor refinement or an extra report bucket would change the approved shape. The register's sole production input path is `parseRegister`, which enforces non-empty payloads — direct construction is a test-surface only. **Human decision required at the checkpoint.**

### Req 3 — citations resolve: **PASS**
`unresolvedCitations` pairs entry↔absent-citation exactly and feeds `isClean`; every committed citation verified against the real tree; enumerated property per spec.

### Req 4 — ported tool leaves the register: **PARTIAL → remediated**
1. **`metals stop` diverged from the predecessor** — two branches fixed:
   - EPERM live pid: `ProcessHandle.isAlive` reported foreign-owned pids alive → `Finding`, while the predecessor's `kill -0` fails on EPERM → "no running instance" + `rm -f` + exit 0. **Fixed**: `canSignal`/`terminate` now run the literal `kill -0`/`kill` subprocesses — the exact predecessor semantics.
   - Nonexistent root arg: `Path.of` never checked existence → silent `Ran(0)`, while the predecessor's `cd "$ROOT" && pwd` under `set -e` exits non-zero. **Fixed**: `Files.isDirectory` guard → `Finding`; pinned by a new test (`MigrationProtocolSpec` — nonexistent root → Finding).
   - stdout prefix restored to the predecessor's `metals-start:` on the two stop-path lines.
2. **`metals call` discovery caveat disclosed** — the ported `metals start` is a model stub (`MetalsClient.initialize` performs no launch I/O) so `.metals/mcp.url` is only ever written by the predecessor path; the register's Notes now state this explicitly rather than implying full supersession.

## Dangerous-pattern findings — open, spec-silent (for the checkpoint record)

- `classifyAll`'s `registerByPath` map collapses **duplicate register rows for one path** silently — two contradictory blockers for one tool would not be reported. Spec is silent; reporting it needs a `Report` field the approved contract doesn't define.
- `parseRegister` silently drops `<4`-cell `|…|` rows — fail-closed downstream (tool → unclassified → Finding) except a malformed row for a *ported* path would hide a would-be doubly-classified record.
- **Stale register rows** (path absent from the tree) are never reported — completeness is checked tool→register only. Spec-silent.
- `renderRegister` is non-injective on `|`/`,` in fields — generators emit markdown-safe tokens, so the class is untestable as written; committed rows are paths and can't realistically contain either.
- `isRevertTarget` is name-based: an executable literally named `x.predecessor.bak` exits the tool domain — spec-sanctioned, noted for the record.

## Fixed in passing (neighboring-spec defect in a touched file)

- `EntrypointContractSpec` `no-silent-selection`: the removed/mutation tool token was selected by `tokenKind.hashCode().abs % length` — a compile-time constant per branch, so the property exercised exactly one name per class forever. Replaced with real `Gen.elementUnsafe` draws + a `frequency1` mix over the three token classes.
- `isHeaderRow` keyed on first cell `"tool"` only — a register row for a tool literally named "tool" was silently dropped. Now requires the two header cells `tool|path` case-insensitively.
