ALTER TABLE workspace_resume DROP CONSTRAINT workspace_resume_source_kind_check;
ALTER TABLE workspace_resume ADD CONSTRAINT workspace_resume_source_kind_check CHECK (source_kind IN ('PASTE','UPLOAD','COPY'));
CREATE TABLE resume_origin (
    workspace_id uuid PRIMARY KEY REFERENCES workspace(id),
    source_workspace_id uuid REFERENCES workspace(id),
    source_revision bigint,
    file_id uuid UNIQUE,
    original_name varchar(200),
    file_status varchar(20) CHECK (file_status IN ('PENDING','READY','MISSING')),
    file_hash varchar(64),
    byte_size bigint,
    CHECK ((file_id IS NULL) = (file_status IS NULL))
);