// verified-scala3 adapter — pi.
//
// Install to .pi/extensions/verified-scala3-gate.ts (project) or
// ~/.pi/agent/extensions/ (global). Try it first with:
//   pi -e ./openspec/schemas/verified-scala3/hooks/adapters/pi/verified-scala3-gate.ts
//
// pi has no return-based injection on `session_start`, so the equivalent of a
// SessionStart hook is `before_agent_start` fired once per prompt.
//
// FINGERPRINTING MOVED OUT (schema v12, gate-payload spec): this file used to
// keep its own `lastFingerprint` in a closure variable, duplicating the
// suppression logic every adapter would otherwise need to reimplement. gate.sh
// now owns it — keyed by `--session`, so every harness gets the same
// behaviour, not only pi. One pi PROCESS is one session for this extension's
// lifetime, so `process.pid` is what identifies it to gate.sh.
//
// TIER A (spec:hook-tiers) — post-edit only. Verified directly against the
// installed @earendil-works/pi-coding-agent's docs/extensions.md (2026-08-10,
// package version resolved from node_modules, not assumed): `tool_result`
// fires after a tool executes and can append to its own result content —
// the natural post-edit hook, matching "the edit has already happened, so
// this tier cannot strand an agent". `event.input.path` is confirmed by the
// package's own protected-paths.ts example, which filters the identical
// "write"/"edit" tool-name pair this file uses.
//
// COMPLETION GATE — NOT WIRED for pi, and this is a verified absence, not an
// oversight: pi's extension API has no event that can block a turn from
// ending. `agent_end`/`agent_settled` are documented as notification-only
// (no return value affects continuation); the ONLY blocking-capable event in
// the whole API is `tool_call` (`{ block: true }`), scoped to a single tool
// call before it runs, not to turn completion. If a future pi version adds
// one (a `before_agent_settle`-shaped event), wire it here the same way
// Claude Code's Stop hook is wired — until then, claiming this tier exists
// for pi would be exactly the "claim outran evidence" defect this whole
// change targets.
//
// spec:harness-install-verification — the `tool_call` handler below is the
// universal pre-execution tier for pi. It shells out to
// `gate.sh --event tool-call` and maps a block decision to
// `{block:true,reason}`. This is the wiring that makes specs 1–2 (oracle
// ordering lock, human-grant lock) enforce on pi, not just on the 2/3
// harnesses that honor Stop. The handler filters on write/edit/bash tool
// names (the same set the Claude/Devin PreToolUse matcher covers).
//
// All logic lives in gate.sh. This file only decides WHEN to ask and WHERE to
// put the answer — the same division that makes the adapters interchangeable.

import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

const GATE = "openspec/schemas/verified-scala3/hooks/gate.sh";
const TIMEOUT_MS = 10_000;
const SESSION_ID = `pi-${process.pid}`;

function runGate(cwd: string, extraArgs: string[]): Promise<string> {
  const script = join(cwd, GATE);
  if (!existsSync(script)) return Promise.resolve("");
  return new Promise((resolve) => {
    execFile(
      "bash",
      [script, "--repo", cwd, "--session", SESSION_ID, ...extraArgs],
      { cwd, timeout: TIMEOUT_MS, encoding: "utf8" },
      // gate.sh always exits 0 for these two events; on any failure we take
      // the empty string and stay silent. A context hook must never be the
      // reason a turn fails. An UNCHANGED payload is also empty output —
      // gate.sh's own suppression, not a failure — so both cases are
      // handled identically here: no message is returned.
      (_err, stdout) => resolve((stdout ?? "").trim()),
    );
  });
}

// spec:harness-install-verification — run gate.sh --event tool-call and
// parse the JSON decision. Returns {block: boolean, reason: string} or
// undefined (allow) on any failure (fail-open: a context gate must never
// strand the agent).
function runToolCallGate(
  cwd: string,
  toolName: string,
  filePath: string,
): Promise<{ block: boolean; reason: string } | undefined> {
  const script = join(cwd, GATE);
  if (!existsSync(script)) return Promise.resolve(undefined);
  return new Promise((resolve) => {
    execFile(
      "bash",
      [
        script,
        "--repo", cwd,
        "--session", SESSION_ID,
        "--event", "tool-call",
        "--format", "json",
        "--tool", toolName,
        "--file", filePath,
      ],
      { cwd, timeout: TIMEOUT_MS, encoding: "utf8" },
      (_err, stdout) => {
        const output = (stdout ?? "").trim();
        if (!output) return resolve(undefined);
        try {
          const parsed = JSON.parse(output);
          if (parsed.decision === "block") {
            resolve({ block: true, reason: parsed.reason ?? "blocked by verified-scala3 gate" });
          } else {
            resolve(undefined);
          }
        } catch {
          resolve(undefined);
        }
      },
    );
  });
}

export default function (pi: ExtensionAPI) {
  pi.on("before_agent_start", async (_event, ctx: ExtensionContext) => {
    const text = await runGate(ctx.cwd, ["--event", "prompt-submit", "--format", "text"]);
    if (!text) return;

    return {
      message: {
        customType: "verified-scala3-context",
        content: text,
        display: false,
      },
    };
  });

  // spec:harness-install-verification — Pre-execution gate (universal tier).
  // Fires before write/edit/bash tools execute. If gate.sh returns a block
  // decision, the tool call is blocked with the reason. This is the wiring
  // that makes the oracle ordering lock and human-grant lock enforce on pi.
  pi.on("tool_call", async (event, ctx: ExtensionContext) => {
    const toolName: string = event.toolName;
    // Filter to the same tool set the Claude/Devin PreToolUse matcher covers
    if (toolName !== "write" && toolName !== "edit" && toolName !== "bash") return undefined;
    const path = (event.input as { path?: string; command?: string }).path;
    const command = (event.input as { command?: string }).command;
    // For write/edit, use the file path; for bash, use the command string
    const filePath = path ?? command ?? "";
    if (!filePath) return undefined;

    const result = await runToolCallGate(ctx.cwd, toolName, filePath);
    if (!result || !result.block) return undefined;

    // Block the tool call — pi's tool_call handler returns {block: true, reason}
    return { block: true, reason: result.reason };
  });

  pi.on("tool_result", async (event, ctx: ExtensionContext) => {
    if (event.toolName !== "write" && event.toolName !== "edit") return undefined;
    const path = (event.input as { path?: string }).path;
    if (!path) return undefined;

    const findings = await runGate(ctx.cwd, ["--event", "post-edit", "--format", "text", "--file", path]);
    if (!findings) return undefined;

    // Appended to the tool's OWN result content, not a separate injected
    // message: the LLM sees it as part of what the edit produced, and
    // nothing here can alter whether the edit itself succeeded — matching
    // "Post-edit correction never blocks the edit".
    return {
      content: [...event.content, { type: "text", text: `\n[verified-scala3 post-edit]\n${findings}` }],
    };
  });
}
