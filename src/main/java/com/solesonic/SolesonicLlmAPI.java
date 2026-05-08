package com.solesonic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRedisRepositories
@EnableAsync
@EnableScheduling
public class SolesonicLlmAPI {

    static void main(String[] args) {
        SpringApplication.run(SolesonicLlmAPI.class, args);
    }
}
