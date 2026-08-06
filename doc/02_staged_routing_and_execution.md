# 实时-历史分阶段路由与边界感知引擎设计

## 1. 设计动机与核心逻辑

在 InfluxDB 3 架构体系中，数据写入首先进入 WAL (Write-Ahead Log) 及内存中的 In-Memory Buffer，经过特定的条件（如时间间隔、内存阈值或数据量阈值）触发持久化刷盘，在对象存储（如 S3、OSS）或本地磁盘上生成 Parquet 文件。

传统查询方式直接依赖 InfluxDB 3 Core 节点去读取分析全部 Parquet 文件，这在大规模历史数据分析场景下会导致 InfluxDB 3 节点 CPU/内存开销居高不下。

`Influx3xtend` 提出的 **分阶段路由引擎 (Staged Routing Engine)** 旨在解决这一问题：
1. **优先实时原则**：查询第一时间发给 InfluxDB 3 Core，获取内存 buffer 及未落盘的最细粒度实时数据。
2. **动态边界感知**：通过分析 InfluxDB 3 Core 实际返回的数据的时间范围（或配合其内部元数据系统），精准推算出 InfluxDB 3 尚未覆盖的历史切片时间范围 `[T_start, T_real_min)`。
3. **DuckDB 下推调度**：仅针对缺失的历史时间区间调度嵌入式 DuckDB 去分析已落盘 Parquet 文件。

---

## 2. 路由阶段详述

```
查询请求时间范围: [T_start = 7天前, T_end = 现在]
                                     |
                                     v
+-------------------------------------------------------------------------+
| [阶段 1] InfluxDB 3 Core 查询                                           |
| 请求范围: [T_start, T_end]                                              |
| InfluxDB 3 实际返回最老数据时间戳: T_real_min (例如 15 分钟前)               |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| [阶段 2] 动态边界推导                                                   |
| 覆盖分析:                                                                |
| - 实时区间 (InfluxDB 3 覆盖): [T_real_min, T_end]                      |
| - 历史缺失区间 (需 DuckDB 补充): [T_start, T_real_min)                 |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| [阶段 3] 嵌入式 DuckDB 调度                                             |
| 针对 [T_start, T_real_min) 生成 DuckDB Parquet SQL                      |
| DuckDB 直连 S3/OSS 读取 Parquet 文件并输出 Arrow Stream                |
+-------------------------------------------------------------------------+
```

### 2. 实时数据摄取与 10kHz 报文解包

- **采样与上报模型**：工业现场采集卡按 10kHz ($10,000\text{Hz}$) 进行持续采样，**每秒上报 1 包** MQTT 消息；
- **单包负载**：每包 JSON Payload 中包含该秒内连续采样的 10,000 个 Float 点位数组；
- **微秒向量解包**：`MqttTelemetryParser` 在接收到报文后，在 $<1\text{ms}$ 内瞬间展开为 10,000 行时间递增步长为 $100\mu s$ 的 Apache Arrow 堆外向量。
- **动态探针驱动裁决 (Dynamic Probe-Driven Routing)**：
  - **彻底消除硬编码 72h 盲判**：底层绝不机械依赖硬编码的时间阈值。由于 InfluxDB 3 Core 刷盘下沉受到 WAL 大小、缓冲区内存上限、Compactor 调度等多种物理机制影响，落盘时间并不固定；
  - **探针获取 $T_{influx\_min}$**：路由器每次查询时向 InfluxDB 3 发起轻量探针，直接读取当前缓冲中**真正能查到的最老点时间戳 $T_{influx\_min}$**；
  - **100% 精确无漏路由**：
    1. 切片 $[T_{influx\_min}, T_{end})$ 必然在 InfluxDB 3 缓冲/内存中，由 Flight SQL 提取；
    2. 切片 $[T_{start}, T_{influx\_min})$ 必然已刷盘为 Parquet 文件，由 DuckDB 向量化提取；
    3. 若由于跨度超限导致 InfluxDB 3 报错，自动触发 DuckDB 全量 Parquet 扫描兜底！跨度无界！
