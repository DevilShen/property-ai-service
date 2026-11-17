package com.property.ai.domain.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.property.ai.bff.dto.BillInfo;
import com.property.ai.bff.dto.BillItem;
import com.property.ai.bff.dto.RoomInfo;
import com.property.ai.bff.dto.TicketListItem;

/**
 * 映射试跑样本投影：契约 DTO → 「标准字段名 → 值」的扁平行（P2-024 差异统计的输入）。
 *
 * <p>为什么投影：试跑要验证的是「ERP 原始行 → 连接器映射 → 契约 DTO」这条链路是否字段完整、类型正确、
 * 金额恒等式成立（AI 不自算金额，只校验 ERP 给的口径是否自洽）。投影只读取已映射的 DTO 字段，
 * <b>不重新计算业务金额</b>，也不接触房号/手机号以外的隐私字段。
 *
 * <p>{@link MappingTrialEvaluator#KEY_FIELD} 存放样本主键（roomCode / billNo / ticketNo），仅用于差异回查。
 */
final class MappingSampleProjector {

    private MappingSampleProjector() {
    }

    /** 房档样本（CAP-01）：room + owner 两类字段合并在同一行 */
    static Map<String, Object> room(RoomInfo info) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (info == null) {
            return row;
        }
        row.put(MappingTrialEvaluator.KEY_FIELD, info.roomCode());
        row.put("projectId", info.projectId());
        row.put("roomCode", info.roomCode());
        row.put("building", info.building());
        row.put("unit", info.unit());
        row.put("room", info.room());
        row.put("areaM2", info.areaM2());
        row.put("layout", info.layout());
        row.put("parkingNo", info.parkingNo());
        row.put("householdId", info.householdId());
        row.put("ownerNameMasked", info.ownerNameMasked());
        row.put("bindState", info.bindState() == null ? null : info.bindState().value());
        row.put("accountStatus", info.accountStatus() == null ? null : info.accountStatus().value());
        row.put("tags", info.tags());
        if (info.relations() != null && !info.relations().isEmpty()) {
            row.put("relationType", info.relations().get(0).relationType() == null
                    ? null
                    : info.relations().get(0).relationType().value());
        }
        return row;
    }

    /** 账单样本（CAP-03）：含费项明细（items[].xxx 在评估器里按路径取值） */
    static Map<String, Object> bill(BillInfo info) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (info == null) {
            return row;
        }
        row.put(MappingTrialEvaluator.KEY_FIELD, info.billNo() != null ? info.billNo() : info.roomCode());
        row.put("billNo", info.billNo());
        row.put("roomCode", info.roomCode());
        row.put("period", info.period());
        row.put("total", info.total());
        row.put("paid", info.paid());
        row.put("outstanding", info.outstanding());
        row.put("prevTotal", info.prevTotal());
        row.put("lateFee", info.lateFee());
        row.put("diff", info.diff());
        row.put("status", info.status() == null ? null : info.status().value());
        row.put("dueDate", info.dueDate());
        row.put("currency", info.currency());
        List<Map<String, Object>> items = new ArrayList<>();
        if (info.items() != null) {
            for (BillItem item : info.items()) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> rowItem = new LinkedHashMap<>();
                rowItem.put("itemCode", item.itemCode());
                rowItem.put("name", item.label());
                rowItem.put("amount", item.amount());
                items.add(rowItem);
            }
        }
        row.put("items", items);
        return row;
    }

    /** 工单样本（CAP-08 ticket.list）：列表形态不含 symptom/issueScope，评估器按契约支持集自动跳过 */
    static Map<String, Object> ticket(TicketListItem info) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (info == null) {
            return row;
        }
        row.put(MappingTrialEvaluator.KEY_FIELD, info.ticketNo());
        row.put("ticketNo", info.ticketNo());
        row.put("roomCode", info.roomCode());
        row.put("category", info.category() == null ? null : info.category().value());
        row.put("status", info.status() == null ? null : info.status().value());
        row.put("urgency", info.urgency() == null ? null : info.urgency().value());
        row.put("team", info.team());
        row.put("createdAt", info.createdAt());
        row.put("updatedAt", info.updatedAt());
        return row;
    }
}
