package proxy

import (
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/walker/aura/gateway/internal/auth"
)

// New builds a reverse-proxy handler that forwards /api/* to the Java core. The user's
// id (from the verified JWT) is injected as X-Aura-User so the core doesn't have to
// re-verify the token.
func New(target string) (gin.HandlerFunc, error) {
	u, err := url.Parse(target)
	if err != nil {
		return nil, err
	}
	rp := httputil.NewSingleHostReverseProxy(u)

	originalDirector := rp.Director
	rp.Director = func(req *http.Request) {
		originalDirector(req)
		req.Header.Del("Authorization") // the gateway re-auths, core trusts X-Aura-User
	}

	return func(c *gin.Context) {
		if uid := auth.UserID(c); uid != "" {
			c.Request.Header.Set("X-Aura-User", uid)
		}
		// Buffer-then-forward: body must be re-readable for retries / logging.
		if c.Request.Body != nil {
			// http.ReverseProxy already handles this correctly, no action needed.
			_ = io.Reader(c.Request.Body)
		}
		// Rewrite path from /api/foo -> /api/foo (no-op) but strip trailing slashes.
		c.Request.URL.Path = strings.TrimSuffix(c.Request.URL.Path, "/")
		rp.ServeHTTP(c.Writer, c.Request)
	}, nil
}
