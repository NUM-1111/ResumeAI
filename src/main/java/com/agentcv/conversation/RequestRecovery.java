package com.agentcv.conversation;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("m00")
public class RequestRecovery {
    private final JdbcTemplate db;
    public RequestRecovery(JdbcTemplate db){this.db=db;}
    /** Caller must own WorkerGuard. ACCEPTED has not crossed the send boundary. */
    public int recover(){return db.update("update ai_request set execution_status='UNKNOWN',error_code=case when record_status='SAVE_FAILED' then 'RESULT_LOST_ON_RESTART' else 'MODEL_RESULT_UNKNOWN' end,record_status='SAVED',input_json='',updated_at=CURRENT_TIMESTAMP where execution_status='CALLING'");}
    /** Retains operation keys and all visible business records; no model calls. */
    public int cleanup(){return db.update("update ai_request set input_json='' where execution_status in ('SUCCEEDED','MODEL_FAILED','UNKNOWN') and record_status='SAVED' and input_json<>'' and updated_at<CURRENT_TIMESTAMP-interval '1 day'");}
}