package com.agentcv.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiAdvice {
    public static Map<String, Object> error(String code, String message, boolean retryable) {
        return Map.of("code", code, "message", message, "retryable", retryable,
                "request_id", UUID.randomUUID().toString());
    }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status).body(error(error.code, error.getMessage(), error.status == 503));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "请求格式不正确，请检查输入。", false));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> storage(DataAccessException ignored) {
        return ResponseEntity.status(503).body(error("PERSISTENCE_FAILED", "暂时无法读取或保存，请保留输入后重试。", true));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> notFound(Exception ignored) {
        return ResponseEntity.status(404).body(error("NOT_FOUND","此入口不可用，请重新打开当前页面。",false));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ignored) {
        org.slf4j.LoggerFactory.getLogger(ApiAdvice.class).warn("Unhandled API exception type: {}", ignored.getClass().getName());
        return ResponseEntity.status(500).body(error("INTERNAL_ERROR", "操作未能完成，请保留输入。", false));
    }
}