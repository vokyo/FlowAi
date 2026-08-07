package com.vokyo.backend.demo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoSeedProperties.class)
class DemoSeedConfiguration {
}
