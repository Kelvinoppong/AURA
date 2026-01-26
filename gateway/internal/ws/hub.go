package ws

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"

	"github.com/walker/aura/gateway/internal/auth"
	redisx "github.com/walker/aura/gateway/internal/redis"
)

// ClientMessage is the JSON sent by the browser over WS.
// {"type":"chat","conversation_id":"...","message":"..."}
type ClientMessage struct {
	Type           string `json:"type"`
	ConversationID string `json:"conversation_id,omitempty"`
	Message        string `json:"message,omitempty"`
}

// ServerMessage is what the gateway emits back. Token frames stream the reply; trace/done
// frames demarcate the turn.
type ServerMessage struct {
	Type           string `json:"type"`
	Value          string `json:"value,omitempty"`
	ConversationID string `json:"conversation_id,omitempty"`
	Error          string `json:"error,omitempty"`
}

// Handler returns a WS upgrade handler that proxies each chat turn through the Java core's
// SSE stream and fans tokens back to the browser.
type Handler struct {
	CoreURL   string
	Sessions  *redisx.SessionStore
	RateRPS   int
	Logger    *zap.Logger
	Upgrader  websocket.Upgrader
	HTTPClient *http.Client
}

func NewHandler(coreURL string, sessions *redisx.SessionStore, rateRPS int, logger *zap.Logger) *Handler {
	return &Handler{
		CoreURL:  coreURL,
		Sessions: sessions,
		RateRPS:  rateRPS,
		Logger:   logger,
		Upgrader: websocket.Upgrader{
			ReadBufferSize:  2048,
			WriteBufferSize: 4096,
			CheckOrigin:     func(r *http.Request) bool { return true },
		},
		HTTPClient: &http.Client{Timeout: 90 * time.Second},
	}
}

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = 30 * time.Second
	maxMessageSize = 8192
)

func (h *Handler) ServeWS(c *gin.Context) {
	conn, err := h.Upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		h.Logger.Warn("ws upgrade failed", zap.Error(err))
		return
	}
	defer conn.Close()

	connID := uuid.NewString()
	ctx := context.Background()
	userID := auth.UserID(c)

	_ = h.Sessions.RememberSession(ctx, connID, map[string]string{"user": userID}, 2*time.Hour)
	count, _ := h.Sessions.IncrementConnectionCount(ctx)
	h.Logger.Info("ws connected", zap.String("conn", connID), zap.String("user", userID), zap.Int64("total", count))
	defer func() {
		_, _ = h.Sessions.DecrementConnectionCount(ctx)
		_ = h.Sessions.ForgetSession(ctx, connID)
	}()

	conn.SetReadLimit(maxMessageSize)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	// ping loop
	stop := make(chan struct{})
	go func() {
		ticker := time.NewTicker(pingPeriod)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			case <-stop:
				return
			}
		}
	}()
	defer close(stop)

	rateKey := "ws:" + userID
	if rateKey == "ws:" {
		rateKey = "ws:anon:" + connID
	}

	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				h.Logger.Warn("ws read error", zap.Error(err))
			}
			return
		}
		var msg ClientMessage
		if err := json.Unmarshal(raw, &msg); err != nil {
			_ = writeJSON(conn, ServerMessage{Type: "error", Error: "bad_json"})
			continue
		}
		if msg.Type != "chat" || strings.TrimSpace(msg.Message) == "" {
			_ = writeJSON(conn, ServerMessage{Type: "error", Error: "empty_message"})
			continue
		}

		allowed, _, _ := h.Sessions.AllowRate(ctx, rateKey, h.RateRPS)
		if !allowed {
			_ = writeJSON(conn, ServerMessage{Type: "error", Error: "rate_limited"})
			continue
		}

		if err := h.streamTurn(ctx, conn, userID, msg); err != nil {
			_ = writeJSON(conn, ServerMessage{Type: "error", Error: err.Error()})
		}
	}
}

// streamTurn opens an SSE connection to the Java core's /internal/chat/stream endpoint
// and forwards each token event back down the WS.
func (h *Handler) streamTurn(ctx context.Context, conn *websocket.Conn, userID string, msg ClientMessage) error {
	body, _ := json.Marshal(map[string]string{
		"conversationId": msg.ConversationID,
		"message":        msg.Message,
	})

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.CoreURL+"/internal/chat/stream", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "text/event-stream")
	req.Header.Set("Content-Type", "application/json")
	if userID != "" {
		req.Header.Set("X-Aura-User", userID)
	}

	resp, err := h.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("core_stream_failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("core_status_%d: %s", resp.StatusCode, string(b))
	}

	return forwardSSE(conn, resp.Body)
}

// forwardSSE reads an SSE stream (standard "event:" / "data:" lines, blank-line delimited)
// and converts each event into a WS frame for the browser.
func forwardSSE(conn *websocket.Conn, body io.Reader) error {
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	var event string
	var dataBuf bytes.Buffer

	flush := func() error {
		if dataBuf.Len() == 0 {
			event = ""
			return nil
		}
		defer func() {
			dataBuf.Reset()
			event = ""
		}()
		data := dataBuf.Bytes()
		switch event {
		case "token":
			var payload struct {
				Type  string `json:"type"`
				Value string `json:"value"`
			}
			if err := json.Unmarshal(data, &payload); err != nil {
				return nil
			}
			return writeJSON(conn, ServerMessage{Type: "token", Value: payload.Value})
		case "done":
			var payload struct {
				ConversationID string `json:"conversationId"`
			}
			_ = json.Unmarshal(data, &payload)
			return writeJSON(conn, ServerMessage{Type: "done", ConversationID: payload.ConversationID})
		default:
			return nil
		}
	}

	for scanner.Scan() {
		line := scanner.Text()
		switch {
		case line == "":
			if err := flush(); err != nil {
				return err
			}
		case strings.HasPrefix(line, ":"):
			// comment / keepalive
		case strings.HasPrefix(line, "event:"):
			event = strings.TrimSpace(line[len("event:"):])
		case strings.HasPrefix(line, "data:"):
			if dataBuf.Len() > 0 {
				dataBuf.WriteByte('\n')
			}
			dataBuf.WriteString(strings.TrimSpace(line[len("data:"):]))
		}
	}
	if err := flush(); err != nil {
		return err
	}
	return scanner.Err()
}

func writeJSON(conn *websocket.Conn, m ServerMessage) error {
	_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
	return conn.WriteJSON(m)
}
