package com.dataforge.etl.pipeline.service;

import com.dataforge.etl.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DagService 单元测试:拓扑排序、分层并行、环检测、结构校验。
 */
class DagServiceTest {

    private final DagService dagService = new DagService();

    @Test
    void 拓扑排序_链式依赖() {
        List<String> order = dagService.topologicalOrder(Set.of("a", "b", "c"),
                List.of(new DagService.DagEdge("a", "b"), new DagService.DagEdge("b", "c")));
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void 拓扑排序_菱形依赖_保证先后关系() {
        List<String> order = dagService.topologicalOrder(Set.of("a", "b", "c", "d"),
                List.of(new DagService.DagEdge("a", "b"), new DagService.DagEdge("a", "c"),
                        new DagService.DagEdge("b", "d"), new DagService.DagEdge("c", "d")));
        assertEquals(4, order.size());
        assertTrue(order.indexOf("a") < order.indexOf("b"));
        assertTrue(order.indexOf("a") < order.indexOf("c"));
        assertTrue(order.indexOf("b") < order.indexOf("d"));
        assertTrue(order.indexOf("c") < order.indexOf("d"));
    }

    @Test
    void 拓扑分层_同层可并行() {
        List<List<String>> layers = dagService.layeredTopologicalOrder(Set.of("a", "b", "c", "d"),
                List.of(new DagService.DagEdge("a", "b"), new DagService.DagEdge("a", "c"),
                        new DagService.DagEdge("b", "d"), new DagService.DagEdge("c", "d")));
        assertEquals(3, layers.size());
        assertEquals(List.of("a"), layers.get(0));
        assertEquals(List.of("b", "c"), layers.get(1)); // 同层并行
        assertEquals(List.of("d"), layers.get(2));
    }

    @Test
    void 环检测_直接成环_抛异常() {
        BizException e = assertThrows(BizException.class, () -> dagService.topologicalOrder(
                Set.of("a", "b", "c"),
                List.of(new DagService.DagEdge("a", "b"), new DagService.DagEdge("b", "c"),
                        new DagService.DagEdge("c", "a"))));
        assertTrue(e.getMessage().contains("环"));
    }

    @Test
    void 环检测_自环_validate报错() {
        List<String> errors = dagService.validate(Set.of("a"),
                List.of(new DagService.DagEdge("a", "a")));
        assertFalse(errors.isEmpty());
    }

    @Test
    void validate_边引用不存在节点() {
        List<String> errors = dagService.validate(Set.of("a"),
                List.of(new DagService.DagEdge("a", "ghost")));
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("ghost"));
    }

    @Test
    void validate_重复节点键() {
        List<String> errors = dagService.validate(List.of("a", "a"), List.of());
        assertFalse(errors.isEmpty());
    }

    @Test
    void validate_合法DAG_返回空错误列表() {
        List<String> errors = dagService.validate(Set.of("a", "b"),
                List.of(new DagService.DagEdge("a", "b")));
        assertTrue(errors.isEmpty());
    }

    @Test
    void 空图_返回空结果() {
        assertTrue(dagService.topologicalOrder(Set.of(), List.of()).isEmpty());
        assertTrue(dagService.layeredTopologicalOrder(Set.of(), List.of()).isEmpty());
    }
}
