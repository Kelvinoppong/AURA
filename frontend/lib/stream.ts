import { WS_URL, loadToken } from "./api";

export type WsEvent =
  | { type: "token"; value: string }
  | { type: "done"; conversation_id?: string }
  | { type: "error"; error: string };

/**
 * Opens a WebSocket to the gateway and returns a ReadableStream of server events.
 * The UI consumes the stream with a for-await loop -- same shape as a fetch body.
 */
export function openChatStream(): {
  send: (msg: { message: string; conversation_id?: string }) => void;
  stream: ReadableStream<WsEvent>;
  close: () => void;
} {
  const token = loadToken();
  const url = token
    ? `${WS_URL}/ws/chat?token=${encodeURIComponent(token)}`
    : `${WS_URL}/ws/chat`;

  const ws = new WebSocket(url);
  // Some browsers forbid custom headers on WS; most AURA installs allow anon WS and
  // the demo user fallback kicks in. Production would prefer a cookie-based session.

  let controller!: ReadableStreamDefaultController<WsEvent>;
  const stream = new ReadableStream<WsEvent>({
    start(c) {
      controller = c;
    },
    cancel() {
      ws.close();
    },
  });

  ws.onmessage = (ev) => {
    try {
      const parsed = JSON.parse(ev.data) as WsEvent;
      controller.enqueue(parsed);
      if (parsed.type === "done" || parsed.type === "error") {
        controller.close();
      }
    } catch {
      /* ignore malformed frames */
    }
  };
  ws.onerror = () => {
    try {
      controller.enqueue({ type: "error", error: "websocket_error" });
      controller.close();
    } catch {
      /* already closed */
    }
  };
  ws.onclose = () => {
    try {
      controller.close();
    } catch {
      /* */
    }
  };

  return {
    send: (msg) => {
      const dispatch = () =>
        ws.send(
          JSON.stringify({
            type: "chat",
            message: msg.message,
            conversation_id: msg.conversation_id ?? "",
          }),
        );
      if (ws.readyState === WebSocket.OPEN) dispatch();
      else ws.addEventListener("open", dispatch, { once: true });
    },
    stream,
    close: () => ws.close(),
  };
}
