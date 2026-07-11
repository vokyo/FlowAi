package com.vokyo.backend.analytics;

import com.vokyo.backend.analytics.dto.AnalyticsOverviewResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public AnalyticsOverviewResponse getOverview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID projectId,
            @RequestParam(defaultValue = "30") int days
    ) {
        return analyticsService.getOverview(jwt, projectId, days);
    }
}
