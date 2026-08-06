package org.influx3xtend.core.api;

import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.constants.Influx3xtendDefaults;
import org.influx3xtend.core.model.*;
import org.influx3xtend.core.router.StagedQueryRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 链式流式查询构建器 (Influx3QueryBuilder)
 * 工业级防爆保护：包含 SELECT * 警告日志提示、默认安全 Limit 兜底与列裁剪防护
 */
public class Influx3QueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(Influx3QueryBuilder.class);

    private final StagedQueryRouter router;
    private final String database;
    private String measurement;
    private String timeColumn = Influx3xtendConstants.COLUMN_TIME;
    private final List<String> selectFields = new ArrayList<>();
    private final List<FilterCondition> tagFilters = new ArrayList<>();
    private final List<FilterCondition> fieldFilters = new ArrayList<>();
    private TimeRange timeRange;
    private Duration groupByTime;
    private final List<AggregateExpr> aggregates = new ArrayList<>();
    private final List<OrderByExpr> orderByList = new ArrayList<>();
    private Integer limit;

    public Influx3QueryBuilder(StagedQueryRouter router, String defaultDatabase) {
        this.router = router;
        this.database = defaultDatabase;
    }

    public Influx3QueryBuilder measurement(String measurement) {
        this.measurement = measurement;
        return this;
    }

    public Influx3QueryBuilder timeColumn(String timeColumn) {
        if (timeColumn != null && !timeColumn.isBlank()) {
            this.timeColumn = timeColumn;
        }
        return this;
    }

    public Influx3QueryBuilder select(String... fields) {
        if (fields != null) {
            this.selectFields.addAll(List.of(fields));
        }
        return this;
    }

    public Influx3QueryBuilder whereTag(String tag, String value) {
        this.tagFilters.add(FilterCondition.eq(tag, value));
        return this;
    }

    public Influx3QueryBuilder whereField(String field, String operator, Object value) {
        this.fieldFilters.add(new FilterCondition(field, operator, value));
        return this;
    }

    public Influx3QueryBuilder timeRange(Instant start, Instant end) {
        this.timeRange = TimeRange.of(start, end);
        return this;
    }

    public Influx3QueryBuilder groupByTime(Duration duration) {
        this.groupByTime = duration;
        return this;
    }

    // 快捷聚合 API
    public Influx3QueryBuilder count(String field) {
        return aggregate(field, AggregateType.COUNT);
    }

    public Influx3QueryBuilder avg(String field) {
        return aggregate(field, AggregateType.AVG);
    }

    public Influx3QueryBuilder sum(String field) {
        return aggregate(field, AggregateType.SUM);
    }

    public Influx3QueryBuilder min(String field) {
        return aggregate(field, AggregateType.MIN);
    }

    public Influx3QueryBuilder max(String field) {
        return aggregate(field, AggregateType.MAX);
    }

    public Influx3QueryBuilder aggregate(String field, AggregateType type) {
        this.aggregates.add(AggregateExpr.of(field, type));
        return this;
    }

    public Influx3QueryBuilder aggregate(String field, AggregateType type, String alias) {
        this.aggregates.add(AggregateExpr.of(field, type, alias));
        return this;
    }

    public Influx3QueryBuilder orderBy(String field) {
        return orderBy(field, SortOrder.ASC);
    }

    public Influx3QueryBuilder orderByAsc(String field) {
        return orderBy(field, SortOrder.ASC);
    }

    public Influx3QueryBuilder orderByDesc(String field) {
        return orderBy(field, SortOrder.DESC);
    }

    public Influx3QueryBuilder orderByTimeDesc() {
        return orderBy(this.timeColumn != null ? this.timeColumn : Influx3xtendConstants.COLUMN_TIME, SortOrder.DESC);
    }

    public Influx3QueryBuilder orderByTimeAsc() {
        return orderBy(this.timeColumn != null ? this.timeColumn : Influx3xtendConstants.COLUMN_TIME, SortOrder.ASC);
    }

    public Influx3QueryBuilder orderBy(String field, SortOrder order) {
        this.orderByList.add(new OrderByExpr(field, order));
        return this;
    }

    public Influx3QueryBuilder limit(int limit) {
        this.limit = limit;
        return this;
    }

    public QueryResult execute() {
        Objects.requireNonNull(measurement, "Measurement must be specified via .measurement(...)");
        if (timeRange == null) {
            // 默认查最近 24 小时
            timeRange = TimeRange.of(Instant.now().minus(Duration.ofHours(24)), Instant.now());
        }

        // 保护 1：SELECT * 提示警示
        if (selectFields.isEmpty() && aggregates.isEmpty()) {
            log.warn("SELECT * query detected on measurement [{}]. For optimal columnar performance and lower bandwidth, specifying fields via .select(...) is strongly recommended.", measurement);
        }

        // 防爆安全保护：当未显式设置 Limit 时，自动回填默认安全 Limit 保护
        int effectiveLimit = Influx3xtendDefaults.getSafetyMaxLimit(limit);

        QueryRequest request = new QueryRequest(
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
                effectiveLimit
        );

        return router.routeAndExecute(request);
    }
}
