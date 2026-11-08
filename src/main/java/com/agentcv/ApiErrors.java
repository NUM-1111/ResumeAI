package com.agentcv;

import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class ApiErrors {
    ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,
                "requestId",UUID.randomUUID().toString(),"nextAction","保留页面文本，检查后重试或复制兜底"));
    }
    @ExceptionHandler(WorkspaceService.Failure.class) ResponseEntity<?> business(WorkspaceService.Failure e) {return error(e.status,e.code,e.getMessage());}
    @ExceptionHandler({MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> input(Exception e) {return error(400,"INVALID_INPUT","请求字段、对象ID或幂等键无效");}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(DataAccessException e) {return error(503,"STORAGE_UNAVAILABLE","保存或读取失败，未确认成功，请保留页面内容");}
}