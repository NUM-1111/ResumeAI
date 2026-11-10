CREATE TABLE recycle_entry (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL REFERENCES profile(id),
 kind varchar(20) NOT NULL CHECK(kind IN ('PROFILE','WORKSPACE','MATERIAL')),
 object_id uuid NOT NULL, name text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 restored_at timestamptz
);
ALTER TABLE profile ADD COLUMN recycle_batch uuid REFERENCES recycle_entry(id);
ALTER TABLE workspace ADD COLUMN recycle_batch uuid REFERENCES recycle_entry(id);
ALTER TABLE material_import ADD COLUMN recycle_batch uuid REFERENCES recycle_entry(id);
CREATE INDEX recycle_open ON recycle_entry(id) WHERE restored_at IS NULL;
CREATE INDEX workspace_recycle ON workspace(recycle_batch) WHERE recycle_batch IS NOT NULL;
CREATE INDEX material_recycle ON material_import(recycle_batch) WHERE recycle_batch IS NOT NULL;