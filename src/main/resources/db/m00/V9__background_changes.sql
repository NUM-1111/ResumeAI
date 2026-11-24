ALTER TABLE background ADD CONSTRAINT background_id_profile UNIQUE(id,profile_id);
CREATE TABLE background_change (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 workspace_id uuid NOT NULL, background_id uuid NOT NULL REFERENCES background(id),
 expected_version bigint NOT NULL, old_text text NOT NULL, new_text text,
 kind varchar(10) NOT NULL CHECK(kind IN ('CORRECT','WITHDRAW')),
 status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPLIED','EXPIRED')),
 context_epoch bigint NOT NULL, source_request_id uuid UNIQUE REFERENCES ai_request(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id),
 FOREIGN KEY(background_id,profile_id) REFERENCES background(id,profile_id)
);
CREATE INDEX background_changes_profile ON background_change(profile_id,created_at) WHERE status='APPLIED';