package com.solesonic.redis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class RedisStreamConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

    public static final String REACTIVE_BYTES_REDIS_TEMPLATE = "REACTIVE_BYTES_REDIS_TEMPLATE";

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        log.info("Redis streaming enabled — initializing ReactiveStringRedisTemplate");

        return new ReactiveStringRedisTemplate(connectionFactory);
    }

    /**
     * String keys and raw byte values, for what is not text.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(REACTIVE_BYTES_REDIS_TEMPLATE)
    public ReactiveRedisTemplate<String, byte[]> reactiveBytesRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        RedisSerializationContext<String, byte[]> serializationContext = RedisSerializationContext
                .<String, byte[]>newSerializationContext(RedisSerializer.string())
                .value(RedisSerializer.byteArray())
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }
}
