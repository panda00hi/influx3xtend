package com.influx3xtend.annotation;

/**
 * @author panda00hi
 * @date 2025.07.24
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 时间戳注解，用于标识实体类中的时间戳字段
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface XTime {
    /**
     * 时间单位，默认为毫秒
     *
     * @return 时间单位
     */
    TimeUnit value() default TimeUnit.MILLISECONDS;
}