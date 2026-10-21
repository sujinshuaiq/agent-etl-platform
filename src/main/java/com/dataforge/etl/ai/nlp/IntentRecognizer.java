package com.dataforge.etl.ai.nlp;

import com.dataforge.etl.ai.client.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图识别器:规则优先(关键词+正则,高置信度),规则不命中且非模拟模式时
 * 让 LLM 在枚举清单中分类;都不命中返回 UNKNOWN。
 */
@Component
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    /** 识别结果 */
    public record IntentResult(IntentType intent, double confidence, String hitRule) {
    }

    /** 规则定义:正则 + 意图 + 置信度(按顺序优先匹配) */
    private record Rule(Pattern pattern, IntentType intent, double confidence, String name) {
    }

    private final List<Rule> rules = new ArrayList<>();

    private final LlmClient llmClient;

    public IntentRecognizer(LlmClient llmClient) {
        this.llmClient = llmClient;
        // 注意顺序:更具体的规则在前
        rules.add(new Rule(regex("诊断|排查|为什么失败|看下.*原因|看看.*原因"), IntentType.DIAGNOSE_RUN, 0.92, "诊断关键词"));
        rules.add(new Rule(regex("触发|跑一?下|运行.*管道|执行.*管道|启动.*管道"), IntentType.TRIGGER_PIPELINE, 0.90, "触发关键词"));
        rules.add(new Rule(regex("生成|创建|新建|设计.*管道|搭建.*管道"), IntentType.GENERATE_PIPELINE, 0.88, "生成关键词"));
        rules.add(new Rule(regex("运行.*详情|详情.*运行|运行\\s*#?\\d+|run\\s*#?\\d+|第\\s*\\d+\\s*次运行"), IntentType.QUERY_RUN_DETAIL, 0.85, "运行详情"));
        // 知识问答需在质量/总览之前:避免「什么是数据质量」被「质量」误吞
        rules.add(new Rule(regex("什么是|是什么|介绍|解释|概念|怎么理解|如何理解|原理|区别"), IntentType.KNOWLEDGE_QA, 0.75, "知识问答"));
        rules.add(new Rule(regex("失败|挂了|报错|翻车|出错|异常"), IntentType.QUERY_FAILED_RUNS, 0.85, "失败关键词"));
        // 质量规则需在总览之前:避免「质量通过率怎么样」被「怎么样」误吞
        rules.add(new Rule(regex("质量|质检|通过率|检查.*规则"), IntentType.QUERY_QUALITY, 0.82, "质量关键词"));
        rules.add(new Rule(regex("总览|概览|怎么样|整体|大盘|状态如何|情况如何|概况"), IntentType.QUERY_OVERVIEW, 0.85, "总览关键词"));
        rules.add(new Rule(regex("agent|机器人|智能体|节点状态|在线.*几"), IntentType.QUERY_AGENTS, 0.85, "Agent关键词"));
        rules.add(new Rule(regex("帮助|你会什么|怎么用|如何用|功能|help"), IntentType.HELP, 0.95, "帮助关键词"));
    }

    private static Pattern regex(String expr) {
        return Pattern.compile(expr, Pattern.CASE_INSENSITIVE);
    }

    /**
     * 识别意图。
     *
     * @param message 原始消息(建议传入改写后的文本)
     */
    public IntentResult recognize(String message) {
        if (message == null || message.isBlank()) {
            return new IntentResult(IntentType.UNKNOWN, 0.0, "空消息");
        }
        // 1. 规则匹配
        for (Rule rule : rules) {
            if (rule.pattern().matcher(message).find()) {
                return new IntentResult(rule.intent(), rule.confidence(), rule.name());
            }
        }
        // 2. 规则未命中:真实模式让 LLM 在枚举清单中分类
        if (!llmClient.isMock()) {
            IntentResult llmResult = recognizeByLlm(message);
            if (llmResult != null) {
                return llmResult;
            }
        }
        return new IntentResult(IntentType.UNKNOWN, 0.3, "未命中任何规则");
    }

    /** LLM 兜底分类 */
    private IntentResult recognizeByLlm(String message) {
        try {
            StringBuilder candidates = new StringBuilder();
            for (IntentType type : IntentType.values()) {
                candidates.append(type.name()).append(" ");
            }
            String answer = llmClient.chat(
                    "你是意图分类器。把用户消息分类为以下意图之一:" + candidates
                            + "。只输出意图名,不要输出其它内容。",
                    message);
            if (answer != null) {
                String name = answer.trim().toUpperCase().replaceAll("[^A-Z_]", "");
                for (IntentType type : IntentType.values()) {
                    if (type.name().equals(name)) {
                        return new IntentResult(type, 0.6, "LLM分类");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LLM 意图分类失败: {}", e.getMessage());
        }
        return null;
    }
}
