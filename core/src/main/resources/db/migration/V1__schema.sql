-- AURA core schema
-- Flyway migration V1: base tables for auth, tickets, knowledge base, agent telemetry.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

------------------------------------------------------------
-- Auth
------------------------------------------------------------
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    display_name    TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'agent',   -- agent | admin
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

------------------------------------------------------------
-- Conversations + messages
------------------------------------------------------------
CREATE TABLE conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           TEXT NOT NULL DEFAULT 'New conversation',
    ticket_id       UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX conversations_user_idx ON conversations (user_id, updated_at DESC);

CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL,                   -- user | assistant | system
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX messages_conv_idx ON messages (conversation_id, created_at ASC);

------------------------------------------------------------
-- Tickets (Service Cloud-flavoured)
------------------------------------------------------------
CREATE TABLE tickets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject         TEXT NOT NULL,
    body            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'open',    -- open | pending | escalated | resolved
    category        TEXT,                            -- billing | tech | account | general
    priority        TEXT NOT NULL DEFAULT 'normal',  -- low | normal | high | urgent
    customer_email  TEXT,
    assignee_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX tickets_status_idx ON tickets (status, created_at DESC);
CREATE INDEX tickets_assignee_idx ON tickets (assignee_id);

------------------------------------------------------------
-- Knowledge base (RAG)
------------------------------------------------------------
CREATE TABLE knowledge_docs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           TEXT NOT NULL,
    source          TEXT,                            -- url | upload | seed
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 384-dim = all-MiniLM-L6-v2
CREATE TABLE knowledge_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id          UUID NOT NULL REFERENCES knowledge_docs(id) ON DELETE CASCADE,
    chunk_index     INT NOT NULL,
    content         TEXT NOT NULL,
    embedding       vector(384) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW ANN index (pgvector 0.5+). Cosine distance to match sentence-transformer norm.
CREATE INDEX knowledge_chunks_hnsw_idx
    ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX knowledge_chunks_doc_idx ON knowledge_chunks (doc_id, chunk_index);

------------------------------------------------------------
-- Agent traces (observability)
------------------------------------------------------------
CREATE TABLE agent_traces (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    message_id      UUID REFERENCES messages(id) ON DELETE CASCADE,
    agent_name      TEXT NOT NULL,                   -- router | retriever | drafter | qa | escalate
    model           TEXT,                            -- gemini-2.0-flash | gemini-2.0-pro | n/a
    prompt_tokens   INT NOT NULL DEFAULT 0,
    output_tokens   INT NOT NULL DEFAULT 0,
    latency_ms      INT NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'ok',      -- ok | error | skipped
    payload         JSONB,                           -- agent-specific structured output
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX agent_traces_conv_idx ON agent_traces (conversation_id, created_at ASC);
CREATE INDEX agent_traces_msg_idx  ON agent_traces (message_id);
