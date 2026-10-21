package com.dataforge.etl.ai.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HallucinationGuard 单元测试:数字溯源、引用校验、置信度。
 */
class HallucinationGuardTest {

    private final HallucinationGuard guard = new HallucinationGuard();

    @Test
    void 数字可溯源_原样通过() {
        List<String> evidence = List.of("{\"runTotal\":860,\"agentTotal\":10}");
        HallucinationGuard.GuardResult result = guard.guard(
                "平台累计运行 860 次,共 10 个 Agent。", evidence, false);
        assertTrue(result.reply().contains("860"));
        assertTrue(result.reply().contains("10"));
        assertEquals(1.0, result.confidence());
    }

    @Test
    void 数字不可溯源_替换为保守表述() {
        List<String> evidence = List.of("{\"runTotal\":860}");
        HallucinationGuard.GuardResult result = guard.guard(
                "平台累计运行 860 次,失败 999 次。", evidence, false);
        assertTrue(result.reply().contains("860"));
        assertFalse(result.reply().contains("999"), "不可溯源数字应被替换");
        assertTrue(result.reply().contains("若干"));
        assertTrue(result.confidence() < 1.0);
        assertTrue(result.guardNotes().stream().anyMatch(n -> n.contains("999")));
    }

    @Test
    void RAG回复缺少引用_降级为资料不足() {
        HallucinationGuard.GuardResult result = guard.guard(
                "数据质量就是数据很好。", List.of(), true);
        assertTrue(result.reply().contains("资料不足"));
        assertTrue(result.confidence() <= 0.7);
    }

    @Test
    void RAG回复带引用_正常通过() {
        HallucinationGuard.GuardResult result = guard.guard(
                "平台有六类质量检查[资料1]。", List.of(), true);
        assertTrue(result.reply().contains("[资料1]"));
        assertEquals(1.0, result.confidence());
    }

    @Test
    void 引用标号中的数字不参与溯源() {
        // [资料12] 中的 12 不应被当作业务数字溯源
        HallucinationGuard.GuardResult result = guard.guard(
                "平台有六类质量检查[资料12]。", List.of(), true);
        assertEquals(1.0, result.confidence());
    }

    @Test
    void 置信度下限为零() {
        List<String> evidence = List.of("无数据");
        HallucinationGuard.GuardResult result = guard.guard(
                "共 100 次 200 次 300 次 400 次 500 次 600 次", evidence, true);
        assertTrue(result.confidence() >= 0.0 && result.confidence() <= 1.0);
    }
}
