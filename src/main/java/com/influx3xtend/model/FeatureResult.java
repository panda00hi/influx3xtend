package com.influx3xtend.model;

import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;

/**
 * 特征算法计算结果
 *
 * @author panda00hi
 * @date 2025.05.19
 */
@Document(collection = "feature_result")
@Data
public class FeatureResult implements Serializable {
    private String id;

    private String featureNo;

    private Double value;

    private Number key;
}

