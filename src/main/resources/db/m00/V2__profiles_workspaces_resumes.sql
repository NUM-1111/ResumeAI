CREATE TABLE profile (
    id uuid PRIMARY KEY,
    display_name varchar(100) NOT NULL CHECK (length(trim(display_name)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE workspace (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES profile(id),
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    direction varchar(200) NOT NULL CHECK (length(trim(direction)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id, profile_id)
);
CREATE INDEX workspace_profile_page ON workspace(profile_id, id);
CREATE TABLE resume_revision (
    workspace_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(workspace_id, revision),
    FOREIGN KEY(workspace_id, profile_id) REFERENCES workspace(id, profile_id)
);
CREATE TABLE workspace_resume (
    workspace_id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    revision bigint NOT NULL,
    source_kind varchar(20) NOT NULL CHECK (source_kind = 'PASTE'),
    FOREIGN KEY(workspace_id, profile_id) REFERENCES workspace(id, profile_id),
    FOREIGN KEY(workspace_id, revision) REFERENCES resume_revision(workspace_id, revision)
);
CREATE TABLE operation (
    scope text NOT NULL,
    request_key uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    response_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(scope, request_key)
);