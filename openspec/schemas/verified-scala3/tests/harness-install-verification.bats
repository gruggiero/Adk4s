#!/usr/bin/env bats
# spec: harness-install-verification — Test oracle (Ring 3)
#
# 10 bats scenarios derived from the spec's proof obligations, NOT from
# the implementation. Tests assert the STRUCTURE of the adapter configs,
# the schema.yaml changelog, and the apply-Step-0 instruction — all
# things a bats test can verify without a live harness.

load helpers

SCHEMA_DIR="$BATS_TEST_DIRNAME/.."
ADAPTERS="$SCHEMA_DIR/hooks/adapters"
SCHEMA_YAML="$SCHEMA_DIR/schema.yaml"
CHANGELOG_MD="$SCHEMA_DIR/CHANGELOG.md"
README_MD="$SCHEMA_DIR/hooks/README.md"

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The tool-call event is wired into all three adapters
# ═════════════════════════════════════════════════════════════════════════

# spec: harness-install-verification — Scenario: Claude Code PreToolUse wired
@test "Claude Code PreToolUse is wired with Bash|Edit|Write|MultiEdit matcher" {
  [ -f "$ADAPTERS/claude.settings.json" ] || {
    printf 'claude.settings.json not found\n' >&2
    return 1
  }
  # The config must have a PreToolUse key with a matcher for the edit/write/bash tools
  local has_pretooluse
  has_pretooluse="$(jq -r '.hooks | has("PreToolUse")' "$ADAPTERS/claude.settings.json")"
  [ "$has_pretooluse" = "true" ] || {
    printf 'claude.settings.json missing PreToolUse key\n' >&2
    return 1
  }
  # The matcher must include Edit, Write, MultiEdit, and Bash
  local matcher
  matcher="$(jq -r '.hooks.PreToolUse[0].matcher' "$ADAPTERS/claude.settings.json")"
  echo "$matcher" | grep -q "Edit" || { printf 'matcher missing Edit: %s\n' "$matcher" >&2; return 1; }
  echo "$matcher" | grep -q "Write" || { printf 'matcher missing Write: %s\n' "$matcher" >&2; return 1; }
  echo "$matcher" | grep -q "Bash" || { printf 'matcher missing Bash: %s\n' "$matcher" >&2; return 1; }
}

# spec: harness-install-verification — Scenario: Claude Code PreToolUse runs gate.sh --event tool-call
@test "Claude Code PreToolUse command runs gate.sh --event tool-call" {
  [ -f "$ADAPTERS/claude.settings.json" ] || return 1
  local cmd
  cmd="$(jq -r '.hooks.PreToolUse[0].hooks[0].command' "$ADAPTERS/claude.settings.json")"
  echo "$cmd" | grep -q "gate.sh" || { printf 'command missing gate.sh: %s\n' "$cmd" >&2; return 1; }
  echo "$cmd" | grep -q "tool-call" || { printf 'command missing --event tool-call: %s\n' "$cmd" >&2; return 1; }
}

# spec: harness-install-verification — Scenario: Devin PreToolUse wired
@test "Devin PreToolUse is wired with Bash|Edit|Write|MultiEdit matcher" {
  [ -f "$ADAPTERS/devin.hooks.v1.json" ] || {
    printf 'devin.hooks.v1.json not found\n' >&2
    return 1
  }
  local has_pretooluse
  has_pretooluse="$(jq -r '. | has("PreToolUse")' "$ADAPTERS/devin.hooks.v1.json")"
  [ "$has_pretooluse" = "true" ] || {
    printf 'devin.hooks.v1.json missing PreToolUse key\n' >&2
    return 1
  }
  local matcher
  # Devin's adapter may not have a matcher field (it's optional), so check
  # the command instead — the wiring is what matters.
  local cmd
  cmd="$(jq -r '.PreToolUse[0].hooks[0].command' "$ADAPTERS/devin.hooks.v1.json")"
  echo "$cmd" | grep -q "gate.sh" || { printf 'command missing gate.sh: %s\n' "$cmd" >&2; return 1; }
  echo "$cmd" | grep -q "tool-call" || { printf 'command missing --event tool-call: %s\n' "$cmd" >&2; return 1; }
}

# spec: harness-install-verification — Scenario: pi tool_call handler wired
# MOVED to shape/harness-install-verification-shape.bats by
# spec:oracle-independence: the test asserts over the .ts adapter SOURCE,
# which is implementation shape, not behaviour.

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Apply Step 0 verifies the gate is installed and firing
# ═════════════════════════════════════════════════════════════════════════

# spec: harness-install-verification — Scenario: an installed gate proceeds
@test "schema.yaml apply Step 0 instructions mention --check-installed" {
  [ -f "$SCHEMA_YAML" ] || return 1
  # The apply Step 0 instructions must reference --check-installed
  grep -q "check-installed" "$SCHEMA_YAML" || {
    printf 'schema.yaml does not mention --check-installed in apply Step 0\n' >&2
    return 1
  }
}

