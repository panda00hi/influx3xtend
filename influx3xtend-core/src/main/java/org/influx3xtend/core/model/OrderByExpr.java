package org.influx3xtend.core.model;

import java.util.Objects;

public record OrderByExpr(String field, SortOrder order) {

    public OrderByExpr {
        Objects.requireNonNull(field, "Sort field cannot be null");
        if (order == null) {
            order = SortOrder.ASC;
        }
    }

    public static OrderByExpr asc(String field) {
        return new OrderByExpr(field, SortOrder.ASC);
    }

    public static OrderByExpr desc(String field) {
        return new OrderByExpr(field, SortOrder.DESC);
    }
}
