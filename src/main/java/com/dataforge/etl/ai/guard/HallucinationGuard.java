package com.dataforge.etl.ai.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幻觉治理守卫:对 AI 助手的最终回复做出站前校验。
 *
 * <p>规则:
 * 1. 数字溯源:回复中 ≥2 位的数字必须能在证据文本(工具返回/检索内容)中找到,
 *    找不到则替换为保守表述并记录 guardNote;
 * 2. RAG 引用:RAG 回答(needCitation=true)必须包含 [资料x] 引用,否则降级为「资料不足」模板;
 * 3. 置信度:初始 1.0,每处溯源失败 -0.2,引用降级 -0.3,区间 [0,1]。</p>
 */
@Component
public class HallucinationGuard {

    /** 两位及以上整数或小数(跳过 [资料1] 这类引用标号) */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{2,}(?:\\.\\d+)?");
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[资料\\d+\\]");

    /** 治理结果 */
    public record GuardResult(String reply, double confidence, List<String> guardNotes) {
    }

    /**
     * @param reply         待校验回复
     * @param evidenceTexts 证据文本列表(工具返回 JSON、检索内容等)
     * @param needCitation  是否 RAG 回答(必须带引用)
     */
    public GuardResult guard(String reply, List<String> evidenceTexts, boolean needCitation) {
        List<String> notes = new ArrayList<>();
        double confidence = 1.0;
        String checked = reply == null ? "" : reply;

        // 1. 数字溯源(先定位引用标号区间,区间内数字不参与溯源)
        List<int[]> citationSpans = new ArrayList<>();
        Matcher citationFinder = CITATION_PATTERN.matcher(checked);
        while (citationFinder.find()) {
            citationSpans.add(new int[]{citationFinder.start(), citationFinder.end()});
        }
        Matcher matcher = NUMBER_PATTERN.matcher(checked);
        StringBuffer fixed = new StringBuffer();
        while (matcher.find()) {
            String number = matcher.group();
            if (insideSpan(matcher.start(), citationSpans)) {
                matcher.appendReplacement(fixed, Matcher.quoteReplacement(number));
                continue;
            }
            if (!traceable(number, evidenceTexts)) {
                notes.add("数字「" + number + "」无法在工具/检索数据中溯源,已替换为保守表述");
                confidence -= 0.2;
                matcher.appendReplacement(fixed, "若干");
            } else {
                matcher.appendReplacement(fixed, Matcher.quoteReplacement(number));
            }
        }
        matcher.appendTail(fixed);
        checked = fixed.toString();

        // 2. RAG 引用校验
        if (needCitation && !CITATION_PATTERN.matcher(checked).find()) {
            notes.add("RAG 回答缺少资料引用,已降级为「资料不足」模板");
            confidence -= 0.3;
            checked = "资料不足:该问题未能基于知识库资料可靠回答,请换个问法或查阅 docs/ 文档。";
        }

        confidence = Math.max(0, Math.round(confidence * 100.0) / 100.0);
        if (notes.isEmpty()) {
            notes.add("全部数字均可在证据中溯源,校验通过");
        }
        return new GuardResult(checked, confidence, notes);
    }

    /** 判断位置是否处于引用标号区间内 */
    private boolean insideSpan(int position, List<int[]> spans) {
        for (int[] span : spans) {
            if (position >= span[0] && position < span[1]) {
                return true;
            }
        }
        return false;
    }

    /** 数字是否出现在任一证据文本中(支持 "1,000" 千分位与直写两种形式) */
    private boolean traceable(String number, List<String> evidenceTexts) {
        if (evidenceTexts == null) {
            return false;
        }
        String plain = number.replace(",", "");
        for (String evidence : evidenceTexts) {
            if (evidence == null) {
                continue;
            }
            if (evidence.contains(number) || evidence.contains(plain)) {
                return true;
            }
        }
        return false;
    }
}
