# Influx3xtend 高性能数据中间件 - 资深架构设计白皮书 (Architecture Blueprint)

> **项目名称**：Influx3xtend  
> **核心定位**：基于 Java 21、InfluxDB 3 Core 与 DuckDB 的时序与 OLAP 混合向量化分析中间件  
> **作者**：Influx3xtend 架构组  
> **版本**：v1.0.0-RELEASE  

---

## 1. 架构总览与设计哲学

### 1.1 背景与行业痛点
随着物联网 (IoT)、金融高频交易及 IT 运维监控（AIOps）的爆炸式增长，海量时序数据的存储与查询面临双重挑战：
- **实时数据（Heat Data）**：高频点位写入大量驻留在内存/WAL 中，需要极致的低延迟点查与聚合，传统的列式存储落盘存在滞后。
- **历史数据（Cold Data）**：海量数据已经压缩为 Parquet 文件归档至 S3/OSS。如果全量查询都强制由 InfluxDB 3 集中式计算节点执行，不仅集群扩容成本极高，而且在复杂 OLAP 分析场景下难以发挥客户端本地计算优势。

### 1.2 Influx3xtend 架构解耦思想
`Influx3xtend` 提出了 **“引擎分阶段解耦，内存零拷贝统一”** 的混合架构设计哲学：

```
                           +-------------------------------------+
                           |   Influx3xtend Middleware Layer     |
                           +------------------+------------------+
                                              |
                     +------------------------+------------------------+
                     |                                                 |
                     v                                                 v
      +------------------------------+                  +------------------------------+
      |      InfluxDB 3 Core         |                  |       Embedded DuckDB        |
      | (Realtime Buffer / WAL Stream)|                  | (Historical Parquet Engine)  |
      +------------------------------+                  +------------------------------+
                     |                                                 |
                     \------------------------+------------------------/
                                              |
                                              v
                               +------------------------------+
                               | Apache Arrow Zero-Copy Merge |
                               +------------------------------+
```

---

## 2. 系统总体逻辑架构图 (System Logical Architecture)

`Influx3xtend` 采用分层的模块化设计，清晰地划分为 API 接入层、分阶段控制面、引擎驱动层与堆外内存合并面：

```mermaid
graph TD
    subgraph ClientLayer ["1. API 接入与客户端层 (Client & Access Layer)"]
        JavaApp["Java 业务应用 (SDK 模式)"]
        HttpApp["HTTP REST / JSON 客户端"]
        GrpcApp["gRPC / Flight SQL 客户端"]
    end

    subgraph GatewayLayer ["2. 接口与协议网关 (Gateway & Protocol Layer)"]
        FluentAPI["Fluent Query Builder API"]
        SpringGateway["Spring Boot 3.3 Gateway Controller (Virtual Threads)"]
    end

    subgraph ControlPlane ["3. 分阶段路由控制面 (Staged Control Plane)"]
        Router["StagedQueryRouter (分阶段路由器)"]
        BoundaryDetector["Boundary Detector (动态边界感知器)"]
        StorageScanner["ObjectStorageScanner (S3/OSS Parquet 索引扫描器)"]
    end

    subgraph ExecutionPlane ["4. 双引擎执行面 (Execution Plane)"]
        InfluxAdapter["Influx3ClientAdapter (com.influxdb:influxdb3-java)"]
        DuckAdapter["DuckDBEngineAdapter (org.duckdb:duckdb_jdbc)"]
        InfluxCore[("InfluxDB 3 Core Server (Rust / WAL)")]
        S3Storage[("S3 / OSS / Local Parquet Storage")]
    end

    subgraph DataMergePlane ["5. 零拷贝内存合并面 (Zero-Copy Data Plane)"]
        ArrowEngine["ArrowMergeEngine (JVM Heap/Off-Heap Operations)"]
        ArrowSort["Timestamp Mergesort (时间戳向量双指针归并)"]
        ArrowDeduplication["Deduplication (边界点位去重)"]
        ArrowAgg["Second-Pass Aggregator (二次聚合算子)"]
    end

    JavaApp --> FluentAPI
    HttpApp --> SpringGateway
    GrpcApp --> SpringGateway
    SpringGateway --> FluentAPI

    FluentAPI --> Router
    Router --> InfluxAdapter
    InfluxAdapter --> InfluxCore
    InfluxAdapter --> BoundaryDetector

    BoundaryDetector --> StorageScanner
    StorageScanner --> S3Storage
    BoundaryDetector --> DuckAdapter
    DuckAdapter --> S3Storage

    InfluxAdapter -- "Realtime Arrow RecordBatch" --> ArrowEngine
    DuckAdapter -- "Historical Arrow RecordBatch" --> ArrowEngine

    ArrowEngine --> ArrowSort
    ArrowSort --> ArrowDeduplication
    ArrowDeduplication --> ArrowAgg
    ArrowAgg --> QueryResult["QueryResult (POJO Mapper / Arrow Stream)"]
```

