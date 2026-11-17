package com.property.ai.domain.knowledge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.property.ai.bff.dto.KnowledgeSource;
import com.property.ai.domain.ticket.DomainException;

/**
 * 知识域公共口径（P4-005/006）：日期归一、检索分词、引用串拼装。
 *
 * <p>为什么单独一个包内工具类：知识读/检索/统计三个服务必须共用<b>同一套</b>分词与日期口径，
 * 否则「搜索命中」与「统计缺口」会各算一套（口径漂移）。本类无状态、纯函数，全部方法可单测。
 *
 * <h2>检索分词口径（演示级中文分词，不引外部依赖）</h2>
 * 中文问题词（请问/可以/怎么/几点/如何…）不代表知识主题，先按词面剥离，再按空白/标点切分实义片段，
 * 例如 {@code 「装修可以几点施工」→ [装修施工]}、{@code 「公摊电费怎么算」→ [公摊电费算]}。
 * 命中判定同时支持「片段包含关键词」与「关键词包含片段」（双向包含），避免词面长短不一漏检。
 * <b>只做词面匹配，不做语义猜测</b>：无命中返回空数组（AI 必须拒答，不得编造）。
 */
final class KnowledgeSupport {

    /** 契约日期格式（{@code YYYY-MM-DD}）。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 疑问/语气词（按长度倒序剥离，避免先删短词破坏长词）。 */
    private static final List<String> QUESTION_PHRASES = List.of(
            "请问一下", "我想问一下", "想问一下", "可不可以", "能不能", "是否可以", "是不是",
            "为什么", "怎么样", "怎么办", "怎么", "怎样", "如何", "什么", "几点", "多少", "多久",
            "哪里", "哪个", "哪些", "请问", "想问", "是否", "需要", "可以", "我们", "我家", "一下");

    /** 单独出现的单字虚词（只在「整段就是该字」时丢弃，不从词内剥离，避免破坏「自有车位」等词）。 */
    private static final Set<String> SINGLE_STOP_WORDS = Set.of(
            "的", "是", "有", "我", "你", "吗", "呢", "吧", "啊", "呀", "了", "和", "与", "及", "在", "要", "能", "会");

    /** 检索词上限（契约：{@code q} ≤ 200 字符）。 */
    static final int MAX_QUERY_LENGTH = 200;
    /** 单次检索返回上限（契约：默认 10，上限 50）。 */
    static final int MAX_SEARCH_LIMIT = 50;
    /** 缺口主题最大长度（统计展示用）。 */
    private static final int MAX_TOPIC_LENGTH = 40;

    private KnowledgeSupport() {
    }

    // ------------------------------------------------------------------
    // 日期
    // ------------------------------------------------------------------

    /** 宽松解析契约日期；空/格式非法返回 {@code null}（不抛异常，用于读路径的防御）。 */
    static LocalDate dateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** 严格解析入参日期：缺失/格式非法 → 400 {@code E-1001}（{@code details.field} 指明字段）。 */
    static LocalDate requireDate(String field, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw DomainException.badRequest("日期参数必填（" + field + "，格式 YYYY-MM-DD）",
                    Map.of("field", field, "format", "YYYY-MM-DD", "required", true));
        }
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException ex) {
            throw DomainException.badRequest("日期参数格式非法（" + field + "，应为 YYYY-MM-DD）",
                    Map.of("field", field, "value", text, "format", "YYYY-MM-DD"));
        }
    }

    // ------------------------------------------------------------------
    // 检索分词 / 主题聚类
    // ------------------------------------------------------------------

    /** 归一化检索词（小写 + 去首尾空白）。 */
    static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 检索分词：剥离疑问词后按空白/标点切分实义片段（长度 ≥2），全为单字/虚词时退回整串。
     *
     * <p>示例：{@code 「装修可以几点施工」→ [装修, 施工]}、{@code 「公摊电费怎么算」→ [公摊电费]}、
     * {@code 「停水」→ [停水]}。单字片段不参与匹配（避免「一个水字命中一堆知识」的假命中），
     * 但整句就是一个字时保留（{@code 「水」→ [水]}）。
     *
     * @return 去重后的实义片段（保序）；空串返回空列表
     */
    static List<String> tokens(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        for (String rawChunk : normalized.split("[\\s,，。.、;；:：!！?？()（）\\[\\]【】\"'“”‘’/]+")) {
            String stripped = stripQuestionPhrases(rawChunk);
            for (String piece : stripped.split("\\s+")) {
                String token = piece.trim();
                if (token.isEmpty() || SINGLE_STOP_WORDS.contains(token)) {
                    continue;
                }
                pieces.add(token);
            }
        }
        List<String> result = pieces.stream().filter(piece -> piece.length() >= 2).distinct().toList();
        if (!result.isEmpty()) {
            return result;
        }
        String fallback = normalized.replaceAll("\\s+", "");
        return fallback.isEmpty() ? List.of() : List.of(fallback);
    }

    /** 把疑问词替换成分隔符（不拼接，保留实义片段边界）。 */
    private static String stripQuestionPhrases(String raw) {
        String chunk = raw == null ? "" : raw.trim();
        for (String phrase : QUESTION_PHRASES) {
            if (chunk.contains(phrase)) {
                chunk = chunk.replace(phrase, " ");
            }
        }
        return chunk.trim();
    }

    /** 缺口主题：分词结果拼回（便于运营聚类），无实义片段时退回截断的原串。 */
    static String topic(String query) {
        List<String> tokens = tokens(query);
        String topic = tokens.isEmpty() ? normalize(query) : String.join(" ", tokens);
        return topic.length() <= MAX_TOPIC_LENGTH ? topic : topic.substring(0, MAX_TOPIC_LENGTH);
    }

    // ------------------------------------------------------------------
    // 引用串（契约 KnowledgeSearchHit.cite：可直接展示，前端不得自行拼接）
    // ------------------------------------------------------------------

    /**
     * 引用串：{@code K-018 V1.2 · 装修管理规定 §3.1}（ID + 版本 + 出处）。
     * 出处缺失时退化为 {@code K-018 V1.2}（仍带 ID/版本四要素之三，不编造文档名）。
     */
    static String cite(String knowledgeId, String version, KnowledgeSource source) {
        StringBuilder builder = new StringBuilder();
        builder.append(knowledgeId == null ? "" : knowledgeId);
        if (version != null && !version.isBlank()) {
            builder.append(' ').append(version.trim());
        }
        String doc = source == null ? null : source.doc();
        if (doc != null && !doc.isBlank()) {
            builder.append(" · ").append(doc.trim());
            String clause = source.clause();
            if (clause != null && !clause.isBlank()) {
                builder.append(' ').append(clause.trim());
            }
        }
        return builder.toString();
    }
}
