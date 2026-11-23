CREATE TABLE ai_request (
 id uuid PRIMARY KEY, profile_id uuid NOT NULL, workspace_id uuid NOT NULL,
 request_key uuid NOT NULL, payload_hash varchar(64) NOT NULL,
 message text NOT NULL, input_json text NOT NULL,
 model varchar(100) NOT NULL, execution_status varchar(30) NOT NULL DEFAULT 'ACCEPTED',
 record_status varchar(20) NOT NULL DEFAULT 'PENDING',
 answer text, error_code varchar(80), supplier_request_id varchar(200), usage_json text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(workspace_id,profile_id) REFERENCES workspace(id,profile_id),
 UNIQUE(workspace_id,request_key)
);
CREATE UNIQUE INDEX ai_one_active ON ai_request(workspace_id)
 WHERE execution_status IN ('ACCEPTED','CALLING') OR record_status='SAVE_FAILED';
CREATE TABLE conversation_message (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspace(id),
 request_id uuid NOT NULL REFERENCES ai_request(id), role varchar(20) NOT NULL CHECK(role IN ('user','assistant')),
 text text NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(request_id,role)
);
CREATE INDEX conversation_workspace ON conversation_message(workspace_id,created_at,id);