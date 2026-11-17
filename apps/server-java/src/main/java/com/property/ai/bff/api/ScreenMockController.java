package com.property.ai.bff.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12 屏 mock API（P1）。
 * 契约：docs/openapi/openapi.yaml；前端 W3 通过 OpenAPI SDK 调用。
 */
@RestController
@RequestMapping("/api/v1")
public class ScreenMockController {

    @GetMapping("/meta/screens")
    public ApiResponse<ScreenList> listScreens() {
        return ApiResponse.ok(new ScreenList(ScreenCatalog.all()), traceId());
    }

    @GetMapping("/screens/{screenKey}")
    public ResponseEntity<ApiResponse<ScreenMock>> getScreen(@PathVariable String screenKey) {
        ScreenMeta meta = ScreenCatalog.find(screenKey);
        if (meta == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("SCREEN_NOT_FOUND", traceId()));
        }
        ScreenMock mock = new ScreenMock(
                meta.key(),
                meta.title(),
                meta.route(),
                true,
                ScreenCatalog.blocks(screenKey),
                Instant.now().toString()
        );
        return ResponseEntity.ok(ApiResponse.ok(mock, traceId()));
    }

    private String traceId() {
        return UUID.randomUUID().toString();
    }
}
