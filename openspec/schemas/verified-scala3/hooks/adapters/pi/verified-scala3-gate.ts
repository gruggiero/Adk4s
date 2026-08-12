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
