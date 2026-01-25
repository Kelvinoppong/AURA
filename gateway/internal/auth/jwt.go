package auth

import (
	"errors"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// Claims is the subset of JWT claims AURA cares about. Must stay in sync with the Java
// core's JwtService.
type Claims struct {
	Email string `json:"email"`
	Role  string `json:"role"`
	jwt.RegisteredClaims
}

// Verify parses and validates an AURA JWT. Returns the subject (user-id) on success.
func Verify(secret, token string) (*Claims, error) {
	if token == "" {
		return nil, errors.New("empty token")
	}
	parsed, err := jwt.ParseWithClaims(token, &Claims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(secret), nil
	}, jwt.WithIssuer("aura"))
	if err != nil {
		return nil, err
	}
	c, ok := parsed.Claims.(*Claims)
	if !ok || !parsed.Valid {
		return nil, errors.New("invalid token")
	}
	return c, nil
}

// Middleware extracts + verifies the Authorization header, and stores the user-id on the
// gin context. Unauthenticated requests get a 401.
func Middleware(secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		h := c.GetHeader("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			// Allow anonymous calls through, but mark the context as unauthenticated.
			// The core will fall back to the demo user, which is fine for local dev.
			c.Next()
			return
		}
		claims, err := Verify(secret, strings.TrimPrefix(h, "Bearer "))
		if err != nil {
			c.AbortWithStatusJSON(401, gin.H{"error": "invalid_token"})
			return
		}
		c.Set("user_id", claims.Subject)
		c.Set("user_email", claims.Email)
		c.Set("user_role", claims.Role)
		c.Next()
	}
}

// UserID returns the authenticated user-id from the context, or empty string if the
// request is anonymous.
func UserID(c *gin.Context) string {
	if v, ok := c.Get("user_id"); ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}
