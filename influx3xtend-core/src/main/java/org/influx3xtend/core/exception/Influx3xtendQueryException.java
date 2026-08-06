package org.influx3xtend.core.exception;

/**
 * 查询语法或引擎执行失败异常 (Query Execution Exception)
 */
public class Influx3xtendQueryException extends Influx3xtendException {

    public Influx3xtendQueryException(String message) {
        super(message);
    }

    public Influx3xtendQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
