#!/usr/bin/env bats
#
# Implementation-shape suite — tests moved out of the acceptance oracle by
# spec:oracle-independence (change: finish-probatio-replacement).
#
# These tests assert over a tool's SOURCE TEXT — how it is written, not what
# it does. That is exactly the kind of test a port must change, so they live
# here where following the implementation is expected, instead of forcing an
# oracle edit on every swap. Origin: ../workflow-hygiene.bats.

setup() {
  load ../helpers
  SCHEMA="$(schema_dir)"
}

@test "D7: spec-lint.sh drift message references scanner/install-skills.sh, not sync-skills.sh" {
  # The INSTRUCTION DRIFT remediation message must reference the script
  # that actually exists (scanner/install-skills.sh), not the dangling
  # reference (verified-scala3/sync-skills.sh). The message lives in the
  # ported implementation's source — the workflow/ Scala tree — not in
  # the spec-lint.sh shim, which is a bare exec forwarder carrying no text.
  local root
  root="$(repo_root)"
  run grep -rl 'sync-skills\.sh' "$root/workflow" --include='*.scala'
  [ "$status" -ne 0 ]
  run grep -rl 'install-skills\.sh' "$root/workflow" --include='*.scala'
  [ "$status" -eq 0 ]
}

@test "D7: every tool-name in scanner messages resolves to a tracked file" {
  # Extract tool-name references (paths containing .sh) from scanner
  # messages and verify each resolves to a tracked file.
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"

  # Find all .sh references in scanner scripts' echo/printf messages
  local refs
  refs="$(grep -rohE '[a-zA-Z0-9_/.-]+\.sh' "$SCHEMA/scanner/"*.sh | sort -u)"

  # Each referenced .sh must either be a tracked file or a well-known
  # external tool (jq, git, etc. don't end in .sh so won't match)
  local ref
  local dangling=""
  while IFS= read -r ref; do
    # Skip bare filenames that are standard tools (not paths)
    case "$ref" in
      */*) ;;  # path-like — check it
      *) continue ;;  # bare name — skip
    esac
    # Check if it resolves relative to schema dir or repo root
    if [ -f "$schema/$ref" ] || [ -f "$root/$ref" ]; then
      : # resolves
    else
      # Check if it's a tracked file (might be referenced as a relative path)
      if ! (cd "$root" && git ls-files --error-unmatch "$ref" >/dev/null 2>&1); then
        # Check relative to schema dir
        local basename
        basename="$(basename "$ref")"
        if ! (cd "$root" && git ls-files "$rel/**/$basename" | grep -q .); then
          dangling="$dangling $ref"
        fi
      fi
    fi
  done <<<"$refs"
  [ -z "$dangling" ] || { echo "dangling references:$dangling"; false; }
}

@test "D8: the ledger tool has no mutation operation in the ported subcommand dispatch" {
  # The property this guards — the ledger surface admits no mutation
  # operation (update|delete|rewrite|edit) — is asserted at the
  # implementation the seam now resolves to. The predecessor's shell
  # case dispatch is gone under the ported arm (the live ledger.sh is a
  # bare exec shim); the ported dispatch surface is the Subcommand
  # enum, whose mutation names are absent — unparseable, not merely
  # denylisted. The Scala contract pins the enum cases; this test pins
  # the source surface the bats suite can see.
  local root subcommand
  root="$(repo_root)"
  subcommand="$root/workflow/cli/src/main/scala/org/sinemenda/probatio/cli/Subcommand.scala"
  [ -f "$subcommand" ]
  # No mutation case in the enum (Update|Delete|Rewrite|Edit as words).
  run grep -cE '\bUpdate\b|\bDelete\b|\bRewrite\b|\bEdit\b' "$subcommand"
  [ "$output" -eq 0 ]
  # No mutation name is a parseable subcommand string.
  run grep -cE '"update"|"delete"|"rewrite"|"edit"' "$subcommand"
  [ "$output" -eq 0 ]
}

@test "D8: gate.sh extracts cwd from hook JSON using jq, not sed" {
  # The ported payload reader (HarnessPayloadReader.scala) extracts cwd
  # via structured JSON parsing — the jq equivalent — not regex/sed over
  # the payload text. The gate.sh shim is a bare exec forwarder carrying no
  # parsing logic.
  local root reader
  root="$(repo_root)"
  reader="$root/workflow/cli/src/main/scala/org/sinemenda/probatio/cli/HarnessPayloadReader.scala"
  [ -f "$reader" ]
  run grep -c 'ujson' "$reader"
  [ "$status" -eq 0 ]
  run grep -n '"cwd"' "$reader"
  [ "$status" -eq 0 ]
  # Pattern-matching substitution over the payload text is the defect
  # this test guards — a regex/sed extraction must fail the assertion.
  run grep -cE 'findFirstMatchIn|findAllMatchIn|util\.matching\.Regex|sed -[ne]' "$reader"
  [ "$output" -eq 0 ]
}
