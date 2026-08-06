package org.influx3xtend.core.engine;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.influx3xtend.core.model.QueryRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ArrowMergeEngineTest {

    private BufferAllocator allocator;

    @BeforeEach
    public void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
    }

    @AfterEach
    public void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    public void testDynamicSchemaUnionMerge() {
        // Schema 1 (历史数据：包含 time 和 vibration 列)
        Schema histSchema = new Schema(List.of(
                Field.nullable("time", new ArrowType.Int(64, true)),
                Field.nullable("vibration", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
        ));

        // Schema 2 (实时数据：包含 time 和 torque 列，展示非硬编码求并集)
        Schema realSchema = new Schema(List.of(
                Field.nullable("time", new ArrowType.Int(64, true)),
                Field.nullable("torque", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
        ));

        try (VectorSchemaRoot histRoot = VectorSchemaRoot.create(histSchema, allocator);
             VectorSchemaRoot realRoot = VectorSchemaRoot.create(realSchema, allocator)) {

            histRoot.allocateNew();
            BigIntVector histTime = (BigIntVector) histRoot.getVector("time");
            Float8Vector histVib = (Float8Vector) histRoot.getVector("vibration");
            histTime.setSafe(0, 1000L);
            histVib.setSafe(0, 0.25);
            histRoot.setRowCount(1);

            realRoot.allocateNew();
            BigIntVector realTime = (BigIntVector) realRoot.getVector("time");
            Float8Vector realTorque = (Float8Vector) realRoot.getVector("torque");
            realTime.setSafe(0, 2000L);
            realTorque.setSafe(0, 305.5);
            realRoot.setRowCount(1);

            QueryRequest dummyRequest = new QueryRequest(null, "generic_table", null, null, null, null, null, null, null, 10);

            try (VectorSchemaRoot mergedRoot = ArrowMergeEngine.merge(realRoot, histRoot, dummyRequest)) {
                assertThat(mergedRoot).isNotNull();
                assertThat(mergedRoot.getRowCount()).isEqualTo(2);

                // 验证动态并集 Schema 包含 time, vibration, torque 三列
                assertThat(mergedRoot.getSchema().getFields()).hasSize(3);
                assertThat(mergedRoot.getVector("time")).isNotNull();
                assertThat(mergedRoot.getVector("vibration")).isNotNull();
                assertThat(mergedRoot.getVector("torque")).isNotNull();

                // 验证缺失列安全为 Null
                Float8Vector vibVector = (Float8Vector) mergedRoot.getVector("vibration");
                Float8Vector torqueVector = (Float8Vector) mergedRoot.getVector("torque");

                assertThat(vibVector.get(0)).isEqualTo(0.25);
                assertThat(torqueVector.isNull(0)).isTrue(); // 历史行无 torque，为 Null

                assertThat(vibVector.isNull(1)).isTrue(); // 实时行无 vibration，为 Null
                assertThat(torqueVector.get(1)).isEqualTo(305.5);
            }
        }
    }

    @Test
    public void testHeterogeneousTimeVectorMerge() {
        // Schema 1 (实时数据：TimeStampMilliVector)
        Schema realSchema = new Schema(List.of(
                Field.nullable("time", new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MILLISECOND, null)),
                Field.nullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
        ));

        // Schema 2 (历史数据：BigIntVector)
        Schema histSchema = new Schema(List.of(
                Field.nullable("time", new ArrowType.Int(64, true)),
                Field.nullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
        ));

        try (VectorSchemaRoot realRoot = VectorSchemaRoot.create(realSchema, allocator);
             VectorSchemaRoot histRoot = VectorSchemaRoot.create(histSchema, allocator)) {

            realRoot.allocateNew();
            TimeStampMilliVector realTime = (TimeStampMilliVector) realRoot.getVector("time");
            Float8Vector realVal = (Float8Vector) realRoot.getVector("value");
            realTime.setSafe(0, 1600000000000L);
            realVal.setSafe(0, 99.5);
            realRoot.setRowCount(1);

            histRoot.allocateNew();
            BigIntVector histTime = (BigIntVector) histRoot.getVector("time");
            Float8Vector histVal = (Float8Vector) histRoot.getVector("value");
            histTime.setSafe(0, 1500000000000L);
            histVal.setSafe(0, 88.0);
            histRoot.setRowCount(1);

            QueryRequest dummyRequest = new QueryRequest(null, "cpu", null, null, null, null, null, null, null, 10);

            try (VectorSchemaRoot mergedRoot = ArrowMergeEngine.merge(realRoot, histRoot, dummyRequest)) {
                assertThat(mergedRoot).isNotNull();
                assertThat(mergedRoot.getRowCount()).isEqualTo(2);
                assertThat(mergedRoot.getVector("time")).isNotNull();
            }
        }
    }
}
