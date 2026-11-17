package com.property.ai.bff.api;

import java.util.List;

/**
 * 单屏 mock 数据（OpenAPI: ScreenMock）。
 * P1 只返回页面骨架标识与区块名；W3 再接真实 DTO/数据。
 */
public record ScreenMock(
        String screenKey,
        String title,
        String route,
        boolean mock,
        List<String> blocks,
        String updatedAt
) {
}
