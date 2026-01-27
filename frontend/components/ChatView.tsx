"use client";

import { useEffect, useRef, useState } from "react";
import { Send, Sparkles, User2 } from "lucide-react";
import { Api, Conversation, Message, TraceRow, loadToken } from "@/lib/api";
import { openChatStream } from "@/lib/stream";
import AgentTracePanel from "./AgentTracePanel";
import { useRouter } from "next/navigation";

type PendingMsg = { role: "user" | "assistant"; content: string; pending: boolean };

export default function ChatView() {
  const router = useRouter();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<PendingMsg[]>([]);
  const [traces, setTraces] = useState<TraceRow[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [kbChunks, setKbChunks] = useState<number | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!loadToken()) router.push("/login");
  }, [router]);

  useEffect(() => {
    (async () => {
      try {
        const [cs, stats] = await Promise.all([
          Api.listConversations(),
          Api.knowledgeStats().catch(() => ({ chunks: 0 })),
        ]);
        setConversations(cs);
        setKbChunks(stats.chunks);
        if (cs.length) pickConversation(cs[0].id);
      } catch (e) {
        console.error(e);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length, messages[messages.length - 1]?.content]);

  async function pickConversation(id: string) {
    setActiveId(id);
    const [msgs, tr] = await Promise.all([Api.messages(id), Api.traces(id)]);
    setMessages(
      msgs.map((m) => ({
        role: m.role === "assistant" ? "assistant" : "user",
        content: m.content,
        pending: false,
      })),
    );
    setTraces(tr);
  }

  async function newConversation() {
    const c = await Api.createConversation();
    setConversations((cs) => [c, ...cs]);
    setActiveId(c.id);
    setMessages([]);
    setTraces([]);
  }

  async function send() {
    const text = input.trim();
    if (!text || streaming) return;
    setInput("");

    let convId = activeId;
    if (!convId) {
      const c = await Api.createConversation();
      setConversations((cs) => [c, ...cs]);
      convId = c.id;
      setActiveId(c.id);
    }

    const prefix: PendingMsg[] = [
      { role: "user", content: text, pending: false },
      { role: "assistant", content: "", pending: true },
    ];
    setMessages((m) => [...m, ...prefix]);
    setStreaming(true);

    const { send: wsSend, stream, close } = openChatStream();
    wsSend({ message: text, conversation_id: convId });

    try {
      const reader = stream.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value) continue;
        if (value.type === "token") {
          setMessages((m) => {
            const next = m.slice();
            const last = next[next.length - 1];
            next[next.length - 1] = {
              ...last,
              content: (last.content ?? "") + value.value,
              pending: true,
            };
            return next;
          });
        } else if (value.type === "done") {
          setMessages((m) => {
            const next = m.slice();
            const last = next[next.length - 1];
            next[next.length - 1] = { ...last, pending: false };
            return next;
          });
          // Re-pull traces to show the full per-turn breakdown.
          if (convId) Api.traces(convId).then(setTraces).catch(() => {});
        } else if (value.type === "error") {
          setMessages((m) => {
            const next = m.slice();
            const last = next[next.length - 1];
            next[next.length - 1] = {
              ...last,
              content: (last.content ?? "") + `\n\n[error: ${value.error}]`,
              pending: false,
            };
            return next;
          });
        }
      }
    } finally {
      setStreaming(false);
      close();
    }
  }

  return (
    <div className="flex h-full">
      {/* conversation rail */}
      <div className="flex w-64 shrink-0 flex-col border-r border-aura-border bg-aura-surface/50">
        <div className="flex items-center justify-between border-b border-aura-border px-4 py-3">
          <div className="text-xs uppercase tracking-wider text-aura-mute">
            Conversations
          </div>
          <button
            onClick={newConversation}
            className="rounded border border-aura-border px-2 py-0.5 text-xs text-aura-mute hover:bg-aura-surface2 hover:text-aura-text"
          >
            New
          </button>
        </div>
        <div className="scroll-slim flex-1 overflow-auto">
          {conversations.map((c) => (
            <button
              key={c.id}
              onClick={() => pickConversation(c.id)}
              className={`block w-full truncate px-4 py-2 text-left text-sm ${
                c.id === activeId
                  ? "bg-aura-surface2 text-aura-text"
                  : "text-aura-mute hover:bg-aura-surface2 hover:text-aura-text"
              }`}
            >
              {c.title || "New conversation"}
            </button>
          ))}
          {conversations.length === 0 && (
            <div className="p-4 text-xs text-aura-mute">
              No conversations yet. Start one.
            </div>
          )}
        </div>
        {kbChunks !== null && (
          <div className="border-t border-aura-border px-4 py-3 text-xs text-aura-mute">
            KB: {kbChunks.toLocaleString()} chunks indexed
          </div>
        )}
      </div>

      {/* chat transcript */}
      <div className="flex flex-1 flex-col">
        <div className="scroll-slim flex-1 overflow-auto">
          <div className="mx-auto flex max-w-3xl flex-col gap-4 px-6 py-8">
            {messages.length === 0 && (
              <div className="rounded-lg border border-aura-border bg-aura-surface p-6 text-center text-sm text-aura-mute">
                Ask AURA anything. The router will pick a model tier, retrieve
                context, draft a reply, self-QA, and escalate if needed.
              </div>
            )}
            {messages.map((m, i) => (
              <div
                key={i}
                className="flex items-start gap-3"
              >
                <div
                  className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-aura-border ${
                    m.role === "assistant"
                      ? "bg-aura-accent/10 text-aura-accent"
                      : "bg-aura-surface2 text-aura-mute"
                  }`}
                >
                  {m.role === "assistant" ? (
                    <Sparkles size={14} />
                  ) : (
                    <User2 size={14} />
                  )}
                </div>
                <div
                  className={`flex-1 whitespace-pre-wrap rounded-md px-4 py-3 text-sm leading-relaxed ${
                    m.role === "assistant"
                      ? "bg-aura-surface text-aura-text"
                      : "bg-aura-surface2 text-aura-text"
                  }`}
                >
                  {m.content}
                  {m.pending && (
                    <span className="ml-1 inline-block h-3 w-1.5 animate-pulseSoft bg-aura-accent align-middle" />
                  )}
                </div>
              </div>
            ))}
            <div ref={endRef} />
          </div>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
          className="border-t border-aura-border bg-aura-surface p-3"
        >
          <div className="mx-auto flex max-w-3xl items-end gap-2">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
              rows={1}
              placeholder="Ask a customer-support question..."
              className="scroll-slim max-h-40 flex-1 resize-none rounded-md border border-aura-border bg-aura-surface2 px-3 py-2 text-sm text-aura-text placeholder:text-aura-mute/70 focus:border-aura-accent focus:outline-none"
            />
            <button
              type="submit"
              disabled={streaming || !input.trim()}
              className="flex h-10 items-center gap-1.5 rounded-md bg-aura-accent px-4 text-sm font-medium text-aura-bg hover:bg-aura-accent/85 disabled:opacity-50"
            >
              <Send size={14} /> Send
            </button>
          </div>
        </form>
      </div>

      {/* trace panel */}
      <div className="flex w-80 shrink-0 flex-col border-l border-aura-border bg-aura-surface/40">
        <div className="border-b border-aura-border px-4 py-3 text-xs uppercase tracking-wider text-aura-mute">
          Agent trace
        </div>
        <AgentTracePanel traces={traces} />
      </div>
    </div>
  );
}
