"use client";

import { TraceRow } from "@/lib/api";
import { clsx } from "clsx";

const AGENT_COLOURS: Record<string, string> = {
  router: "text-aura-accent",
  retriever: "text-aura-accent2",
  drafter: "text-aura-warn",
  qa: "text-aura-accent",
  escalate: "text-aura-err",
};

export default function AgentTracePanel({ traces }: { traces: TraceRow[] }) {
  if (!traces || traces.length === 0) {
    return (
      <div className="p-4 text-sm text-aura-mute">
        Send a message to see the per-turn agent trace.
      </div>
    );
  }

  // Group by messageId
  const grouped = new Map<string, TraceRow[]>();
  for (const t of traces) {
    const arr = grouped.get(t.messageId) ?? [];
    arr.push(t);
    grouped.set(t.messageId, arr);
  }
  const turns = [...grouped.entries()];

  return (
    <div className="scroll-slim flex-1 overflow-auto p-3">
      {turns.map(([msgId, rows]) => {
        const totalIn = rows.reduce((a, r) => a + r.promptTokens, 0);
        const totalOut = rows.reduce((a, r) => a + r.outputTokens, 0);
        const totalMs = rows.reduce((a, r) => a + r.latencyMs, 0);
        return (
          <div
            key={msgId}
            className="mb-3 rounded-md border border-aura-border bg-aura-surface p-3"
          >
            <div className="mb-2 flex items-center justify-between text-xs text-aura-mute">
              <span>turn {msgId.slice(0, 6)}</span>
              <span>
                {totalIn + totalOut} tok · {totalMs} ms
              </span>
            </div>
            <ol className="space-y-1.5">
              {rows.map((r) => (
                <li
                  key={r.id}
                  className="flex items-center justify-between gap-2 text-xs"
                >
                  <div className="flex items-center gap-2 truncate">
                    <span
                      className={clsx(
                        "font-mono uppercase tracking-wider",
                        AGENT_COLOURS[r.agentName] ?? "text-aura-text",
                      )}
                    >
                      {r.agentName}
                    </span>
                    <span className="truncate text-aura-mute">{r.model}</span>
                  </div>
                  <div className="flex shrink-0 items-center gap-3 text-aura-mute">
                    <span>
                      {r.promptTokens}/{r.outputTokens} tok
                    </span>
                    <span>{r.latencyMs} ms</span>
                    <span
                      className={clsx(
                        "rounded px-1.5 py-0.5 font-mono text-[10px]",
                        r.status === "ok"
                          ? "bg-aura-accent2/10 text-aura-accent2"
                          : r.status === "rejected"
                            ? "bg-aura-err/10 text-aura-err"
                            : "bg-aura-surface2 text-aura-mute",
                      )}
                    >
                      {r.status}
                    </span>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        );
      })}
    </div>
  );
}
