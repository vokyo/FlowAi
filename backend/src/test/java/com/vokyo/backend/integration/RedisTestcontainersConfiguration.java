package com.vokyo.backend.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Redis for the tests that need one, imported next to
 * {@link TestcontainersConfiguration}. The image is the one docker-compose.yml runs.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    }

}
