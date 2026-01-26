"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/api";
import { Sparkles } from "lucide-react";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("demo@aura.ai");
  const [password, setPassword] = useState("aura-demo");
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setLoading(true);
    try {
      await login(email, password);
      router.push("/");
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "login failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-aura-bg">
      <div className="w-full max-w-sm rounded-lg border border-aura-border bg-aura-surface p-6 shadow-xl">
        <div className="mb-5 flex items-center gap-2">
          <Sparkles size={18} className="text-aura-accent2" />
          <div className="text-lg font-semibold">AURA</div>
        </div>
        <p className="mb-5 text-xs text-aura-mute">
          Autonomous User Response Agent. Sign in with the seeded demo
          credentials (pre-filled).
        </p>
        <form onSubmit={submit} className="flex flex-col gap-3">
          <label className="text-xs text-aura-mute">
            Email
            <input
              className="mt-1 w-full rounded-md border border-aura-border bg-aura-surface2 px-3 py-2 text-sm text-aura-text focus:border-aura-accent focus:outline-none"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
            />
          </label>
          <label className="text-xs text-aura-mute">
            Password
            <input
              type="password"
              className="mt-1 w-full rounded-md border border-aura-border bg-aura-surface2 px-3 py-2 text-sm text-aura-text focus:border-aura-accent focus:outline-none"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          {err && <div className="text-xs text-aura-err">{err}</div>}
          <button
            type="submit"
            disabled={loading}
            className="mt-1 rounded-md bg-aura-accent px-4 py-2 text-sm font-medium text-aura-bg hover:bg-aura-accent/85 disabled:opacity-60"
          >
            {loading ? "Signing in..." : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
