# helpers.bash — shared setup and assertions for the workflow's bats suites.
#
# Loaded by every suite with `load helpers`. Keep it bash + the declared
# prerequisite set (bash, git, jq): a helper that needs more than the suites
# themselves would make the tests less portable than the code they check.

# ── locations ────────────────────────────────────────────────────────────
# Resolved from this file, not from the caller's cwd — bats runs suites from
# an unpredictable directory.
schema_dir() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
}

repo_root() {
  cd "$(schema_dir)" && git rev-parse --show-toplevel
}

# ── enumeration sources ──────────────────────────────────────────────────
# Discovered at test time rather than listed, so a newly added file is covered
# automatically. A property that hard-codes its domain stops covering the
# thing it was written for the moment someone adds a file.

# Every tracked markdown, shell, YAML and HTML file under the schema directory.
# WIDENED at Ring 8: the original globbed only *.md and *.sh, which excluded
# schema.yaml (the workflow definition itself) and docs/*.html — and the worst
# offender was in docs/09-tooling.html, stating the superseded rule AS A RULE.
schema_docs_and_scripts() {
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"
  (cd "$root" && git ls-files \
    "$rel/*.md" "$rel/**/*.md" \
    "$rel/*.sh" "$rel/**/*.sh" \
    "$rel/*.yaml" "$rel/**/*.yaml" "$rel/*.yml" "$rel/**/*.yml" \
    "$rel/**/*.html")
}

# ── superseded-rule detection ────────────────────────────────────────────
# Keyed on the SHAPE of the assertion, not on three literal spellings of one
# tool name. The first version matched only `no jq` / `without jq` /
# `never use jq` unbackticked — and the baseline text it was written to catch
# read "no `jq`" WITH backticks, so it never matched it. An implementation
# that left that paragraph verbatim passed the whole suite.
#
# EXEMPTION, within two lines of the hit, either:
#  - a supersession marker — "superseded", "was previously", "is replaced by",
#    "predates the v12", "before v12" — for text that QUOTES the old rule; or
#  - an explicit `deps-rule:allow <reason>` marker, for a statement that is
#    scoped to particular scripts rather than asserting a workflow-wide rule.
#    The matcher cannot tell those apart, so the distinction is declared, not
#    inferred. Same convention as `// danger-scan:allow`: a justified
#    occurrence carries its justification next to it, and is therefore a
#    deliberate act rather than a silent pass.
superseded_rule_hits() { # $1=absolute file path
  local pat exempt
  # shellcheck disable=SC2016  # single quotes are intentional: these are grep
  # PATTERNS containing backticks and metacharacters, not strings to expand.
  pat='no +`?jq`?|without +`?jq`?|never +use +`?jq`?|no +JSON +processor'
  # shellcheck disable=SC2016  # as above
  pat="$pat"'|dependency-free|bash +(and|\+) +`?git( +grep)?`? +only|only +bash +and +git'
  exempt='superseded|supersedes|was previously|is replaced by|replaced by a|predates the v12|before v12|deps-rule:allow'
  grep -niE "$pat" "$1" 2>/dev/null | while IFS= read -r hit; do
    n="${hit%%:*}"
    lo=$((n - 2))
    [ "$lo" -lt 1 ] && lo=1
    if ! sed -n "${lo},$((n + 2))p" "$1" | grep -qiE "$exempt"; then
      printf '%s:%s\n' "$1" "$n"
    fi
  done
}

# Every tracked shell script under the schema directory.
schema_scripts() {
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"
  (cd "$root" && git ls-files "$rel/**/*.sh" "$rel/*.sh")
}

# ── active change discovery ──────────────────────────────────────────────
# Reachability tests must not name a change: a suite hard-coded to the change
# that introduced it goes permanently red the moment that change is archived,
# in a suite CI runs on every pipeline. Discovered at test time instead.
first_active_change() {
  local root d
  root="$(repo_root)"
  for d in "$root"/openspec/changes/*/; do
    [ -d "$d" ] || continue
    case "$d" in */archive/*) continue ;; esac
    basename "$d"
    return 0
  done
  return 1
}

# ── prerequisite table parsing ───────────────────────────────────────────
# Selects table rows STRUCTURALLY — every data row between the header
# separator and the next non-table line. The first version listed the six tool
# names in the selector, so a seventh prerequisite selected zero rows and the
# property passed vacuously: the declared failure mode was unreachable.
# Emits one "tool<TAB>justification" line per row.
prereq_rows() { # $1=file
  awk -F'|' '
    /^\| *Prerequisite *\|/ { intable = 1; next }   # header
    intable && /^\|[- :]*\|/ { next }               # separator
    intable && !/^\|/ { intable = 0 }
    intable {
      tool = $2; why = $3
      gsub(/`/, "", tool)
      gsub(/^[ \t]+|[ \t]+$/, "", tool)
      gsub(/^[ \t]+|[ \t]+$/, "", why)
      if (tool != "") print tool "\t" why
    }
  ' "$1"
}

# ── living-document limitation records ───────────────────────────────────
# A record that supersedes an earlier one must carry BOTH a date and the
# mechanism that established it. Emits offending lines.
unsourced_limitation_records() { # $1=file
  grep -niE 're-established|superseding the earlier|last verified' "$1" 2>/dev/null |
    while IFS= read -r hit; do
      # Look at a small window: the marker line plus the two following, since
      # these records are prose-wrapped.
      n="${hit%%:*}"
      win="$(sed -n "${n},$((n + 2))p" "$1")"
      has_date=0
      has_mech=0
      case "$win" in *[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]*) has_date=1 ;; esac
      case "$win" in
        *spec:* | *scanner* | *shellcheck* | *bats* | *"scan.sh"* | *"registry-check"* | *"spec-lint"*) has_mech=1 ;;
      esac
      if [ "$has_date" -eq 0 ] || [ "$has_mech" -eq 0 ]; then
        printf '%s (date:%s mechanism:%s)\n' "${hit%%:*}" "$has_date" "$has_mech"
      fi
    done
}

# ── assertions ───────────────────────────────────────────────────────────
# Each prints WHY it failed and against WHAT. A bare `[ "$status" -eq 0 ]`
# reports "returned 1" and leaves the reader to guess.

assert_status() { # $1=expected $2=actual $3=context
  if [ "$2" -ne "$1" ]; then
    printf 'expected exit %s, got %s — %s\n' "$1" "$2" "${3:-}" >&2
    printf 'output was:\n%s\n' "${output:-<empty>}" >&2
    return 1
  fi
}

assert_contains() { # $1=haystack $2=needle $3=context
  case "$1" in
    *"$2"*) return 0 ;;
    *)
      printf 'expected to find: %s\n' "$2" >&2
      printf 'in: %s\n' "${3:-<subject>}" >&2
      return 1
      ;;
  esac
}

assert_not_contains() { # $1=haystack $2=needle $3=context
  case "$1" in
    *"$2"*)
      printf 'expected NOT to find: %s\n' "$2" >&2
      printf 'but it is present in: %s\n' "${3:-<subject>}" >&2
      return 1
      ;;
    *) return 0 ;;
  esac
}

# Fails with the offending file listed, not just a count — a property that
# reports "3 files failed" makes the reader re-run it by hand to learn which.
assert_empty_list() { # $1=list $2=message
  if [ -n "$(printf '%s' "$1" | tr -d '[:space:]')" ]; then
    printf '%s\n' "${2:-unexpected entries}" >&2
    printf '%s\n' "$1" >&2
    return 1
  fi
}
