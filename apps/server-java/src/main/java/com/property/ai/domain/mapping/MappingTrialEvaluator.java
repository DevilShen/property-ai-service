package com.property.ai.domain.mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.property.ai.bff.dto.MappingDiffItem;
import com.property.ai.bff.dto.MappingDiffKind;
import com.property.ai.bff.dto.MappingField;

/**
 * 映射试跑差异评估器（P2-024，契约 v0.3.2 {@code MappingTrialReport}）。
 *
 * <p>比对四件事（逐样本）：
 * <ol>
 *   <li><b>字段完整性</b>：契约标准字段在映射后 DTO 里是否存在（缺失且非必填 → 不算差异；必填缺失 → {@code required_missing}）；</li>
 *   <li><b>类型</b>：标准字段声明类型（string/number/enum/datetime/array）与实际值类型是否一致 → 不一致 {@code type_mismatch}；</li>
 *   <li><b>金额恒等式</b>（仅账单）：{@code Σitems[].amount == total}、{@code total - paid == outstanding}、
 *       {@code total - prevTotal == diff}——AI 不自算金额，只校验 ERP 口径自洽；</li>
 *   <li><b>样本计数</b>：{@code checked = 实际比对样本数}（不是请求 sampleSize）、
 *       {@code matched = 零差异样本数}、{@code diffCount = 有 ≥1 条差异的样本数}（一条样本只计 1）。</li>
 * </ol>
 *
 * <p>{@code diffRate = diffCount / checked}；{@code passed = checked > 0 && diffRate <= threshold}（P2 门禁 0.02）。
 * 字段字典里存在但契约 DTO <b>不暴露</b>的内部字段（如 ownerId / phoneTail4）不参与判定：
 * 这类字段属内部核验数据，不进对外 DTO，不能因「DTO 没有」判成差异。
 */
@Component
public class MappingTrialEvaluator {

    /** 样本主键在投影行里的键（roomCode / billNo / ticketNo） */
    public static final String KEY_FIELD = "__key";

    /** P2 门禁：差异率 ≤2% 视为通过 */
    public static final double DEFAULT_THRESHOLD = 0.02;

    /** 单次报告最多回带的差异条目（防超大响应；计数不受影响） */
    private static final int MAX_DIFF_ITEMS = 200;

    /** 契约 DTO 暴露的标准字段（按映射类别）；不在集合里的 targetField 视为内部字段，跳过判定 */
    private static final Map<String, Set<String>> CANONICAL_TARGETS = Map.of(
            "room", Set.of("projectId", "roomCode", "building", "unit", "room", "areaM2", "layout", "parkingNo",
                    "householdId", "ownerNameMasked", "bindState", "accountStatus", "tags"),
            "bill", Set.of("billNo", "period", "total", "paid", "outstanding", "prevTotal", "lateFee",
                    "items[].itemCode", "items[].name", "items[].amount", "status", "dueDate", "currency"),
            "ticket", Set.of("ticketNo", "category", "status", "urgency", "team", "createdAt", "updatedAt"));

    private final double threshold;

    public MappingTrialEvaluator(@Value("${property.mapping.diff-threshold:0.02}") double threshold) {
        this.threshold = threshold <= 0 ? DEFAULT_THRESHOLD : threshold;
    }

    public double threshold() {
        return threshold;
    }

    /** 试跑统计结果（映射到契约 {@code MappingTrialReport} 的计数字段） */
    public record TrialResult(int checked, int matched, int diffCount, double diffRate, boolean passed,
            List<MappingDiffItem> items) {
    }

    /**
     * 评估一次试跑。
     *
     * @param category room|bill|ticket（决定哪些标准字段参与判定）
     * @param fields   ERP 字段字典（源字段/标准字段/类型/必填）
     * @param samples  映射后样本（{@link MappingSampleProjector} 投影行）
     */
    public TrialResult evaluate(String category, List<MappingField> fields, List<Map<String, Object>> samples) {
        Set<String> supported = CANONICAL_TARGETS.getOrDefault(category == null ? "" : category, Set.of());
        List<MappingField> checkedFields = new ArrayList<>();
        if (fields != null) {
            for (MappingField field : fields) {
                if (field != null && supported.contains(field.targetField())) {
                    checkedFields.add(field);
                }
            }
        }
        List<MappingDiffItem> items = new ArrayList<>();
        int checked = 0;
        int matched = 0;
        int diffCount = 0;
        if (samples != null) {
            for (Map<String, Object> sample : samples) {
                if (sample == null) {
                    continue;
                }
                checked++;
                List<MappingDiffItem> rowDiffs = new ArrayList<>();
                String key = text(sample.get(KEY_FIELD));
                String roomCode = text(sample.get("roomCode"));
                for (MappingField field : checkedFields) {
                    Object value = valueOf(sample, field.targetField());
                    if (isBlank(value)) {
                        if (field.required()) {
                            rowDiffs.add(new MappingDiffItem(key, MappingDiffKind.REQUIRED_MISSING, field.sourceField(),
                                    field.targetField(), "必填字段非空（类型 " + field.type() + "）", "", roomCode));
                        }
                        continue;
                    }
                    String problem = typeProblem(field.type(), value);
                    if (problem != null) {
                        rowDiffs.add(new MappingDiffItem(key, MappingDiffKind.TYPE_MISMATCH, field.sourceField(),
                                field.targetField(), describeType(field.type()), problem + "：" + summarize(value),
                                roomCode));
                    }
                }
                if ("bill".equals(category)) {
                    rowDiffs.addAll(billIdentities(sample, key, roomCode));
                }
                if (rowDiffs.isEmpty()) {
                    matched++;
                } else {
                    diffCount++;
                    for (MappingDiffItem diff : rowDiffs) {
                        if (items.size() >= MAX_DIFF_ITEMS) {
                            break;
                        }
                        items.add(diff);
                    }
                }
            }
        }
        double diffRate = checked == 0 ? 0.0 : BigDecimal.valueOf(diffCount)
                .divide(BigDecimal.valueOf(checked), 6, RoundingMode.HALF_UP).doubleValue();
        boolean passed = checked > 0 && BigDecimal.valueOf(diffRate).compareTo(BigDecimal.valueOf(threshold)) <= 0;
        return new TrialResult(checked, matched, diffCount, diffRate, passed, List.copyOf(items));
    }

