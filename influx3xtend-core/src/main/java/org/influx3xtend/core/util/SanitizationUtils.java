package org.influx3xtend.core.util;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 标识符与安全过滤校验工具类 (SanitizationUtils)
 * 防止在拼接动态 Flight SQL 及 DuckDB Parquet SQL 时发生 SQL 注入攻击
 */
public final class SanitizationUtils {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final Set<String> ALLOWED_OPERATORS = Set.of(
            "=", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "IN", "NOT IN"
    );

    private SanitizationUtils() {}

    /**
     * 校验 SQL 标识符（如表名、列名），仅允许常规标识符字符 [a-zA-Z0-9_]
     */
    public static String sanitizeIdentifier(String name, String paramName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(paramName + " cannot be null or blank");
        }
        String trimmed = name.trim();
        if (!IDENTIFIER_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier for " + paramName + ": " + name);
        }
        return trimmed;
    }

    /**
     * 校验比较操作符，仅允许合法白名单操作符
     */
    public static String sanitizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "=";
        }
        String upperOp = operator.trim().toUpperCase();
        if (!ALLOWED_OPERATORS.contains(upperOp)) {
            throw new IllegalArgumentException("Invalid SQL comparison operator: " + operator);
        }
        return upperOp;
    }
}
