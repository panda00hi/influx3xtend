package com.influx3xtend.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * @author panda00hi
 * @date 2025.04.28
 */
// todo 后续优化项引入注解。目前先在各自的实现引擎中自行解析赋值
//  1. 识别与数据库中字段的命名映射关系；
//  2. 标识类型，如tag、field等；
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Data
public class TemperatureData extends PointBase {
    private String location;
    private Double value;
}
