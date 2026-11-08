-- Environment-only schema, no profile/resume business data.
CREATE TABLE environment_checkpoint (
    id integer PRIMARY KEY CHECK (id = 1),
    marker text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO environment_checkpoint (id, marker) VALUES (1, '环境验证：中文持久化正常');