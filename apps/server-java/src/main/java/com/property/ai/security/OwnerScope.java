package com.property.ai.security;

import java.util.Optional;
import java.util.regex.Pattern;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;

/**
 * 业主数据范围解析（P3-028，契约 v1.1.0 §7.2 / 架构 §14.2「数据级权限」）。
 *
 * <p>职责：把「登录主体」翻译成「本户房号 / 业主归属」，供领域服务强制数据范围
 * （{@code TicketService} 的 get/list(mine)/transition 校验与建单归属回填）。
 *
 * <h2>口径（demo，不得说成生产级）</h2>
 * 契约 v1.1.0 的 {@link Actor} 没有 {@code householdId} 字段（冻结），P3 沿用投诉/费用域既有约定
 * <b>{@code owner-<roomCode>}</b>（演示账号 {@code owner-3-2-1802} → 房号 {@code 3-2-1802}）从
 * {@code actorId} 解析；P5 接真实身份体系后由 SSO 声明注入，本类只保留一个解析入口便于替换。
 *
 * <p>本类只做「解析」，判定与拒绝（403 {@code E-2002}）由调用方服务层执行——不在 Controller 写角色判断。
 */
public final class OwnerScope {

    /** 房号归一格式（楼栋-单元-房间，与契约 §2 roomCode 同口径） */
    private static final Pattern ROOM_CODE = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{3,4}$");
    /** 演示业主账号前缀（owner-3-2-1802） */
    private static final String OWNER_ACCOUNT_PREFIX = "owner-";

    private OwnerScope() {
    }

    /** 是否业主视角（只有 OWNER 受本户数据范围限制；staff/平台角色不受限）。 */
    public static boolean isOwner(Actor actor) {
        return actor != null && actor.role() == ActorRole.OWNER;
    }

    /**
     * 是否 staff 代报角色（steward / agent / supervisor，P3 W13 task-43）：
     * 可在项目内任意房号代业主建单，并回填该房演示业主归属。
     */
    public static boolean isStaffProxy(Actor actor) {
        if (actor == null || actor.role() == null) {
            return false;
        }
        return switch (actor.role()) {
            case STEWARD, AGENT, SUPERVISOR -> true;
            default -> false;
        };
    }

    /** 本户房号（owner 且演示账号可解析时非空；其它角色恒空）。 */
    public static Optional<String> ownerRoom(Actor actor) {
        return isOwner(actor) ? roomFromActorId(actor.actorId()) : Optional.empty();
    }

    /**
     * 演示业主账号 → 本户房号（{@code owner-3-2-1802} → {@code 3-2-1802}）。
     *
     * <p>仅 demo 约定；P5 由登录主体绑定（{@code Actor + householdId}）替换。
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

    /**
     * 房号 → 演示业主账号 ID（staff 代报/建单时回填 {@code ownerId} 归属用；无匹配返回空）。
     *
     * <p>只扫 6 个演示账号登记表（不查 ERP、不碰真实业主信息）；无匹配则留空（不编造归属）。
     */
    public static Optional<String> demoOwnerActorId(String roomCode) {
        String normalized = normalizeRoomCode(roomCode);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (DemoAccount account : DemoAccounts.ACCOUNTS) {
            if (account.role() != ActorRole.OWNER) {
                continue;
            }
            if (roomFromActorId(account.actorId()).filter(normalized::equals).isPresent()) {
                return Optional.of(account.actorId());
            }
        }
        return Optional.empty();
    }

    /**
     * 房号 → 演示业主主体 ID（<b>按约定推导</b>：{@code <roomCode>} → {@code owner-<roomCode>}，P3 W13 task-43）。
     *
     * <p>与 {@link #demoOwnerActorId(String)} 的区别：后者只认 6 个演示账号登记表里的房号；
     * staff 代报可能落在房档池里的**任意房号**（如 e2e 造数的 {@code 8-2-501}），
     * 此时仍按演示约定回填归属 {@code owner-8-2-501}（不查真实业主档案，房号非法则留空不编造）。
     *
     * <p>用于 staff 代报建单的归属回填与 owner「本户房号」读范围判定；生产由登录主体绑定替换。
     */
    public static Optional<String> ownerActorIdForRoom(String roomCode) {
        String normalized = normalizeRoomCode(roomCode);
        if (normalized.isEmpty() || !ROOM_CODE.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(OWNER_ACCOUNT_PREFIX + normalized);
    }

    /** 房号归一（全角连字符/空格容错，与 {@code ApiSupport.requireRoomCode} 同口径）。 */
    public static String normalizeRoomCode(String raw) {
        return raw == null ? "" : raw.trim().replace('－', '-').replace('—', '-').replace(" ", "");
    }
}
