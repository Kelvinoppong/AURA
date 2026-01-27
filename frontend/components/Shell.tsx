"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { MessageSquare, Ticket, BarChart3, LogOut, Sparkles } from "lucide-react";
import { clearToken } from "@/lib/api";
import { useRouter } from "next/navigation";

const nav = [
  { href: "/", label: "Chat", icon: MessageSquare },
  { href: "/tickets", label: "Tickets", icon: Ticket },
  { href: "/telemetry", label: "Telemetry", icon: BarChart3 },
];

export default function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  return (
    <div className="flex h-screen w-screen">
      <aside className="flex w-60 shrink-0 flex-col border-r border-aura-border bg-aura-surface">
        <div className="flex items-center gap-2 px-5 py-5">
          <Sparkles size={20} className="text-aura-accent2" />
          <div className="text-lg font-semibold tracking-tight">AURA</div>
        </div>
        <nav className="flex flex-1 flex-col gap-1 px-2">
          {nav.map(({ href, label, icon: Icon }) => {
            const active = pathname === href;
            return (
              <Link
                key={href}
                href={href}
                className={`flex items-center gap-3 rounded-md px-3 py-2 text-sm transition ${
                  active
                    ? "bg-aura-surface2 text-aura-text"
                    : "text-aura-mute hover:bg-aura-surface2 hover:text-aura-text"
                }`}
              >
                <Icon size={16} />
                {label}
              </Link>
            );
          })}
        </nav>
        <button
          onClick={() => {
            clearToken();
            router.push("/login");
          }}
          className="m-3 flex items-center gap-2 rounded-md border border-aura-border px-3 py-2 text-sm text-aura-mute hover:bg-aura-surface2 hover:text-aura-text"
        >
          <LogOut size={14} />
          Sign out
        </button>
      </aside>
      <main className="flex flex-1 flex-col overflow-hidden">{children}</main>
    </div>
  );
}
