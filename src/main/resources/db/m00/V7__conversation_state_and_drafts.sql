CREATE TABLE conversation_state (
 workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
 version bigint NOT NULL DEFAULT 0,
 epoch bigint NOT NULL DEFAULT 0,
 boundary_at timestamptz NOT NULL DEFAULT '-infinity'
);
CREATE TABLE conversation_draft (
 workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
 text text NOT NULL DEFAULT '', version bigint NOT NULL DEFAULT 0
);
ALTER TABLE ai_request ADD COLUMN visible boolean NOT NULL DEFAULT true;