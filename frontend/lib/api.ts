export const API_URL =
  process.env.NEXT_PUBLIC_API_URL || "http://localhost:8081";
export const WS_URL =
  process.env.NEXT_PUBLIC_WS_URL || "ws://localhost:8081";

const TOKEN_KEY = "aura.jwt";

export function saveToken(t: string) {
  if (typeof window !== "undefined") window.localStorage.setItem(TOKEN_KEY, t);
}
export function loadToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}
export function clearToken() {
  if (typeof window !== "undefined") window.localStorage.removeItem(TOKEN_KEY);
}

function authHeaders(): HeadersInit {
  const t = loadToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(init.headers || {}),
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status} ${res.statusText} :: ${body.slice(0, 200)}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

export async function login(email: string, password: string) {
  const r = await apiFetch<{
    token: string;
    email: string;
    displayName: string;
    role: string;
  }>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  saveToken(r.token);
  return r;
}

// ---------- types ----------
export type Conversation = {
  id: string;
  userId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};
export type Message = {
  id: string;
  conversationId: string;
  role: "user" | "assistant" | "system";
  content: string;
  createdAt: string;
};
export type Ticket = {
  id: string;
  subject: string;
  body: string;
  status: string;
  category: string | null;
  priority: string;
  customerEmail: string | null;
  createdAt: string;
  updatedAt: string;
};
export type TraceRow = {
  id: string;
  conversationId: string;
  messageId: string;
  agentName: string;
  model: string;
  promptTokens: number;
  outputTokens: number;
  latencyMs: number;
  status: string;
  payloadJson: string;
  createdAt: string;
};

// ---------- API wrappers ----------
export const Api = {
  createConversation: (title?: string) =>
    apiFetch<Conversation>("/api/chat/conversations", {
      method: "POST",
      body: JSON.stringify({ title }),
    }),
  listConversations: () =>
    apiFetch<Conversation[]>("/api/chat/conversations?limit=50"),
  messages: (id: string) =>
    apiFetch<Message[]>(`/api/chat/conversations/${id}/messages`),
  traces: (id: string) =>
    apiFetch<TraceRow[]>(`/api/chat/conversations/${id}/traces?limit=200`),

  listTickets: (status?: string) =>
    apiFetch<Ticket[]>(
      `/api/tickets?limit=100${status ? `&status=${status}` : ""}`,
    ),
  updateTicketStatus: (id: string, status: string) =>
    apiFetch<Ticket>(`/api/tickets/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    }),

  knowledgeStats: () =>
    apiFetch<{ chunks: number }>("/api/knowledge/stats"),

  telemetry: () =>
    apiFetch<{
      perModel: Record<
        string,
        {
          calls: number;
          promptTokens: number;
          outputTokens: number;
          avgLatencyMs: number;
          usd: number;
        }
      >;
    }>("/api/telemetry"),
};
