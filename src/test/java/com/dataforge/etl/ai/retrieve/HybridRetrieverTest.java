package com.dataforge.etl.ai.retrieve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HybridRetriever 单元测试:排序合理性与三项分数。
 */
class HybridRetrieverTest {

    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new HybridRetriever(new KnowledgeBase());
    }

    @Test
    void 相关查询命中相关文档() {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve("管道失败了怎么排查", 3);
        assertEquals(3, top.size());
        // 最相关的应是 FAQ-排查 知识块
        assertEquals("kb-020", top.get(0).id());
    }

    @Test
    void 质量概念查询命中质量文档() {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve("数据质量有哪些检查", 3);
        assertTrue(top.stream().anyMatch(c -> c.id().equals("kb-012") || c.id().equals("kb-013")));
    }

    @Test
    void 三项分数都存在且hybrid在区间内() {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve("什么是 ETL", 5);
        for (HybridRetriever.RetrievedChunk chunk : top) {
            assertTrue(chunk.keywordScore() >= 0, "keywordScore 应非负");
            assertTrue(chunk.vectorScore() >= 0 && chunk.vectorScore() <= 1.0, "vectorScore 应在 [0,1]");
            double expectedHybrid = 0.5 * chunk.keywordScore() + 0.5 * chunk.vectorScore();
            assertEquals(expectedHybrid, chunk.hybridScore(), 0.001, "hybridScore 应为两项均值");
        }
    }

    @Test
    void 按混合分降序排列() {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve("Agent 离线怎么办", 5);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).hybridScore() >= top.get(i).hybridScore(),
                    "结果应按 hybridScore 降序");
        }
    }

    @Test
    void topK生效() {
        assertEquals(1, retriever.retrieve("平台", 1).size());
        assertEquals(5, retriever.retrieve("平台", 5).size());
    }

    @Test
    void 完全不相关查询分数很低() {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve("量子引力波烹饪指南", 3);
        assertTrue(top.get(0).hybridScore() < 0.3, "无关查询最高分应很低,实际: " + top.get(0).hybridScore());
    }
}
