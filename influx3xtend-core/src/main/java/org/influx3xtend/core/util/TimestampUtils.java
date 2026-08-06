package org.influx3xtend.core.util;

import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.influx3xtend.core.constants.Influx3xtendConstants;

import java.time.Instant;

/**
 * 集中式时间戳与 Arrow 时间类型归一化转换工具类 (TimestampUtils)
 */
public final class TimestampUtils {

    private TimestampUtils() {}

    /**
     * 将 Arrow 时间向量或原始 Long 时间戳安全归一化为 Epoch 毫秒数 (Epoch Milliseconds)
     */
    public static long toEpochMillis(long rawTimestamp, TimeStampVector tsVector) {
        if (tsVector != null && tsVector.getField().getType() instanceof ArrowType.Timestamp tsType) {
            switch (tsType.getUnit()) {
                case NANOSECOND:
                    return rawTimestamp / Influx3xtendConstants.NS_TO_MS_DIVISOR;
                case MICROSECOND:
                    return rawTimestamp / Influx3xtendConstants.US_TO_MS_DIVISOR;
                case MILLISECOND:
                    return rawTimestamp;
                case SECOND:
                    return rawTimestamp * 1000L;
            }
        }

        // 根据数值数量级自动启发式判断 (Ns / Us / Ms)
        if (rawTimestamp > Influx3xtendConstants.NANO_TIMESTAMP_THRESHOLD) {
            return rawTimestamp / Influx3xtendConstants.NS_TO_MS_DIVISOR;
        }
        if (rawTimestamp > Influx3xtendConstants.MICRO_TIMESTAMP_THRESHOLD) {
            return rawTimestamp / Influx3xtendConstants.US_TO_MS_DIVISOR;
        }
        return rawTimestamp;
    }

    /**
     * 将 Raw 对象解析为 Instant
     */
    public static Instant toInstant(Object rawObj, int fallbackRowIndex) {
        if (rawObj == null) {
            return Instant.ofEpochMilli(System.currentTimeMillis() - (fallbackRowIndex * 1000L));
        }
        if (rawObj instanceof Instant inst) {
            return inst;
        }
        if (rawObj instanceof Number num) {
            long ms = toEpochMillis(num.longValue(), null);
            return Instant.ofEpochMilli(ms);
        }
        try {
            return Instant.parse(rawObj.toString());
        } catch (Exception e) {
            return Instant.ofEpochMilli(System.currentTimeMillis() - (fallbackRowIndex * 1000L));
        }
    }
}
