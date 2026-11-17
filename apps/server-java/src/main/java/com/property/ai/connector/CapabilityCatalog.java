package com.property.ai.connector;

import java.util.List;
import java.util.Optional;

import com.property.ai.bff.dto.CapabilityCode;
import com.property.ai.bff.dto.CapabilityLevel;

/**
 * 18 项标准能力目录（架构 §3.2 CAP-01…CAP-18，顺序固定）。
 *
 * <p>口径与 {@code services/mock-erp/meta_catalog.py}、契约 {@code CapabilityCode} 一一对应：
 * 设计编号 / 能力码 / 中文名 / 能力级别 / 缺能力时的替代通道。
 * 服务端是能力事实源（前端不硬编码入口显隐），探测矩阵与连接器注册表都读本目录。
 */
public final class CapabilityCatalog {

    /** 单能力目录条目（设计编号 CAP-01…CAP-18） */
    public record Entry(
            CapabilityCode code,
            String designCode,
            String name,
            CapabilityLevel level,
            String fallback) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry(CapabilityCode.ROOM_QUERY, "CAP-01", "房档查询", CapabilityLevel.L1, "只读视图兜底 + 人工核对"),
            new Entry(CapabilityCode.OWNER_VERIFY, "CAP-02", "业主身份核验", CapabilityLevel.L1,
                    "改为人工核验 + 管家电话确认"),
            new Entry(CapabilityCode.BILL_QUERY, "CAP-03", "账单查询", CapabilityLevel.L1, "只答已确认部分，其余转人工"),
            new Entry(CapabilityCode.PAY_JUMP, "CAP-04", "缴费跳转", CapabilityLevel.L2, "隐藏入口 + 引导前台/公众号缴费"),
            new Entry(CapabilityCode.INVOICE_APPLY, "CAP-05", "开票申请", CapabilityLevel.L2, "跳转 ERP 自助开票页"),
            new Entry(CapabilityCode.TICKET_CREATE, "CAP-06", "工单创建", CapabilityLevel.L2, "落 AI 工单池，人工补录 ERP"),
            new Entry(CapabilityCode.TICKET_ASSIGN, "CAP-07", "工单派单/改派", CapabilityLevel.L2, "转人工派单"),
            new Entry(CapabilityCode.TICKET_STATUS, "CAP-08", "工单进度", CapabilityLevel.L1, "以短信/电话进度为准并转人工"),
            new Entry(CapabilityCode.TICKET_CLOSE, "CAP-09", "完工回写", CapabilityLevel.L2, "人工回写完工结论"),
            new Entry(CapabilityCode.COMPLAINT_CREATE, "CAP-10", "投诉单创建", CapabilityLevel.L2, "落 AI 侧投诉池，人工补录"),
            new Entry(CapabilityCode.NOTICE_PUSH, "CAP-11", "通知推送", CapabilityLevel.L2, "改用企微群发 + 短信"),
            new Entry(CapabilityCode.NOTICE_RECEIPT, "CAP-12", "通知回执", CapabilityLevel.L1, "人工回执确认"),
            new Entry(CapabilityCode.VISIT_RECORD, "CAP-13", "回访记录", CapabilityLevel.L2, "导出 CSV，次日人工回填"),
            new Entry(CapabilityCode.DUNNING_TASK, "CAP-14", "催缴任务", CapabilityLevel.L2, "AI 生成话术，人工确认外呼"),
            new Entry(CapabilityCode.KB_SEARCH, "CAP-15", "知识检索", CapabilityLevel.L1, "平台内实现，不依赖 ERP"),
            new Entry(CapabilityCode.VISITOR_AUTH, "CAP-16", "访客授权", CapabilityLevel.L2, "引导前台登记放行"),
            new Entry(CapabilityCode.PARKING_AUTH, "CAP-17", "停车授权", CapabilityLevel.L2, "引导前台办理月卡/临停"),
            new Entry(CapabilityCode.HANDOFF_HUMAN, "CAP-18", "转人工", CapabilityLevel.L2, "平台内实现，不依赖 ERP"));

    private CapabilityCatalog() {
    }

    /** 全量 18 项（顺序 CAP-01…CAP-18） */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> find(CapabilityCode code) {
        return ENTRIES.stream().filter(entry -> entry.code() == code).findFirst();
    }

    /** 必须存在的条目（能力码来自契约枚举，缺失即编码缺陷） */
    public static Entry entry(CapabilityCode code) {
        return find(code).orElseThrow(() -> new IllegalStateException("能力目录缺少：" + code));
    }
}
