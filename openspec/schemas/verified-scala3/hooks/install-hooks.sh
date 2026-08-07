#!/usr/bin/env bash
# install-hooks.sh — wire hooks/gate.sh into a project's agent harnesses.
#
# DELIBERATELY SEPARATE FROM install-skills.sh. A skill is inert text that an
# agent may read; a hook is a shell command the harness EXECUTES, on every
# session, with your permissions. Installing one is a different decision from
# installing the other, so it is a different command and it defaults to
# --dry-run. Read what it will write before you let it write.
#
# Tier B only: the installed hook injects context and never blocks.
#
# Usage:
#   install-hooks.sh [--agent claude|pi|devin|all] [--apply] [--project PATH]
#     --agent    which harness to wire (default: all that are already present)
#     --apply    actually write; without it, prints the plan and changes nothing
#     --project  project root (default: git toplevel, else cwd)
#
# Uninstall: delete .pi/extensions/verified-scala3-gate.ts, delete
# .devin/hooks.v1.json, remove the SessionStart entry from .claude/settings.json.
# Or leave them and set VERIFIED_SCALA3_HOOKS=off, which gate.sh honours.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
ADAPTERS="$SELF_DIR/adapters"
AGENT=""
APPLY=0
PROJECT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --agent)   AGENT="${2:-}"; shift 2 ;;
    --apply)   APPLY=1; shift ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *)         echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -z "$PROJECT" ] && PROJECT="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
[ -d "$PROJECT/openspec" ] || { echo "install-hooks: $PROJECT has no openspec/ — nothing to wire" >&2; exit 2; }

# Default to harnesses this project already uses: installing config for an
# agent nobody runs here is clutter that later reads as intent.
if [ -z "$AGENT" ] || [ "$AGENT" = "all" ]; then
  AGENT=""
  [ -d "$PROJECT/.claude" ] && AGENT="$AGENT claude"
  [ -d "$PROJECT/.pi" ]     && AGENT="$AGENT pi"
  [ -d "$PROJECT/.devin" ]  && AGENT="$AGENT devin"
  [ -z "$AGENT" ] && { echo "install-hooks: no .claude/, .pi/ or .devin/ in $PROJECT — pass --agent explicitly"; exit 0; }
fi

say() { if [ "$APPLY" -eq 1 ]; then echo "  WROTE  $1"; else echo "  would write  $1"; fi; }

echo "install-hooks: project $PROJECT"
echo "install-hooks: agents  ${AGENT# }"
[ "$APPLY" -eq 0 ] && echo "install-hooks: DRY RUN — re-run with --apply to write"

for a in $AGENT; do
  case "$a" in
    pi)
      dst="$PROJECT/.pi/extensions/verified-scala3-gate.ts"
      say "$dst"
      if [ "$APPLY" -eq 1 ]; then
        mkdir -p "$(dirname "$dst")"
        cp "$ADAPTERS/pi/verified-scala3-gate.ts" "$dst"
      fi
      ;;
    devin)
      dst="$PROJECT/.devin/hooks.v1.json"
      if [ -f "$dst" ]; then
        echo "  SKIP   $dst already exists — merge the SessionStart entry from"
        echo "         $ADAPTERS/devin.hooks.v1.json by hand (refusing to clobber)"
      else
        say "$dst"
        if [ "$APPLY" -eq 1 ]; then
          mkdir -p "$(dirname "$dst")"
          cp "$ADAPTERS/devin.hooks.v1.json" "$dst"
        fi
      fi
      ;;
    claude)
      dst="$PROJECT/.claude/settings.json"
      # not say(): whether anything is written depends on the merge below, and
      # a "WROTE" line printed before that decision would be a small lie
      if [ "$APPLY" -eq 1 ]; then echo "  MERGE  $dst"; else echo "  would merge   $dst (SessionStart hook)"; fi
      if [ "$APPLY" -eq 1 ]; then
        mkdir -p "$(dirname "$dst")"
        if command -v python3 >/dev/null 2>&1; then
          python3 - "$dst" "$ADAPTERS/claude.settings.json" <<'PY'
import json, os, sys
dst, frag_path = sys.argv[1], sys.argv[2]
cur = {}
if os.path.exists(dst):
    with open(dst) as f:
        try: cur = json.load(f)
        except json.JSONDecodeError:
            sys.exit(f"install-hooks: {dst} is not valid JSON — fix it or merge by hand")
frag = json.load(open(frag_path))
hooks = cur.setdefault("hooks", {})
entries = hooks.setdefault("SessionStart", [])
new = frag["hooks"]["SessionStart"][0]
cmd = new["hooks"][0]["command"]
# idempotent: never append a second copy of the same command
already = any(
    h.get("command") == cmd
    for e in entries
    for h in e.get("hooks", [])
)
if already:
    print("  (SessionStart hook already present — left unchanged)")
else:
    entries.append(new)
    with open(dst, "w") as f:
        json.dump(cur, f, indent=2)
        f.write("\n")
PY
        else
          echo "  python3 not found — add this to $dst by hand:"
          sed 's/^/    /' "$ADAPTERS/claude.settings.json"
        fi
      fi
      ;;
    *) echo "  unknown agent: $a" >&2 ;;
  esac
done

echo
echo "install-hooks: verify with"
echo "  bash $SELF_DIR/gate.sh --event session-start --format text --repo $PROJECT"
echo "install-hooks: disable at any time with  export VERIFIED_SCALA3_HOOKS=off"
