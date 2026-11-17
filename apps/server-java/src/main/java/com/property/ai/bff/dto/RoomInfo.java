package com.property.ai.bff.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAPI: RoomInfo（房档只读（CAP-01 room.query））。
 * 字段名 / 必填 / 枚举与 docs/openapi/openapi.yaml v0.3.2 严格对齐；仅 DTO，不含业务逻辑。
 *
 * <p>v0.3.2 增量：末尾追加可选 {@code freshness}（数据新鲜度）。为不破坏 P2 W5 既有
 * {@code new RoomInfo(...)} 构造调用（连接器映射/对账/测试），保留 20 参数旧构造重载；
 * {@code freshness} 为 null 时不序列化，W5 响应形状不变。</p>
 */
public record RoomInfo(
        String householdId,
        String projectId,
        /** 归一房号（= mock/data 的 roomNo） */
        String roomCode,
        String building,
        String unit,
        String room,
        /** 建筑面积 ㎡ */
        BigDecimal areaM2,
        String layout,
        String ownerNameMasked,
        BindState bindState,
        /** 车位号；null = 无车位 */
        String parkingNo,
        AccountStatus accountStatus,
        int overdueDays,
        BigDecimal arrearsAmount,
        List<HouseholdRelation> relations,
        List<String> tags,
        RoomSource source,
        boolean mock,
        String dataAsOf,
        String updatedAt,
        /** v0.3.2 增量：数据新鲜度（命中缓存/降级 fallback=cache 时必带，可为 null） */
        DataFreshness freshness) {

    /** P2 W5 兼容构造（无 freshness；null 时不序列化，W5 响应形状不破坏）。 */
    public RoomInfo(
            String householdId,
            String projectId,
            String roomCode,
            String building,
            String unit,
            String room,
            BigDecimal areaM2,
            String layout,
            String ownerNameMasked,
            BindState bindState,
            String parkingNo,
            AccountStatus accountStatus,
            int overdueDays,
            BigDecimal arrearsAmount,
            List<HouseholdRelation> relations,
            List<String> tags,
            RoomSource source,
            boolean mock,
            String dataAsOf,
            String updatedAt) {
        this(householdId, projectId, roomCode, building, unit, room, areaM2, layout, ownerNameMasked,
                bindState, parkingNo, accountStatus, overdueDays, arrearsAmount, relations, tags,
                source, mock, dataAsOf, updatedAt, null);
    }

    /** v0.3.2 增量字段：非缓存直读时省略，保持 W5 响应形状。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Override
    public DataFreshness freshness() {
        return freshness;
    }
}
