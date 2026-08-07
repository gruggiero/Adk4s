// verified-scala3 Tier B adapter — pi.
//
// Install to .pi/extensions/verified-scala3-gate.ts (project) or
// ~/.pi/agent/extensions/ (global). Try it first with:
//   pi -e ./openspec/schemas/verified-scala3/hooks/adapters/pi/verified-scala3-gate.ts
//
// pi has no return-based injection on `session_start`, so the equivalent of a
// SessionStart hook is `before_agent_start` fired once. It runs on EVERY
// prompt, so the payload is fingerprinted and re-injected only when the facts
// actually change — which is a feature, not a workaround: if the agent creates
// openspec/concepts/ mid-session, the applicability of the altitude rule
// changes, and it should be re-announced rather than remembered.
//
// All logic lives in gate.sh. This file only decides WHEN to ask and WHERE to
// put the answer — the same division that makes the adapters interchangeable.

import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

const GATE = "openspec/schemas/verified-scala3/hooks/gate.sh";
const TIMEOUT_MS = 10_000;

function runGate(cwd: string): Promise<string> {
  const script = join(cwd, GATE);
  if (!existsSync(script)) return Promise.resolve("");
  return new Promise((resolve) => {
    execFile(
      "bash",
      [script, "--event", "session-start", "--format", "text", "--repo", cwd],
      { cwd, timeout: TIMEOUT_MS, encoding: "utf8" },
      // gate.sh always exits 0; on any failure we take the empty string and
      // stay silent. A context hook must never be the reason a turn fails.
      (_err, stdout) => resolve((stdout ?? "").trim()),
    );
  });
}

export default function (pi: ExtensionAPI) {
  let lastFingerprint: string | null = null;

  pi.on("before_agent_start", async (_event, ctx: ExtensionContext) => {
    const text = await runGate(ctx.cwd);
    if (!text) return;

    // cheap content fingerprint — re-inject only when the facts differ
    const fingerprint = `${text.length}:${text}`;
    if (fingerprint === lastFingerprint) return;
    lastFingerprint = fingerprint;

    return {
      message: {
        customType: "verified-scala3-context",
        content: text,
        display: false,
      },
    };
  });
}
