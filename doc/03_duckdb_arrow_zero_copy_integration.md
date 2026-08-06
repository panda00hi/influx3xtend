# DuckDB 与 Apache Arrow 零拷贝合并与内存管理设计

## 1. 为什么选择 DuckDB + Apache Arrow

在 `Influx3xtend` 中：
- **InfluxDB 3 Core** 底层基于 Apache Arrow 与 Rust DataFusion 构建，其 Flight SQL 接口原生返回 Apache Arrow RecordBatch。
- **DuckDB** 是一款极为优秀的嵌入式分析数据库，天然支持通过 C/C++ API 和 JNI 接口直接导入/导出 Apache Arrow 内存结构，且其向量化列式执行引擎直接读写 Parquet 性能达到极限。
- **Apache Arrow** 提供了标准化的内存列族表示法，允许数据在 InfluxDB 3 客户端、DuckDB JNI 引擎与 Java 21 堆外内存之间做**零拷贝 (Zero-Copy) 共享与交互**，省去了序列化/反序列化成 Java 对象 (POJO) 的巨大 CPU 和内存 GC 开销。

---

## 2. 内存架构与零拷贝流转方案

```
+-----------------------------------------------------------------------------------+
|                                 Java 21 JVM 进程                                   |
|                                                                                   |
|  +---------------------------+                     +---------------------------+  |
|  | InfluxDB 3 Flight Client  |                     |  Embedded DuckDB (JNI)    |  |
|  +-------------+-------------+                     +-------------+-------------+  |
|                |                                                 |                |
|                v (Arrow Flight Stream)                           v (DuckDB C API) |
|  +-------------+-------------+                     +-------------+-------------+  |
|  | Arrow RecordBatch (Real)  |                     | Arrow RecordBatch (Hist)    |  |
|  +-------------+-------------+                     +-------------+-------------+  |
|                |                                                 |                |
|                +-----------------------+-------------------------+                |
|                                        |                                          |
|                                        v                                          |
|                        +---------------+---------------+                          |
|                        | Arrow Vector Merge Engine     |                          |
|                        | (JVM Direct Memory Operation) |                          |
|                        +---------------+---------------+                          |
|                                        |                                          |
|                                        v                                          |
|                        +---------------+---------------+                          |
|                        | Final Arrow RecordBatchStream |                          |
|                        +-------------------------------+                          |
+-----------------------------------------------------------------------------------+
```

### 零拷贝核心要点
1. **DuckDB 到 Arrow 导出**：
   通过 DuckDB Java JDBC/JNI 提供的 C Data Interface 接口，将 DuckDB 查询结果直接转换为 `org.apache.arrow.vector.VectorSchemaRoot`，避免转为 `java.sql.ResultSet`。
2. **堆外内存 (Off-Heap Direct Memory)**：
   Arrow Vector 使用 Native Direct Memory（堆外内存），数据存放在 JVM 堆外，GC 不会扫描 Arrow 内存块，避免海量数据下 GC Pause 风险。
3. **C Data Interface**：
   利用 Apache Arrow 的 `ArrowArray` 与 `ArrowSchema` C 结构体标准，在 C++ (DuckDB) 与 Java 之间安全传递指针。

---

## 3. Arrow Vector 归并与二次聚合引擎算法设计

当两端查询分别返回 `Realtime Arrow Stream` 和 `Historical Arrow Stream` 时，JVM 内的合并引擎需要完成以下三大操作：

### 3.1 时间戳向量双指针归并排序 (Two-Pointer Timestamp Mergesort)
时序数据通常按 `time` 递增或递减排序。
1. 提取 Realtime Stream 与 Historical Stream 中的时间戳向量 `TimeStampVector`（Int64 堆外内存）。
2. 使用双指针（`ptr_real`, `ptr_hist`）同步顺序遍历两个 RecordBatch。
3. 按照 `time` 顺序构建输出的 `VectorSchemaRoot`。

### 3.2 边界交叠点位去重 (Deduplication)
若 `realtime_row.time == historical_row.time` 且主键 Tag 相同：
- 采用 **Realtime Data Priority**（实时数据优先）原则，保留 InfluxDB 3 Core 实时 RecordBatch 中的行，跳过 DuckDB 中的重复行。

### 3.3 二次聚合计算 (Second-Pass Aggregation)
当用户查询包含聚合函数（如 `AVG`, `SUM`, `COUNT`, `MAX`, `MIN`）且设置了 `GROUP BY` 时：
- **下推分片**：两端（InfluxDB 3 与 DuckDB）各自计算 Partial Aggregations。
- **下推函数转换**：对于 `AVG(temperature)`，路由器将其重构为下推查询：
  ```sql
  SELECT time_bucket, SUM(temperature) as sum_temp, COUNT(temperature) as count_temp FROM ... GROUP BY time_bucket
  ```
- **JVM 堆外二次合并**：
  $$\text{AVG}_{\text{final}} = \frac{\text{sum\_temp}_{\text{real}} + \text{sum\_temp}_{\text{hist}}}{\text{count\_temp}_{\text{real}} + \text{count\_temp}_{\text{hist}}}$$
  $$\text{MAX}_{\text{final}} = \max(\text{max}_{\text{real}}, \text{max}_{\text{hist}})$$

---

## 4. JVM 21 堆外内存管理与安全规范

由于使用了大量堆外内存与 JNI 操作，必须遵守严格的内存管理规范：

1. **BufferAllocator 生命周期控制**：
   使用 Apache Arrow 提供的 `RootAllocator` 管理内存，每个查询请求分配一个 `BufferAllocator` 作用域，查询完毕后必须显式调用 `close()` 释放。
   ```java
   try (BufferAllocator allocator = rootAllocator.newChildAllocator("query-" + queryId, 0, Long.MAX_VALUE);
        VectorSchemaRoot mergedRoot = VectorSchemaRoot.create(schema, allocator)) {
       // 执行合并运算
   } // 自动调用 close()，泄漏时会抛出 IllegalStateException
   ```
2. **Java 21 JVM 启动参数**：
   在 Java 21 环境下运行必须配置以下模块开放参数，以允许 Arrow 和 DuckDB 访问 `java.nio` 堆外内存：
   ```bash
   --add-opens=java.base/java.nio=ALL-UNNAMED
   --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
   ```
3. **DuckDB 内存限制设置**：
   为了防止嵌入式 DuckDB 占用过多系统内存导致 OOM，在创建 DuckDB 连接时显式限制其最大内存：
   ```sql
   SET max_memory = '4GB';
   SET threads = 4;
   ```
