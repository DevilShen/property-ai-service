package com.property.ai.bff.api;

/**
 * 12 屏元数据（OpenAPI: ScreenMeta / ScreenList）。
 */
public record ScreenMeta(String key, String title, String route, int order, boolean mock) {
}
