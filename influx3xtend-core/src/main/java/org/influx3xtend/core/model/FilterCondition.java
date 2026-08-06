package org.influx3xtend.core.model;

import java.util.Objects;

/**
 * 过滤条件表达式 (FilterCondition)
 *
 * @param field    字段名称 / Tag 名称
 * @param operator 比较运算符 (=, !=, >, >=, <, <=, IN)
 * @param value    比较目标值
 */
public record FilterCondition(String field, String operator, Object value) {

    public FilterCondition {
        Objects.requireNonNull(field, "Filter field cannot be null");
        Objects.requireNonNull(operator, "Operator cannot be null");
        Objects.requireNonNull(value, "Filter value cannot be null");
    }

    public static FilterCondition eq(String field, Object value) {
        return new FilterCondition(field, "=", value);
    }

    public static FilterCondition neq(String field, Object value) {
        return new FilterCondition(field, "!=", value);
    }

    public static FilterCondition gt(String field, Object value) {
        return new FilterCondition(field, ">", value);
    }

    public static FilterCondition gte(String field, Object value) {
        return new FilterCondition(field, ">=", value);
    }

    public static FilterCondition lt(String field, Object value) {
        return new FilterCondition(field, "<", value);
    }

    public static FilterCondition lte(String field, Object value) {
        return new FilterCondition(field, "<=", value);
    }
}
