package com.vokyo.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.ai.springai.SpringAiModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "app.ai",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnProperty(
            prefix = "spring.ai.model",
            name = "chat",
            havingValue = "openai"
    )
    AiModelGateway aiModelGateway(
            ChatModel chatModel,
            ObjectProvider<ObjectMapper> objectMappers
    ) {
        ObjectMapper objectMapper = objectMappers.getIfAvailable(
                () -> new ObjectMapper().findAndRegisterModules()
        );
        return new SpringAiModelGateway(chatModel, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.ai",
            name = "enabled",
            havingValue = "true"
    )
    RestClientCustomizer aiRequestTimeoutCustomizer(AiProperties properties) {
        return builder -> builder.requestFactory(
                ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults().withTimeouts(
                                properties.requestTimeout(),
                                properties.requestTimeout()
                        )
                )
        );
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
