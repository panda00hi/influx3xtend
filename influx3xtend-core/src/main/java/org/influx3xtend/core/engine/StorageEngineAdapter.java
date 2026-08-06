package org.influx3xtend.core.engine;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.model.QueryRequest;

/**
 * 存储与计算引擎统一适配器抽象 SPI 接口 (StorageEngineAdapter)
 * 遵循依赖倒置原则 (DIP)，使 InfluxDB 3 Flight 引擎与 DuckDB Parquet 引擎遵循统一的响应契约
 */
public interface StorageEngineAdapter extends AutoCloseable {

    /**
     * 执行列裁剪与条件下推的向量化查询，返回堆外 VectorSchemaRoot 结果切片
     *
     * @param request 标准查询请求对象
     * @return 包含 Arrow 列式数据的 VectorSchemaRoot（可能为 null）
     */
    VectorSchemaRoot executeQuery(QueryRequest request);
}
