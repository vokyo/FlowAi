package com.vokyo.backend.security.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

    @Bean
    TimeMeter rateLimitTimeMeter() {
        return TimeMeter.SYSTEM_NANOTIME;
    }
}
