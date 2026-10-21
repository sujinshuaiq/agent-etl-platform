package com.dataforge.etl.ai.nlp;

import com.dataforge.etl.ai.AiProperties;
import com.dataforge.etl.ai.client.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRewriter 单元测试:同义词、停用词、时间归一化。
 */
class QueryRewriterTest {

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.setApiKey(""); // 模拟模式:只走规则
        rewriter = new QueryRewriter(new LlmClient(properties, new ObjectMapper()));
    }

    @Test
    void 同义词替换() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("有管道挂了吗");
        assertTrue(r.rewritten().contains("失败"));
        assertTrue(r.expansions().stream().anyMatch(e -> e.contains("挂了")));

        QueryRewriter.RewriteResult r2 = rewriter.rewriteByRules("流水线报错了吗");
        assertTrue(r2.rewritten().contains("管道"));
        assertTrue(r2.rewritten().contains("失败"));
    }

    @Test
    void 停用词清理() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("请帮我看看平台总览一下吧");
        assertFalse(r.rewritten().contains("请"));
        assertFalse(r.rewritten().contains("帮我"));
        assertFalse(r.rewritten().contains("一下"));
        assertTrue(r.rewritten().contains("总览"));
    }

    @Test
    void 时间归一化_今天() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("今天有失败的运行吗");
        assertNotNull(r.timeFrom());
        assertNotNull(r.timeTo());
        assertEquals(LocalDate.now(), r.timeFrom().toLocalDate());
    }

    @Test
    void 时间归一化_昨天() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("昨天的运行情况");
        assertEquals(LocalDate.now().minusDays(1), r.timeFrom().toLocalDate());
        assertEquals(LocalDate.now().minusDays(1), r.timeTo().toLocalDate());
    }

    @Test
    void 时间归一化_近7天() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("近7天的趋势");
        assertEquals(LocalDate.now().minusDays(7), r.timeFrom().toLocalDate());
    }

    @Test
    void 无时间词_时间为空() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules("平台总览");
        assertNull(r.timeFrom());
        assertNull(r.timeTo());
    }

    @Test
    void 空输入不抛异常() {
        QueryRewriter.RewriteResult r = rewriter.rewriteByRules(null);
        assertEquals("", r.rewritten());
    }
}
