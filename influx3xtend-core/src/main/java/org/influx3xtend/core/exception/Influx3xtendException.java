package org.influx3xtend.core.exception;

/**
 * Influx3xtend 根异常基类 (Base Influx3xtend Exception)
 */
public class Influx3xtendException extends RuntimeException {

    public Influx3xtendException(String message) {
        super(message);
    }

    public Influx3xtendException(String message, Throwable cause) {
        super(message, cause);
    }
}
