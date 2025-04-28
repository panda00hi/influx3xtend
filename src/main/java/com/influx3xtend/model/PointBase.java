package com.influx3xtend.model;

import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * @author panda00hi
 * @date 2025.04.28
 */
@Data
public class PointBase implements Serializable {
    private Timestamp time;
}
