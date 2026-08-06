package org.influx3xtend.core.exception;

/**
 * 数据库或存储服务连接失败异常 (Connection Exception)
 */
public class Influx3xtendConnectionException extends Influx3xtendException {

    public Influx3xtendConnectionException(String message) {
        super(message);
    }

    public Influx3xtendConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
