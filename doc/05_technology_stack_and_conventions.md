# 技术栈选型、Java 21 特性运用与编码规范

## 1. 核心技术栈版本选型清单

所有依赖版本统一在根 POM 中进行严格的版本声明与锁定，禁止子模块自行随意引入冲突的版本：

| 核心组件 | 推荐版本 | 选型理由 |
| :--- | :--- | :--- |
| **JDK Base** | Java 21 LTS | 现代 LTS 版本，全面启用虚拟线程 (Virtual Threads) 和 Foreign Function & Memory (FFM) API |
| **Build Tool** | Apache Maven 3.9+ | 规范化的 Maven 多模块依赖管理与 Lifecycle 构建 |
| **InfluxDB 3 SDK** | `com.influxdb:influxdb3-java` `1.10.0` | InfluxData 官方专为 InfluxDB 3.x 打造的 Java 客户端 SDK |
| **DuckDB Driver** | `org.duckdb:duckdb_jdbc` `1.1.3`+ | 嵌入式 DuckDB (JNI) 原生向量化列式 SQL 引擎 |
| **Apache Arrow** | `org.apache.arrow:arrow-vector` `18.1.0` / `19.0.0` | 内存中零拷贝向量数据存储格式标准 |
| **Arrow Flight** | `org.apache.arrow:arrow-flight-sql` `18.1.0` | 高性能 gRPC 向量流传输协议 |
| **Server Framework** | Spring Boot `3.3.x` | 网关服务的 Web 与 Bean 自动装配框架 |
| **S3 SDK** | `software.amazon.awssdk:s3` `2.25.x`+ | AWS / MinIO / OSS S3 对象存储 Java SDK |
| **Test Framework** | JUnit 5 + AssertJ + Mockito | 现代化单元测试与断言机制 |

---

## 2. Java 21 LTS 关键特性应用规范

### 2.1 虚拟线程 (Virtual Threads - Project Loom)
- **应用场景**：在 `influx3xtend-server` 网关及异步网络 I/O 查询中，使用虚拟线程池替换传统的 ExecutorService 线程池。
- **编码约定**：
  ```java
  // 使用 Java 21 原生 VirtualThreadPerTaskExecutor 句柄
  try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ArrowRecordBatchStream> realFuture = executor.submit(() -> queryInflux3());
      Future<ArrowRecordBatchStream> histFuture = executor.submit(() -> queryDuckDB());
      // 等待并发路由查询完成
  }
  ```
- **禁忌**：避免在 Virtual Thread 中持有包含 `synchronized` 块的长耗时 I/O 操作（可能引起 Pinning），推荐采用 `java.util.concurrent.locks.ReentrantLock`。

### 2.2 Record 类 (Java Record)
- **应用场景**：所有内部 DTO、路由上下文 (RoutingContext)、数据块元数据 (BatchMetadata) 及 API 配置必须优先定义为不可变 `record`。
- **示例**：
  ```java
  public record TimeRange(Instant start, Instant end) {
      public TimeRange {
          if (start != null && end != null && start.isAfter(end)) {
              throw new IllegalArgumentException("Start time must be before end time");
          }
      }
  }
  ```

### 2.5 统一导包规范 (Import Declarations Convention)
- **头部集中声明**：所有依赖包与类路径，必须统一书写在 Java 源文件的头部 `import` 区域；
- **严禁代码内联**：禁止在 Java 方法签名、变量声明或 `new` 表达式中使用内联全限定包路径（如 `org.apache.arrow.vector.types.pojo.Schema`），以提高代码的可读性与简洁性。

---

## 3. 代码开发与架构设计原则

1. **SOLID 原则**：
   - **单一职责**：`Router` 仅负责时间拆分与调度决策；`MergeEngine` 仅负责 Arrow 内存流的操作；`DuckDBClient` 仅负责 JNI SQL 交互。
   - **开闭原则**：对象存储适配层通过 `ObjectStorageScanner` 接口抽象，允许未来平滑扩展 S3、OSS、HDFS 或本地 File System。
2. **堆外内存安全防范**：
   - 凡涉及 `VectorSchemaRoot`、`BufferAllocator` 或 `DuckDBVector` 的对象，方法必须明确其“所有权 (Ownership)”。
   - 谁创建，谁负责 `close()`；或在方法 JavaDoc 中显式标注 `@MustBeClosed`。
3. **零日志垃圾 (Zero Noise Logging)**：
   - 生产环境禁止使用 `System.out.println`。
   - 调试使用 SLF4J + Logback，热点循环 (Merge Loop) 内禁止频繁拼接字符串打 Log。
