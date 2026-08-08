package com.vokyo.backend.demo;

import com.vokyo.backend.demo.dto.DemoStatusResponse;
import com.vokyo.backend.security.SecurityConfiguration;
import com.vokyo.backend.security.ratelimit.RateLimitFilter;
import com.vokyo.backend.web.ApiErrorService;
import com.vokyo.backend.web.ApiErrorWriter;
import com.vokyo.backend.web.ApiObservability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEventsTestExecutionListener;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in page reads this endpoint before anyone holds a token, so the two
 * things worth pinning are that it answers without authentication and that a
 * deployment which never enabled seeding hands out no credentials.
 */
class DemoStatusControllerTests {

    @Test
    void reportsNothingWhenTheDemoIsOff() {
        DemoStatusResponse response = new DemoStatusController(
                new DemoSeedProperties(false, "demo@flowai.dev", "demo1234", "Northwind Labs")
        ).getStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.email()).isNull();
        assertThat(response.password()).isNull();
    }

    @Test
    void reportsTheSeededCredentialsWhenTheDemoIsOn() {
        DemoStatusResponse response = new DemoStatusController(
                new DemoSeedProperties(true, "demo@flowai.dev", "demo1234", "Northwind Labs")
        ).getStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.email()).isEqualTo("demo@flowai.dev");
        assertThat(response.password()).isEqualTo("demo1234");
    }

    @ExtendWith(SpringExtension.class)
    @WebMvcTest(
            controllers = DemoStatusController.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = RateLimitFilter.class
            )
    )
    @EnableConfigurationProperties(DemoSeedProperties.class)
    @Import({
            SecurityConfiguration.class,
            ApiErrorWriter.class,
            ApiErrorService.class,
            ApiObservability.class,
            HttpTests.TestBeans.class
    })
    @TestPropertySource(properties = {
            "app.demo.enabled=true",
            "app.demo.email=demo@flowai.dev",
            "app.demo.password=demo1234",
            "app.demo.workspace-name=Northwind Labs"
    })
    @TestExecutionListeners(
            listeners = {
                    ServletTestExecutionListener.class,
                    DirtiesContextBeforeModesTestExecutionListener.class,
                    ApplicationEventsTestExecutionListener.class,
                    DependencyInjectionTestExecutionListener.class,
                    DirtiesContextTestExecutionListener.class,
                    EventPublishingTestExecutionListener.class
            },
            inheritListeners = false
    )
    static class HttpTests {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void answersWithoutAuthenticationSoTheSignInPageCanReadIt() throws Exception {
            mockMvc.perform(get("/api/demo/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.email").value("demo@flowai.dev"))
                    .andExpect(jsonPath("$.password").value("demo1234"));
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class TestBeans {

            @Bean
            MeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }

            @Bean
            JwtDecoder jwtDecoder() {
                return token -> {
                    throw new JwtException("Token decoding is not used by these MockMvc tests");
                };
            }
        }
    }
}
