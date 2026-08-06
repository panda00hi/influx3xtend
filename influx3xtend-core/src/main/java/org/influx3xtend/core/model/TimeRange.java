package org.influx3xtend.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 时间范围定义 (TimeRange)
 * 
 * @param start 起始时间 (包含)
 * @param end   结束时间 (不包含)
 */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "Start time cannot be null");
        Objects.requireNonNull(end, "End time cannot be null");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start time (" + start + ") cannot be after end time (" + end + ")");
        }
    }

    public static TimeRange of(Instant start, Instant end) {
        return new TimeRange(start, end);
    }
}
