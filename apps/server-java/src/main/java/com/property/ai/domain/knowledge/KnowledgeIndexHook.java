package com.property.ai.domain.knowledge;

/**
 * 知识导入「审核联动索引」钩子（task-86，契约 v1.5.0 imports ④）。
 *
 * <p>为什么用钩子而不是让 {@link KnowledgeChangeService} 直接依赖导入服务：
 * 审核通过要触发编排层 {@code POST /internal/rag/index} 并落 {@code knowledge.index} 审计，
 * 这部分能力属于导入域（{@code domain/knowledge/importer}）。若审核服务直接依赖导入服务，
 * 会形成「导入服务 → 变更服务（登记待审变更单）→ 导入服务」的循环依赖。
 * 故由导入服务实现本接口，审核服务用 {@code ObjectProvider} 延迟注入（无循环，且未装配时安全降级）。
 *
 * <p><b>契约边界</b>：仅 {@code pending_review} 的**导入来源**知识会触发本钩子；
 * 非导入知识（既有知识变更审核）行为完全不变。钩子内部失败只告警（不阻断审核结果），
 * 索引失败按 {@code knowledge.index} 审计留痕（result=failed），知识保持 published 可手动重试。
 */
public interface KnowledgeIndexHook {

    /**
     * 导入来源知识审核结束回调。
     *
     * @param knowledgeId 被审核的导入知识 ID
     * @param approved    true=审核通过（应触发索引）；false=审核驳回（应置 rejected，不索引）
     * @param traceId     本次审核请求的 traceId（审计/日志贯穿）
     */
    void onReviewed(String knowledgeId, boolean approved, String traceId);
}
