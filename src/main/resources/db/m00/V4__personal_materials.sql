CREATE TABLE material (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 name varchar(200) NOT NULL CHECK(length(trim(name))>0), text text NOT NULL,
 row_version bigint NOT NULL DEFAULT 1, text_version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 content_updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 original_name varchar(200), file_id uuid UNIQUE, file_status varchar(20),
 limitation text NOT NULL DEFAULT '', original_hash varchar(64) NOT NULL,
 UNIQUE(id,profile_id), CHECK(file_status IS NULL OR file_status IN ('PENDING','READY'))
);
CREATE INDEX material_order ON material(profile_id,content_updated_at DESC,id DESC);
CREATE TABLE workspace_scope (
 workspace_id uuid PRIMARY KEY REFERENCES workspace(id), version bigint NOT NULL DEFAULT 1
);
CREATE TABLE workspace_material_reference (
 workspace_id uuid NOT NULL, material_id uuid NOT NULL, profile_id uuid NOT NULL,
 PRIMARY KEY(workspace_id,material_id),
 FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id),
 FOREIGN KEY(material_id,profile_id) REFERENCES material(id,profile_id)
);