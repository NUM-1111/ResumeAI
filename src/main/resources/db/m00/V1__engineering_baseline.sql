CREATE TABLE engineering_baseline (
    id integer PRIMARY KEY CHECK (id = 1),
    marker text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO engineering_baseline(id, marker) VALUES (1, 'M00：隔离工程基线');