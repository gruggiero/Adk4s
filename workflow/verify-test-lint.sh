#!/usr/bin/env bash
# spec: hermetic-test-processes — planted-violation check for the test lint.
# spec: archive-safe-fixtures — same check for the literal change-path bans.
#
# Scalafix has no negative fixture of its own: this script plants a file
# violating EVERY banned shape into each workflow module's test sources,
# runs `Test / scalafix`, asserts the lint rejects each banned shape with
# file and rule id, then removes the plant. A lint that passes a planted
# violation — or a rule whose pattern never fires — proves nothing.
#
# Banned shapes proven here: `new ProcessBuilder`, `scala.sys.process` AND
# the Predef-alias `sys.process`, `os.proc`, `.environment` (parenless —
# Java nullary methods need no parens), `Runtime.getRuntime` (Runtime.exec
# inherits the invoking environment too), and repo-root-anchored literal
# `openspec/changes/<name>` paths in their segment-chain, resolve,
# interpolated, and cwd-anchored-constructor spellings.
#
# Usage: workflow/verify-test-lint.sh          (all three modules)
#        workflow/verify-test-lint.sh core     (one module: core|cli|plugin)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

declare -A PLANT_DIR=(
  [core]="workflow/core/src/test/scala/org/sinemenda/probatio/migration"
  [cli]="workflow/cli/src/test/scala/org/sinemenda/probatio/migration"
  [plugin]="workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin"
)
declare -A PROJECT=(
  [core]="probatio-core"
  [cli]="probatio-cli"
  [plugin]="sbt-probatio"
)
declare -A PKG=(
  [core]="org.sinemenda.probatio.migration"
  [cli]="org.sinemenda.probatio.migration"
  [plugin]="org.sinemenda.probatio.plugin"
)

modules="${1:-core cli plugin}"
status=0

for mod in $modules; do
  dir="${PLANT_DIR[$mod]}"
  planted="$dir/LintNegativePlanted.scala"
  # A planted violation must compile — scalafix runs after Test/compile —
  # but violate every raw-process ban the test conf carries. The plugin has
  # no os-lib dependency, so `os.proc` is planted as a string literal there:
  # the DisableSyntax check is text-level and must fire regardless.
  if [ "$mod" = "plugin" ]; then
    cat > "$planted" <<EOF
package ${PKG[$mod]}

object LintNegativePlanted {
  val pb: ProcessBuilder = new ProcessBuilder("echo", "x")
  val env: java.util.Map[String, String] = pb.environment
  val p: scala.sys.process.ProcessBuilder = sys.process.Process("echo y")
  val r: Process = Runtime.getRuntime().exec("echo w")
  val s: String = "os.proc(\\"x\\")"
  // spec: archive-safe-fixtures — the plugin has no os-lib; the segment
  // chain is planted as source text (the DisableSyntax check is
  // text-level), the resolve/interp/cwd shapes compile on java.nio.
  val repoRoot: java.nio.file.Path = java.nio.file.Paths.get("/tmp")
  val chain: String = """repoRoot / "openspec" / "changes" / "x" banned"""
  val res: java.nio.file.Path = repoRoot.resolve("openspec/changes/x")
  val interp: String = s"\$repoRoot/openspec/changes/x"
  val cwd: java.nio.file.Path = java.nio.file.Paths.get("openspec/changes/x")
  val cwdVar: java.nio.file.Path = java.nio.file.Paths.get("openspec", "changes", "x")
}
EOF
  else
    cat > "$planted" <<EOF
package ${PKG[$mod]}

object LintNegativePlanted {
  val pb: ProcessBuilder = new ProcessBuilder("echo", "x")
  val env: java.util.Map[String, String] = pb.environment
  val p: scala.sys.process.ProcessBuilder = sys.process.Process("echo y")
  val r: Process = Runtime.getRuntime().exec("echo w")
  val o: os.proc = os.proc("echo", "z")
  // spec: archive-safe-fixtures — a planted violation must compile
  // (scalafix runs after Test/compile). os.Path has no resolve method,
  // so the resolve spelling is planted as source text; the other three
  // shapes compile as written.
  val repoRoot: os.Path = os.pwd
  val chain: os.Path = repoRoot / "openspec" / "changes" / "x"
  val res: String = """repoRoot.resolve("openspec/changes/x") banned"""
  val interp: String = s"\$repoRoot/openspec/changes/x"
  val cwd: os.Path = os.Path("openspec/changes/x", os.pwd)
  val cwdVar: String = """Paths.get("openspec", "changes", "x") banned"""
}
EOF
  fi
  out="$(sbt --error "${PROJECT[$mod]}/Test/scalafix" 2>&1)" && rc=0 || rc=$?
  rm -f "$planted"
  if [ "$rc" -eq 0 ]; then
    echo "FAIL [$mod]: lint passed with a planted raw-process violation" >&2
    status=1
    continue
  fi
  for rule in NoRawProcessBuilder NoScalaSysProcess NoOsProc NoBuilderEnvMutation NoRuntimeExec \
              NoRepoRootChangePathChain NoRepoRootChangeResolve NoRepoRootChangeInterp \
              NoCwdChangePath NoCwdChangePathVariadic; do
    if printf '%s' "$out" | grep -q "LintNegativePlanted.scala:.*$rule"; then
      echo "ok [$mod]: $rule fired with file and line"
    else
      echo "FAIL [$mod]: $rule did not name LintNegativePlanted.scala" >&2
      status=1
    fi
  done
  if [ "$status" -ne 0 ]; then
    printf '%s\n' "$out" | tail -15 >&2
  fi
done

exit "$status"
