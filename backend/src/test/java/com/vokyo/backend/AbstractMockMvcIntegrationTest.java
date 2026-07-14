package com.vokyo.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vokyo.backend.auth.RefreshTokenCookieService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

abstract class AbstractMockMvcIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanIntegrationTestDatabase() {
        databaseCleaner.clean();
    }

    protected ResultActions postJson(String path, String json, String accessToken) throws Exception {
        return postJson(path, json, accessToken, null);
    }

    protected ResultActions postJson(
            String path,
            String json,
            String accessToken,
            String refreshToken
    ) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (accessToken != null) {
            request.header("Authorization", bearer(accessToken));
        }
        if (refreshToken != null) {
            request.cookie(refreshCookie(refreshToken));
        }
        return mockMvc.perform(request);
    }

    protected JsonNode readJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    protected String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    protected String refreshToken(ResultActions actions) throws Exception {
        Cookie cookie = actions.andReturn().getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME);
        return requireNonNull(cookie, "Expected response to set the refresh token cookie").getValue();
    }

    protected Cookie refreshCookie(String refreshToken) {
        return new Cookie(RefreshTokenCookieService.COOKIE_NAME, refreshToken);
    }

    protected String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
