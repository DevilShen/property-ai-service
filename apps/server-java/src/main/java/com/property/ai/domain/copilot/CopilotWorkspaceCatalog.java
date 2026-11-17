package com.property.ai.domain.copilot;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.CopilotWorkspaceId;
import com.property.ai.bff.dto.CopilotWorkspaceInfo;
import com.property.ai.bff.dto.CopilotWorkspaceListResult;
import com.property.ai.bff.dto.CopilotWorkspaceStatus;

/**
 * Copilot 8 工作区元数据（P4-011，契约 v1.2.0 {@code GET /copilot/workspaces}、{@code CopilotWorkspaceId}）。
 *
 * <h2>固定顺序（契约枚举顺序，不得前端硬编码）</h2>
 * summary → script → portrait → digest → group → knowledge → report → handoff，固定 8 项、不分页。
 *
 * <h2>可用状态口径（{@code CopilotWorkspaceStatus}）</h2>
 * <ul>
 *   <li>{@code available}：P4 W16 已接真实数据（本任务 7 个 Copilot 端点）或已有既有端点
 *       （{@code knowledge} = {@code /knowledge/search}；{@code handoff} = {@code /tickets/{ticketNo}/handoff-summary}）；</li>
 *   <li>{@code degraded}：部分数据缺失/mock 通道不可用（本任务不预置，运行时由前端按响应 {@code mock} 标注）；</li>
 *   <li>{@code planned}：未上线（入口置灰，不展示编造数据）。本任务 8 个入口均已有后端能力，故无 planned。</li>
 * </ul>
 *
 * <p><b>mock 语义</b>：Copilot 结果一律 {@code mock:true} = 平台侧规则组装/演示编排结果，<b>未接真实模型</b>
 * （AGENTS.md §2：模型调用归 Python 编排层，Java 不引模型 SDK）。
 */
@Component
public class CopilotWorkspaceCatalog {

    /** 工作区定义（ID / 中文名 / 状态 / 一句话职责） */
    private record Definition(CopilotWorkspaceId id, String name, CopilotWorkspaceStatus status,
            String description) {
    }

    /**
     * 8 项固定顺序（与 {@link CopilotWorkspaceId} 枚举顺序一致；ID/枚举取自契约 DTO，不手写字符串）。
     */
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(CopilotWorkspaceId.SUMMARY, "工单摘要", CopilotWorkspaceStatus.AVAILABLE,
                    "群消息或通话转写压成 5 行摘要 + 3 条待办，知识引用随摘要返回"),
            new Definition(CopilotWorkspaceId.SCRIPT, "话术生成", CopilotWorkspaceStatus.AVAILABLE,
                    "场景 × 情绪 × 渠道生成 3 版话术并做禁区扫描（只生成不外发）"),
            new Definition(CopilotWorkspaceId.PORTRAIT, "业主画像", CopilotWorkspaceStatus.AVAILABLE,
                    "脱敏画像：运营标签 + 服务历史计数 + 沟通禁忌（不含手机号/欠费金额明文）"),
            new Definition(CopilotWorkspaceId.DIGEST, "日报摘要", CopilotWorkspaceStatus.AVAILABLE,
                    "当日诉求/工单/投诉指标 + 条目化摘要（数字全部来自服务端聚合）"),
            new Definition(CopilotWorkspaceId.GROUP, "群服务", CopilotWorkspaceStatus.AVAILABLE,
                    "人工确认后经 mock 通道群发公告并留痕（不做群内自动回复）"),
            new Definition(CopilotWorkspaceId.KNOWLEDGE, "知识秒查", CopilotWorkspaceStatus.AVAILABLE,
                    "按 published + 生效期检索知识，返回引用四要素（无命中必须拒答转人工）"),
            new Definition(CopilotWorkspaceId.REPORT, "报表", CopilotWorkspaceStatus.AVAILABLE,
                    "周期指标 + 分组明细（口径与看板一致，不编造数字）"),
            new Definition(CopilotWorkspaceId.HANDOFF, "转人工作交接", CopilotWorkspaceStatus.AVAILABLE,
                    "工单转人工交接摘要（复用 /tickets/{ticketNo}/handoff-summary）"));

    /** 8 个工作区（固定顺序、固定条数）。 */
    public CopilotWorkspaceListResult list() {
        List<CopilotWorkspaceInfo> items = new ArrayList<>(DEFINITIONS.size());
        for (Definition definition : DEFINITIONS) {
            items.add(new CopilotWorkspaceInfo(definition.id(), definition.name(), definition.status(),
                    definition.description()));
        }
        return new CopilotWorkspaceListResult(List.copyOf(items), items.size());
    }
}