- **查询下发**：将用户在 Builder 中构建的 filter 条件与聚合逻辑下发给 InfluxDB 3。
- **返回解析**：如果 InfluxDB 3 返回的 RecordBatch 中有数据，计算该 Batch 中 `time` 向量字段的最小值 `T_real_min`。如果 InfluxDB 3 中没有任何数据或报错（如超时），则将 `T_real_min` 设为 `T_end`。

### 阶段 2：动态边界推导与切片拆分
- **切片判断公式**：
  $$\text{Historical Range} = [T_{start}, \min(T_{real\_min}, T_{end}))$$
- **特殊边界场景处理**：
  1. **场景 A (完全落盘/已在 InfluxDB 3 返回全量)**：若 $T_{real\_min} \le T_{start}$，说明 InfluxDB 3 已经查到了全量数据，不需要调度 DuckDB。
  2. **场景 B (完全未落盘/全实时数据)**：若 $T_{start} \ge T_{real\_min}$，说明查询区间全部位于实时内存区，无需调度 DuckDB。
  3. **场景 C (跨越实时与历史)**：$T_{start} < T_{real\_min}$，触发阶段 3，调度 DuckDB 查询 $[T_{start}, T_{real\_min})$。

### 阶段 3：DuckDB 历史 Parquet 查询调度
- **Parquet 文件路径解析**：
  - 模式 1：通过对象存储（S3/OSS）的前缀扫描匹配（如 `s3://bucket/engine/DB/TABLE/YYYY/MM/DD/*.parquet`）。
  - 模式 2：解析 InfluxDB 3 的 Catalog / Manifest 索引文件确定需要读取的文件列表。
- **SQL 下推与生成**：
  ```sql
  SELECT time, device_id, temperature, humidity 
  FROM read_parquet('s3://my-bucket/db/sensor_data/*/*/*/*.parquet')
  WHERE time >= '2026-07-16T00:00:00Z' AND time < '2026-07-23T10:45:00Z'
    AND device_id = 'dev-1001';
  ```
- **嵌入式 DuckDB 执行**：在 JVM 内通过 JNI 启动 DuckDB 引擎，配置 S3 秘钥与凭证后，将查询结果以 Apache Arrow Stream 形式返回。

---

## 3. 边界边缘重叠与去重机制

在阶段 1 与阶段 3 的交界点 $T_{real\_min}$ 附近，可能存在“已经被写进 Parquet 但尚未从 InfluxDB 3 内存 buffer 中清除”的微量重复点位。

为了保障数据的一致性与准确性：
1. **去重键定义**：由 `time` (Timestamp) + 业务 Key/Tags (如 `device_id`) 拼接唯一主键。
2. **去重策略**：在 **Arrow Merge Engine** 阶段，合并引擎对两个 Arrow RecordBatch Stream 进行归并排序时，采用后到优先（或 InfluxDB 3 实时数据优先）的去重算法，丢弃重复的时间戳记录。

---

## 4. 路由逻辑 Java 代码框架参考

```java
public class StagedRouter {
    private final InfluxDB3Client influx3Client;
    private final EmbeddedDuckDBEngine duckDBEngine;

    public QueryResult routeAndExecute(QueryRequest request) {
        // 1. 优先执行 InfluxDB 3 查询
        ArrowRecordBatchStream realtimeStream = influx3Client.query(request);
        Instant minRealtimeTimestamp = realtimeStream.getMinTimestamp();

        Instant reqStart = request.getTimeRange().getStart();
        Instant reqEnd = request.getTimeRange().getEnd();

        // 2. 检查是否需要 DuckDB 补充历史数据
        if (minRealtimeTimestamp != null && minRealtimeTimestamp.isAfter(reqStart)) {
            TimeRange historicalRange = new TimeRange(reqStart, minRealtimeTimestamp);
            QueryRequest historicalRequest = request.withTimeRange(historicalRange);

            // 3. 调度 DuckDB 历史查询
            ArrowRecordBatchStream historicalStream = duckDBEngine.queryParquet(historicalRequest);

            // 4. 零拷贝合并
            return ArrowMergeEngine.merge(realtimeStream, historicalStream, request);
        } else {
            // 纯实时或 InfluxDB 3 已覆盖全量
            return new QueryResult(realtimeStream);
        }
    }
}
```
