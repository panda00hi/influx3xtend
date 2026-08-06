# Java Fluent API 与 REST 网关接口规范设计

## 1. 概述

`Influx3xtend` 提供了两种使用模式：
1. **SDK 嵌入模式 (`influx3xtend-core`)**：作为 Java jar 包直接被业务应用引入，提供类型安全的 **Fluent Builder API**。
2. **Standalone Gateway 服务模式 (`influx3xtend-server`)**：作为独立运行的 Spring Boot 3.3 网关应用部署，对外提供标准的 **REST / JSON API** 接口，方便非 Java 语言（如 Python、Go、Node.js）或 HTTP 客户端调用。

---

## 2. Java SDK Fluent API 设计规范

### 2.1 客户端初始化与配置 (`Influx3xtendClient`)

```java
package org.influx3xtend.core.api;

import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;

public class ClientExample {
    public static void main(String[] args) {
        // 创建全局 Client 实例 (单例复用，内部管理线程池与 Allocator)
        Influx3xtendClient client = Influx3xtendClient.builder()
            // 官方 InfluxDB 3.x 客户端配置
            .influx3Config(InfluxDB3Config.builder()
                .host("http://influx3-core.internal:8086")
                .token("my-secret-token")
                .database("factory_metrics")
                .build())
            // 历史 Parquet 对象存储配置
            .storageConfig(S3StorageConfig.builder()
                .endpoint("https://s3.us-east-1.amazonaws.com")
                .region("us-east-1")
                .bucket("tsdb-parquet-bucket")
                .accessKey("AKIA...")
                .secretKey("SECRET...")
                .build())
            // DuckDB 内存限制选项
            .duckdbMaxMemory("4GB")
            .build();
    }
}
```

### 2.2 流式查询构建器 (`Influx3QueryBuilder`)

```java
QueryResult result = client.query()
    .measurement("sensor_data")
    .select("temperature", "humidity", "vibration")
    .whereTagEquals("factory_id", "factory-01")
    .whereTagIn("device_type", List.of("robot_arm", "conveyor"))
    .whereFieldGreaterThan("temperature", 45.5)
    .timeRange(Instant.now().minus(Duration.ofDays(30)), Instant.now())
    .groupByTime(Duration.ofMinutes(15))
    .aggregate("temperature", AggregateType.AVG)
    .aggregate("vibration", AggregateType.MAX)
    .orderBy("time", SortOrder.ASC)
    .limit(1000)
    .execute();
```

### 2.3 结果集获取与映射 (`QueryResult`)

`QueryResult` 支持高性能 Arrow 流与 Java POJO 对象两种读取方式：

```java
// 方式 A：极致性能 - 获取 Arrow RecordBatch Stream (零拷贝)
try (VectorSchemaRoot root = result.getArrowRoot()) {
    BigIntVector timeVector = (BigIntVector) root.getVector("time");
    Float8Vector tempVector = (Float8Vector) root.getVector("temperature");
    for (int i = 0; i < root.getRowCount(); i++) {
        long timestamp = timeVector.get(i);
        double temp = tempVector.get(i);
        // 业务高效向量化处理...
    }
}

// 方式 B：业务友好 - 自动反射映射为 Java POJO
List<SensorDataPOJO> pojoList = result.toPojoList(SensorDataPOJO.class);
```

#### POJO 标注注解定义
```java
public class SensorDataPOJO {
    @Column(name = "time")
    private Instant time;

    @Column(name = "factory_id")
    private String factoryId;

    @Column(name = "temperature")
    private Double temperature;

    // Getters and Setters...
}
```

---

## 3. REST Gateway 网关 API 规范 (HTTP API)

`influx3xtend-server` 网关统一对外提供标准的 JSON REST 接口。

### 3.1 数据查询接口 `/api/v1/query`

- **HTTP Method**: `POST`
- **Content-Type**: `application/json`

#### 请求体 (Request Body)
```json
{
  "database": "factory_metrics",
  "measurement": "sensor_data",
  "select": ["temperature", "humidity", "vibration"],
  "where": {
    "tags": {
      "factory_id": "factory-01"
    },
    "fields": [
      {
        "field": "temperature",
        "operator": ">",
        "value": 45.5
      }
    ]
  },
  "time_range": {
    "start": "2026-07-01T00:00:00Z",
    "end": "2026-07-23T11:00:00Z"
  },
  "group_by_time": "15m",
  "aggregates": [
    {"field": "temperature", "type": "AVG", "alias": "avg_temp"},
    {"field": "vibration", "type": "MAX", "alias": "max_vib"}
  ],
  "limit": 1000
}
```

#### 响应体 (Response Body)
```json
{
  "code": 200,
  "message": "success",
  "execution_stats": {
    "total_rows": 2112,
    "realtime_rows_from_influx3": 96,
    "historical_rows_from_duckdb": 2016,
    "elapsed_time_ms": 38
  },
  "schema": [
    {"name": "time", "type": "TIMESTAMP_MS"},
    {"name": "factory_id", "type": "STRING"},
    {"name": "avg_temp", "type": "DOUBLE"},
    {"name": "max_vib", "type": "DOUBLE"}
  ],
  "data": [
    {
      "time": "2026-07-01T00:00:00Z",
      "factory_id": "factory-01",
      "avg_temp": 46.2,
      "max_vib": 0.82
    }
  ]
}
```

---

## 4. 错误处理与状态码设计

| 错误码 (Code) | HTTP 状态码 | 说明 |
| :--- | :--- | :--- |
| `200` | 200 OK | 查询成功 |
| `4001` | 400 Bad Request | 查询参数语法校验失败 (如 timeRange 无效) |
| `5001` | 500 Server Error | InfluxDB 3 Core 节点通信异常或超时 |
| `5002` | 500 Server Error | DuckDB JNI 引擎分析 Parquet 异常 (如 S3 鉴权失败) |
| `5003` | 500 Server Error | JVM 堆外 Arrow 内存合并异常 |
