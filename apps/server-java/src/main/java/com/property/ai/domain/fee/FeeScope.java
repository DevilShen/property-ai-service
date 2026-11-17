package com.property.ai.domain.fee;

import java.util.Optional;
import java.util.regex.Pattern;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;

/**
 * 费用只读数据范围（P3 W10，契约 v1.1.0 §7.2 / 架构 §14.2）。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code owner}：<b>仅本户</b>（欠费台账按本户房号过滤，无法通过参数越权；跨房解释 → 403 {@code E-2002}）；</li>
 *   <li>{@code steward}/{@code supervisor}：本片区/本项目（P3 W10 为单项目演示，作用域 = 全量，片区细化随 P3-028 数据范围落地）；</li>
 *   <li>{@code agent}：仅 {@code /fees/explain}（契约 §7.2：坐席可代查解释，不可见欠费台账）；</li>
 *   <li>{@code it}/{@code admin}：无费用能力（403 {@code E-2001}，由 Controller 依 {@code PermissionMatrix} 判定）。</li>
 * </ul>
 *
 * <p><b>demo 口径（不得说成生产级）</b>：登录主体 {@link Actor} 目前没有房号/户号字段
 * （数据范围字段 {@code Actor.householdId} 属 P3-028 范围），演示账号 {@code owner-3-2-1802}
 * 按 {@code owner-<roomCode>} 约定从 {@code actorId} 解析本户房号；P3-028 落地后改为按登录主体绑定读取，
 * 本类只保留一个解析入口便于替换（已在交付报告列为遗留风险）。
 */
public record FeeScope(String ownerRoom, String actorId, ActorRole role) {

    /** 房号归一格式（楼栋-单元-房间，与契约 §2 roomCode 同口径） */
    private static final Pattern ROOM_CODE = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");
    /** 演示业主账号前缀（owner-3-2-1802） */
    private static final String OWNER_ACCOUNT_PREFIX = "owner-";

    /** 未鉴权（{@code property.security.enabled=false} 的测试/离线演示）→ 不限房号。 */
    public static FeeScope unrestricted() {
        return new FeeScope(null, null, null);
    }

    /** 由当前演示主体推导数据范围（未登录 → 不限；owner → 本户）。 */
    public static FeeScope of(Actor actor) {
        if (actor == null) {
            return unrestricted();
        }
        String ownerRoom = actor.role() == ActorRole.OWNER ? roomFromActorId(actor.actorId()).orElse(null) : null;
        return new FeeScope(ownerRoom, actor.actorId(), actor.role());
    }

    /** 是否业主视角（只允许本户数据）。 */
    public boolean ownerScoped() {
        return role == ActorRole.OWNER;
    }

    /** 本户房号（owner 且可解析时非空）。 */
    public Optional<String> boundRoom() {
        return Optional.ofNullable(ownerRoom);
    }

    /** 是否可访问指定房号的费用解释（非 owner 一律允许，owner 仅本户）。 */
    public boolean allowsRoom(String roomCode) {
        if (!ownerScoped()) {
            return true;
        }
        return ownerRoom != null && ownerRoom.equals(roomCode);
    }

    /** 缓存键里的数据范围标识（避免 owner 与 staff 命中同一份缓存）。 */
    public String cacheKey() {
        if (ownerScoped()) {
            return "owner:" + (ownerRoom == null ? "unbound" : ownerRoom);
        }
        return "staff";
    }

    /**
     * 演示业主账号 → 本户房号（{@code owner-3-2-1802} → {@code 3-2-1802}）。
     *
     * <p>仅 demo 约定；P3-028 落地后由登录主体绑定（{@code Actor + householdId}）替换。
     */
    public static Optional<String> roomFromActorId(String actorId) {
        if (actorId == null) {
            return Optional.empty();
        }
        String normalized = actorId.trim();
        if (!normalized.startsWith(OWNER_ACCOUNT_PREFIX)) {
            return Optional.empty();
        }
        String candidate = normalized.substring(OWNER_ACCOUNT_PREFIX.length());
        return ROOM_CODE.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
