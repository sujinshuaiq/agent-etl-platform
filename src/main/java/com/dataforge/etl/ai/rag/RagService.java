package com.dataforge.etl.ai.rag;

import com.dataforge.etl.ai.client.LlmClient;
import com.dataforge.etl.ai.retrieve.HybridRetriever;
import com.dataforge.etl.ai.retrieve.KnowledgeBase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 服务:混合检索 top3 → 拼 grounded prompt → 生成带引用的回答。
 * 模拟模式:基于检索到的资料内容模板化拼接回答(仍带引用);
 * 真实模式:让 LLM 仅依据资料回答,句末带 [资料x] 引用。
 */
@Service
public class RagService {

    /** 检索分低于该阈值视为「资料不足」 */
    private static final double MIN_HYBRID_SCORE = 0.05;

    private final HybridRetriever retriever;
    private final KnowledgeBase knowledgeBase;
    private final LlmClient llmClient;

    public RagService(HybridRetriever retriever, KnowledgeBase knowledgeBase, LlmClient llmClient) {
        this.retriever = retriever;
        this.knowledgeBase = knowledgeBase;
        this.llmClient = llmClient;
    }

    /** RAG 回答结果(evidenceTexts 为资料全文,供幻觉治理做数字溯源) */
    public record RagAnswer(String answer, List<Map<String, Object>> citations, boolean grounded,
                            List<String> evidenceTexts) {
    }

    /** 回答知识类问题(question 建议为改写后文本) */
    public RagAnswer answer(String question) {
        List<HybridRetriever.RetrievedChunk> top = retriever.retrieve(question, 3);
        // 资料不足判定
        if (top.isEmpty() || top.get(0).hybridScore() < MIN_HYBRID_SCORE) {
            return new RagAnswer("资料不足:知识库中没有找到与「" + question + "」足够相关的内容,"
                    + "请换个问法,或查阅 docs/ 目录下的文档。", List.of(), false, List.of());
        }

        List<Map<String, Object>> citations = new ArrayList<>();
        List<String> evidenceTexts = new ArrayList<>();
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            HybridRetriever.RetrievedChunk chunk = top.get(i);
            Map<String, Object> cite = new LinkedHashMap<>();
            cite.put("id", chunk.id());
            cite.put("title", chunk.title());
            cite.put("snippet", chunk.snippet());
            citations.add(cite);
            String content = fullContent(chunk.id());
            evidenceTexts.add(content);
            material.append("[资料").append(i + 1).append("] ").append(chunk.title())
                    .append(":").append(content).append("\n");
        }

        // 真实模式:LLM 基于资料生成,失败回落模板
        if (!llmClient.isMock()) {
            String llmAnswer = llmClient.chat(
                    "你是 DataForge 平台的运维助手。仅依据下面提供的资料回答用户问题;"
                            + "回答使用简洁中文;每个关键句末尾标注引用来源,格式 [资料1]/[资料2];"
                            + "资料不足以回答时明确说「资料不足」,不要编造。\n\n资料:\n" + material,
                    question);
            if (llmAnswer != null && !llmAnswer.isBlank()) {
                return new RagAnswer(llmAnswer.trim(), citations, true, evidenceTexts);
            }
        }

        // 模拟模式:模板化拼接(首条资料为主,第二条补充),强制带引用
        StringBuilder reply = new StringBuilder("根据平台资料:");
        for (int i = 0; i < Math.min(2, top.size()); i++) {
            reply.append(fullContent(top.get(i).id())).append("[资料").append(i + 1).append("]");
            if (i == 0 && top.size() > 1) {
                reply.append(" 另外,");
            }
        }
        return new RagAnswer(reply.toString(), citations, true, evidenceTexts);
    }

    private String fullContent(String chunkId) {
        return knowledgeBase.getChunks().stream()
                .filter(c -> c.id().equals(chunkId))
                .map(KnowledgeBase.Chunk::content)
                .findFirst().orElse("");
    }
}
