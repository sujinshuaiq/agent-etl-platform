package com.dataforge.etl.engine;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.service.AgentService;
import com.dataforge.etl.alert.service.AlertService;
import com.dataforge.etl.common.enums.*;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.message.MessageBus;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.entity.PipelineEdge;
import com.dataforge.etl.pipeline.entity.PipelineNode;
import com.dataforge.etl.pipeline.repository.PipelineEdgeRepository;
import com.dataforge.etl.pipeline.repository.PipelineNodeRepository;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.pipeline.service.DagService;
import com.dataforge.etl.run.entity.NodeRun;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.entity.RunLog;
import com.dataforge.etl.run.repository.NodeRunRepository;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import com.dataforge.etl.run.repository.RunLogRepository;
import com.dataforge.etl.run.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管道执行引擎:多 Agent ETL 编排核心。
 *
 * <p>执行模型:
 * 1. trigger() 创建 PENDING 运行记录并异步提交到 pipelineExecutor;
 * 2. executePipeline() 按 DAG 拓扑分层,同一层节点提交 nodeExecutor 并行执行;
 * 3. 每个节点由对应 AgentType 的 NodeExecutor 策略执行,带重试与取消检查;
 * 4. 全程写 RunLog、更新进度、广播 SSE 事件;失败按 FAIL_FAST / CONTINUE 处理;
 * 5. 失败运行产生告警。</p>
 */
