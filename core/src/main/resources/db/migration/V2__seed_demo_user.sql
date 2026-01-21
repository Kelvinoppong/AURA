-- Seed a demo user so the app is usable on first boot.
-- Password is "aura-demo" -- bcrypt hash generated with BCrypt cost 10.
-- Produced by: BCrypt.hashpw("aura-demo", BCrypt.gensalt(10))

INSERT INTO users (email, password_hash, display_name, role)
VALUES (
    'demo@aura.ai',
    '$2a$10$WQ2W3.7vmLbWv7sjyI4Lb.ieF3t.vmYUC1nV3h8M4pE2hQmYwRhxC',
    'Demo Agent',
    'admin'
)
ON CONFLICT (email) DO NOTHING;
