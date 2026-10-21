package com.dataforge.etl.ai.nlp;

import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位填充器:为每个意图定义必填/可选槽,从消息与「会话记忆」中抽取槽值;
 * 必填槽缺失时返回 missingSlots,由上层生成追问(多轮槽位补全)。
 */
@Component
public class SlotFiller {

    /** 槽位定义 */
    public record SlotDef(String name, boolean required, String description) {
    }

    /** 填充结果 */
    public record SlotResult(Map<String, Object> slots, List<String> missingSlots) {
        public boolean complete() {
            return missingSlots.isEmpty();
        }
    }

    private static final Pattern RUN_ID_PATTERN = Pattern.compile("(?:运行|run|RUN|#|编号)\\s*#?\\s*(\\d{1,10})");
    private static final Pattern BARE_ID_PATTERN = Pattern.compile("^\\s*#?(\\d{1,10})\\s*$");
    private static final Pattern HOURS_PATTERN = Pattern.compile("(?:近|最近)?\\s*(\\d{1,4})\\s*(?:个)?小时");

    private final PipelineRepository pipelineRepository;

    public SlotFiller(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    /** 每个意图的槽位定义(供 /api/ai/intents 展示与填充共用) */
    public Map<IntentType, List<SlotDef>> slotDefinitions() {
        Map<IntentType, List<SlotDef>> defs = new EnumMap<>(IntentType.class);
        defs.put(IntentType.QUERY_OVERVIEW, List.of());
        defs.put(IntentType.QUERY_FAILED_RUNS, List.of(new SlotDef("hours", false, "时间范围(小时,默认24)")));
        defs.put(IntentType.QUERY_RUN_DETAIL, List.of(new SlotDef("runId", true, "运行ID")));
        defs.put(IntentType.QUERY_AGENTS, List.of());
        defs.put(IntentType.QUERY_QUALITY, List.of());
        defs.put(IntentType.TRIGGER_PIPELINE, List.of(
                new SlotDef("pipelineId", true, "管道ID(或管道名)")));
        defs.put(IntentType.DIAGNOSE_RUN, List.of(
                new SlotDef("runId", true, "运行ID(或管道名,取最近一次)")));
        defs.put(IntentType.GENERATE_PIPELINE, List.of(
                new SlotDef("description", true, "管道需求描述")));
        defs.put(IntentType.KNOWLEDGE_QA, List.of(
                new SlotDef("question", true, "问题内容")));
        defs.put(IntentType.HELP, List.of());
        defs.put(IntentType.UNKNOWN, List.of());
        return defs;
    }

    /**
     * 填充槽位。
     *
     * @param intent      已识别意图
     * @param message     当前消息(改写后)
     * @param memory      会话槽位记忆(此前已收集到的槽值,可为空)
     * @param rewriteTime 改写器归一化的时间范围(可为空)
     */
    public SlotResult fill(IntentType intent, String message, Map<String, Object> memory,
                           QueryRewriter.RewriteResult rewrite) {
        Map<String, Object> slots = new LinkedHashMap<>(memory == null ? Map.of() : memory);
        List<SlotDef> defs = slotDefinitions().getOrDefault(intent, List.of());
        if (defs.isEmpty()) {
            return new SlotResult(slots, List.of());
        }
        String text = message == null ? "" : message;

        for (SlotDef def : defs) {
            if (slots.containsKey(def.name()) && slots.get(def.name()) != null) {
                continue; // 会话记忆里已有
            }
            switch (def.name()) {
                case "runId" -> extractRunId(text).ifPresent(id -> slots.put("runId", id));
                case "pipelineId" -> extractPipelineId(text).ifPresent(id -> slots.put("pipelineId", id));
                case "hours" -> {
                    Integer hours = extractHours(text, rewrite);
                    if (hours != null) {
                        slots.put("hours", hours);
                    }
                }
                case "description", "question" -> slots.put(def.name(), text);
                default -> {
                }
            }
        }

        // 汇总缺失必填槽
        List<String> missing = new ArrayList<>();
        for (SlotDef def : defs) {
            if (def.required() && slots.get(def.name()) == null) {
                missing.add(def.name());
            }
        }
        return new SlotResult(slots, missing);
    }

    /** 抽取运行 ID:「运行 123」「run #123」、消息中独立出现的数字,或整条消息就是数字 */
    public Optional<Long> extractRunId(String text) {
        Matcher m = RUN_ID_PATTERN.matcher(text);
        if (m.find()) {
            return Optional.of(Long.parseLong(m.group(1)));
        }
        // 独立数字(前后不是数字/字母),用于槽位补全时用户只回一个数字的场景
        Matcher standalone = Pattern.compile("(?<![\\dA-Za-z])#?(\\d{1,10})(?![\\dA-Za-z])").matcher(text);
        if (standalone.find()) {
            return Optional.of(Long.parseLong(standalone.group(1)));
        }
        return Optional.empty();
    }

    /** 抽取管道 ID:「管道 3」/裸数字,或按管道名称模糊匹配 */
    public Optional<Long> extractPipelineId(String text) {
        Matcher bare = BARE_ID_PATTERN.matcher(text);
        if (bare.matches()) {
            return Optional.of(Long.parseLong(bare.group(1)));
        }
        Matcher m = Pattern.compile("管道\\s*#?\\s*(\\d{1,10})").matcher(text);
        if (m.find()) {
            return Optional.of(Long.parseLong(m.group(1)));
        }
        // 管道名模糊匹配
        for (Pipeline pipeline : pipelineRepository.findAll()) {
            if (pipeline.getName() != null && text.contains(pipeline.getName())) {
                return Optional.of(pipeline.getId());
            }
        }
        return Optional.empty();
    }

    /** 抽取小时数:「近48小时」/「48小时」;时间词映射(今天=24h,近7天=168h) */
    public Integer extractHours(String text, QueryRewriter.RewriteResult rewrite) {
        Matcher m = HOURS_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        if (rewrite != null && rewrite.timeFrom() != null && rewrite.timeTo() != null) {
            long hours = java.time.Duration.between(rewrite.timeFrom(), rewrite.timeTo()).toHours();
            if (hours > 0 && hours <= 720) {
                return (int) hours;
            }
        }
        return null;
    }
}
