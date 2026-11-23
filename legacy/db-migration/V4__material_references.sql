ALTER TABLE material_import ADD COLUMN material_kind varchar(20) NOT NULL DEFAULT 'RESUME' CHECK(material_kind IN ('RESUME','REFERENCE'));
CREATE TABLE material_confirmation (
 material_id uuid NOT NULL, profile_id uuid NOT NULL, revision integer NOT NULL,
 text text NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(material_id,profile_id,revision),
 FOREIGN KEY(material_id,profile_id) REFERENCES material_import(id,profile_id)
);
INSERT INTO material_confirmation(material_id,profile_id,revision,text)
 SELECT id,profile_id,row_version,confirmed_text FROM material_import WHERE check_state='CONFIRMED';
CREATE TABLE workspace_material_reference (
 workspace_id uuid NOT NULL, profile_id uuid NOT NULL, material_id uuid NOT NULL,
 PRIMARY KEY(workspace_id,material_id),
 FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id),
 FOREIGN KEY(material_id,profile_id) REFERENCES material_import(id,profile_id)
);
CREATE INDEX reference_material ON workspace_material_reference(material_id,profile_id);