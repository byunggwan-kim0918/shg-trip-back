package com.shg.trip.shgtrip.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@EnableRedisRepositories(basePackages = "com.shg.trip.shgtrip.domain.auth.repository")
public class RedisConfig {
}
