package com.dataforge.etl.ai.nlp;

import com.dataforge.etl.ai.AiProperties;
import com.dataforge.etl.ai.client.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentRecognizer 单元测试(模拟模式下规则路径)。
 */
class IntentRecognizerTest {

    private IntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        // apiKey 为空 → 模拟模式,只走规则
        AiProperties properties = new AiProperties();
        properties.setApiKey("");
        recognizer = new IntentRecognizer(new LlmClient(properties, new ObjectMapper()));
    }

    @Test
    void 总览意图() {
        assertEquals(IntentType.QUERY_OVERVIEW, recognizer.recognize("平台现在怎么样").intent());
        assertEquals(IntentType.QUERY_OVERVIEW, recognizer.recognize("给我看总览").intent());
    }

    @Test
    void 失败运行意图() {
        assertEquals(IntentType.QUERY_FAILED_RUNS, recognizer.recognize("最近有失败的运行吗").intent());
        assertEquals(IntentType.QUERY_FAILED_RUNS, recognizer.recognize("有管道挂了吗").intent());
    }

    @Test
    void 诊断意图() {
        assertEquals(IntentType.DIAGNOSE_RUN, recognizer.recognize("帮我诊断一下").intent());
        assertEquals(IntentType.DIAGNOSE_RUN, recognizer.recognize("诊断运行 123").intent());
    }

    @Test
    void 触发意图() {
        assertEquals(IntentType.TRIGGER_PIPELINE, recognizer.recognize("触发管道 1").intent());
        assertEquals(IntentType.TRIGGER_PIPELINE, recognizer.recognize("运行一下用户日更管道").intent());
    }

    @Test
    void 生成管道意图() {
        assertEquals(IntentType.GENERATE_PIPELINE, recognizer.recognize("帮我创建一个同步管道").intent());
    }

    @Test
    void 知识问答意图() {
        assertEquals(IntentType.KNOWLEDGE_QA, recognizer.recognize("什么是数据质量").intent());
    }

    @Test
    void Agent与质量意图() {
        assertEquals(IntentType.QUERY_AGENTS, recognizer.recognize("Agent 都在线吗").intent());
        assertEquals(IntentType.QUERY_QUALITY, recognizer.recognize("质量通过率怎么样").intent());
    }

    @Test
    void 未识别() {
        assertEquals(IntentType.UNKNOWN, recognizer.recognize("今天天气不错").intent());
        assertEquals(IntentType.UNKNOWN, recognizer.recognize("").intent());
    }

    @Test
    void 置信度范围() {
        IntentRecognizer.IntentResult result = recognizer.recognize("平台现在怎么样");
        assertTrue(result.confidence() > 0.5 && result.confidence() <= 1.0);
    }
}