@Component
public class PipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineNodeRepository nodeRepository;
    private final PipelineEdgeRepository edgeRepository;
    private final PipelineRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;
    private final RunLogRepository runLogRepository;
    private final DagService dagService;
    private final AgentService agentService;
    private final AlertService alertService;
    private final RunService runService;
    private final RunEventPublisher eventPublisher;
    private final MessageBus messageBus;
    private final ThreadPoolTaskExecutor pipelineExecutor;
    private final ThreadPoolTaskExecutor nodeExecutor;
    private final Map<AgentType, NodeExecutor> executorRegistry = new EnumMap<>(AgentType.class);

    /** 运行取消标志表:runId → 取消标志 */
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Value("${dataforge.simulation.min-node-duration-ms:300}")
    private long minNodeDurationMs;

    @Value("${dataforge.simulation.max-node-duration-ms:3000}")
    private long maxNodeDurationMs;

    @Value("${dataforge.engine.node-timeout-seconds:300}")
    private long nodeTimeoutSeconds;

    public PipelineEngine(PipelineRepository pipelineRepository,
                          PipelineNodeRepository nodeRepository,
                          PipelineEdgeRepository edgeRepository,
                          PipelineRunRepository runRepository,
                          NodeRunRepository nodeRunRepository,
                          RunLogRepository runLogRepository,
                          DagService dagService,
                          AgentService agentService,
                          AlertService alertService,
                          RunService runService,
                          RunEventPublisher eventPublisher,
                          MessageBus messageBus,
                          @Qualifier("pipelineExecutor") ThreadPoolTaskExecutor pipelineExecutor,
                          @Qualifier("nodeExecutor") ThreadPoolTaskExecutor nodeExecutor,
                          List<NodeExecutor> executors) {
        this.pipelineRepository = pipelineRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.runLogRepository = runLogRepository;
        this.dagService = dagService;
        this.agentService = agentService;
        this.alertService = alertService;
        this.runService = runService;
        this.eventPublisher = eventPublisher;
        this.messageBus = messageBus;
        this.pipelineExecutor = pipelineExecutor;
        this.nodeExecutor = nodeExecutor;
        // 节点执行器注册表:策略模式,新类型注册 Bean 即可被引擎发现
        for (NodeExecutor executor : executors) {
            executorRegistry.put(executor.supportType(), executor);
        }
    }

    // ==================== 对外操作 ====================

    /**
     * 触发一次管道运行(异步),立即返回 runId。
     */
    public Long trigger(Long pipelineId, TriggerType triggerType) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> BizException.notFound("管道不存在: id=" + pipelineId));
        if (pipeline.getStatus() == PipelineStatus.DISABLED) {
            throw BizException.badRequest("管道已停用,无法触发: " + pipeline.getName());
        }
        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipeline.getId());
        run.setPipelineName(pipeline.getName());
        run.setStatus(RunStatus.PENDING);
        run.setTriggerType(triggerType);
        run.setProgress(0);
        run = runRepository.save(run);
        cancelFlags.put(run.getId(), new AtomicBoolean(false));
        Long runId = run.getId();
        log.info("触发管道运行: pipeline={}, runId={}, triggerType={}", pipeline.getName(), runId, triggerType);
        pipelineExecutor.submit(() -> executeSafely(runId));
        publishRun(run);
        return runId;
    }

    /**
     * 请求取消运行:PENDING 直接置为 CANCELED;RUNNING 置取消标志,引擎在节点边界响应。
     */
    public void requestCancel(Long runId) {
        PipelineRun run = runRepository.findById(runId)
                .orElseThrow(() -> BizException.notFound("运行记录不存在: id=" + runId));
        if (run.getStatus() == RunStatus.PENDING) {
            cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean(false)).set(true);
            run.setStatus(RunStatus.CANCELED);
            run.setEndTime(LocalDateTime.now());
            run.setErrorMessage("排队阶段被取消");
            runRepository.save(run);
            writeLog(runId, null, LogLevel.WARN, "运行在排队阶段被取消");
            publishRun(run);
            log.info("运行已取消(PENDING): runId={}", runId);
            return;
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            throw BizException.badRequest("仅运行中/等待中的任务可以取消,当前状态: " + run.getStatus());
        }
        cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean(false)).set(true);
        writeLog(runId, null, LogLevel.WARN, "收到取消请求,将在节点边界停止");
        log.info("运行取消标志已置位: runId={}", runId);
    }

    // ==================== 编排执行 ====================

    /** 异步执行的入口包装:兜底异常处理与资源清理 */
    private void executeSafely(Long runId) {
        try {
            executePipeline(runId);
        } catch (Exception e) {
            log.error("管道运行异常: runId={}, error={}", runId, e.getMessage(), e);
            try {
                PipelineRun run = runRepository.findById(runId).orElse(null);
                if (run != null && (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PENDING)) {
                    run.setStatus(RunStatus.FAILED);
                    run.setEndTime(LocalDateTime.now());
                    run.setErrorMessage("引擎内部异常: " + e.getMessage());
                    runRepository.save(run);
                    publishRun(run);
                }
            } catch (Exception inner) {
                log.error("兜底标记失败也出错: runId={}", runId, inner);
            }
        } finally {
            cancelFlags.remove(runId);
        }
    }

    private void executePipeline(Long runId) {
        PipelineRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        AtomicBoolean cancelFlag = cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean(false));
        // 排队阶段已被取消:不再启动
        if (run.getStatus() != RunStatus.PENDING || cancelFlag.get()) {
            return;
        }
        Pipeline pipeline = pipelineRepository.findById(run.getPipelineId()).orElse(null);
        if (pipeline == null) {
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage("管道已被删除");
            run.setEndTime(LocalDateTime.now());
            runRepository.save(run);
            publishRun(run);
            return;
        }
        List<PipelineNode> nodes = nodeRepository.findByPipelineIdOrderByIdAsc(pipeline.getId());
        List<PipelineEdge> edges = edgeRepository.findByPipelineIdOrderByIdAsc(pipeline.getId());

        // ---- 运行启动 ----
        run.setStatus(RunStatus.RUNNING);
        run.setStartTime(LocalDateTime.now());
        runRepository.save(run);
        publishRun(run);
        writeLog(runId, null, LogLevel.INFO,
                "管道「" + pipeline.getName() + "」开始运行,触发方式 " + run.getTriggerType() + ",节点数 " + nodes.size());

        List<String> nodeKeys = nodes.stream().map(PipelineNode::getNodeKey).toList();
        List<DagService.DagEdge> dagEdges = edges.stream()
                .map(e -> new DagService.DagEdge(e.getFromNodeKey(), e.getToNodeKey())).toList();
        List<List<String>> layers = dagService.layeredTopologicalOrder(nodeKeys, dagEdges);
        Map<String, PipelineNode> nodeByKey = new HashMap<>();
        nodes.forEach(n -> nodeByKey.put(n.getNodeKey(), n));
        // 上游依赖表:nodeKey → 上游 nodeKey 列表
        Map<String, List<String>> upstreams = new HashMap<>();
        nodeKeys.forEach(k -> upstreams.put(k, new ArrayList<>()));
        dagEdges.forEach(e -> upstreams.get(e.toNodeKey()).add(e.fromNodeKey()));

        Map<String, Long> rowsOutByKey = new ConcurrentHashMap<>();
        Map<String, NodeRun> nodeRunByKey = new ConcurrentHashMap<>();
        Set<String> failedKeys = ConcurrentHashMap.newKeySet();
        int totalNodes = Math.max(1, nodes.size());
        AtomicInteger completed = new AtomicInteger(0);
        // 实时进度计数:节点一完成即推进(含被跳过的节点),不再按层批量更新
        AtomicInteger finishedCount = new AtomicInteger(0);
        boolean canceled = false;
        boolean fatal = false;
        String fatalError = null;

        // ---- 逐层执行 ----
        for (List<String> layer : layers) {
            if (cancelFlag.get()) {
                canceled = true;
                break;
            }
            if (fatal) {
                break; // FAIL_FAST:后续层不再调度
            }
            // 拆分:上游有失败/跳过的节点直接 SKIPPED,其余并行执行
            List<PipelineNode> runnable = new ArrayList<>();
            for (String key : layer) {
                PipelineNode node = nodeByKey.get(key);
                boolean upstreamFailed = upstreams.get(key).stream().anyMatch(failedKeys::contains);
                if (upstreamFailed) {
                    NodeRun skipped = markSkipped(run, node, "上游节点失败/跳过,本节点跳过");
                    nodeRunByKey.put(key, skipped);
                    failedKeys.add(key);
                    completed.incrementAndGet();
                } else {
                    runnable.add(node);
                }
            }
            // 同层并行
            finishedCount.set(completed.get());
            List<CompletableFuture<NodeOutcome>> futures = runnable.stream()
                    .map(node -> CompletableFuture.supplyAsync(
                            () -> executeNodeWithRetry(run, pipeline, node, sumUpstreams(node, upstreams, rowsOutByKey),
                                    upstreamHandoffs(node, upstreams, nodeRunByKey), cancelFlag),
                            nodeExecutor)
                            .whenComplete((outcome, err) -> {
                                // 节点一结束就实时落库:行数统计 + 进度,前端可看到运行中的读取/写入变化
                                if (outcome != null && outcome.nodeRun() != null) {
                                    nodeRunByKey.put(outcome.nodeKey(), outcome.nodeRun());
                                }
                                int done = finishedCount.incrementAndGet();
                                synchronized (run) {
                                    aggregateRows(run, nodes, nodeRunByKey);
                                    run.setProgress(Math.min(99, done * 100 / totalNodes));
                                    runRepository.save(run);
                                }
                                publishRun(run);
                            }))
                    .toList();
            try {
                long timeout = Math.max(60, nodeTimeoutSeconds) * Math.max(1, pipeline.getRetryCount() + 1);
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeout, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fatal = true;
                fatalError = "节点执行超时(>" + nodeTimeoutSeconds + "s)";
                writeLog(runId, null, LogLevel.ERROR, fatalError);
            } catch (Exception e) {
                log.warn("等待节点执行结果时异常: runId={}, error={}", runId, e.getMessage());
            }
            // 汇总本层结果
            for (int i = 0; i < runnable.size(); i++) {
                NodeOutcome outcome;
                try {
                    outcome = futures.get(i).get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    outcome = NodeOutcome.failed(runnable.get(i).getNodeKey(), "结果获取失败: " + e.getMessage());
                }
                NodeRun nodeRun = outcome.nodeRun();
                nodeRunByKey.put(outcome.nodeKey(), nodeRun);
                completed.incrementAndGet();
                if (outcome.success()) {
                    rowsOutByKey.put(outcome.nodeKey(), nodeRun.getRowsOut() == null ? 0 : nodeRun.getRowsOut());
                } else {
                    failedKeys.add(outcome.nodeKey());
                    if (outcome.canceled()) {
                        canceled = true;
                    }
                    if (fatalError == null && outcome.errorMessage() != null) {
                        fatalError = outcome.errorMessage();
                    }
                }
            }
            // FAIL_FAST:本层有失败即中止
            if (pipeline.getFailureStrategy() == FailureStrategy.FAIL_FAST
                    && runnable.stream().anyMatch(n -> failedKeys.contains(n.getNodeKey()))) {
                fatal = true;
                writeLog(runId, null, LogLevel.ERROR, "失败策略 FAIL_FAST:节点失败,中止后续调度");
            }
        }

        // ---- 未调度节点标记 SKIPPED(取消或 FAIL_FAST 中止的下游) ----
        for (PipelineNode node : nodes) {
            if (!nodeRunByKey.containsKey(node.getNodeKey())) {
                String reason = canceled ? "运行被取消,本节点未执行" : "上游失败,本节点未执行";
                nodeRunByKey.put(node.getNodeKey(), markSkipped(run, node, reason));
                failedKeys.add(node.getNodeKey());
            }
        }

        // ---- 收尾:汇总状态与行数 ----
        finalizeRun(run, pipeline, nodes, nodeRunByKey, canceled, fatal, fatalError);
    }

    /**
     * 执行单个节点(含重试)。返回结果对象,不向上抛异常(取消除外,也以结果形式返回)。
     */
    private NodeOutcome executeNodeWithRetry(PipelineRun run, Pipeline pipeline, PipelineNode node,
                                             long rowsIn, List<HandoffInfo> handoffs, AtomicBoolean cancelFlag) {
        Long runId = run.getId();
        // 创建节点运行记录
        NodeRun nodeRun = new NodeRun();
        nodeRun.setRunId(runId);
        nodeRun.setNodeKey(node.getNodeKey());
        nodeRun.setNodeName(node.getName());
        nodeRun.setAgentType(node.getAgentType());
        nodeRun.setRowsIn(rowsIn);
        nodeRun.setStatus(NodeRunStatus.RUNNING);
        nodeRun.setStartTime(LocalDateTime.now());
        nodeRun.setRetryAttempt(0);
        nodeRun = nodeRunRepository.save(nodeRun);
        publishNode(nodeRun);
        writeLog(runId, nodeRun.getId(), LogLevel.INFO,
                "节点「" + node.getName() + "」开始执行(类型 " + node.getAgentType() + ",输入 " + rowsIn + " 行)");

        // 选择执行 Agent
        Agent agent = agentService.pickAgent(node.getAgentType());
        Long agentId = null;
        if (agent != null) {
            agentId = agent.getId();
            nodeRun.setAgentName(agent.getName());
            nodeRunRepository.save(nodeRun);
            agentService.taskStarted(agentId);
            writeLog(runId, nodeRun.getId(), LogLevel.INFO, "分配执行 Agent: " + agent.getName() + " (" + agent.getCode() + ")");
            // 消息总线:上游 Agent → 本节点 Agent 的数据交接(每条上游边一条消息)
            for (HandoffInfo h : handoffs) {
                messageBus.dataHandoff(run, h.fromAgent(), agent.getName(), h.rows());
            }
        }

        NodeExecutor executor = executorRegistry.get(node.getAgentType());
        RetryPolicy retryPolicy = new RetryPolicy(
                pipeline.getRetryCount() == null ? 0 : pipeline.getRetryCount(),
                pipeline.getRetryIntervalSeconds() == null ? 0 : pipeline.getRetryIntervalSeconds());
        long nodeStart = System.currentTimeMillis();
        boolean success = false;
        boolean canceled = false;
        long rowsOut = 0;
        String errorMessage = null;
        final Long nodeRunId = nodeRun.getId();
        NodeExecutionContext.LogSink logSink = (level, message) -> writeLog(runId, nodeRunId, level, message);

        try {
            if (agent == null) {
                throw BizException.badRequest("没有可用的 " + node.getAgentType() + " 类型在线 Agent");
            }
            if (executor == null) {
                throw BizException.badRequest("未注册的节点执行器类型: " + node.getAgentType());
            }
            for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
                nodeRun.setRetryAttempt(attempt - 1);
                try {
                    NodeExecutionContext ctx = new NodeExecutionContext(runId, nodeRunId,
                            node.getNodeKey(), node.getName(), node.getConfig(), rowsIn,
                            cancelFlag, minNodeDurationMs, maxNodeDurationMs, logSink);
                    injectDeterministicFailure(runId, node);
                    NodeOutput output = executor.execute(ctx);
                    rowsOut = output.rowsOut();
                    if (output.message() != null) {
                        logSink.log(LogLevel.INFO, output.message());
                    }
                    success = true;
                    break;
                } catch (NodeExecutionContext.CancelledException e) {
                    canceled = true;
                    errorMessage = e.getMessage();
                    break; // 取消不重试
                } catch (Exception e) {
                    errorMessage = e.getMessage();
                    logSink.log(LogLevel.WARN, "第 " + attempt + " 次执行失败: " + e.getMessage());
                    if (e instanceof BizException) {
                        break; // 配置/参数类错误是永久性故障,重试只会拖延失败暴露
                    }
                    if (retryPolicy.canRetry(attempt)) {
                        logSink.log(LogLevel.INFO, retryPolicy.getRetryCount() - attempt + 1 + " 次重试机会,"
                                + retryPolicy.intervalMillis() / 1000 + " 秒后重试");
                        sleepWithCancel(retryPolicy.intervalMillis(), cancelFlag, logSink);
                    }
                }
            }
        } catch (NodeExecutionContext.CancelledException e) {
            canceled = true;
            errorMessage = e.getMessage();
        } catch (Exception e) {
            errorMessage = e.getMessage();
        } finally {
            if (agentId != null) {
                agentService.taskFinished(agentId, success, System.currentTimeMillis() - nodeStart);
            }
        }

        // 落库节点终态
        nodeRun.setEndTime(LocalDateTime.now());
        nodeRun.setDurationMs(System.currentTimeMillis() - nodeStart);
        nodeRun.setRowsOut(rowsOut);
        nodeRun.setErrorMessage(errorMessage);
        if (canceled) {
            nodeRun.setStatus(NodeRunStatus.CANCELED);
            logSink.log(LogLevel.WARN, "节点「" + node.getName() + "」已取消");
        } else if (success) {
            nodeRun.setStatus(NodeRunStatus.SUCCESS);
            logSink.log(LogLevel.INFO, "节点「" + node.getName() + "」执行成功,输出 " + rowsOut + " 行,耗时 "
                    + nodeRun.getDurationMs() + "ms");
            // 消息总线:产出就绪,向全体 Agent 广播
            if (agent != null) {
                messageBus.dataReady(run, agent.getName(), rowsOut);
            }
        } else {
            nodeRun.setStatus(NodeRunStatus.FAILED);
            logSink.log(LogLevel.ERROR, "节点「" + node.getName() + "」执行失败: " + errorMessage);
        }
        nodeRun = nodeRunRepository.save(nodeRun);
        publishNode(nodeRun);
        return new NodeOutcome(node.getNodeKey(), nodeRun, success, canceled, errorMessage);
    }

    // ==================== 内部工具 ====================

    /** 节点运行结果(引擎内部传递) */
    private record NodeOutcome(String nodeKey, NodeRun nodeRun, boolean success, boolean canceled,
                               String errorMessage) {
        static NodeOutcome failed(String nodeKey, String error) {
            NodeRun nr = new NodeRun();
            nr.setNodeKey(nodeKey);
            nr.setStatus(NodeRunStatus.FAILED);
            nr.setRowsOut(0L);
            return new NodeOutcome(nodeKey, nr, false, false, error);
        }
    }

    /** 标记节点为 SKIPPED 并落库 */
    private NodeRun markSkipped(PipelineRun run, PipelineNode node, String reason) {
        NodeRun nodeRun = new NodeRun();
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getNodeKey());
        nodeRun.setNodeName(node.getName());
        nodeRun.setAgentType(node.getAgentType());
        nodeRun.setStatus(NodeRunStatus.SKIPPED);
        nodeRun.setErrorMessage(reason);
        nodeRun.setDurationMs(0L);
        nodeRun = nodeRunRepository.save(nodeRun);
        writeLog(run.getId(), nodeRun.getId(), LogLevel.WARN, "节点「" + node.getName() + "」跳过: " + reason);
        publishNode(nodeRun);
        return nodeRun;
    }

    /** 收尾:计算最终状态、行数统计、耗时,失败产生告警 */
    private void finalizeRun(PipelineRun run, Pipeline pipeline, List<PipelineNode> nodes,
                             Map<String, NodeRun> nodeRunByKey, boolean canceled, boolean fatal, String fatalError) {
        aggregateRows(run, nodes, nodeRunByKey);
        long rowsRead = run.getRowsRead() == null ? 0 : run.getRowsRead();
        long rowsWritten = run.getRowsWritten() == null ? 0 : run.getRowsWritten();
        long rowsFiltered = run.getRowsFiltered() == null ? 0 : run.getRowsFiltered();
        boolean anyFailed = false;
        boolean anySuccess = false;
        for (PipelineNode node : nodes) {
            NodeRun nr = nodeRunByKey.get(node.getNodeKey());
            if (nr == null) {
                continue;
            }
            if (nr.getStatus() == NodeRunStatus.SUCCESS) {
                anySuccess = true;
            } else if (nr.getStatus() == NodeRunStatus.FAILED) {
                anyFailed = true;
            }
        }
        run.setEndTime(LocalDateTime.now());
        run.setDurationMs(run.getStartTime() == null ? 0L
                : Duration.between(run.getStartTime(), run.getEndTime()).toMillis());
        if (canceled) {
            run.setStatus(RunStatus.CANCELED);
            run.setErrorMessage("运行被手动取消");
        } else if (fatal) {
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage(fatalError);
        } else if (anyFailed) {
            run.setStatus(anySuccess ? RunStatus.PARTIAL_SUCCESS : RunStatus.FAILED);
            run.setErrorMessage(fatalError);
        } else {
            run.setStatus(RunStatus.SUCCESS);
        }
        run.setProgress(run.getStatus() == RunStatus.CANCELED ? run.getProgress() : 100);
        runRepository.save(run);
        publishRun(run);
        writeLog(run.getId(), null, LogLevel.INFO,
                "管道「" + pipeline.getName() + "」运行结束,状态 " + run.getStatus()
                        + ",读取 " + rowsRead + " 行,写入 " + rowsWritten + " 行,过滤 " + rowsFiltered + " 行,耗时 "
                        + run.getDurationMs() + "ms");
        log.info("管道运行结束: runId={}, status={}, duration={}ms", run.getId(), run.getStatus(), run.getDurationMs());

        // 失败/部分成功产生告警
        if (run.getStatus() == RunStatus.FAILED) {
            alertService.raise(Severity.ERROR, "管道运行失败: " + pipeline.getName(),
                    "运行 #" + run.getId() + " 失败。" + (fatalError == null ? "" : "原因: " + fatalError),
                    "执行引擎", run.getId());
        } else if (run.getStatus() == RunStatus.PARTIAL_SUCCESS) {
            alertService.raise(Severity.WARN, "管道部分成功: " + pipeline.getName(),
                    "运行 #" + run.getId() + " 存在失败节点,其余分支已完成。", "执行引擎", run.getId());
        }
    }

    /** 一次数据交接的描述:上游 Agent 名称 + 交出行数 */
    private record HandoffInfo(String fromAgent, long rows) {
    }

    /** 计算某节点的全部上游交接(上游节点已成功且有执行 Agent) */
    private List<HandoffInfo> upstreamHandoffs(PipelineNode node, Map<String, List<String>> upstreams,
                                               Map<String, NodeRun> nodeRunByKey) {
        List<HandoffInfo> result = new ArrayList<>();
        for (String upKey : upstreams.getOrDefault(node.getNodeKey(), List.of())) {
            NodeRun up = nodeRunByKey.get(upKey);
            if (up != null && up.getAgentName() != null) {
                result.add(new HandoffInfo(up.getAgentName(), up.getRowsOut() == null ? 0 : up.getRowsOut()));
            }
        }
        return result;
    }

    /** 汇总运行级行数(读取/写入/过滤),执行中与收尾都会调用 */
    private void aggregateRows(PipelineRun run, List<PipelineNode> nodes, Map<String, NodeRun> nodeRunByKey) {
        long rowsRead = 0, rowsWritten = 0, rowsFiltered = 0;
        for (PipelineNode node : nodes) {
            NodeRun nr = nodeRunByKey.get(node.getNodeKey());
            if (nr == null || nr.getStatus() != NodeRunStatus.SUCCESS) {
                continue;
            }
            long in = nr.getRowsIn() == null ? 0 : nr.getRowsIn();
            long out = nr.getRowsOut() == null ? 0 : nr.getRowsOut();
            if (node.getAgentType() == AgentType.EXTRACTOR) {
                rowsRead += out;
            } else if (node.getAgentType() == AgentType.LOADER) {
                rowsWritten += out;
            } else if (node.getAgentType() == AgentType.TRANSFORMER) {
                rowsFiltered += Math.max(0, in - out);
            }
        }
        run.setRowsRead(rowsRead);
        run.setRowsWritten(rowsWritten);
        run.setRowsFiltered(rowsFiltered);
    }

    /** 上游输出行数求和 */
    private long sumUpstreams(PipelineNode node, Map<String, List<String>> upstreams, Map<String, Long> rowsOutByKey) {
        return upstreams.getOrDefault(node.getNodeKey(), List.of()).stream()
                .mapToLong(key -> rowsOutByKey.getOrDefault(key, 0L))
                .sum();
    }

    /**
     * 确定性故障注入:约 3% 的节点组合会模拟失败,
     * 用于演示重试 / 失败策略 / 告警链路(编排节点不注入)。
     */
    private void injectDeterministicFailure(Long runId, PipelineNode node) {
        if (node.getAgentType() == AgentType.ORCHESTRATOR || node.getId() == null) {
            return;
        }
        if ((runId * 31 + node.getId()) % 29 == 0) {
            throw new IllegalStateException("模拟节点异常:下游连接抖动(用于演示重试与告警)");
        }
    }

    /** 可中断的重试等待 */
    private void sleepWithCancel(long millis, AtomicBoolean cancelFlag, NodeExecutionContext.LogSink logSink) {
        long remaining = millis;
        while (remaining > 0) {
            if (cancelFlag.get()) {
                throw new NodeExecutionContext.CancelledException("运行已被取消");
            }
            long slice = Math.min(200, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeExecutionContext.CancelledException("执行线程被中断");
            }
            remaining -= slice;
        }
    }

    // ==================== 日志与事件 ====================

    /** 写一条运行日志:落库 + SSE 广播 */
    private void writeLog(Long runId, Long nodeRunId, LogLevel level, String message) {
        try {
            RunLog runLog = runLogRepository.save(new RunLog(runId, nodeRunId, level, message));
            eventPublisher.publish("LOG", runLog);
        } catch (Exception e) {
            log.warn("写运行日志失败: runId={}, message={}", runId, e.getMessage());
        }
    }

    /** 广播运行状态事件 */
    private void publishRun(PipelineRun run) {
        try {
            eventPublisher.publish("RUN_UPDATE", runService.toListItem(run));
        } catch (Exception e) {
            log.warn("广播 RUN_UPDATE 失败: {}", e.getMessage());
        }
    }

    /** 广播节点状态事件 */
    private void publishNode(NodeRun nodeRun) {
        try {
            eventPublisher.publish("NODE_UPDATE", nodeRun);
        } catch (Exception e) {
            log.warn("广播 NODE_UPDATE 失败: {}", e.getMessage());
        }
    }
}
