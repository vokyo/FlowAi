package com.vokyo.backend.ai;

import com.vokyo.backend.ai.springai.SpringAiModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
    @ConditionalOnBean(ChatModel.class)
    AiModelGateway aiModelGateway(ChatModel chatModel) {
        return new SpringAiModelGateway(chatModel);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}