package org.influx3xtend.core.model;

import java.util.Objects;

/**
 * 聚合表达式 (AggregateExpr)
 *
 * @param field 目标字段
 * @param type  聚合类型 (AVG, SUM, COUNT, MAX, MIN)
 * @param alias 别名
 */
public record AggregateExpr(String field, AggregateType type, String alias) {

    public AggregateExpr {
        Objects.requireNonNull(field, "Aggregate field cannot be null");
        Objects.requireNonNull(type, "Aggregate type cannot be null");
        if (alias == null || alias.isBlank()) {
            alias = type.name().toLowerCase() + "_" + field;
        }
    }

    public static AggregateExpr of(String field, AggregateType type) {
        return new AggregateExpr(field, type, null);
    }

    public static AggregateExpr of(String field, AggregateType type, String alias) {
        return new AggregateExpr(field, type, alias);
    }
}
