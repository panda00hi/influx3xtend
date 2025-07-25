package com.influx3xtend.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * @author panda00hi
 * @date 2025.04.28
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
public class WeatherData extends PointBase {
    private String devNo;
    private String location;
    private Double temp;
    private Double hum;
}
