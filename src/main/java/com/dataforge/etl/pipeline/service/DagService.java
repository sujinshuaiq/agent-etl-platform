package com.dataforge.etl.pipeline.service;

import com.dataforge.etl.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DAG 服务:拓扑排序(Kahn 算法)、分层(同层节点可并行执行)、环检测与结构校验。
 *
 * <p>本类不依赖任何 Spring Bean / 数据库,纯内存计算,便于单元测试。</p>
 */
@Component
public class DagService {

    /** DAG 中的有向边 */
    public record DagEdge(String fromNodeKey, String toNodeKey) {
    }

    /**
     * 校验 DAG 结构,返回错误列表(空列表表示合法)。
     * 校验项:节点键非空且不重复、边的端点必须存在、不允许自环、不允许重复边、不能有环。
     */
    public List<String> validate(Collection<String> nodeKeys, List<DagEdge> edges) {
        List<String> errors = new ArrayList<>();
        Set<String> keySet = new HashSet<>();
        for (String key : nodeKeys) {
            if (key == null || key.isBlank()) {
                errors.add("存在空的节点键(nodeKey)");
            } else if (!keySet.add(key)) {
                errors.add("节点键重复: " + key);
            }
        }
        Set<String> seenEdges = new HashSet<>();
        for (DagEdge edge : edges) {
            if (!keySet.contains(edge.fromNodeKey())) {
                errors.add("边的起点不存在: " + edge.fromNodeKey());
            }
            if (!keySet.contains(edge.toNodeKey())) {
                errors.add("边的终点不存在: " + edge.toNodeKey());
            }
            if (Objects.equals(edge.fromNodeKey(), edge.toNodeKey())) {
                errors.add("节点不允许自环: " + edge.fromNodeKey());
            }
            if (!seenEdges.add(edge.fromNodeKey() + "->" + edge.toNodeKey())) {
                errors.add("重复边: " + edge.fromNodeKey() + " -> " + edge.toNodeKey());
            }
        }
        if (errors.isEmpty() && !nodeKeys.isEmpty()) {
            // 环检测:Kahn 无法处理完的节点即环上的节点
            Set<String> cyclic = findCyclicNodes(keySet, edges);
            if (!cyclic.isEmpty()) {
                errors.add("DAG 中存在环,涉及节点: " + String.join(", ", cyclic));
            }
        }
        return errors;
    }

    /**
     * 拓扑排序(扁平顺序)。存在环时抛出 BizException。
     */
    public List<String> topologicalOrder(Collection<String> nodeKeys, List<DagEdge> edges) {
        List<List<String>> layers = layeredTopologicalOrder(nodeKeys, edges);
        List<String> order = new ArrayList<>();
        layers.forEach(order::addAll);
        return order;
    }

    /**
     * 拓扑分层:第 0 层为入度为 0 的起点节点;同一层内节点互不依赖,可并行执行。
     * 存在环时抛出 BizException,消息中包含环上节点。
     */
    public List<List<String>> layeredTopologicalOrder(Collection<String> nodeKeys, List<DagEdge> edges) {
        // 入度表与邻接表
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String key : nodeKeys) {
            inDegree.put(key, 0);
            adjacency.put(key, new ArrayList<>());
        }
        for (DagEdge edge : edges) {
            if (!inDegree.containsKey(edge.fromNodeKey()) || !inDegree.containsKey(edge.toNodeKey())) {
                throw BizException.badRequest("边引用了不存在的节点: "
                        + edge.fromNodeKey() + " -> " + edge.toNodeKey());
            }
            adjacency.get(edge.fromNodeKey()).add(edge.toNodeKey());
            inDegree.merge(edge.toNodeKey(), 1, Integer::sum);
        }

        List<List<String>> layers = new ArrayList<>();
        // 当前层:所有入度为 0 的节点(排序保证结果稳定)
        List<String> current = inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        int processed = 0;
        while (!current.isEmpty()) {
            layers.add(current);
            processed += current.size();
            List<String> next = new ArrayList<>();
            for (String key : current) {
                for (String downstream : adjacency.get(key)) {
                    int remaining = inDegree.merge(downstream, -1, Integer::sum);
                    if (remaining == 0) {
                        next.add(downstream);
                    }
                }
            }
            current = next.stream().sorted().toList();
        }
        if (processed < inDegree.size()) {
            Set<String> cyclic = findCyclicNodes(inDegree.keySet(), edges);
            throw BizException.badRequest("DAG 中存在环,涉及节点: " + String.join(", ", cyclic));
        }
        return layers;
    }

    /** 找出无法被拓扑处理掉的节点(即环上的节点) */
    private Set<String> findCyclicNodes(Collection<String> nodeKeys, List<DagEdge> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String key : nodeKeys) {
            inDegree.put(key, 0);
            adjacency.put(key, new ArrayList<>());
        }
        for (DagEdge edge : edges) {
            if (inDegree.containsKey(edge.fromNodeKey()) && inDegree.containsKey(edge.toNodeKey())) {
                adjacency.get(edge.fromNodeKey()).add(edge.toNodeKey());
                inDegree.merge(edge.toNodeKey(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.poll();
            processed.add(key);
            for (String downstream : adjacency.get(key)) {
                if (inDegree.merge(downstream, -1, Integer::sum) == 0) {
                    queue.add(downstream);
                }
            }
        }
        Set<String> cyclic = new TreeSet<>(inDegree.keySet());
        cyclic.removeAll(processed);
        return cyclic;
    }
}
