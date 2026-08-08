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

# Every tracked markdown and shell file under the schema directory.
schema_docs_and_scripts() {
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"
  (cd "$root" && git ls-files "$rel/**/*.md" "$rel/*.md" "$rel/**/*.sh" "$rel/*.sh")
}

# Every tracked shell script under the schema directory.
schema_scripts() {
  local root schema rel
  root="$(repo_root)"
  schema="$(schema_dir)"
  rel="${schema#"$root"/}"
  (cd "$root" && git ls-files "$rel/**/*.sh" "$rel/*.sh")
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
