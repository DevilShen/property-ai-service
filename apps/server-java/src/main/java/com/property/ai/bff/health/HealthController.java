package com.property.ai.bff.health;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：与 W1 Node mock 保持同一响应字段，便于前端/探针无感切换。
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public HealthResponse health() {
        return new HealthResponse(true, "property-ai-server", true, Instant.now().toString());
    }

    public record HealthResponse(boolean ok, String service, boolean mock, String now) {
    }
}