---

## 3. 实时-历史分阶段路由与状态机设计

### 3.1 查询路由 Sequence 序列图

```mermaid
sequenceDiagram
    autonumber
    actor App as 业务客户端 (Client)
    participant Router as StagedQueryRouter
    participant Influx3 as InfluxDB 3 Core (Realtime)
    participant Detector as Boundary Detector
    participant DuckDB as Embedded DuckDB Engine
    participant Merge as Arrow Merge Engine

    App->>Router: execute(QueryRequest [T_start ~ T_end])
    
    rect rgb(235, 245, 255)
        Note over Router, Influx3: 【阶段 1】优先查询 InfluxDB 3 Core 实时数据
        Router->>Influx3: executeQuery(Flight SQL) [T_start ~ T_end]
        Influx3-->>Router: 返回 realRoot (VectorSchemaRoot)
    end

    rect rgb(255, 245, 235)
        Note over Router, Detector: 【阶段 2】边界感知与缺失切片推导
        Router->>Detector: analyzeMinTimestamp(realRoot)
        Detector-->>Router: 最老实时点位时间戳 T_real_min
        Router->>Router: 计算缺失历史切片 [T_start, min(T_real_min, T_end))
    end

    rect rgb(235, 255, 235)
        Note over Router, DuckDB: 【阶段 3】历史切片 DuckDB 向量化下推
        alt 存在历史缺失切片 [T_start, T_real_min)
            Router->>DuckDB: queryParquet(Parquet SQL [T_start, T_real_min))
            DuckDB-->>Router: 返回 histRoot (VectorSchemaRoot)
        end
    end

    rect rgb(245, 235, 255)
        Note over Router, Merge: 【阶段 4】JVM 堆外零拷贝归并与去重
        Router->>Merge: merge(realRoot, histRoot, request)
        Merge->>Merge: 双指针时间戳排序 + 实时优先点位去重 + 二次聚合
        Merge-->>Router: 返回 mergedRoot (VectorSchemaRoot)
    end

    Router-->>App: 返回 QueryResult (支持 toPojoList 或 Arrow Stream)
```

### 3.2 边界推导状态机 (Routing State Machine)

```mermaid
stateDiagram-v2
    [*] --> Influx3RealtimeQuery: 接收查询请求 [T_start, T_end]
    Influx3RealtimeQuery --> EvaluateCoverage: 获得 InfluxDB 3 结果批次 realRoot
    
    state EvaluateCoverage {
        [*] --> CheckMinTimestamp
        CheckMinTimestamp --> FullRealtime: T_real_min <= T_start
        CheckMinTimestamp --> FullHistorical: realRoot 为空且无实时数据
        CheckMinTimestamp --> PartialHybrid: T_start < T_real_min < T_end
    }

    FullRealtime --> DirectlyReturnRealtime: 仅返回 realRoot
    FullHistorical --> DispatchDuckDBAll: 调度 DuckDB 查询 [T_start, T_end]
    PartialHybrid --> DispatchDuckDBHistorical: 调度 DuckDB 查询 [T_start, T_real_min)

    DispatchDuckDBAll --> ArrowMerge
    DispatchDuckDBHistorical --> ArrowMerge
    DirectlyReturnRealtime --> [*]
    ArrowMerge --> [*]: 返回最终零拷贝向量流
```

---

## 4. 堆外内存模型与零拷贝交互结构 (Memory Architecture)

由于采用了 Apache Arrow 与 DuckDB JNI，数据交互完全在 **Direct Off-Heap Memory (堆外内存)** 中进行，JVM 堆内存仅保留少量元数据引用，从而根治了传统 Java 时序中间件在海量数据查询时的 GC Pause 风险。

