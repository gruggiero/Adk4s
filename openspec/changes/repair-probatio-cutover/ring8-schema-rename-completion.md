# Ring 8 — adversarial spec-compliance review: schema-rename-completion

Change: `repair-probatio-cutover` — spec 10/11.
Baseline: `55837a0b39b720e732fe45a157e22d370bcf09c7`.
Fresh context: yes — isolated read-only subagent `c5aacae6`, inputs limited to
the spec, the typed contract, and the current state of every changed file (no
implementation conversation; the subagent had no shell tool, so the diff was
reviewed as current-tree state plus the Step-3 change record).

## Verdict at review: 3 PASS / 2 PARTIAL / 0 FAIL
(+3 properties PASS, 2 compile-negatives PASS)

| Requirement | Verdict |
|---|---|
| R1 Installed instruction documents carry the current stamp | PARTIAL → remediated |
| R2 Cache/state directory migration runs on first use | PARTIAL → remediated |
| R3 Shipped documents and templates use the current name | PASS |
| R4 Acceptance suite asserts the schema's actual version | PASS |
| R5 Directory rename deferred with coupling recorded | PASS |
| P1 every-searched-root-is-classified | PASS |
| P2 migration-is-idempotent | PASS |
| P3 no-shipped-document-presents-the-previous-name-as-current | PASS |
| CN1 RenameDeferral without reason unconstructible | PASS |
| CN2 Unreadable not equatable with Absent (type-ascription re-encoding) | PASS |

## R1 findings — remediated

1. **Stale instruction documents survived in searched roots.** The user-authored
   `verified-scala3-escape-analysis` skill (no schema stamp, invisible to the
   stamp audit) presented "the verified-scala3 workflow" as current in
   `~/.agents/skills`, `~/.zcode/skills`, and `.pi/skills`. Remediated by human
   decision: renamed `probatio-escape-analysis` + prose updated in all three
   copies; only `openspec/schemas/verified-scala3` path references remain (the
   legitimately deferred directory name).

2. **The named bats oracle lacked the check.** `harness-install-verification.bats`
   is the proof-obligation artifact for "every installed document carries the
   current stamp" but contained no stamp assertion. Remediated: two tests added —
   no searched root carries a `verified-scala3-schema/` stamp, and every
   schema-stamped (`*-schema/*`) document reads `probatio-schema/14.0.0`.
   Verified green 12/12.

## R2 findings — remediated

The adapter could silently latch a wrong terminal state — the one shape the
spec's lossless/idempotent intent forbids:

(a) **Unreadable-but-present legacy dir migrated as "empty", permanently.**
    `topLevelFiles` swallowed a failed `Files.list` into `Nil` while
    `isDirectory` still reported `legacyExists`; the kernel prescribed empty
    contents and `current` was created EMPTY — every later run's `newExists`
    arm then masked the real contents forever. Fixed: the snapshot is now
    TOTAL at the adapter level (`Option[CacheState]` — a failed listing yields
    `None` → no migration decision), matching the spec's unreadable≠absent
    discipline. Test added: unreadable legacy → nothing created; readable
    again → migration completes (nothing latched).

(b) **Mid-copy failure latched a partial migration.** A copy exception left
    `current` existing with a subset of contents — indistinguishable from done.
    Fixed: materialisation stages into a `*.migrating` sibling and moves into
    place only when every copy landed; failure cleans the staging dir so the
    next run retries. `current` is never observed partially populated.
    Test added: unreadable source file → no `current`, no staging residue;
    retry migrates everything.

## Secondary observations (not violations)

- Flat-model fidelity: only top-level regular files migrate — faithful to the
  kernel's `List[String]` model; documented.
- Neither-present divergence is deliberate: the kernel returns `newExists=true`
  while the adapter writes nothing — correct (a later-appearing legacy dir can
  still migrate).
- `install-hooks.sh` recommended the deprecated `VERIFIED_SCALA3_HOOKS` alias as
  THE disable instruction — fixed to `PROBATIO_HOOKS=off` (alias still honoured).
- Pre-existing cli-entrypoint defect (out of scope): under the JAR-fallback
  launcher, `sun.java.command`'s basename is the JAR name → `UnknownSubcommand`
  before `runSubcommand` — the migration caller is unreachable via that path.
  Recorded; not a spec-10 surface.

## Not assessable in this context

- The change-level exit criterion "no acceptance file worse than the
  predecessor control via `probatioOracleDiff`" requires executing the
  differential harness — no shell tool available to the reviewer; it runs at
  commit time.
- The oracle-tampering sweep beyond spec-10 files: the spec-10 surface was
  reviewed clean (fixture changes align stale D8 fixtures to the current
  chain-state contract; assertions were not loosened). The definitive
  `git diff`-based sweep runs at commit time.
