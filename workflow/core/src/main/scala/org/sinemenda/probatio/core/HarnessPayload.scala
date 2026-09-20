package org.sinemenda.probatio.core

/**
 * The structured input a harness supplies on the gate's input channel
 * (spec 8). Exactly one read per invocation at the top level.
 *
 * The fields are the ones the gate's tiers consume: the tool name, the
 * tool's input (`file_path`/`path`/`command` inside it), the tool's
 * response, the harness's working directory (the repository-resolution
 * fallback), and `stop_hook_active` (the completion tier's fast path).
 * `interrupted` is derived from the response — the same fact stated
 * once, not a second channel that could disagree.
 *
 * Absent fields are the predecessor's jq `// empty` values: empty string,
 * `ujson.Null`, `false`. A payload the reader cannot classify still
 * parses — the fields land on the absent values and the gate classifies
 * the response to `Skip` rather than to a fabricated exit.
 *
 * spec: gate-event-completeness — Concepts Introduced (new): HarnessPayload
 * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
 */
final case class HarnessPayload private (
  toolName: String,
  toolInput: ujson.Value,
  toolResponse: ujson.Value,
  cwd: String,
  stopHookActive: ujson.Value,
  interrupted: Boolean
)

object HarnessPayload:

  /**
   * The only construction path — `interrupted` is derived from the
   * response's `.interrupted == true` test (the predecessor's jq
   * comparison: only the JSON literal `true` counts).
   */
  def of(
    toolName: String,
    toolInput: ujson.Value,
    toolResponse: ujson.Value,
    cwd: String,
    stopHookActive: ujson.Value
  ): HarnessPayload =
    val interrupted: Boolean = toolResponse match
      case obj: ujson.Obj =>
        obj.value.get("interrupted").contains(ujson.Bool(true))
      case _ => false // danger-scan:allow jq `== true` on a non-object is false — never interrupted
    HarnessPayload(toolName, toolInput, toolResponse, cwd, stopHookActive, interrupted)

end HarnessPayload
