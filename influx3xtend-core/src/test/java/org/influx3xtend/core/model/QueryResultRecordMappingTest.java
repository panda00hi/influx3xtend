package org.influx3xtend.core.model;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class QueryResultRecordMappingTest {

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

    public record SensorDataRecord(
            Instant timestamp,
            String device_id,
            double temperature
    ) {}

    @Test
    public void testJava21RecordMapping() {
        List<Field> fields = List.of(
                Field.nullable("timestamp", new ArrowType.Int(64, true)),
                Field.nullable("device_id", new ArrowType.Utf8()),
                Field.nullable("temperature", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
        );

        Schema schema = new Schema(fields);
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            root.allocateNew();

            BigIntVector tsVec = (BigIntVector) root.getVector("timestamp");
            VarCharVector devVec = (VarCharVector) root.getVector("device_id");
            Float8Vector tempVec = (Float8Vector) root.getVector("temperature");

            long nowMs = System.currentTimeMillis();
            tsVec.setSafe(0, nowMs);
            devVec.setSafe(0, "DEV_001".getBytes(StandardCharsets.UTF_8));
            tempVec.setSafe(0, 36.5);

            root.setRowCount(1);

            QueryResult result = new QueryResult(root, 10, 1, 0);
            List<SensorDataRecord> list = result.toPojoList(SensorDataRecord.class);

            assertEquals(1, list.size());
            SensorDataRecord record = list.get(0);
            assertEquals("DEV_001", record.device_id());
            assertEquals(36.5, record.temperature(), 0.001);
            assertNotNull(record.timestamp());
        }
    }

    @Test
    public void testQueryResultNullRootSafety() {
        try (QueryResult emptyRes = new QueryResult(null, 5)) {
            assertEquals(0, emptyRes.getRowCount());
            assertTrue(emptyRes.toMapList().isEmpty());
            assertTrue(emptyRes.toPojoList(Object.class).isEmpty());
        }
    }
}
