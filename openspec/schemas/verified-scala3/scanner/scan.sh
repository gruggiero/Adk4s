#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  Concept Scanner — wrapper for concept-scanner.scala
#
#  Usage:
#    ./scan.sh                           # scan current directory, print markdown
#    ./scan.sh /path/to/project          # scan specific project
#    ./scan.sh /path/to/project --json   # JSON output
#    ./scan.sh . --output inventory.md   # write to file
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCANNER="$SCRIPT_DIR/concept-scanner.scala"

if ! command -v scala-cli &> /dev/null; then
  echo "ERROR: scala-cli not found. Install: https://scala-cli.virtuslab.org/" >&2
  echo "Falling back to grep-based scan..." >&2

  PROJECT="${1:-.}"
  echo "# Concept Inventory"
  echo ""
  echo "<!-- Grep-based scan (scala-cli not available) -->"
  echo ""
  echo "## Opaque Types"
  grep -rn "opaque type" "$PROJECT/src/main/scala/" 2>/dev/null || echo "  (none found)"
  echo ""
  echo "## Enums / Sealed Traits"
  grep -rn "enum \|sealed trait " "$PROJECT/src/main/scala/" 2>/dev/null || echo "  (none found)"
  echo ""
  echo "## Service Traits"
  grep -rn "trait.*\[F\[_\]" "$PROJECT/src/main/scala/" 2>/dev/null || echo "  (none found)"
  echo ""
  echo "## Generators"
  grep -rn "val gen\|Gen\[" "$PROJECT/src/test/scala/" 2>/dev/null || echo "  (none found)"
  exit 0
fi

scala-cli run "$SCANNER" -- "$@"
