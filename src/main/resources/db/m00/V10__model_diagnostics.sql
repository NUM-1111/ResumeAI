-- Safe timings/counters only. Never stores headers, credentials or response text.
ALTER TABLE ai_request ADD COLUMN diagnostics_json text NOT NULL DEFAULT '{}';