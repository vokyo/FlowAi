package com.vokyo.backend.demo;

import com.vokyo.backend.demo.dto.DemoStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the sign-in page offer a one-click way into the demo workspace.
 *
 * <p>Registered whatever the seeder is set to, so the answer is always a plain
 * {@code enabled: false} rather than a 404 the client has to interpret. That
 * keeps the demo down to the single {@code DEMO_SEED_ENABLED} switch: turn it
 * off and the button disappears on its own, with no second flag to remember and
 * no build-time constant baked into the frontend.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoStatusController {

    private final DemoSeedProperties properties;

    public DemoStatusController(DemoSeedProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public DemoStatusResponse getStatus() {
        if (!properties.enabled()) {
            return DemoStatusResponse.disabled();
        }

        return new DemoStatusResponse(true, properties.email(), properties.password());
    }
}
