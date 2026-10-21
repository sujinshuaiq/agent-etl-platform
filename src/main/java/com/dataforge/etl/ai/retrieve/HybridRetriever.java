package com.dataforge.etl.ai.retrieve;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器:关键词分(简化 BM25)+ 向量分(char bigram 哈希 256 维余弦),
 * hybridScore = 0.5 * keyword + 0.5 * vector。
 *
 * <p>中文检索无分词器,统一用字符二元组(char bigram)切分;
 * 纯本地实现,不依赖外部 embedding 服务,保证模拟模式可用。</p>
 */
@Component
public class HybridRetriever {

    private static final int VECTOR_DIM = 256;
    /** BM25 参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final KnowledgeBase knowledgeBase;

    // 预计算:每块的 bigram 词频、长度、向量,以及全局 df
    private final List<Map<String, Integer>> chunkTermFreqs = new ArrayList<>();
    private final List<double[]> chunkVectors = new ArrayList<>();
    private final Map<String, Integer> documentFreq = new HashMap<>();
    private double avgDocLength = 0;

    public HybridRetriever(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        buildIndex();
    }

    /** 检索结果项 */
    public record RetrievedChunk(String id, String title, String snippet,
                                 double keywordScore, double vectorScore, double hybridScore) {
    }

    /** 构建索引(启动一次) */
    private void buildIndex() {
        long totalLen = 0;
        for (KnowledgeBase.Chunk chunk : knowledgeBase.getChunks()) {
            String text = chunk.title() + " " + chunk.content() + " " + String.join(" ", chunk.tags());
            Map<String, Integer> tf = termFrequency(text);
            chunkTermFreqs.add(tf);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            totalLen += len;
            tf.keySet().forEach(term -> documentFreq.merge(term, 1, Integer::sum));
            chunkVectors.add(embed(text));
        }
        avgDocLength = chunkTermFreqs.isEmpty() ? 1.0 : (double) totalLen / chunkTermFreqs.size();
    }

    /**
     * 混合检索。
     *
     * @param query 查询(建议传入改写后文本)
     * @param topK  返回条数
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        Map<String, Integer> queryTf = termFrequency(query);
        double[] queryVector = embed(query);
        int docCount = chunkTermFreqs.size();

        List<RetrievedChunk> scored = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            double kw = bm25(queryTf, chunkTermFreqs.get(i), docCount);
            double vec = cosine(queryVector, chunkVectors.get(i));
            double hybrid = 0.5 * kw + 0.5 * vec;
            KnowledgeBase.Chunk chunk = knowledgeBase.getChunks().get(i);
            scored.add(new RetrievedChunk(chunk.id(), chunk.title(),
                    snippet(chunk.content()), round4(kw), round4(vec), round4(hybrid)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::hybridScore).reversed())
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
    }

    // ==================== 关键词分(简化 BM25) ====================

    private double bm25(Map<String, Integer> queryTf, Map<String, Integer> docTf, int docCount) {
        double docLen = docTf.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0;
        for (Map.Entry<String, Integer> entry : queryTf.entrySet()) {
            String term = entry.getKey();
            int tf = docTf.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFreq.getOrDefault(term, 0);
            double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
            score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLen / avgDocLength));
        }
        // 归一化:除以理论最大值(所有查询词都拿满 idf 的情况),压到 0~1 区间附近
        double maxIdfSum = queryTf.keySet().stream()
                .mapToDouble(t -> Math.log(1 + (docCount - documentFreq.getOrDefault(t, 0) + 0.5)
                        / (documentFreq.getOrDefault(t, 0) + 0.5)))
                .sum();
        return maxIdfSum <= 0 ? 0 : Math.min(1.0, score / (maxIdfSum * (K1 + 1)));
    }

    // ==================== 向量分(bigram 哈希 + 余弦) ====================

    /** char bigram 哈希到 256 维并归一化 */
    private double[] embed(String text) {
        double[] vector = new double[VECTOR_DIM];
        for (String bigram : bigrams(normalize(text))) {
            int slot = Math.floorMod(bigram.hashCode(), VECTOR_DIM);
            vector[slot] += 1.0;
        }
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < VECTOR_DIM; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0;
        for (int i = 0; i < VECTOR_DIM; i++) {
            dot += a[i] * b[i];
        }
        return Math.max(0, dot); // 归一化后余弦 ∈ [0,1]
    }

    // ==================== 工具 ====================

    /** 词频:char bigram(中文友好) */
    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String bigram : bigrams(normalize(text))) {
            tf.merge(bigram, 1, Integer::sum);
        }
        return tf;
    }

    /** 归一化:小写、去标点空白 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    private List<String> bigrams(String normalized) {
        List<String> bigrams = new ArrayList<>();
        if (normalized.length() < 2) {
            if (!normalized.isEmpty()) {
                bigrams.add(normalized);
            }
            return bigrams;
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            bigrams.add(normalized.substring(i, i + 2));
        }
        return bigrams;
    }

    private String snippet(String content) {
        return content.length() <= 80 ? content : content.substring(0, 80) + "…";
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
