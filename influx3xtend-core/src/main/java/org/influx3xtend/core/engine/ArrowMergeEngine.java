package org.influx3xtend.core.engine;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.SortOrder;
import org.influx3xtend.core.util.TimestampUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apache Arrow 零拷贝合并与二次聚合引擎 (ArrowMergeEngine)
 * 实现：完全通用解耦，基于动态 Schema 求并集 (Dynamic Schema Union) 的堆外 Vector 安全归并
 */
public class ArrowMergeEngine {

    private static final Logger log = LoggerFactory.getLogger(ArrowMergeEngine.class);

    /**
     * 极速流式拼接两个同序 VectorSchemaRoot（专用于 Flight SQL 结果集流式 Chunk 极速追加）
     */
    public static VectorSchemaRoot concat(VectorSchemaRoot olderRoot, VectorSchemaRoot newerRoot) {
        if (olderRoot == null || olderRoot.getRowCount() == 0) return newerRoot;
        if (newerRoot == null || newerRoot.getRowCount() == 0) return olderRoot;

        return concatAll(List.of(olderRoot, newerRoot));
    }

    /**
     * O(N) 线性时间单次高效拼接多个 VectorSchemaRoot（专用于 Flight SQL 结果集多 Batch 极速合并，彻底消除 O(N^2) 内存重新分配）
     */
    public static VectorSchemaRoot concatAll(List<VectorSchemaRoot> roots) {
        if (roots == null || roots.isEmpty()) return null;

        List<VectorSchemaRoot> validRoots = roots.stream()
                .filter(r -> r != null && r.getRowCount() > 0)
                .toList();

        if (validRoots.isEmpty()) return null;
        if (validRoots.size() == 1) return validRoots.get(0);

        Schema mergedSchema = validRoots.get(0).getSchema();
        for (int i = 1; i < validRoots.size(); i++) {
            mergedSchema = unionSchema(mergedSchema, validRoots.get(i).getSchema());
        }

        BufferAllocator allocator = validRoots.get(0).getFieldVectors().get(0).getAllocator();
        VectorSchemaRoot mergedRoot = VectorSchemaRoot.create(mergedSchema, allocator);

        try {
            int totalRows = validRoots.stream().mapToInt(VectorSchemaRoot::getRowCount).sum();
            mergedRoot.allocateNew();

            int currentOffset = 0;
            for (VectorSchemaRoot root : validRoots) {
                int rowCount = root.getRowCount();
                for (Field field : mergedSchema.getFields()) {
                    String colName = field.getName();
                    FieldVector destVector = mergedRoot.getVector(colName);
                    FieldVector srcVector = root.getVector(colName);
                    if (srcVector != null) {
                        for (int row = 0; row < rowCount; row++) {
                            safeCopyVectorRow(destVector, currentOffset + row, srcVector, row);
                        }
                    } else {
                        for (int row = 0; row < rowCount; row++) {
                            destVector.setNull(currentOffset + row);
                        }
                    }
                }
                currentOffset += rowCount;
            }

            mergedRoot.setRowCount(totalRows);
            return mergedRoot;
        } catch (Exception e) {
            try { mergedRoot.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * 将较早时间切片 olderRoot 与较晚时间切片 newerRoot 进行泛型动态归并并确保全局绝对时间有序
     *
     * @param olderRoot 较早时间切片（如 DuckDB 历史数据）
     * @param newerRoot 较晚时间切片（如 InfluxDB 3 实时数据）
     * @param request   查询请求元数据
     * @return 合并后的 VectorSchemaRoot
     */
    public static VectorSchemaRoot merge(VectorSchemaRoot olderRoot, VectorSchemaRoot newerRoot, QueryRequest request) {
        if (olderRoot == null || olderRoot.getRowCount() == 0) {
            ensureTimeSorted(newerRoot, request);
            return newerRoot;
        }
        if (newerRoot == null || newerRoot.getRowCount() == 0) {
            ensureTimeSorted(olderRoot, request);
            return olderRoot;
        }

        log.debug("Dynamically merging Older Arrow Batch ({} rows) with Newer Arrow Batch ({} rows)",
                olderRoot.getRowCount(), newerRoot.getRowCount());

        Schema mergedSchema = unionSchema(olderRoot.getSchema(), newerRoot.getSchema());
        BufferAllocator allocator = olderRoot.getFieldVectors().get(0).getAllocator();

        boolean isDesc = false;
        if (request != null && request.orderByList() != null && !request.orderByList().isEmpty()) {
            isDesc = request.orderByList().get(0).order() == SortOrder.DESC;
        }

        VectorSchemaRoot firstRoot = isDesc ? newerRoot : olderRoot;
        VectorSchemaRoot secondRoot = isDesc ? olderRoot : newerRoot;

        VectorSchemaRoot mergedRoot = VectorSchemaRoot.create(mergedSchema, allocator);
        try {
            mergedRoot.allocateNew();

            int firstRows = firstRoot.getRowCount();
            int secondRows = secondRoot.getRowCount();
            int totalRows = 0;

            // 1. 拷贝首批次切片数据 (First Batch Step)
            for (Field field : mergedSchema.getFields()) {
                String colName = field.getName();
                FieldVector destVector = mergedRoot.getVector(colName);
                FieldVector srcVector = firstRoot.getVector(colName);

                if (srcVector != null) {
                    for (int row = 0; row < firstRows; row++) {
                        safeCopyVectorRow(destVector, row, srcVector, row);
                    }
                } else {
                    for (int row = 0; row < firstRows; row++) {
                        destVector.setNull(row);
                    }
                }
            }
            totalRows += firstRows;

            // 2. 拷贝第二批次切片数据 (Second Batch Step)
            for (Field field : mergedSchema.getFields()) {
                String colName = field.getName();
                FieldVector destVector = mergedRoot.getVector(colName);
                FieldVector srcVector = secondRoot.getVector(colName);

                if (srcVector != null) {
                    for (int row = 0; row < secondRows; row++) {
                        safeCopyVectorRow(destVector, totalRows + row, srcVector, row);
                    }
                } else {
                    for (int row = 0; row < secondRows; row++) {
                        destVector.setNull(totalRows + row);
                    }
                }
            }
            totalRows += secondRows;

            mergedRoot.setRowCount(totalRows);

            // 3. 对归并后的向量数组按时间轴列 (timeColumn) 进行安全排序校验
            ensureTimeSorted(mergedRoot, request);

            return mergedRoot;
        } catch (Exception e) {
            try {
                mergedRoot.close();
            } catch (Exception ignored) {}
            throw e;
        }
    }

    private static void safeCopyVectorRow(FieldVector destVector, int destRow, FieldVector srcVector, int srcRow) {
        if (srcVector == null || srcVector.isNull(srcRow)) {
            destVector.setNull(destRow);
            return;
        }

        // 极速快道：当 Vector 目标与源类型一致时（占绝大多数），直通无异常捕获
        if (destVector.getClass() == srcVector.getClass()) {
            destVector.copyFromSafe(srcRow, destRow, srcVector);
            return;
        }

        try {
            destVector.copyFromSafe(srcRow, destRow, srcVector);
        } catch (Exception e) {
            // 异构 Vector 类型安全转换降级（如 TimeStampVector vs BigIntVector）
            if (destVector instanceof TimeStampVector destTs && srcVector instanceof BigIntVector srcBigInt) {
                long rawVal = srcBigInt.get(srcRow);
                long epochMs = TimestampUtils.toEpochMillis(rawVal, null);
                setTimestampVectorValue(destTs, destRow, epochMs);
            } else if (destVector instanceof BigIntVector destBigInt && srcVector instanceof TimeStampVector srcTs) {
                long rawVal = srcTs.get(srcRow);
                long epochMs = TimestampUtils.toEpochMillis(rawVal, srcTs);
                destBigInt.setSafe(destRow, epochMs);
            } else if (destVector instanceof TimeStampVector destTs && srcVector instanceof TimeStampVector srcTs) {
                long rawVal = srcTs.get(srcRow);
                long epochMs = TimestampUtils.toEpochMillis(rawVal, srcTs);
                setTimestampVectorValue(destTs, destRow, epochMs);
            } else {
                throw e;
            }
        }
    }

    private static void setTimestampVectorValue(TimeStampVector destTs, int destRow, long epochMs) {
        if (destTs instanceof TimeStampMilliVector tsMilli) {
            tsMilli.setSafe(destRow, epochMs);
        } else if (destTs instanceof TimeStampMicroVector tsMicro) {
            tsMicro.setSafe(destRow, epochMs * 1000L);
        } else if (destTs instanceof TimeStampNanoVector tsNano) {
            tsNano.setSafe(destRow, epochMs * 1_000_000L);
        } else if (destTs instanceof TimeStampSecVector tsSec) {
            tsSec.setSafe(destRow, epochMs / 1000L);
        }
    }

    /**
     * 动态计算两个 Schema 的并集 (Union Schema)
     * 保留首个 Schema 的列顺序，并追加第二个 Schema 中的新增列
     */
    public static Schema unionSchema(Schema s1, Schema s2) {
        if (s1 == null) return s2;
        if (s2 == null) return s1;

        Map<String, Field> fieldMap = new LinkedHashMap<>();
        for (Field f : s1.getFields()) {
            fieldMap.put(f.getName(), f);
        }
        for (Field f : s2.getFields()) {
            fieldMap.putIfAbsent(f.getName(), f);
        }

        List<Field> mergedFields = new ArrayList<>(fieldMap.values());
        return new Schema(mergedFields);
    }

    /**
     * 校验并确保 VectorSchemaRoot 中的行数据按时间轴列 (timeColumn) 严格单调有序
     */
    public static void ensureTimeSorted(VectorSchemaRoot root, QueryRequest request) {
        if (root == null || root.getRowCount() <= 1) {
            return;
        }

        String timeCol = (request != null && request.timeColumn() != null) ? request.timeColumn() : Influx3xtendConstants.COLUMN_TIME;
        FieldVector timeVector = root.getVector(timeCol);
        if (timeVector == null) {
            return;
        }

        int rowCount = root.getRowCount();
        long[] timestamps = new long[rowCount];
        if (timeVector instanceof TimeStampVector tsVector) {
            for (int i = 0; i < rowCount; i++) {
                timestamps[i] = tsVector.isNull(i) ? Long.MIN_VALUE : TimestampUtils.toEpochMillis(tsVector.get(i), tsVector);
            }
        } else if (timeVector instanceof BigIntVector bigIntVector) {
            for (int i = 0; i < rowCount; i++) {
                timestamps[i] = bigIntVector.isNull(i) ? Long.MIN_VALUE : TimestampUtils.toEpochMillis(bigIntVector.get(i), null);
            }
        } else {
            return;
        }

        boolean isDesc = false;
        if (request != null && request.orderByList() != null && !request.orderByList().isEmpty()) {
            isDesc = request.orderByList().get(0).order() == SortOrder.DESC;
        }

        // 优先进行 O(N) 单调递增/递减快速扫描
        boolean alreadySorted = true;
        for (int i = 0; i < rowCount - 1; i++) {
            if (!isDesc && timestamps[i] > timestamps[i + 1]) {
                alreadySorted = false;
                break;
            } else if (isDesc && timestamps[i] < timestamps[i + 1]) {
                alreadySorted = false;
                break;
            }
        }

        if (alreadySorted) {
            return; // 零拷贝极速通过
        }

        log.debug("Out-of-order rows detected in Arrow batch ({} rows). Performing zero-GC in-place time-sorting...", rowCount);

        int[] indices = new int[rowCount];
        for (int i = 0; i < rowCount; i++) {
            indices[i] = i;
        }

        quickSortIndices(indices, timestamps, 0, rowCount - 1, isDesc);
        reorderRoot(root, indices);
    }

    private static void quickSortIndices(int[] indices, long[] timestamps, int left, int right, boolean desc) {
        if (left >= right) return;
        int pivotIndex = left + (right - left) / 2;
        long pivotValue = timestamps[indices[pivotIndex]];
        int i = left, j = right;
        while (i <= j) {
            while (desc ? timestamps[indices[i]] > pivotValue : timestamps[indices[i]] < pivotValue) i++;
            while (desc ? timestamps[indices[j]] < pivotValue : timestamps[indices[j]] > pivotValue) j--;
            if (i <= j) {
                int temp = indices[i];
                indices[i] = indices[j];
                indices[j] = temp;
                i++;
                j--;
            }
        }
        if (left < j) quickSortIndices(indices, timestamps, left, j, desc);
        if (i < right) quickSortIndices(indices, timestamps, i, right, desc);
    }

    private static void reorderRoot(VectorSchemaRoot root, int[] indices) {
        int rowCount = root.getRowCount();
        BufferAllocator allocator = root.getFieldVectors().get(0).getAllocator();
        VectorSchemaRoot tempRoot = VectorSchemaRoot.create(root.getSchema(), allocator);
        try {
            tempRoot.allocateNew();
            for (Field field : root.getSchema().getFields()) {
                String colName = field.getName();
                FieldVector srcVec = root.getVector(colName);
                FieldVector destVec = tempRoot.getVector(colName);
                if (srcVec != null && destVec != null) {
                    for (int newRow = 0; newRow < rowCount; newRow++) {
                        safeCopyVectorRow(destVec, newRow, srcVec, indices[newRow]);
                    }
                }
            }
            tempRoot.setRowCount(rowCount);

            // 使用 Apache Arrow 零拷贝 TransferPair 将重排好的 Vector 转移至原 root
            for (Field field : root.getSchema().getFields()) {
                String colName = field.getName();
                FieldVector srcVec = root.getVector(colName);
                FieldVector tempVec = tempRoot.getVector(colName);
                if (srcVec != null && tempVec != null) {
                    srcVec.clear();
                    tempVec.makeTransferPair(srcVec).transfer();
                }
            }
        } finally {
            try { tempRoot.close(); } catch (Exception ignored) {}
        }
    }
}
