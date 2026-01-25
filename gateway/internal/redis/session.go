package redisx

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// SessionStore is a thin convenience wrapper around go-redis. It backs both user sessions
// (WebSocket connection ids -> user ids) and the token-bucket rate limiter.
type SessionStore struct {
	rdb *redis.Client
}

func NewSessionStore(addr string) *SessionStore {
	return &SessionStore{
		rdb: redis.NewClient(&redis.Options{Addr: addr}),
	}
}

func (s *SessionStore) Ping(ctx context.Context) error {
	return s.rdb.Ping(ctx).Err()
}

// RememberSession stores arbitrary session JSON with a TTL. Used to track active WS
// connections and user metadata.
func (s *SessionStore) RememberSession(ctx context.Context, id string, payload any, ttl time.Duration) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return s.rdb.Set(ctx, "aura:session:"+id, b, ttl).Err()
}

func (s *SessionStore) ForgetSession(ctx context.Context, id string) error {
	return s.rdb.Del(ctx, "aura:session:"+id).Err()
}

func (s *SessionStore) LoadSession(ctx context.Context, id string, dst any) (bool, error) {
	v, err := s.rdb.Get(ctx, "aura:session:"+id).Bytes()
	if err == redis.Nil {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal(v, dst)
}

// AllowRate is a simple fixed-window limiter. Returns (allowed, currentCount).
// Much cheaper than a proper token bucket and perfectly fine for a hackathon-grade demo.
func (s *SessionStore) AllowRate(ctx context.Context, key string, maxPerSec int) (bool, int64, error) {
	k := fmt.Sprintf("aura:rate:%s:%d", key, time.Now().Unix())
	n, err := s.rdb.Incr(ctx, k).Result()
	if err != nil {
		return true, 0, err // fail-open
	}
	if n == 1 {
		_ = s.rdb.Expire(ctx, k, 2*time.Second).Err()
	}
	return n <= int64(maxPerSec), n, nil
}

// IncrementConnectionCount tracks live WebSocket connections globally. Exposed at /health
// so the load test can assert the "200+ concurrent users" number at runtime.
func (s *SessionStore) IncrementConnectionCount(ctx context.Context) (int64, error) {
	return s.rdb.Incr(ctx, "aura:connections").Result()
}

func (s *SessionStore) DecrementConnectionCount(ctx context.Context) (int64, error) {
	return s.rdb.Decr(ctx, "aura:connections").Result()
}

func (s *SessionStore) ConnectionCount(ctx context.Context) (int64, error) {
	v, err := s.rdb.Get(ctx, "aura:connections").Int64()
	if err == redis.Nil {
		return 0, nil
	}
	return v, err
}

func (s *SessionStore) Close() error {
	return s.rdb.Close()
}
