package org.influx3xtend.core.model;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.exception.QueryExecutionException;
import org.influx3xtend.core.util.TimestampUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一查询结果 (QueryResult)
 * 包装 Apache Arrow VectorSchemaRoot，提供堆外内存安全释放、POJO / Java 21 Record 忽略大小写自适应映射、Map/JSON 导出与可观测性元数据
 */
public class QueryResult implements AutoCloseable {

    private final VectorSchemaRoot root;
    private final long executionTimeMs;
    private final int realtimeRows;
    private final int historicalRows;

    public QueryResult(VectorSchemaRoot root, long executionTimeMs, int realtimeRows, int historicalRows) {
        this.root = root;
        this.executionTimeMs = executionTimeMs;
        this.realtimeRows = realtimeRows;
        this.historicalRows = historicalRows;
    }

    public QueryResult(VectorSchemaRoot root, int rowCount) {
        this(root, 0L, rowCount, 0);
    }

    public VectorSchemaRoot getRoot() {
        return root;
    }

    public int getRowCount() {
        return root != null ? root.getRowCount() : 0;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public int getRealtimeRows() {
        return realtimeRows;
    }

    public int getHistoricalRows() {
        return historicalRows;
    }

    /**
     * 将 Arrow VectorSchemaRoot 导出为轻量级 Map 字典列表
     */
    public List<Map<String, Object>> toMapList() {
        if (root == null || root.getRowCount() == 0) {
            return List.of();
        }

        List<Map<String, Object>> resultList = new ArrayList<>(root.getRowCount());
        List<FieldVector> vectors = root.getFieldVectors();

        for (int rowIndex = 0; rowIndex < root.getRowCount(); rowIndex++) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (FieldVector vector : vectors) {
                String fieldName = vector.getField().getName();
                Object val = vector.isNull(rowIndex) ? null : getVectorValue(vector, rowIndex);
                rowMap.put(fieldName, val);
            }
            resultList.add(rowMap);
        }

        return resultList;
    }

    /**
     * 将 Arrow VectorSchemaRoot 中的行转换为指定类型的 Java POJO / Java 21 Record 列表
     * 强力支持列名大小写不敏感匹配 (Case-Insensitive Column Alignment) 与 Record 规范构造函数实例化
     */
    public <T> List<T> toPojoList(Class<T> clazz) {
        if (root == null || root.getRowCount() == 0) {
            return List.of();
        }

        if (clazz.isRecord()) {
            return toRecordList(clazz);
        }

        List<T> resultList = new ArrayList<>(root.getRowCount());
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);

            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            FieldVector[] mappedVectors = new FieldVector[fields.length];

            for (int i = 0; i < fields.length; i++) {
                fields[i].setAccessible(true);
                Column colAnn = fields[i].getAnnotation(Column.class);
                String columnName = (colAnn != null && !colAnn.name().isBlank()) ? colAnn.name() : fields[i].getName();
                mappedVectors[i] = findVector(columnName);
            }

