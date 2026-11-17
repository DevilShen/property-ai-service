package com.property.ai.bff.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 12 屏目录：与前端 apps/web/src/app/screens.ts、OpenAPI ScreenKey 三处对齐。
 * 任何新增/改名必须同时改三处，并跑 pnpm check:arch。
 */
public final class ScreenCatalog {

    private static final Map<String, ScreenMeta> SCREENS = new LinkedHashMap<>();

    static {
        add("overview", "方案总览", "/overview", 1);
        add("owner", "业主端 AI 客服", "/owner", 2);
        add("progress", "工单进度 · 闭环", "/progress", 3);
        add("copilot", "管家 Copilot", "/copilot", 4);
        add("desk", "坐席工作台", "/desk", 5);
        add("billing", "费用与催缴", "/billing", 6);
        add("erp", "ERP 适配中心", "/erp", 7);
        add("mapping", "字段与状态映射", "/mapping", 8);
        add("orchestration", "能力编排 · 运行时", "/orchestration", 9);
        add("trace", "全链路回溯", "/trace", 10);
        add("knowledge", "知识库运营", "/knowledge", 11);
        add("dashboard", "运营看板", "/dashboard", 12);
    }

    private ScreenCatalog() {
    }

    private static void add(String key, String title, String route, int order) {
        SCREENS.put(key, new ScreenMeta(key, title, route, order, true));
    }

    public static List<ScreenMeta> all() {
        return List.copyOf(SCREENS.values());
    }

    public static ScreenMeta find(String key) {
        return SCREENS.get(key);
    }

    public static List<String> blocks(String key) {
        return switch (key) {
            case "overview" -> List.of("六层架构", "18 项能力", "5 套 ERP", "三条闭环", "90 天路线");
            case "owner" -> List.of("会话", "身份核验", "账单卡", "五问报修", "语音/转人工");
            case "progress" -> List.of("7 节点时间轴", "三次推送", "回访评价", "证据包");
            case "copilot" -> List.of("知识秒查", "话术生成", "工单摘要", "业主画像", "日报周报", "群服务", "知识共建", "历史会话");
            case "desk" -> List.of("三队列", "上下文同屏", "分级", "SLA", "质检");
            case "billing" -> List.of("欠费台账", "分层策略", "催缴话术", "渠道红线", "转化漏斗");
            case "erp" -> List.of("5 套 ERP", "能力矩阵", "探测", "降级", "灰度");
            case "mapping" -> List.of("字段映射", "状态映射", "字典映射", "试跑", "版本");
            case "orchestration" -> List.of("7 跳链路", "项目路由表", "规则", "降级台账");
            case "trace" -> List.of("会话回放", "调用瀑布", "知识引用", "护栏", "证据包");
            case "knowledge" -> List.of("知识清单", "版本/生效期", "缺口", "badcase", "变更卡点");
            case "dashboard" -> List.of("自助率", "工单质量", "缴费转化", "收缴率", "ROI");
            default -> List.of();
        };
    }
}
