package org.influx3xtend.core.model;

import org.influx3xtend.core.constants.Influx3xtendConstants;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 客户端查询请求结构体 (QueryRequest)
 * 原生支持动态表名、字段投影、自定义时间列名与多维 Tag/Field 过滤
 */
public record QueryRequest(
        String database,
        String measurement,
        String timeColumn,
        List<String> selectFields,
        List<FilterCondition> tagFilters,
        List<FilterCondition> fieldFilters,
        TimeRange timeRange,
        Duration groupByTime,
        List<AggregateExpr> aggregates,
        List<OrderByExpr> orderByList,
        Integer limit
) {
    public QueryRequest {
        Objects.requireNonNull(measurement, "Measurement cannot be null");
        if (timeColumn == null || timeColumn.isBlank()) {
            timeColumn = Influx3xtendConstants.COLUMN_TIME;
        }
        if (selectFields == null) selectFields = Collections.emptyList();
        if (tagFilters == null) tagFilters = Collections.emptyList();
        if (fieldFilters == null) fieldFilters = Collections.emptyList();
        if (aggregates == null) aggregates = Collections.emptyList();
        if (orderByList == null) orderByList = Collections.emptyList();
    }

    /**
     * 重构兼容构造函数：默认使用 Influx3xtendConstants.COLUMN_TIME
     */
    public QueryRequest(
            String database,
            String measurement,
            List<String> selectFields,
            List<FilterCondition> tagFilters,
            List<FilterCondition> fieldFilters,
            TimeRange timeRange,
            Duration groupByTime,
            List<AggregateExpr> aggregates,
            List<OrderByExpr> orderByList,
            Integer limit
    ) {
        this(database, measurement, Influx3xtendConstants.COLUMN_TIME, selectFields, tagFilters, fieldFilters, timeRange, groupByTime, aggregates, orderByList, limit);
    }

    public QueryRequest withTimeRange(TimeRange newTimeRange) {
        return new QueryRequest(
                database,
                measurement,
                timeColumn,
                selectFields,
                tagFilters,
                fieldFilters,
                newTimeRange,
                groupByTime,
                aggregates,
                orderByList,
                limit
        );
    }

    public QueryRequest withLimit(Integer newLimit) {
        return new QueryRequest(
                database,
                measurement,
                timeColumn,
                selectFields,
                tagFilters,
                fieldFilters,
                timeRange,
                groupByTime,
                aggregates,
                orderByList,
                newLimit
        );
    }
}
