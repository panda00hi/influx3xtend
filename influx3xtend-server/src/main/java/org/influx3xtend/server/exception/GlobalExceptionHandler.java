package org.influx3xtend.server.exception;

import org.influx3xtend.core.exception.Influx3xtendConnectionException;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.exception.Influx3xtendQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API Gateway 全局异常拦截处理 (GlobalExceptionHandler)
 * 当 InfluxDB 服务不可用或查询语法错误时，格式化输出清晰的排查提示
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Influx3xtendConnectionException.class)
    public ResponseEntity<Map<String, Object>> handleConnectionException(Influx3xtendConnectionException e) {
        log.error("InfluxDB connection error: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("error", "InfluxDB Connection Failed");
        body.put("message", e.getMessage() + " - Please check if InfluxDB 3 core service is running and accessible.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Influx3xtendQueryException.class)
    public ResponseEntity<Map<String, Object>> handleQueryException(Influx3xtendQueryException e) {
        log.error("Query execution error: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("error", "Query Execution Error");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Influx3xtendException.class)
    public ResponseEntity<Map<String, Object>> handleMiddlewareException(Influx3xtendException e) {
        log.error("Middleware error: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("error", "Influx3xtend Exception");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Unhandled server exception: {}", e.getMessage(), e);
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("error", "Internal Server Error");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
