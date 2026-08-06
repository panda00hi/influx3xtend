package org.influx3xtend.core.exception;

/**
 * 查询执行异常 (QueryExecutionException)
 */
public class QueryExecutionException extends Influx3xtendException {

    public QueryExecutionException(String message) {
        super(message);
    }

    public QueryExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
