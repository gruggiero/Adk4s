# gate-hookjson-contract.jq — the HOOK-JSON OUTPUT CONTRACT.
#
# spec: gate-payload (change: add-correctness-substratum), Step 1 typed
# contract. Validates gate.sh's `--format hook-json` output — the one output
# shape here that is genuinely structured JSON. (`--format text` and the
# heartbeat/--check-installed formats are covered by prose + bats directly;
# see the Step 1 gate presentation for why a jq contract fits this one shape
# and not the others.)
#
# Usage: gate.sh ... --format hook-json | jq -e -f gate-hookjson-contract.jq
#
# This contract governs NON-EMPTY hook-json output only. A no-op call
# (suppressed — unchanged facts — or a repository outside the workflow)
# legitimately produces EMPTY stdout in every format, hook-json included; it
# does NOT emit a JSON object with additionalContext omitted. Callers MUST
# check for that case with `[ -z "$output" ]` (or equivalent) BEFORE piping
# to this contract. `jq -e` is vacuously true on empty input — it never
# evaluates the logic below at all when stdin is empty — so piping the
# no-op case through this file would silently "pass" without checking
# anything. (Found at Ring 8: an earlier version of this comment claimed the
# no-op case produced "a valid hook-json envelope (an empty one)", which was
# neither what gate.sh does nor what this contract actually verifies.)

def fail($clause): error("gate-hookjson-contract: " + $clause);

if type != "object" then fail("must be a JSON object")
elif (.hookSpecificOutput | type) != "object" then
  fail("must have a hookSpecificOutput object")
elif (.hookSpecificOutput.hookEventName | type) != "string"
     or (.hookSpecificOutput.hookEventName | length) == 0 then
  fail("hookSpecificOutput.hookEventName must be a non-empty string")
elif ([.hookSpecificOutput.hookEventName] | map(. as $e |
      (["SessionStart","UserPromptSubmit"] | index($e)) == null) | any) then
  fail("hookEventName must be SessionStart or UserPromptSubmit, got: " +
    .hookSpecificOutput.hookEventName)
elif (.hookSpecificOutput | has("additionalContext") | not) then
  # Absence is legitimate WHEN THIS OBJECT WAS ACTUALLY EMITTED — e.g. a
  # future format variant that sends the envelope but omits the key. Today's
  # gate.sh does not exercise this branch (it emits nothing instead), but a
  # non-empty object missing the key is still a valid envelope, not a
  # malformed one.
  .
elif (.hookSpecificOutput.additionalContext | type) != "string" then
  fail("additionalContext, when present, must be a string")
else .
end