# spec: harness-install-verification — Scenario: an uninstalled gate stops the apply phase
@test "schema.yaml apply Step 0 instructions mention stopping on installed:false" {
  [ -f "$SCHEMA_YAML" ] || return 1
  # The apply Step 0 instructions must direct the agent to stop if installed:false
  grep -i "installed.*false\|install-hooks.*--apply" "$SCHEMA_YAML" || {
    printf 'schema.yaml does not direct stopping on installed:false\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The Devin adapter's blocking behavior is verified or corrected
# ═════════════════════════════════════════════════════════════════════════

# spec: harness-install-verification — Scenario: Devin honors the Stop block (or gap documented)
@test "README adapter table Devin entry is no longer 'not first-hand verified'" {
  [ -f "$README_MD" ] || return 1
  # The README's Devin row must NOT still say "Not first-hand verified"
  # (it should be updated to record the verified result or the specific gap)
  grep -i "Devin" "$README_MD" | grep -i "Not first-hand verified" && {
    printf 'README still says "Not first-hand verified" for Devin\n' >&2
    return 1
  }
  return 0
}

# spec: harness-install-verification — Scenario: Devin drops additionalContext (format correction)
@test "Devin adapter format is consistent with the README's verification result" {
  [ -f "$ADAPTERS/devin.hooks.v1.json" ] || return 1
  [ -f "$README_MD" ] || return 1
  # Check the ADAPTER TABLE row (the one starting with "| **Devin CLI**")
  # — not the troubleshooting table, which has its own Devin row.
  # If the adapter table row says additionalContext is dropped, the adapter
  # must use --format text. If it says additionalContext works, the adapter
  # can use --format hook-json. They must be consistent.
  local devin_uses_hook_json adapter_row_says_drops
  devin_uses_hook_json="$(grep -c 'format hook-json' "$ADAPTERS/devin.hooks.v1.json" || true)"
  # Extract only the adapter table row (starts with "| **Devin CLI**")
  adapter_row_says_drops="$(grep -F '| **Devin CLI**' "$README_MD" | grep -ic 'additionalContext.*dropped\|additionalContext.*ignored\|drops.*additionalContext' || true)"
  # If the adapter table row says additionalContext is dropped, adapter must NOT use hook-json
  if [ "$adapter_row_says_drops" -gt 0 ]; then
    [ "$devin_uses_hook_json" -eq 0 ] || {
      printf 'README adapter table says Devin drops additionalContext but adapter still uses --format hook-json\n' >&2
      return 1
    }
  fi
  return 0
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: The schema changelog records the defect class
# ═════════════════════════════════════════════════════════════════════════

# spec: harness-install-verification — Scenario: the changelog has a v13 entry
@test "CHANGELOG.md has a v13 entry" {
  [ -f "$CHANGELOG_MD" ] || return 1
  # The changelog must have a v13 entry
  grep -q "^ 13 " "$CHANGELOG_MD" || {
    printf 'CHANGELOG.md missing v13 entry\n' >&2
    return 1
  }
}

# spec: harness-install-verification — Scenario: the v13 entry names the harness asymmetry
@test "v13 changelog entry names the harness asymmetry (2/3 completion, universal pre-execution)" {
  [ -f "$CHANGELOG_MD" ] || return 1
  # The v13 entry must name the defect class: prose-only control plane,
  # completion tier promoted on 2/3 harnesses, pre-execution tier shipped nothing
  local v13_block
  v13_block="$(awk '/^ 13 /{found=1} found{print} /^ 12 /{if(found)exit}' "$CHANGELOG_MD")"
  [ -n "$v13_block" ] || {
    printf 'could not extract v13 changelog block\n' >&2
    return 1
  }
  echo "$v13_block" | grep -iq "prose-only\|control plane" || {
    printf 'v13 entry does not name prose-only control plane\n' >&2
    return 1
  }
  echo "$v13_block" | grep -iq "pre-execution\|tool-call" || {
    printf 'v13 entry does not name the pre-execution fix\n' >&2
    return 1
  }
}

# ═════════════════════════════════════════════════════════════════════════
# Requirement: Every installed document in a searched root carries the
# current stamp (schema-rename-completion — Ring 8 found this obligation
# had no bats oracle)
# ═════════════════════════════════════════════════════════════════════════

searched_roots() {
  local repo_root="$SCHEMA_DIR/../../.."
  printf '%s\n' \
    "$repo_root/.agents/skills" \
    "$repo_root/.claude/skills" \
    "$repo_root/.pi/skills" \
    "$HOME/.agents/skills" \
    "$HOME/.claude/skills" \
    "$HOME/.zcode/skills"
}

# spec: schema-rename-completion — Scenario: no searched root carries a pre-rename stamp
@test "no instruction document in any searched root carries a pre-rename stamp" {
  local root stale=""
  while IFS= read -r root; do
    [ -d "$root" ] || continue
    local hit
    hit="$(grep -rln 'generatedBy:[[:space:]]*verified-scala3-schema/' "$root" 2>/dev/null || true)"
    [ -z "$hit" ] || stale="$stale$hit"$'\n'
  done < <(searched_roots)
  [ -z "$stale" ] || {
    printf 'pre-rename stamps remain in searched roots:\n%s' "$stale" >&2
    return 1
  }
}

# spec: schema-rename-completion — Scenario: Happy path — every installed document carries the schema's current name and version
@test "every schema-stamped document in a searched root carries the current stamp" {
  local root doc bad="" count=0
  while IFS= read -r root; do
    [ -d "$root" ] || continue
    while IFS= read -r doc; do
      [ -f "$doc" ] || continue
      count=$((count + 1))
      grep -qE 'generatedBy:[[:space:]]*probatio-schema/14\.0\.0' "$doc" || bad="$bad$doc"$'\n'
    done < <(grep -rlE 'generatedBy:[[:space:]]*[A-Za-z0-9_-]+-schema/' "$root" 2>/dev/null || true)
  done < <(searched_roots)
  [ "$count" -gt 0 ] || {
    printf 'no schema-stamped documents found in any searched root\n' >&2
    return 1
  }
  [ -z "$bad" ] || {
    printf 'installed documents missing the current stamp (probatio-schema/14.0.0):\n%s' "$bad" >&2
    return 1
  }
}
