CREATE TABLE profile (
 id uuid PRIMARY KEY, display_name varchar(100) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE workspace (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id), name varchar(100) NOT NULL,
 direction varchar(200) NOT NULL, row_version integer NOT NULL DEFAULT 1, target_version integer NOT NULL DEFAULT 1,
 UNIQUE(id, profile_id)
);
CREATE TABLE material_import (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id), name varchar(200) NOT NULL,
 original_hash varchar(64) NOT NULL, storage_key uuid NOT NULL, extracted_text text NOT NULL,
 confirmed_text text, check_state varchar(20) NOT NULL DEFAULT 'UNCHECKED', row_version integer NOT NULL DEFAULT 1,
 UNIQUE(id, profile_id), CHECK(check_state IN ('UNCHECKED','CONFIRMED')),
 CHECK ((check_state='CONFIRMED') = (confirmed_text IS NOT NULL))
);
CREATE INDEX material_profile ON material_import(profile_id,id);
CREATE TABLE resume_revision (
 workspace_id uuid NOT NULL, profile_id uuid NOT NULL, revision integer NOT NULL CHECK(revision>0),
 text text NOT NULL, reason varchar(30) NOT NULL, source_kind varchar(30) NOT NULL,
 source_id uuid NOT NULL, source_revision integer NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(workspace_id,profile_id,revision), FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id)
);
CREATE TABLE current_resume (
 workspace_id uuid PRIMARY KEY, profile_id uuid NOT NULL, revision integer NOT NULL,
 client_edit_sequence bigint NOT NULL DEFAULT 0,
 FOREIGN KEY(workspace_id,profile_id,revision) REFERENCES resume_revision(workspace_id,profile_id,revision)
);
CREATE TABLE resume_source_binding (
 workspace_id uuid NOT NULL, profile_id uuid NOT NULL, material_id uuid NOT NULL,
 PRIMARY KEY(workspace_id,material_id), FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id),
 FOREIGN KEY(material_id,profile_id) REFERENCES material_import(id,profile_id)
);
CREATE TABLE operation (
 scope text NOT NULL, request_key uuid NOT NULL, payload_hash varchar(64) NOT NULL,
 response_json text NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(scope,request_key)
);
CREATE INDEX workspace_profile ON workspace(profile_id,id);