```
+-----------------------------------------------------------------------------------+
|                                JVM Process (Java 21)                              |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | JVM Heap Memory (堆内内存 - 仅占用 KB 级引用)                               |  |
|  |  - QueryRequest (Record)                                                    |  |
|  |  - Influx3QueryBuilder                                                      |  |
|  |  - FieldVector Ref Pointers                                                 |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | Apache Arrow Direct Off-Heap Memory (堆外内存 - 存储 MB/GB 级向量点位)       |  |
|  |                                                                             |  |
|  |  [Int64 Time Vector]     [Float8 Value Vector]   [VarChar Tag Vector]       |  |
|  |  +-------------------+   +-------------------+   +-------------------+      |  |
|  |  | 1774263600000     |   | 45.2              |   | dev-1001          |      |  |
|  |  | 1774263601000     |   | 45.8              |   | dev-1001          |      |  |
|  |  | ...               |   | ...               |   | ...               |      |  |
|  |  +-------------------+   +-------------------+   +-------------------+      |  |
|  |                                                                             |  |
|  |  RootAllocator (内存安全检测与生命周期控制 - 自动防止 Memory Leak)             |  |
|  +-----------------------------------------------------------------------------+  |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 5. 两种部署拓扑架构 (Deployment Topology)

### 5.1 嵌入式 SDK 模式 (Embedded SDK Architecture)
适用于纯 Java 微服务体系，中间件作为 jar 包引入到业务微服务中，拥有极高的本地吞吐量与极低 RPC 开销。

```
+-------------------------------------------------------------------+
| Application Node (JVM)                                            |
|                                                                   |
|   +-----------------------------------------------------------+   |
|   | Business Logic                                            |   |
|   +-----------------------------+-----------------------------+   |
|                                 |                                 |
|   +-----------------------------v-----------------------------+   |
|   | influx3xtend-core.jar                                     |   |
|   |   - Influx3ClientAdapter  --> InfluxDB 3 Server (Flight SQL) |   |
|   |   - Embedded DuckDB (JNI) --> S3 Parquet Files            |   |
|   |   - ArrowMergeEngine                                      |   |
|   +-----------------------------------------------------------+   |
+-------------------------------------------------------------------+
```

### 5.2 独立 Gateway 部署模式 (Standalone Kubernetes Gateway Architecture)
适用于多语言（Python/Go/Node.js）混合架构或云原生环境，中间件作为独立的弹性 Pod 运行。

```mermaid
graph LR
    subgraph Clients ["多语言业务端"]
        PyClient["Python Data Science"]
        GoClient["Go Microservice"]
        NodeClient["Node.js Web Front"]
    end

    subgraph IngressLayer ["云原生入口"]
        NLB["Network Load Balancer (NLB)"]
    end

    subgraph K8sCluster ["Influx3xtend Kubernetes Pod Cluster"]
        Pod1["Gateway Pod 1 (Virtual Threads)"]
        Pod2["Gateway Pod 2 (Virtual Threads)"]
        Pod3["Gateway Pod N (Virtual Threads)"]
    end

    subgraph DataStores ["后端存储与引擎"]
        InfluxDB3[("InfluxDB 3 Cluster (Realtime)")]
        ObjectStore[("S3 / OSS / MinIO (Parquet)")]
    end

    PyClient --> NLB
    GoClient --> NLB
    NodeClient --> NLB

    NLB --> Pod1
    NLB --> Pod2
    NLB --> Pod3

    Pod1 --> InfluxDB3
    Pod1 --> ObjectStore
    Pod2 --> InfluxDB3
    Pod2 --> ObjectStore
    Pod3 --> InfluxDB3
    Pod3 --> ObjectStore
```

---

## 6. 技术栈选型矩阵与基准预期

| 模块组件 | 技术选型 | 版本 | 架构作用与选型优势 |
| :--- | :--- | :--- | :--- |
| **JDK 运行时** | OpenJDK / Temurin | `21 LTS` | 引入 Virtual Threads (Loom)，网络并发能力提升 10x；原生支持 Record 与 FFM 堆外内存访问 |
| **实时节点客户端** | `com.influxdb:influxdb3-java` | `1.10.0` | InfluxData 官方 SDK，基于 Arrow Flight SQL 原生支持高吞吐点位写入与 SQL 查询 |
| **历史向量化引擎** | `org.duckdb:duckdb_jdbc` | `1.1.3` | 嵌入式 JNI 分析引擎，直连 S3/OSS Parquet，向量化执行速度超越传统 SQL 引擎 |
| **零拷贝内存标准** | `org.apache.arrow:arrow-vector` | `18.1.0` | 统一内存列式结构，实现 DuckDB 与 InfluxDB 3 返回结果的零拷贝交换 |
| **网关框架** | Spring Boot | `3.3.4` | 结合 `spring.threads.virtual.enabled=true` 提供高效 REST 网关 |

### 性能预期目标 (SLA Targets)
- **并发吞吐**：独立网关单 Pod (4C8G) 支持 **15,000+ QPS** 的轻量聚合查询。
- **查询延迟**：实时/历史混合范围查询 P99 延迟 控制在 **< 50ms**。
- **GC 影响**：通过 Apache Arrow 堆外内存管理，JVM 垃圾回收暂停时间（GC Pause）控制在 **< 5ms**。
