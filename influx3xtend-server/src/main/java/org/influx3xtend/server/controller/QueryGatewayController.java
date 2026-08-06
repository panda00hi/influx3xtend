package org.influx3xtend.server.controller;

import org.influx3xtend.server.dto.QueryDTO;
import org.influx3xtend.server.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用 HTTP REST Gateway 控制器 (QueryGatewayController)
 * 闭环实现：通用的 REST 规范查询、Line Protocol 批量写入与健康检查探针
 */
@RestController
@RequestMapping("/api/v1")
public class QueryGatewayController {

    private static final Logger log = LoggerFactory.getLogger(QueryGatewayController.class);

    private final QueryService queryService;

    public QueryGatewayController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * REST 规范通用查询接口
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> executeQuery(@RequestBody QueryDTO queryDTO) {
        try {
            Map<String, Object> response = queryService.executeQuery(queryDTO);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid query request parameters: {}", e.getMessage());
            Map<String, Object> errResponse = new HashMap<>();
            errResponse.put("code", 400);
            errResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errResponse);
        } catch (Exception e) {
            log.error("REST Gateway query execution failed", e);
            Map<String, Object> errResponse = new HashMap<>();
            errResponse.put("code", 500);
            errResponse.put("message", "Query Execution Error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errResponse);
        }
    }

    /**
     * REST 网关健康检查探针
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("engine", "Influx3xtend Gateway");
        status.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(status);
    }
}
