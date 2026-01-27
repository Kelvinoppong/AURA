"use client";

import { useEffect, useState } from "react";
import Shell from "@/components/Shell";
import { Api } from "@/lib/api";

type Row = {
  calls: number;
  promptTokens: number;
  outputTokens: number;
  avgLatencyMs: number;
  usd: number;
};

export default function TelemetryPage() {
  const [data, setData] = useState<Record<string, Row> | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const load = () =>
      Api.telemetry()
        .then((r) => setData(r.perModel))
        .catch((e: unknown) =>
          setErr(e instanceof Error ? e.message : "failed"),
        );
    load();
    const i = setInterval(load, 3000);
    return () => clearInterval(i);
  }, []);

  const rows = data ? Object.entries(data) : [];
  const totalCalls = rows.reduce((a, [, r]) => a + r.calls, 0);
  const totalCost = rows.reduce((a, [, r]) => a + r.usd, 0);

  return (
    <Shell>
      <div className="flex h-full flex-col">
        <div className="border-b border-aura-border px-6 py-4">
          <h1 className="text-lg font-semibold">LLM Router Telemetry</h1>
          <p className="text-xs text-aura-mute">
            Live per-model call counts, tokens, latency and estimated USD cost.
            Updates every 3s.
          </p>
        </div>
        <div className="mx-auto w-full max-w-4xl px-6 py-6">
          <div className="mb-6 grid grid-cols-3 gap-3">
            <Stat label="Total calls" value={totalCalls.toLocaleString()} />
            <Stat label="Estimated spend" value={`$${totalCost.toFixed(4)}`} />
            <Stat label="Models in rotation" value={String(rows.length)} />
          </div>
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wider text-aura-mute">
              <tr>
                <th className="py-2">Model</th>
                <th className="py-2 text-right">Calls</th>
                <th className="py-2 text-right">Tokens in</th>
                <th className="py-2 text-right">Tokens out</th>
                <th className="py-2 text-right">Avg latency</th>
                <th className="py-2 text-right">USD</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(([model, r]) => (
                <tr key={model} className="border-t border-aura-border">
                  <td className="py-2 font-mono text-xs">{model}</td>
                  <td className="py-2 text-right">{r.calls}</td>
                  <td className="py-2 text-right">
                    {r.promptTokens.toLocaleString()}
                  </td>
                  <td className="py-2 text-right">
                    {r.outputTokens.toLocaleString()}
                  </td>
                  <td className="py-2 text-right">{r.avgLatencyMs} ms</td>
                  <td className="py-2 text-right">${r.usd.toFixed(4)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="py-6 text-center text-aura-mute">
                    {err ?? "No telemetry yet — send a message on the Chat page."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </Shell>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-aura-border bg-aura-surface p-4">
      <div className="text-xs text-aura-mute">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
    </div>
  );
}
