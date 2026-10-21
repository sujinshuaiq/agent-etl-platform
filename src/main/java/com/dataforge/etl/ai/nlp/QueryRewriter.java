package com.dataforge.etl.ai.nlp;

import com.dataforge.etl.ai.client.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询改写器:把口语化的原始问题归一化为结构化查询。
 *
 * <p>规则链:
 * 1. 中文时间词归一化(今天/昨天/近7天/本周/上周/近N小时 → 具体 [from, to] 区间);
 * 2. 同义词替换(挂了/报错/翻车 → 失败,流水线/管线 → 管道,机器人/智能体 → Agent);
 * 3. 礼貌停用词清理(请/帮我/一下/呢/啊 等);
 * 4. 真实模式下再让 LLM 做一轮语义改写(失败则保留规则结果)。</p>
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    /** 同义词表:口语 → 平台术语 */
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>() {{
        put("挂了", "失败");
        put("挂掉", "失败");
        put("报错", "失败");
        put("翻车", "失败");
        put("出错", "失败");
        put("流水线", "管道");
        put("管线", "管道");
        put("机器人", "Agent");
        put("智能体", "Agent");
        put("概览", "总览");
        put("跑一下", "运行");
    }};

    /** 礼貌/语气停用词 */
    private static final List<String> STOPWORDS = List.of(
            "麻烦你", "麻烦", "请帮我", "帮我", "请", "一下", "可以吗", "好不好", "呢", "啊", "呀", "吧", "哦");

    private final LlmClient llmClient;

    public QueryRewriter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 改写结果 */
    public record RewriteResult(String original, String rewritten, List<String> expansions,
                                LocalDateTime timeFrom, LocalDateTime timeTo) {
    }

    /** 完整改写入口(规则 + 可选 LLM 润色) */
    public RewriteResult rewrite(String query) {
        RewriteResult ruleResult = rewriteByRules(query);
        if (llmClient.isMock() || query == null || query.isBlank()) {
            return ruleResult;
        }
        // 真实模式:LLM 再改写一轮,失败时保留规则结果
        try {
            String polished = llmClient.chat(
                    "你是查询改写器。把用户的口语化中文问题改写为简洁的检索查询,保留关键实体(运行ID、管道名、时间范围),"
                            + "只输出改写后的查询,不要解释。",
                    query);
            if (polished != null && !polished.isBlank()) {
                return new RewriteResult(query, polished.trim(), ruleResult.expansions(),
                        ruleResult.timeFrom(), ruleResult.timeTo());
            }
        } catch (Exception e) {
            log.warn("LLM 改写失败,使用规则改写结果: {}", e.getMessage());
        }
        return ruleResult;
    }

    /** 纯规则改写(可独立单测) */
    public RewriteResult rewriteByRules(String query) {
        if (query == null) {
            query = "";
        }
        String rewritten = query.trim();
        List<String> expansions = new ArrayList<>();

        // 1. 时间归一化
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = null, to = null;
        if (rewritten.contains("今天")) {
            from = LocalDate.now().atStartOfDay();
            to = now;
            expansions.add("今天 → " + LocalDate.now());
        } else if (rewritten.contains("昨天")) {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            from = yesterday.atStartOfDay();
            to = yesterday.atTime(LocalTime.MAX.withNano(0));
            expansions.add("昨天 → " + yesterday);
        } else if (rewritten.contains("近7天") || rewritten.contains("最近7天") || rewritten.contains("近一周")) {
            from = now.minusDays(7);
            to = now;
            expansions.add("近7天 → " + from.toLocalDate() + " ~ " + to.toLocalDate());
        } else if (rewritten.contains("本周")) {
            LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
            from = monday.atStartOfDay();
            to = now;
            expansions.add("本周 → " + monday + " 起");
        } else if (rewritten.contains("上周")) {
            LocalDate lastMonday = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1);
            from = lastMonday.atStartOfDay();
            to = lastMonday.plusDays(6).atTime(LocalTime.MAX.withNano(0));
            expansions.add("上周 → " + lastMonday + " ~ " + lastMonday.plusDays(6));
        } else if (rewritten.contains("近24小时") || rewritten.contains("最近24小时")) {
            from = now.minusHours(24);
            to = now;
            expansions.add("近24小时");
        }

        // 2. 同义词替换
        for (Map.Entry<String, String> entry : SYNONYMS.entrySet()) {
            if (rewritten.contains(entry.getKey())) {
                rewritten = rewritten.replace(entry.getKey(), entry.getValue());
                expansions.add(entry.getKey() + " → " + entry.getValue());
            }
        }

        // 3. 停用词清理
        for (String stopword : STOPWORDS) {
            rewritten = rewritten.replace(stopword, "");
        }
        rewritten = rewritten.replaceAll("\\s+", " ").trim();
        return new RewriteResult(query, rewritten, expansions, from, to);
    }
}