            for (int rowIndex = 0; rowIndex < root.getRowCount(); rowIndex++) {
                T instance = constructor.newInstance();
                for (int i = 0; i < fields.length; i++) {
                    FieldVector vector = mappedVectors[i];
                    if (vector != null && !vector.isNull(rowIndex)) {
                        Object val = getVectorValue(vector, rowIndex);
                        if (val != null) {
                            setFieldValue(fields[i], instance, val);
                        }
                    }
                }
                resultList.add(instance);
            }
        } catch (Exception e) {
            throw new QueryExecutionException("Failed to map Arrow VectorSchemaRoot to POJO " + clazz.getName(), e);
        }

        return resultList;
    }

    private static final Map<Class<?>, RecordMetadata<?>> RECORD_METADATA_CACHE = new ConcurrentHashMap<>();

    private record RecordMetadata<T>(
            Constructor<T> constructor,
            RecordComponent[] components,
            String[] columnNames
    ) {}

    /**
     * 原生支持 Java 21 Record 类型转换映射 (具备高并发 ConcurrentHashMap 静态元数据缓存)
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> toRecordList(Class<T> clazz) {
        RecordMetadata<T> meta = (RecordMetadata<T>) RECORD_METADATA_CACHE.computeIfAbsent(clazz, c -> {
            try {
                RecordComponent[] comps = c.getRecordComponents();
                Class<?>[] paramTypes = new Class<?>[comps.length];
                String[] colNames = new String[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    paramTypes[i] = comps[i].getType();
                    Column colAnn = comps[i].getAnnotation(Column.class);
                    colNames[i] = (colAnn != null && !colAnn.name().isBlank()) ? colAnn.name() : comps[i].getName();
                }
                Constructor<T> ctor = (Constructor<T>) c.getDeclaredConstructor(paramTypes);
                ctor.setAccessible(true);
                return new RecordMetadata<>(ctor, comps, colNames);
            } catch (Exception e) {
                throw new QueryExecutionException("Failed to inspect Record constructor metadata for " + c.getName(), e);
            }
        });

        RecordComponent[] components = meta.components();
        FieldVector[] mappedVectors = new FieldVector[components.length];
        for (int i = 0; i < components.length; i++) {
            mappedVectors[i] = findVector(meta.columnNames()[i]);
        }

        List<T> resultList = new ArrayList<>(root.getRowCount());
        try {
            Constructor<T> constructor = meta.constructor();
            int rowCount = root.getRowCount();

            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    FieldVector vector = mappedVectors[i];
                    if (vector != null && !vector.isNull(rowIndex)) {
                        Object val = getVectorValue(vector, rowIndex);
                        args[i] = convertValueType(val, components[i].getType());
                    } else {
                        args[i] = getDefaultValueForPrimitive(components[i].getType());
                    }
                }
                resultList.add(constructor.newInstance(args));
            }
        } catch (Exception e) {
            throw new QueryExecutionException("Failed to map Arrow VectorSchemaRoot to Record " + clazz.getName(), e);
        }

        return resultList;
    }

    private FieldVector findVector(String columnName) {
        FieldVector vector = root.getVector(columnName);
        if (vector != null) {
            return vector;
        }
        String normalizedTarget = normalizeName(columnName);
        for (FieldVector v : root.getFieldVectors()) {
            String vName = v.getField().getName();
            if (vName.equalsIgnoreCase(columnName) || normalizeName(vName).equalsIgnoreCase(normalizedTarget)) {
                return v;
            }
        }
        return null;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.replace("_", "").toLowerCase();
    }

    private Object getVectorValue(FieldVector vector, int index) {
        if (vector instanceof TimeStampVector tsVector) {
            long rawVal = tsVector.get(index);
            long epochMs = TimestampUtils.toEpochMillis(rawVal, tsVector);
            return Instant.ofEpochMilli(epochMs);
        } else if (vector instanceof BigIntVector bigIntVector) {
            return bigIntVector.get(index);
        } else if (vector instanceof IntVector intVector) {
            return intVector.get(index);
        } else if (vector instanceof SmallIntVector smallIntVector) {
            return (int) smallIntVector.get(index);
        } else if (vector instanceof TinyIntVector tinyIntVector) {
            return (int) tinyIntVector.get(index);
        } else if (vector instanceof Float8Vector float8Vector) {
            return float8Vector.get(index);
        } else if (vector instanceof Float4Vector float4Vector) {
            return float4Vector.get(index);
        } else if (vector instanceof VarCharVector varCharVector) {
            byte[] bytes = varCharVector.get(index);
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
        } else if (vector instanceof BitVector bitVector) {
            return bitVector.get(index) == 1;
        } else {
            Object obj = vector.getObject(index);
            return obj != null ? obj.toString() : null;
        }
    }

    private void setFieldValue(java.lang.reflect.Field field, Object instance, Object val) throws IllegalAccessException {
        field.set(instance, convertValueType(val, field.getType()));
    }

    private Object convertValueType(Object val, Class<?> targetType) {
        if (val == null) {
            return getDefaultValueForPrimitive(targetType);
        }
        if (targetType == Instant.class) {
            if (val instanceof Instant inst) return inst;
            if (val instanceof Long lVal) return parseInstantFromRawLong(lVal);
            try {
                return Instant.parse(val.toString());
            } catch (Exception ignored) {
            }
        } else if (targetType == Double.class || targetType == double.class) {
            return val instanceof Number n ? n.doubleValue() : Double.parseDouble(val.toString());
        } else if (targetType == Long.class || targetType == long.class) {
            return val instanceof Number n ? n.longValue() : Long.parseLong(val.toString());
        } else if (targetType == Integer.class || targetType == int.class) {
            return val instanceof Number n ? n.intValue() : Integer.parseInt(val.toString());
        } else if (targetType == String.class) {
            return val.toString();
        }
        return val;
    }

    private Object getDefaultValueForPrimitive(Class<?> type) {
        if (type == double.class) return 0.0d;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == float.class) return 0.0f;
        return null;
    }

    private static Instant parseInstantFromRawLong(long lVal) {
        return TimestampUtils.toInstant(lVal, 0);
    }

    @Override
    public void close() {
        if (root != null) {
            root.close();
        }
    }
}
