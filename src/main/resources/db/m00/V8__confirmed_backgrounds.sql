CREATE TABLE background_candidate (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 workspace_id uuid NOT NULL, request_id uuid NOT NULL UNIQUE REFERENCES ai_request(id),
 version bigint NOT NULL DEFAULT 1, context_epoch bigint NOT NULL,
 items_json text NOT NULL, display_text text NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CONFIRMED','EXPIRED')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id), UNIQUE(id,profile_id)
);
CREATE TABLE background_confirmation (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 candidate_id uuid NOT NULL UNIQUE, candidate_version bigint NOT NULL,
 user_text text NOT NULL, selected_json text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(candidate_id,profile_id) REFERENCES background_candidate(id,profile_id)
);
CREATE TABLE background (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 confirmation_id uuid NOT NULL REFERENCES background_confirmation(id),
 item_number integer NOT NULL CHECK(item_number>0), text text NOT NULL,
 version bigint NOT NULL DEFAULT 1, active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(confirmation_id,item_number)
);
CREATE INDEX background_profile ON background(profile_id,created_at,id) WHERE active;