package org.influx3xtend.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * HTTP REST 网关统一查询请求 DTO (QueryDTO)
 * 具备强类型规范与 Jackson JSON 序列化注解，彻底告别不透明的 Map<String, Object>
 */
public record QueryDTO(
        @JsonProperty("database") String database,
        @JsonProperty("measurement") String measurement,
        @JsonProperty("time_column") String timeColumn,
        @JsonProperty("start_time") String startTime,
        @JsonProperty("end_time") String endTime,
        @JsonProperty("hours_ago") Integer hoursAgo,
        @JsonProperty("select_fields") List<String> selectFields,
        @JsonProperty("tags") Map<String, String> tags,
        @JsonProperty("order") String order,
        @JsonProperty("order_by") String orderBy,
        @JsonProperty("limit") Integer limit
) {}