    // ------------------------------------------------------------------
    // 字段完整性 / 类型
    // ------------------------------------------------------------------

    /** 取标准字段值：支持 {@code items[].xxx} 路径（要求每条子项都有该字段，返回首条值做类型判定） */
    private static Object valueOf(Map<String, Object> sample, String targetField) {
        if (targetField == null) {
            return null;
        }
        int pathIndex = targetField.indexOf("[].");
        if (pathIndex < 0) {
            return sample.get(targetField);
        }
        String container = targetField.substring(0, pathIndex);
        String child = targetField.substring(pathIndex + 3);
        Object containerValue = sample.get(container);
        if (!(containerValue instanceof Collection<?> rows) || rows.isEmpty()) {
            return null;
        }
        Object first = null;
        boolean firstAssigned = false;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                return null;
            }
            Object value = map.get(child);
            if (isBlank(value)) {
                return null;
            }
            if (!firstAssigned) {
                first = value;
                firstAssigned = true;
            }
        }
        return first;
    }

    /** 类型校验：返回 null = 通过，否则返回实际类型描述 */
    private static String typeProblem(String declaredType, Object value) {
        String type = declaredType == null ? "string" : declaredType.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "string", "text" -> value instanceof String ? null : typeName(value);
            case "number" -> value instanceof BigDecimal || value instanceof Number ? null : typeName(value);
            case "enum" -> value instanceof String text && !text.isBlank() ? null : typeName(value);
            case "datetime", "date" -> value instanceof String text && isIsoDate(text) ? null : typeName(value);
            case "array", "array<string>", "list" -> value instanceof Collection<?> || value.getClass().isArray()
                    ? null
                    : typeName(value);
            default -> null;
        };
    }

    private static boolean isIsoDate(String text) {
        return text.matches("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$");
    }

    private static String describeType(String declaredType) {
        return declaredType == null ? "string" : declaredType;
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof BigDecimal || value instanceof Number) {
            return "number";
        }
        if (value instanceof Collection<?>) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------
    // 金额恒等式（只校验 ERP 口径自洽，AI 不自算金额）
    // ------------------------------------------------------------------

    private static List<MappingDiffItem> billIdentities(Map<String, Object> sample, String key, String roomCode) {
        List<MappingDiffItem> diffs = new ArrayList<>();
        BigDecimal total = decimal(sample.get("total"));
        BigDecimal paid = decimal(sample.get("paid"));
        BigDecimal outstanding = decimal(sample.get("outstanding"));
        BigDecimal prevTotal = decimal(sample.get("prevTotal"));
        BigDecimal diff = decimal(sample.get("diff"));
        BigDecimal itemsSum = sumItemAmounts(sample.get("items"));
        if (total != null && itemsSum != null && itemsSum.compareTo(total) != 0) {
            diffs.add(new MappingDiffItem(key, MappingDiffKind.TYPE_MISMATCH, "Σitems[].amount", "total",
                    total.toPlainString(), itemsSum.toPlainString(), roomCode));
        }
        if (total != null && paid != null && outstanding != null && total.subtract(paid).compareTo(outstanding) != 0) {
            diffs.add(new MappingDiffItem(key, MappingDiffKind.TYPE_MISMATCH, "paid/outstanding", "outstanding",
                    total.subtract(paid).toPlainString(), outstanding.toPlainString(), roomCode));
        }
        if (total != null && prevTotal != null && diff != null
                && total.subtract(prevTotal).compareTo(diff) != 0) {
            diffs.add(new MappingDiffItem(key, MappingDiffKind.TYPE_MISMATCH, "prevTotal", "diff",
                    total.subtract(prevTotal).toPlainString(), diff.toPlainString(), roomCode));
        }
        return diffs;
    }

    private static BigDecimal sumItemAmounts(Object items) {
        if (!(items instanceof Collection<?> rows) || rows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                return null;
            }
            BigDecimal amount = decimal(map.get("amount"));
            if (amount == null) {
                return null;
            }
            sum = sum.add(amount);
        }
        return sum;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String summarize(Object value) {
        String text = value instanceof Collection<?> collection
                ? "array(" + collection.size() + ")"
                : String.valueOf(value);
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    /** 供报告/日志复用的字段计数（不参与判定） */
    static Map<String, Integer> fieldStats(String category, List<MappingField> fields) {
        Set<String> supported = CANONICAL_TARGETS.getOrDefault(category == null ? "" : category, Set.of());
        Map<String, Integer> stats = new LinkedHashMap<>();
        int checkedFields = 0;
        int internalFields = 0;
        if (fields != null) {
            for (MappingField field : fields) {
                if (field == null) {
                    continue;
                }
                if (supported.contains(field.targetField())) {
                    checkedFields++;
                } else {
                    internalFields++;
                }
            }
        }
        stats.put("checkedFields", checkedFields);
        stats.put("internalFields", internalFields);
        return stats;
    }
}
