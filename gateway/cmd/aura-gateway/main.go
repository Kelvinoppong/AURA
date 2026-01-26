package main

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/walker/aura/gateway/internal/auth"
	"github.com/walker/aura/gateway/internal/config"
	"github.com/walker/aura/gateway/internal/proxy"
	redisx "github.com/walker/aura/gateway/internal/redis"
	"github.com/walker/aura/gateway/internal/ws"
)

func main() {
	logger, _ := zap.NewProduction()
	defer logger.Sync()

	cfg := config.FromEnv()
	logger.Info("aura gateway starting",
		zap.String("addr", cfg.Addr),
		zap.String("core", cfg.CoreURL),
		zap.String("redis", cfg.RedisAddr))

	sessions := redisx.NewSessionStore(cfg.RedisAddr)
	defer sessions.Close()
	pingCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := sessions.Ping(pingCtx); err != nil {
		logger.Warn("redis unreachable at startup (will retry lazily)", zap.Error(err))
	}

	reverse, err := proxy.New(cfg.CoreURL)
	if err != nil {
		logger.Fatal("bad core URL", zap.Error(err))
	}

	wsHandler := ws.NewHandler(cfg.CoreURL, sessions, cfg.RateLimitRPS, logger)

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(requestLogger(logger))
	r.Use(cors.New(cors.Config{
		AllowOrigins:     cfg.CORSOrigins,
		AllowMethods:     []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Authorization", "Content-Type", "Accept"},
		ExposeHeaders:    []string{"Authorization"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))

	r.GET("/health", func(c *gin.Context) {
		ctx, ccl := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer ccl()
		n, _ := sessions.ConnectionCount(ctx)
		c.JSON(200, gin.H{
			"status":          "ok",
			"service":         "aura-gateway",
			"connections":     n,
			"core_url":        cfg.CoreURL,
			"rate_limit_rps":  cfg.RateLimitRPS,
			"time":            time.Now().UTC().Format(time.RFC3339),
		})
	})

	// JWT middleware is applied globally; /api/auth/login itself doesn't require a token.
	r.Use(auth.Middleware(cfg.JWTSecret))

	// Rate-limit every non-WS request.
	r.Use(rateLimit(sessions, cfg.RateLimitRPS))

	// Proxy /api/** to the Java core.
	r.Any("/api/*path", reverse)

	// WebSocket streaming endpoint.
	r.GET("/ws/chat", wsHandler.ServeWS)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           r,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logger.Fatal("server failed", zap.Error(err))
	}
}

func requestLogger(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		t0 := time.Now()
		c.Next()
		logger.Info("req",
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("took", time.Since(t0)))
	}
}

func rateLimit(s *redisx.SessionStore, rps int) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Don't rate-limit health or auth bootstrapping; ws has its own per-connection limit.
		p := c.Request.URL.Path
		if p == "/health" || p == "/api/auth/login" || p == "/ws/chat" {
			c.Next()
			return
		}
		key := auth.UserID(c)
		if key == "" {
			key = "ip:" + c.ClientIP()
		}
		allowed, _, _ := s.AllowRate(c.Request.Context(), "http:"+key, rps)
		if !allowed {
			c.AbortWithStatusJSON(429, gin.H{"error": "rate_limited"})
			return
		}
		c.Next()
	}
}
