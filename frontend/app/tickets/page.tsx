"use client";

import { useEffect, useState } from "react";
import Shell from "@/components/Shell";
import { Api, Ticket } from "@/lib/api";
import { clsx } from "clsx";

const STATUSES = ["all", "open", "pending", "escalated", "resolved"] as const;

export default function TicketsPage() {
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [filter, setFilter] = useState<(typeof STATUSES)[number]>("all");
  const [loading, setLoading] = useState(true);

  async function load(f: (typeof STATUSES)[number]) {
    setLoading(true);
    try {
      const data = await Api.listTickets(f === "all" ? undefined : f);
      setTickets(data);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(filter);
  }, [filter]);

  async function updateStatus(t: Ticket, status: string) {
    const updated = await Api.updateTicketStatus(t.id, status);
    setTickets((ts) => ts.map((x) => (x.id === t.id ? updated : x)));
  }

  return (
    <Shell>
      <div className="flex h-full flex-col">
        <div className="flex items-center justify-between border-b border-aura-border px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold">Tickets</h1>
            <p className="text-xs text-aura-mute">
              Escalations created by the Escalation agent show up here.
            </p>
          </div>
          <div className="flex items-center gap-1">
            {STATUSES.map((s) => (
              <button
                key={s}
                onClick={() => setFilter(s)}
                className={clsx(
                  "rounded-md border px-2.5 py-1 text-xs",
                  filter === s
                    ? "border-aura-accent bg-aura-accent/10 text-aura-accent"
                    : "border-aura-border text-aura-mute hover:bg-aura-surface2",
                )}
              >
                {s}
              </button>
            ))}
          </div>
        </div>
        <div className="scroll-slim flex-1 overflow-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-aura-surface text-xs uppercase tracking-wider text-aura-mute">
              <tr>
                <th className="px-6 py-3 text-left">Subject</th>
                <th className="px-4 py-3 text-left">Category</th>
                <th className="px-4 py-3 text-left">Priority</th>
                <th className="px-4 py-3 text-left">Status</th>
                <th className="px-4 py-3 text-left">Created</th>
                <th className="px-4 py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody>
              {tickets.map((t) => (
                <tr
                  key={t.id}
                  className="border-t border-aura-border hover:bg-aura-surface/70"
                >
                  <td className="max-w-md truncate px-6 py-3" title={t.subject}>
                    {t.subject}
                  </td>
                  <td className="px-4 py-3 text-aura-mute">
                    {t.category ?? "—"}
                  </td>
                  <td className="px-4 py-3">
                    <PriorityPill p={t.priority} />
                  </td>
                  <td className="px-4 py-3">
                    <StatusPill s={t.status} />
                  </td>
                  <td className="px-4 py-3 text-xs text-aura-mute">
                    {new Date(t.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {t.status !== "resolved" && (
                      <button
                        onClick={() => updateStatus(t, "resolved")}
                        className="rounded border border-aura-border px-2 py-0.5 text-xs text-aura-mute hover:bg-aura-surface2 hover:text-aura-text"
                      >
                        Mark resolved
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {!loading && tickets.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-6 py-8 text-center text-aura-mute">
                    No tickets in this view.
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

function PriorityPill({ p }: { p: string }) {
  const map: Record<string, string> = {
    low: "bg-aura-surface2 text-aura-mute",
    normal: "bg-aura-accent/10 text-aura-accent",
    high: "bg-aura-warn/10 text-aura-warn",
    urgent: "bg-aura-err/10 text-aura-err",
  };
  return (
    <span className={clsx("rounded px-2 py-0.5 text-xs", map[p] ?? map.normal)}>
      {p}
    </span>
  );
}

function StatusPill({ s }: { s: string }) {
  const map: Record<string, string> = {
    open: "bg-aura-accent/10 text-aura-accent",
    pending: "bg-aura-warn/10 text-aura-warn",
    escalated: "bg-aura-err/10 text-aura-err",
    resolved: "bg-aura-accent2/10 text-aura-accent2",
  };
  return (
    <span className={clsx("rounded px-2 py-0.5 text-xs", map[s] ?? map.open)}>
      {s}
    </span>
  );
}
