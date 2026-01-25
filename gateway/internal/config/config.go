package config

import (
	"os"
	"strconv"
	"strings"
)

// Config holds runtime settings for the AURA gateway. Values are sourced from environment
// variables so the same binary runs cleanly in docker-compose, EC2 and local dev.
type Config struct {
	Addr         string
	CoreURL      string
	RedisAddr    string
	JWTSecret    string
	CORSOrigins  []string
	RateLimitRPS int
}

func FromEnv() Config {
	return Config{
		Addr:         envOr("AURA_GATEWAY_ADDR", ":8081"),
		CoreURL:      envOr("AURA_CORE_URL", "http://localhost:8080"),
		RedisAddr:    envOr("AURA_REDIS_ADDR", "localhost:6379"),
		JWTSecret:    envOr("AURA_JWT_SECRET", "change-me-in-production-please-32chars"),
		CORSOrigins:  splitCSV(envOr("AURA_CORS_ORIGINS", "http://localhost:3000")),
		RateLimitRPS: atoiOr(envOr("AURA_RATE_LIMIT_RPS", "20"), 20),
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func atoiOr(s string, def int) int {
	n, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return n
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
