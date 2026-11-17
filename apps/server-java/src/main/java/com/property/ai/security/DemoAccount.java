package com.property.ai.security;

import com.property.ai.bff.dto.Actor;
import com.property.ai.bff.dto.ActorRole;

/**
 * 演示账号登记（P2 W8 demo，契约 v0.3.3 {@code POST /api/v1/auth/login} / README §2.1）。
 *
 * <p><b>不是真实账号体系</b>：与 {@code packages/shared/src/index.ts} 的 {@code DemoAccount}
 * 同字段、{@code DEMO_ACCOUNTS} 同值；不含真实手机号/工号；口令统一 {@code demo123}。
 * 增加/修改演示账号属于契约变更（先改 README §2.1 + shared，再同步 Java）。
 *
 * @param account   登录账号（{@code LoginRequest.account}）
 * @param actorId   登录后 {@code Actor.actorId}（P2 与 account 同值）
 * @param role      演示角色
 * @param name      演示显示名（非真实姓名）
 * @param projectId 所属项目（{@code null} = 平台级：IT/管理员）
 */
public record DemoAccount(String account, String actorId, ActorRole role, String name, String projectId) {

    /** 转契约 {@code Actor}（{@code mock} 恒 {@code true}：demo 身份，不冒充真实鉴权）。 */
    public Actor toActor() {
        return new Actor(actorId, role, name, projectId, true);
    }
}
