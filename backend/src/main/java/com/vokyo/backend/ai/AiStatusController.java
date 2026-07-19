package com.vokyo.backend.ai;

import com.vokyo.backend.ai.dto.AiStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiStatusController {

    private final AiStatusService statusService;

    public AiStatusController(AiStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public AiStatusResponse getStatus() {
        return statusService.getStatus();
    }
}