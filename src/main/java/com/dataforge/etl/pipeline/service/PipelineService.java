package com.dataforge.etl.pipeline.service;

import com.dataforge.etl.common.enums.PipelineStatus;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.entity.PipelineEdge;
import com.dataforge.etl.pipeline.entity.PipelineNode;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 管道业务服务:CRUD(整棵 DAG 提交/替换)、启停、拓扑校验。
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final DagService dagService;
    private final ObjectMapper objectMapper;

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineRunRepository pipelineRunRepository,
                           DagService dagService,
                           ObjectMapper objectMapper) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.dagService = dagService;
        this.objectMapper = objectMapper;
    }

    /**
     * 管道列表(摘要):附带节点数与最近一次运行状态。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSummaries() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Pipeline p : pipelineRepository.findAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("name", p.getName());
            item.put("code", p.getCode());
            item.put("description", p.getDescription());
            item.put("category", p.getCategory());
            item.put("status", p.getStatus());
            item.put("nodeCount", p.getNodes() == null ? 0 : p.getNodes().size());
            // 最近一次运行(按 id 倒序取第一条)
            PipelineRun lastRun = pipelineRunRepository.findTop1ByPipelineIdOrderByIdDesc(p.getId());
            item.put("lastRunStatus", lastRun == null ? null : lastRun.getStatus());
            item.put("lastRunAt", lastRun == null ? null : lastRun.getStartTime());
            item.put("createdAt", p.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /** 管道详情(含 nodes/edges)。open-in-view=false,需在事务内触发懒加载。 */
    @Transactional(readOnly = true)
    public Pipeline getDetail(Long id) {
        Pipeline pipeline = getById(id);
        // 触发懒加载,保证序列化时节点与边已加载
        pipeline.getNodes().size();
        pipeline.getEdges().size();
        return pipeline;
    }

    public Pipeline getById(Long id) {
        return pipelineRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("管道不存在: id=" + id));
    }

    /** 新增管道:整棵 DAG 一次提交 */
    @Transactional
    public Pipeline create(Pipeline input) {
        if (pipelineRepository.existsByCode(input.getCode())) {
            throw BizException.badRequest("管道编码已存在: " + input.getCode());
        }
        input.setId(null);
        // 结构校验(节点键、边端点、环)
        validateGraph(input);
        bindGraph(input, input.getNodes(), input.getEdges());
        log.info("新增管道: {} ({}),节点数 {}", input.getName(), input.getCode(),
                input.getNodes() == null ? 0 : input.getNodes().size());
        return pipelineRepository.save(input);
    }

    /** 更新管道:标量字段合并 + 节点/边整体替换 */
    @Transactional
    public Pipeline update(Long id, Pipeline input) {
        Pipeline pipeline = getById(id);
        if (input.getCode() != null && !input.getCode().equals(pipeline.getCode())
                && pipelineRepository.existsByCode(input.getCode())) {
            throw BizException.badRequest("管道编码已存在: " + input.getCode());
        }
        pipeline.setName(input.getName());
        pipeline.setCode(input.getCode());
        pipeline.setDescription(input.getDescription());
        pipeline.setCategory(input.getCategory());
        if (input.getStatus() != null) {
            pipeline.setStatus(input.getStatus());
        }
        if (input.getFailureStrategy() != null) {
            pipeline.setFailureStrategy(input.getFailureStrategy());
        }
        if (input.getRetryCount() != null) {
            pipeline.setRetryCount(input.getRetryCount());
        }
        if (input.getRetryIntervalSeconds() != null) {
            pipeline.setRetryIntervalSeconds(input.getRetryIntervalSeconds());
        }
        if (input.getTimeoutMinutes() != null) {
            pipeline.setTimeoutMinutes(input.getTimeoutMinutes());
        }
        // 整棵 DAG 替换:前端提交什么就保存什么
        if (input.getNodes() != null || input.getEdges() != null) {
            List<PipelineNode> newNodes = input.getNodes() == null ? List.of() : input.getNodes();
            List<PipelineEdge> newEdges = input.getEdges() == null ? List.of() : input.getEdges();
            // 先校验再替换,非法图不落库
            Pipeline tmp = new Pipeline();
            tmp.setNodes(newNodes);
            tmp.setEdges(newEdges);
            validateGraph(tmp);
            // 触发旧集合加载后清空(orphanRemoval 会删除旧记录)
            pipeline.getNodes().clear();
            pipeline.getEdges().clear();
            for (PipelineNode node : newNodes) {
                node.setId(null); // 整体替换,旧节点一律重建
                node.setPipeline(pipeline);
                pipeline.getNodes().add(node);
            }
            for (PipelineEdge edge : newEdges) {
                edge.setId(null);
                edge.setPipeline(pipeline);
                pipeline.getEdges().add(edge);
            }
        }
        log.info("更新管道: {} ({})", pipeline.getName(), pipeline.getCode());
        Pipeline saved = pipelineRepository.save(pipeline);
        saved.getNodes().size();
        saved.getEdges().size();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Pipeline pipeline = getById(id);
        pipelineRepository.delete(pipeline);
        log.info("删除管道: {} ({})", pipeline.getName(), pipeline.getCode());
    }

    @Transactional
    public Pipeline enable(Long id) {
        Pipeline pipeline = getById(id);
        if (pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            throw BizException.badRequest("管道没有任何节点,无法启用");
        }
        pipeline.setStatus(PipelineStatus.ENABLED);
        log.info("启用管道: {}", pipeline.getName());
        return pipelineRepository.save(pipeline);
    }

    @Transactional
    public Pipeline disable(Long id) {
        Pipeline pipeline = getById(id);
        pipeline.setStatus(PipelineStatus.DISABLED);
        log.info("停用管道: {}", pipeline.getName());
        return pipelineRepository.save(pipeline);
    }

    /**
     * 拓扑校验:{valid, errors, order}。
     * order 为拓扑排序后的节点键序列,供前端展示执行顺序。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(Long id) {
        Pipeline pipeline = getById(id);
        pipeline.getNodes().size();
        pipeline.getEdges().size();
        List<String> nodeKeys = pipeline.getNodes().stream().map(PipelineNode::getNodeKey).toList();
        List<DagService.DagEdge> edges = toDagEdges(pipeline.getEdges());
        List<String> errors = new ArrayList<>(dagService.validate(nodeKeys, edges));
        for (PipelineNode node : pipeline.getNodes()) {
            String configError = nodeConfigError(node);
            if (configError != null) {
                errors.add(configError);
            }
        }
        List<String> order = errors.isEmpty()
                ? dagService.topologicalOrder(nodeKeys, edges)
                : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("order", order);
        return result;
    }

    // ---------- 内部工具 ----------

    /** 提交前结构校验,不合法直接抛业务异常 */
    private void validateGraph(Pipeline pipeline) {
        List<PipelineNode> nodes = pipeline.getNodes() == null ? List.of() : pipeline.getNodes();
        List<PipelineEdge> edges = pipeline.getEdges() == null ? List.of() : pipeline.getEdges();
        for (PipelineNode node : nodes) {
            if (node.getAgentType() == null) {
                throw BizException.badRequest("节点 " + node.getNodeKey() + " 缺少 agentType");
            }
            // 配置类错误在保存期暴露,避免带病 DAG 到运行期才失败
            String configError = nodeConfigError(node);
            if (configError != null) {
                throw BizException.badRequest(configError);
            }
        }
        List<String> errors = dagService.validate(
                nodes.stream().map(PipelineNode::getNodeKey).toList(), toDagEdges(edges));
        if (!errors.isEmpty()) {
            throw BizException.badRequest("管道 DAG 校验失败: " + String.join("; ", errors));
        }
    }

    /**
     * 节点配置合法性检查,返回错误描述;合法返回 null。
     * 按 AgentType 约定:EXTRACTOR 必须有 sourceTable,LOADER 必须有 targetTable,
     * TRANSFORMER 的 filterRatio 须在 0~1,QUALITY_CHECKER 的 ruleIds 须为数组。
     */
    private String nodeConfigError(PipelineNode node) {
        String config = node.getConfig();
        JsonNode json;
        try {
            json = (config == null || config.isBlank())
                    ? objectMapper.createObjectNode() : objectMapper.readTree(config);
        } catch (Exception e) {
            return "节点「" + node.getName() + "」config 不是合法 JSON";
        }
        switch (node.getAgentType()) {
            case EXTRACTOR -> {
                String table = json.path("sourceTable").asText("");
                if (table.isBlank() || !table.matches("^[A-Za-z0-9_]+$")) {
                    return "抽取节点「" + node.getName() + "」缺少合法的 sourceTable 配置";
                }
            }
            case LOADER -> {
                String table = json.path("targetTable").asText("");
                if (table.isBlank() || !table.matches("^[A-Za-z0-9_]+$")) {
                    return "加载节点「" + node.getName() + "」缺少合法的 targetTable 配置";
                }
            }
            case TRANSFORMER -> {
                double ratio = json.path("filterRatio").asDouble(0);
                if (ratio < 0 || ratio > 1) {
                    return "转换节点「" + node.getName() + "」filterRatio 必须在 0~1 之间";
                }
            }
            case QUALITY_CHECKER -> {
                if (json.has("ruleIds") && !json.get("ruleIds").isArray()) {
                    return "质检节点「" + node.getName() + "」ruleIds 必须是数组";
                }
            }
            default -> { }
        }
        return null;
    }

    /** 把请求中的节点/边挂到管道上 */
    private void bindGraph(Pipeline pipeline, List<PipelineNode> nodes, List<PipelineEdge> edges) {
        List<PipelineNode> nodeList = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        List<PipelineEdge> edgeList = edges == null ? new ArrayList<>() : new ArrayList<>(edges);
        for (PipelineNode node : nodeList) {
            node.setId(null);
            node.setPipeline(pipeline);
        }
        for (PipelineEdge edge : edgeList) {
            edge.setId(null);
            edge.setPipeline(pipeline);
        }
        pipeline.setNodes(nodeList);
        pipeline.setEdges(edgeList);
    }

    private List<DagService.DagEdge> toDagEdges(List<PipelineEdge> edges) {
        return edges.stream()
                .map(e -> new DagService.DagEdge(e.getFromNodeKey(), e.getToNodeKey()))
                .toList();
    }
}
