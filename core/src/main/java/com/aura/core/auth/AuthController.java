package com.aura.core.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record LoginResponse(String token, String email, String displayName, String role) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        var user = users.findByEmail(req.email().toLowerCase()).orElse(null);
        if (user == null || !encoder.matches(req.password(), user.passwordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        }
        String token = jwt.issue(user.id(), user.email(), user.role());
        return ResponseEntity.ok(new LoginResponse(token, user.email(), user.displayName(), user.role()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        try {
            var claims = jwt.verify(auth.substring(7));
            return ResponseEntity.ok(Map.of(
                    "id", claims.getSubject(),
                    "email", claims.get("email"),
                    "role", claims.get("role")));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_token"));
        }
    }
}
