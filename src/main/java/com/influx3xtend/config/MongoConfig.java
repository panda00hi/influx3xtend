package com.influx3xtend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * @author panda00hi
 * @date 2025.06.24
 */
@Configuration
public class MongoConfig {

    @Bean
    public MappingMongoConverter mappingMongoConverter(
            MongoDatabaseFactory mongoDatabaseFactory,
            MongoMappingContext context,
            MongoCustomConversions conversions) {

        // 使用推荐的 DbRefResolver
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(mongoDatabaseFactory);

        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, context);
        converter.setCustomConversions(conversions);

        // 去掉 _class 字段
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        return converter;
    }
}
