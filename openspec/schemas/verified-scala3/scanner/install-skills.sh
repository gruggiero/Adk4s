#!/usr/bin/env bash
# install-skills.sh — copy the verified-scala3 schema's skill sources into a
# project's agent skill directories, so each coding agent picks them up:
#   .claude/skills/  (Claude Code)
#   .pi/skills/      (pi)
#   .devin/skills/   (Devin)
#
# The schema's skills/ directory is the versioned source of truth; the agent
# directories are frequently git-ignored (repo-level or user-global), which is
# exactly how skill updates get lost. Run this after adopting the schema and
# after every schema upgrade.
#
# Usage: install-skills.sh [project-root]   (default: current directory)
#        install-skills.sh --check-installed
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_SRC="$SCRIPT_DIR/../skills"

# D5 FIX: --check-installed verifies the declared prerequisite set
if [ "${1:-}" = "--check-installed" ]; then
  missing=0
  for prereq in bash git jq python3 shellcheck bats shfmt; do
    if command -v "$prereq" >/dev/null 2>&1; then
      printf 'install-skills: %s — present\n' "$prereq"
    else
      printf 'install-skills: %s — MISSING\n' "$prereq" >&2
      missing=$((missing + 1))
    fi
  done
  if [ "$missing" -gt 0 ]; then
    printf 'install-skills: %d prerequisite(s) missing\n' "$missing" >&2
    exit 1
  fi
  printf 'install-skills: all prerequisites present\n'
  exit 0
fi

ROOT="${1:-.}"
AGENT_DIRS=(".claude/skills" ".pi/skills" ".devin/skills")

if [ ! -d "$SKILLS_SRC" ]; then
  echo "install-skills: no skills directory at $SKILLS_SRC" >&2
  exit 1
fi

installed=0
for agent_dir in "${AGENT_DIRS[@]}"; do
  target="$ROOT/$agent_dir"
  mkdir -p "$target"
  for skill_dir in "$SKILLS_SRC"/*/; do
    name="$(basename "$skill_dir")"
    mkdir -p "$target/$name"
    cp "$skill_dir"SKILL.md "$target/$name/SKILL.md"
    echo "installed $name -> $target/$name/SKILL.md"
    installed=$((installed + 1))
  done
done

echo "install-skills: $installed skill copy(ies) installed."
echo "NOTE: if an agent directory (.claude/, .pi/, .devin/) is git-ignored in"
echo "this repo (check 'git check-ignore <dir>/skills' and your user-global"
echo "ignore file), the installed copies are local-only by design — the"
echo "schema's skills/ directory remains the shared source of truth."
