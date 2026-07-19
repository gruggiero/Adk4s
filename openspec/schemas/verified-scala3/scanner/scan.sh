#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  Concept Scanner — wrapper for concept-scanner.scala
#
#  Usage:
#    ./scan.sh                           # scan current directory, print markdown
#    ./scan.sh /path/to/project          # scan specific project
#    ./scan.sh /path/to/project --json   # JSON output
#    ./scan.sh . --output openspec/concept-inventory.md   # refresh the
#                                        # PROJECT-scoped living inventory
#    ./scan.sh . --output openspec/changes/<change>/inventory-snapshots/<spec>-before.md
#                                        # per-spec snapshot for the delta check
#
#  Source discovery is MULTI-MODULE: every `src/` root in the repo is
#  scanned (module/src/main/scala etc.), not just a top-level src/.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCANNER="$SCRIPT_DIR/concept-scanner.scala"

if ! command -v scala-cli &> /dev/null; then
  echo "ERROR: scala-cli not found. Install: https://scala-cli.virtuslab.org/" >&2
  echo "Falling back to grep-based scan..." >&2

  PROJECT="${1:-.}"
  main_files() {
    find "$PROJECT" -path '*/src/main/scala/*' -name '*.scala' \
      -not -path '*/target/*' -not -path '*/openspec/*' -not -path '*/.*/*' 2>/dev/null
  }
  test_files() {
    find "$PROJECT" -path '*/src/test/scala/*' -name '*.scala' \
      -not -path '*/target/*' -not -path '*/openspec/*' -not -path '*/.*/*' 2>/dev/null
  }
  echo "# Concept Inventory"
  echo ""
  echo "<!-- Grep-based scan (scala-cli not available); multi-module -->"
  echo ""
  echo "## Opaque Types"
  main_files | xargs -r grep -n "opaque type" || echo "  (none found)"
  echo ""
  echo "## Enums / Sealed Traits"
  main_files | xargs -r grep -n "enum \|sealed trait " || echo "  (none found)"
  echo ""
  echo "## Service Traits"
  main_files | xargs -r grep -n "trait.*\[F\[_\]" || echo "  (none found)"
  echo ""
  echo "## Generators"
  test_files | xargs -r grep -n "val gen\|Gen\[" || echo "  (none found)"
  exit 0
fi

scala-cli run "$SCANNER" -- "$@"
