package com.dataforge.etl.ai;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.service.AgentService;
import com.dataforge.etl.ai.client.LlmClient;
import com.dataforge.etl.ai.entity.AiChatMessage;
import com.dataforge.etl.ai.guard.ConstraintValidator;
import com.dataforge.etl.ai.guard.HallucinationGuard;
import com.dataforge.etl.ai.nlp.*;
import com.dataforge.etl.ai.rag.RagService;
import com.dataforge.etl.ai.repository.AiChatMessageRepository;
import com.dataforge.etl.ai.retrieve.HybridRetriever;
import com.dataforge.etl.common.enums.*;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.dashboard.DashboardService;
import com.dataforge.etl.datasource.entity.DataSource;
import com.dataforge.etl.datasource.repository.DataSourceRepository;
import com.dataforge.etl.engine.PipelineEngine;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.entity.PipelineEdge;
import com.dataforge.etl.pipeline.entity.PipelineNode;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.pipeline.service.PipelineService;
import com.dataforge.etl.quality.entity.QualityRule;
import com.dataforge.etl.quality.repository.QualityRuleRepository;
import com.dataforge.etl.run.entity.NodeRun;
import com.dataforge.etl.run.entity.RunLog;
import com.dataforge.etl.run.repository.RunLogRepository;
import com.dataforge.etl.run.service.RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 助手编排服务:chat 全流程——
 * 查询改写 → 意图识别 → 槽位填充(缺失追问,多轮补全) → 路由执行(平台工具 / RAG / 帮助)
 * → 幻觉治理 → 消息落库。
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private final LlmClient llmClient;
    private final QueryRewriter queryRewriter;
    private final IntentRecognizer intentRecognizer;
    private final SlotFiller slotFiller;
    private final RagService ragService;
    private final ConstraintValidator constraintValidator;
    private final HallucinationGuard hallucinationGuard;
    private final AiChatMessageRepository chatMessageRepository;
    private final DashboardService dashboardService;
    private final RunService runService;
    private final AgentService agentService;
    private final PipelineService pipelineService;
    private final PipelineEngine pipelineEngine;
    private final PipelineRepository pipelineRepository;
    private final RunLogRepository runLogRepository;
    private final DataSourceRepository dataSourceRepository;
    private final QualityRuleRepository qualityRuleRepository;
    private final HybridRetriever hybridRetriever;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    /** 会话槽位记忆:sessionId → 待补全状态(内存版,重启清空) */
    private final Map<String, SessionState> sessionMemory = new ConcurrentHashMap<>();

    /** 会话待补全状态 */
    private static class SessionState {
        IntentType pendingIntent;
        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> missingSlots = new ArrayList<>();
    }

    public AiAssistantService(LlmClient llmClient, QueryRewriter queryRewriter,
                              IntentRecognizer intentRecognizer, SlotFiller slotFiller,
                              RagService ragService, ConstraintValidator constraintValidator,
                              HallucinationGuard hallucinationGuard,
                              AiChatMessageRepository chatMessageRepository,
                              DashboardService dashboardService, RunService runService,
                              AgentService agentService, PipelineService pipelineService,
                              PipelineEngine pipelineEngine, PipelineRepository pipelineRepository,
                              RunLogRepository runLogRepository,
                              DataSourceRepository dataSourceRepository,
                              QualityRuleRepository qualityRuleRepository,
                              HybridRetriever hybridRetriever,
                              AiProperties aiProperties,
                              ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.queryRewriter = queryRewriter;
        this.intentRecognizer = intentRecognizer;
        this.slotFiller = slotFiller;
        this.ragService = ragService;
        this.constraintValidator = constraintValidator;
        this.hallucinationGuard = hallucinationGuard;
        this.chatMessageRepository = chatMessageRepository;
        this.dashboardService = dashboardService;
        this.runService = runService;
        this.agentService = agentService;
        this.pipelineService = pipelineService;
        this.pipelineEngine = pipelineEngine;
        this.pipelineRepository = pipelineRepository;
        this.runLogRepository = runLogRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.qualityRuleRepository = qualityRuleRepository;
        this.hybridRetriever = hybridRetriever;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    // ==================== 状态 ====================

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", aiProperties.isEnabled());
        status.put("mockMode", llmClient.isMock());
        status.put("model", aiProperties.getModel());
        status.put("baseUrl", aiProperties.getBaseUrl());
        status.put("capabilities", List.of("意图识别", "槽位补全", "查询改写", "混合检索",
                "RAG", "约束校验", "幻觉治理", "Function Calling"));
        return status;
    }

    // ==================== 对话主流程 ====================

    /**
     * 对话入口。
     */
    public Map<String, Object> chat(String message, String sessionId) {
        if (message == null || message.isBlank()) {
            throw BizException.badRequest("消息不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        final String sid = sessionId;
        // 落库用户消息
        chatMessageRepository.save(new AiChatMessage(sid, "user", message, null));

        // 1. 查询改写
        QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(message);

        // 2. 意图识别(若会话存在待补全槽位,优先延续上次的意图)
        SessionState state = sessionMemory.get(sid);
        IntentRecognizer.IntentResult intentResult;
        if (state != null && state.pendingIntent != null && !state.missingSlots.isEmpty()) {
            intentResult = new IntentRecognizer.IntentResult(state.pendingIntent, 0.95, "会话延续(槽位补全)");
        } else {
            intentResult = intentRecognizer.recognize(rewrite.rewritten());
        }

        // 3. 槽位填充(合并会话记忆)
        Map<String, Object> memory = state == null ? Map.of() : state.slots;
        SlotFiller.SlotResult slotResult = slotFiller.fill(intentResult.intent(),
                rewrite.rewritten() + " " + message, memory, rewrite);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sid);
        response.put("intent", intentResult.intent().name());
        response.put("intentConfidence", intentResult.confidence());
        response.put("slots", slotResult.slots());
        response.put("missingSlots", slotResult.missingSlots());
        response.put("mockMode", llmClient.isMock());
        response.put("toolCalls", List.of());
        response.put("citations", List.of());
        response.put("guardNotes", List.of());
        response.put("confidence", 1.0);

        // 4. 必填槽缺失 → 追问
        if (!slotResult.complete()) {
            SessionState newState = new SessionState();
            newState.pendingIntent = intentResult.intent();
            newState.slots.putAll(slotResult.slots());
            newState.missingSlots.addAll(slotResult.missingSlots());
            sessionMemory.put(sid, newState);

            String reply = buildFollowUpQuestion(intentResult.intent(), slotResult.missingSlots());
            response.put("reply", reply);
            persistAssistant(sid, reply, intentResult.intent());
            return response;
        }
        // 槽位齐全,清除待补全状态
        sessionMemory.remove(sid);

        // 5. 路由执行
        ChatOutcome outcome = route(intentResult.intent(), slotResult.slots(), message, rewrite);

        // 6. 幻觉治理
        HallucinationGuard.GuardResult guardResult = hallucinationGuard.guard(
                outcome.reply(), outcome.evidence(), outcome.needCitation());

        response.put("reply", guardResult.reply());
        response.put("toolCalls", outcome.toolCalls());
        response.put("citations", outcome.citations());
        response.put("confidence", guardResult.confidence());
        response.put("guardNotes", guardResult.guardNotes());

        persistAssistant(sid, guardResult.reply(), intentResult.intent());
        return response;
    }

    /** 路由执行结果 */
    private record ChatOutcome(String reply, List<Map<String, Object>> toolCalls,
                               List<Map<String, Object>> citations, List<String> evidence,
                               boolean needCitation) {
    }

    /** 按意图路由到平台工具 / RAG / 帮助 */
    private ChatOutcome route(IntentType intent, Map<String, Object> slots, String message,
                              QueryRewriter.RewriteResult rewrite) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        try {
            switch (intent) {
                case QUERY_OVERVIEW -> {
                    Map<String, Object> overview = dashboardService.overview();
                    toolCalls.add(toolCall("get_platform_overview", Map.of(),
                            "平台总览:运行 " + overview.get("runTotal") + " 次"));
                    evidence.add(toJson(overview));
                    String reply = "平台总览:共 " + overview.get("agentTotal") + " 个 Agent(在线 "
                            + overview.get("agentOnline") + " 个)," + overview.get("datasourceTotal")
                            + " 个数据源," + overview.get("pipelineTotal") + " 条管道(启用 "
                            + overview.get("pipelineEnabled") + " 条);累计运行 " + overview.get("runTotal")
                            + " 次,当前运行中 " + overview.get("runRunning") + " 次,今日成功 "
                            + overview.get("runSuccessToday") + " 次、失败 " + overview.get("runFailedToday")
                            + " 次;近 7 天成功率 " + overview.get("successRate7d") + "%,平均耗时 "
                            + overview.get("avgDurationMs7d") + "ms,今日处理 " + overview.get("rowsProcessedToday")
                            + " 行;未读告警 " + overview.get("alertUnread") + " 条。";
                    return outcome(reply, toolCalls, evidence);
                }
                case QUERY_FAILED_RUNS -> {
                    long hours = slots.get("hours") == null ? 24L : Long.parseLong(slots.get("hours").toString());
                    ConstraintValidator.ValidationResult check = constraintValidator.validateToolCall(
                            "list_failed_runs", Map.of("hours", hours), message);
                    if (!check.allowed()) {
                        return outcome("参数不合法:" + String.join(";", check.violations()), toolCalls, evidence);
                    }
                    LocalDateTime from = LocalDateTime.now().minusHours(hours);
                    List<Map<String, Object>> failed = runService.page(RunStatus.FAILED, null, 0, 100)
                            .getContent().stream()
                            .filter(r -> r.getStartTime() != null && !r.getStartTime().isBefore(from))
                            .map(runService::toListItem)
                            .toList();
                    toolCalls.add(toolCall("list_failed_runs", Map.of("hours", hours),
                            "近 " + hours + " 小时失败 " + failed.size() + " 次"));
                    evidence.add(toJson(failed));
                    evidence.add("失败次数:" + failed.size() + ",时间范围:" + hours + "小时");
                    String reply;
                    if (failed.isEmpty()) {
                        reply = "近 " + hours + " 小时没有失败的运行,平台运转正常。";
                    } else {
                        StringBuilder sb = new StringBuilder("近 " + hours + " 小时共有 " + failed.size()
                                + " 次失败运行:");
                        failed.stream().limit(5).forEach(r -> sb.append("\n- 运行 #").append(r.get("id"))
                                .append(" 「").append(r.get("pipelineName")).append("」(")
                                .append(r.get("startTime")).append(") 原因:")
                                .append(r.get("errorMessage") == null ? "未知" : r.get("errorMessage")));
                        if (failed.size() > 5) {
                            sb.append("\n…其余 ").append(failed.size() - 5).append(" 条略");
                        }
                        sb.append("\n可以对我说「诊断运行 <id>」进一步分析。");
                        reply = sb.toString();
                    }
                    return outcome(reply, toolCalls, evidence);
                }
                case QUERY_RUN_DETAIL -> {
                    Long runId = Long.parseLong(slots.get("runId").toString());
                    Map<String, Object> detail = runService.detail(runId);
                    toolCalls.add(toolCall("get_run_detail", Map.of("runId", runId),
                            "运行 #" + runId + " 状态 " + detail.get("status")));
                    evidence.add(toJson(detail));
                    @SuppressWarnings("unchecked")
                    List<NodeRun> nodeRuns = (List<NodeRun>) detail.get("nodeRuns");
                    long okNodes = nodeRuns.stream().filter(n -> n.getStatus() == NodeRunStatus.SUCCESS).count();
                    String reply = "运行 #" + runId + "(" + detail.get("pipelineName") + "):状态 "
                            + detail.get("status") + ",触发方式 " + detail.get("triggerType") + ",开始于 "
                            + detail.get("startTime") + ",耗时 " + detail.get("durationMs") + "ms;读取 "
                            + detail.get("rowsRead") + " 行、写入 " + detail.get("rowsWritten") + " 行、过滤 "
                            + detail.get("rowsFiltered") + " 行;节点 " + nodeRuns.size() + " 个,成功 "
                            + okNodes + " 个。"
                            + (detail.get("errorMessage") != null ? "错误:" + detail.get("errorMessage") : "");
                    evidence.add("成功节点数:" + okNodes + ",总节点数:" + nodeRuns.size());
                    return outcome(reply, toolCalls, evidence);
                }
                case QUERY_AGENTS -> {
                    List<Agent> agents = agentService.listAll();
                    long online = agents.stream().filter(a -> a.getStatus() == AgentStatus.ONLINE).count();
                    long offline = agents.stream().filter(a -> a.getStatus() == AgentStatus.OFFLINE).count();
                    long maint = agents.stream().filter(a -> a.getStatus() == AgentStatus.MAINTENANCE).count();
                    toolCalls.add(toolCall("list_agents", Map.of(),
                            "Agent 共 " + agents.size() + " 个,在线 " + online));
                    evidence.add(toJson(agents.stream().map(a -> Map.of("name", a.getName(),
                            "status", a.getStatus().name(), "currentTasks", a.getCurrentTasks())).toList()));
                    evidence.add("Agent 总数:" + agents.size() + ",在线:" + online + ",离线:" + offline + ",维护:" + maint);
                    StringBuilder sb = new StringBuilder("当前共 " + agents.size() + " 个 Agent:在线 "
                            + online + " 个、离线 " + offline + " 个、维护中 " + maint + " 个。");
                    agents.stream().filter(a -> a.getStatus() != AgentStatus.ONLINE).forEach(a ->
                            sb.append("\n- ").append(a.getName()).append("(").append(a.getStatus()).append(")"));
                    return outcome(sb.toString(), toolCalls, evidence);
                }
                case QUERY_QUALITY -> {
                    Map<String, Object> summary = dashboardService.qualitySummary();
                    toolCalls.add(toolCall("get_quality_summary", Map.of(),
                            "质量通过率 " + summary.get("passRate") + "%"));
                    evidence.add(toJson(summary));
                    String reply = "数据质量:累计检查 " + summary.get("totalChecks") + " 次,通过 "
                            + summary.get("passed") + " 次,未通过 " + summary.get("failed")
                            + " 次,通过率 " + summary.get("passRate") + "%。";
                    return outcome(reply, toolCalls, evidence);
                }
                case TRIGGER_PIPELINE -> {
                    Long pipelineId = Long.parseLong(slots.get("pipelineId").toString());
                    ConstraintValidator.ValidationResult check = constraintValidator.validateToolCall(
                            "trigger_pipeline", Map.of("pipelineId", pipelineId), message);
                    if (!check.allowed()) {
                        return outcome("已阻止本次触发:" + String.join(";", check.violations())
                                + "。如需触发,请明确说「触发/运行 管道 <id>」。", toolCalls, evidence);
                    }
                    Pipeline pipeline = pipelineRepository.findById(pipelineId)
                            .orElseThrow(() -> BizException.notFound("管道不存在: id=" + pipelineId));
                    Long runId = pipelineEngine.trigger(pipelineId, TriggerType.API);
                    toolCalls.add(toolCall("trigger_pipeline", Map.of("pipelineId", pipelineId),
                            "已触发,运行 #" + runId));
                    evidence.add("触发管道:" + pipeline.getName() + ",运行ID:" + runId);
                    String reply = "已按你的要求触发管道「" + pipeline.getName() + "」(ID " + pipelineId
                            + "),新运行 ID 为 " + runId + ",可在运行页实时查看进度;对我说「诊断运行 "
                            + runId + "」可随时分析。";
                    return outcome(reply, toolCalls, evidence);
                }
                case DIAGNOSE_RUN -> {
                    Long runId = Long.parseLong(slots.get("runId").toString());
                    Map<String, Object> diagnosis = diagnose(runId);
                    toolCalls.add(toolCall("diagnose_run", Map.of("runId", runId),
                            "根因:" + diagnosis.get("rootCause")));
                    evidence.add(toJson(diagnosis));
                    @SuppressWarnings("unchecked")
                    List<String> suggestions = (List<String>) diagnosis.get("suggestions");
                    String reply = "诊断运行 #" + runId + "(" + diagnosis.get("pipelineName") + ",状态 "
                            + diagnosis.get("status") + "):\n根因:" + diagnosis.get("rootCause")
                            + "\n分析:" + diagnosis.get("diagnosis") + "\n建议:"
                            + String.join(";", suggestions);
                    return outcome(reply, toolCalls, evidence);
                }
                case GENERATE_PIPELINE -> {
                    Map<String, Object> generated = generatePipeline(message);
                    toolCalls.add(toolCall("generate_pipeline", Map.of("description", message),
                            "已生成管道 #" + generated.get("pipelineId")));
                    evidence.add(toJson(generated));
                    String reply = "已为你生成管道「" + generated.get("name") + "」(ID "
                            + generated.get("pipelineId") + ",共 " + generated.get("nodeCount")
                            + " 个节点,DRAFT 状态)。" + generated.get("explanation")
                            + " 可到管道详情页查看 DAG 并启用。";
                    return outcome(reply, toolCalls, evidence);
                }
                case KNOWLEDGE_QA -> {
                    RagService.RagAnswer rag = ragService.answer(rewrite.rewritten());
                    return new ChatOutcome(rag.answer(), toolCalls, rag.citations(),
                            rag.evidenceTexts(), rag.grounded());
                }
                case HELP -> {
                    return outcome(helpMenu(), toolCalls, evidence);
                }
                default -> {
                    return outcome("我还没有完全理解你的意思。你可以试试:\n" + helpMenu(), toolCalls, evidence);
                }
            }
        } catch (BizException e) {
            return outcome("操作失败:" + e.getMessage(), toolCalls, evidence);
        } catch (Exception e) {
            log.error("AI 助手处理异常", e);
            return outcome("处理时发生内部错误:" + e.getMessage(), toolCalls, evidence);
        }
    }

    // ==================== 诊断 ====================

    /** 运行诊断:拉 nodeRuns + 错误日志,模板化(mock)或 LLM(真实)生成根因分析 */
    public Map<String, Object> diagnose(Long runId) {
        Map<String, Object> detail = runService.detail(runId);
        @SuppressWarnings("unchecked")
        List<NodeRun> nodeRuns = (List<NodeRun>) detail.get("nodeRuns");
        // 最近 30 条 WARN/ERROR 日志
        List<RunLog> warnErrorLogs = runLogRepository.findByRunIdOrderByTimestampAscIdAsc(runId).stream()
                .filter(l -> l.getLevel() == LogLevel.WARN || l.getLevel() == LogLevel.ERROR)
                .toList();
        List<RunLog> errorLogs = warnErrorLogs.subList(Math.max(0, warnErrorLogs.size() - 30), warnErrorLogs.size());

        List<NodeRun> failedNodes = nodeRuns.stream()
                .filter(n -> n.getStatus() == NodeRunStatus.FAILED || n.getStatus() == NodeRunStatus.CANCELED)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("pipelineName", detail.get("pipelineName"));
        result.put("status", detail.get("status"));
        result.put("mockMode", llmClient.isMock());

        String logDigest = errorLogs.stream().map(RunLog::getMessage).limit(10)
                .reduce("", (a, b) -> a + "\n" + b);

        if (!llmClient.isMock()) {
            String llmAnswer = llmClient.chat(
                    "你是 ETL 运维专家。根据运行状态、失败节点与错误日志,输出根因分析与修复建议,"
                            + "格式:根因:...\n分析:...\n建议:1)... 2)...",
                    "运行#" + runId + " 状态 " + detail.get("status") + ",失败节点:"
                            + failedNodes.stream().map(n -> n.getNodeName() + "(" + n.getErrorMessage() + ")").toList()
                            + "\n错误日志:" + logDigest);
            if (llmAnswer != null && !llmAnswer.isBlank()) {
                result.put("rootCause", failedNodes.isEmpty() ? "未发现失败节点" : "见 LLM 分析");
                result.put("diagnosis", llmAnswer.trim());
                result.put("suggestions", List.of("按上方 LLM 分析执行"));
                return result;
            }
        }

        // 模拟模式:模板化分析
        if (failedNodes.isEmpty()) {
            result.put("rootCause", detail.get("errorMessage") == null ? "未发现失败节点"
                    : String.valueOf(detail.get("errorMessage")));
            result.put("diagnosis", "该运行没有失败的节点,整体状态 " + detail.get("status")
                    + "。若结果不符合预期,建议核对节点配置与数据量(读取 " + detail.get("rowsRead")
                    + " 行,写入 " + detail.get("rowsWritten") + " 行)。");
            result.put("suggestions", List.of("核对源表数据量", "查看运行日志中的 WARN 条目"));
        } else {
            NodeRun firstFailed = failedNodes.get(0);
            String err = firstFailed.getErrorMessage() == null ? "未知错误" : firstFailed.getErrorMessage();
            result.put("rootCause", "节点「" + firstFailed.getNodeName() + "」(" + firstFailed.getAgentType()
                    + ") 失败:" + err);
            StringBuilder diagnosis = new StringBuilder("失败节点「" + firstFailed.getNodeName()
                    + "」在重试 " + firstFailed.getRetryAttempt() + " 次后仍未成功。");
            if (err.contains("超时") || err.contains("连接")) {
                diagnosis.append("错误特征指向下游/源端连接问题,属于典型的外部依赖抖动。");
            } else if (err.contains("质量")) {
                diagnosis.append("错误来自质量检查未通过,说明上游数据存在脏数据。");
            } else {
                diagnosis.append("错误信息为:").append(err).append("。");
            }
            diagnosis.append(" 关联错误日志 ").append(errorLogs.size()).append(" 条。");
            result.put("diagnosis", diagnosis.toString());
            result.put("suggestions", List.of(
                    "检查节点「" + firstFailed.getNodeName() + "」的配置(表名/规则/目标表)",
                    "确认对应数据源连接正常(数据源页可点测试)",
                    "修复后对该运行执行重跑(rerun)"));
        }
        return result;
    }

    // ==================== 生成管道 ====================

    /** 自然语言生成管道:mock 模式按关键词套 5 节点模板,真实模式让 LLM 出 JSON 再校验落库 */
    @Transactional
    public Map<String, Object> generatePipeline(String description) {
        if (description == null || description.isBlank()) {
            throw BizException.badRequest("管道描述不能为空");
        }
        if (!llmClient.isMock()) {
            Map<String, Object> llmResult = tryGenerateByLlm(description);
            if (llmResult != null) {
                return llmResult;
            }
        }
        return generateByTemplate(description);
    }

    /** 模板生成:按关键词选择源表,生成 编排→抽取→转换→加载→质检 五节点 DAG */
    private Map<String, Object> generateByTemplate(String description) {
        // 关键词 → 源表 / 主题
        String topic;
        String sourceTable;
        String targetTable;
        if (description.contains("订单")) {
            topic = "订单";
            sourceTable = "ods_orders";
            targetTable = "dwd_orders_ai";
        } else if (description.contains("日志") || description.contains("事件")) {
            topic = "事件日志";
            sourceTable = "ods_events";
            targetTable = "dwd_events_ai";
        } else {
            topic = "用户";
            sourceTable = "ods_users";
            targetTable = "dwd_users_ai";
        }
        // H2 数据源
        Long h2Id = dataSourceRepository.findAll().stream()
                .filter(ds -> ds.getType() == DataSourceType.H2)
                .map(DataSource::getId).findFirst().orElse(null);
        // 目标表相关的质量规则
        List<Long> ruleIds = qualityRuleRepository.findAll().stream()
                .filter(r -> r.getTargetTable().equals(sourceTable))
                .map(QualityRule::getId).limit(3).toList();

        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        Pipeline pipeline = new Pipeline();
        pipeline.setName("AI生成-" + topic + "同步管道");
        pipeline.setCode("ai_pl_" + suffix);
        pipeline.setDescription("由 AI 助手根据描述生成:「" + description + "」");
        pipeline.setCategory("AI生成");
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipeline.setFailureStrategy(FailureStrategy.FAIL_FAST);
        pipeline.setRetryCount(1);
        pipeline.setRetryIntervalSeconds(5);
        pipeline.setTimeoutMinutes(30);

        List<PipelineNode> nodes = List.of(
                aiNode(pipeline, "n_start", "编排入口", AgentType.ORCHESTRATOR, null,
                        "{\"note\":\"" + topic + "同步流程入口\"}", 80, 200),
                aiNode(pipeline, "n_extract", "抽取" + topic + "表", AgentType.EXTRACTOR, h2Id,
                        "{\"sourceTable\":\"" + sourceTable + "\",\"batchSize\":1000}", 300, 200),
                aiNode(pipeline, "n_transform", "清洗转换", AgentType.TRANSFORMER, null,
                        "{\"rules\":[\"trim\",\"dedup\"],\"filterRatio\":0.05}", 520, 200),
                aiNode(pipeline, "n_load", "加载入仓", AgentType.LOADER, h2Id,
                        "{\"targetTable\":\"" + targetTable + "\",\"writeMode\":\"INSERT\"}", 740, 200),
                aiNode(pipeline, "n_check", "质量检查", AgentType.QUALITY_CHECKER, null,
                        "{\"ruleIds\":" + ruleIds + "}", 960, 200));
        List<PipelineEdge> edges = new ArrayList<>();
        String[][] pairs = {{"n_start", "n_extract"}, {"n_extract", "n_transform"},
                {"n_transform", "n_load"}, {"n_load", "n_check"}};
        for (String[] pair : pairs) {
            PipelineEdge edge = new PipelineEdge(pair[0], pair[1]);
            edge.setPipeline(pipeline);
            edges.add(edge);
        }
        pipeline.setNodes(nodes);
        pipeline.setEdges(edges);

        Pipeline saved = pipelineService.create(pipeline);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pipelineId", saved.getId());
        result.put("name", saved.getName());
        result.put("nodeCount", 5);
        result.put("mockMode", llmClient.isMock());
        result.put("explanation", "根据描述中的关键词「" + topic + "」匹配到源表 " + sourceTable
                + ",生成标准五节点 DAG(编排入口→抽取→清洗→加载→质检),质检关联规则 " + ruleIds + "。");
        return result;
    }

    /** 真实模式:LLM 输出 JSON DAG,解析 + 拓扑校验后落库;失败返回 null 回落模板 */
    private Map<String, Object> tryGenerateByLlm(String description) {
        try {
            String schema = "{\"name\":\"管道名\",\"nodes\":[{\"nodeKey\":\"...\",\"name\":\"...\","
                    + "\"agentType\":\"EXTRACTOR|TRANSFORMER|LOADER|QUALITY_CHECKER|ORCHESTRATOR\","
                    + "\"config\":\"JSON字符串\"}],\"edges\":[{\"fromNodeKey\":\"...\",\"toNodeKey\":\"...\"}]}";
            String answer = llmClient.chat(
                    "你是 ETL 管道设计专家。根据用户描述输出严格的 JSON DAG(不要输出任何其它文字)。schema:" + schema
                            + "。可用源表:ods_users,ods_orders,ods_events;目标表以 dwd_ 开头命名。",
                    description);
            if (answer == null) {
                return null;
            }
            String json = answer.replaceAll("(?s)```json|```", "").trim();
            var tree = objectMapper.readTree(json);
            // 组装实体
            Pipeline pipeline = new Pipeline();
            pipeline.setName(tree.path("name").asText("AI生成管道"));
            pipeline.setCode("ai_pl_" + (System.currentTimeMillis() % 100000));
            pipeline.setDescription("由 AI 助手生成:「" + description + "」");
            pipeline.setCategory("AI生成");
            pipeline.setStatus(PipelineStatus.DRAFT);
            List<PipelineNode> nodes = new ArrayList<>();
            int idx = 0;
            for (var n : tree.path("nodes")) {
                PipelineNode node = aiNode(pipeline, n.path("nodeKey").asText("n" + (++idx)),
                        n.path("name").asText("节点" + idx),
                        AgentType.valueOf(n.path("agentType").asText("ORCHESTRATOR")),
                        null, n.path("config").asText("{}"), 100 + idx * 180, 200);
                nodes.add(node);
            }
            List<PipelineEdge> edges = new ArrayList<>();
            for (var e : tree.path("edges")) {
                PipelineEdge edge = new PipelineEdge(e.path("fromNodeKey").asText(), e.path("toNodeKey").asText());
                edge.setPipeline(pipeline);
                edges.add(edge);
            }
            pipeline.setNodes(nodes);
            pipeline.setEdges(edges);
            // create 内部会做 DAG 拓扑校验,非法则抛异常回落
            Pipeline saved = pipelineService.create(pipeline);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pipelineId", saved.getId());
            result.put("name", saved.getName());
            result.put("nodeCount", nodes.size());
            result.put("mockMode", false);
            result.put("explanation", "LLM 生成 DAG 并通过拓扑校验后落库。");
            return result;
        } catch (Exception e) {
            log.warn("LLM 管道生成失败,回落模板: {}", e.getMessage());
            return null;
        }
    }

    private PipelineNode aiNode(Pipeline pipeline, String key, String name, AgentType type,
                                Long datasourceId, String config, int x, int y) {
        PipelineNode node = new PipelineNode();
        node.setPipeline(pipeline);
        node.setNodeKey(key);
        node.setName(name);
        node.setAgentType(type);
        node.setDatasourceId(datasourceId);
        node.setConfig(config);
        node.setPositionX(x);
        node.setPositionY(y);
        return node;
    }

    // ==================== 检索调试口 ====================

    /** 检索调试:改写 + 混合检索,返回三项分数 */
    public Map<String, Object> retrieveDebug(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw BizException.badRequest("query 不能为空");
        }
        QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(query);
        List<HybridRetriever.RetrievedChunk> chunks = hybridRetriever.retrieve(
                rewrite.rewritten(), topK == null ? 5 : topK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("rewrittenQuery", rewrite.rewritten());
        result.put("expansions", rewrite.expansions());
        result.put("chunks", chunks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", c.id());
            m.put("title", c.title());
            m.put("snippet", c.snippet());
            m.put("keywordScore", c.keywordScore());
            m.put("vectorScore", c.vectorScore());
            m.put("hybridScore", c.hybridScore());
            return m;
        }).toList());
        result.put("mockMode", llmClient.isMock());
        return result;
    }

    // ==================== 历史与工具 ====================

    public List<AiChatMessage> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return chatMessageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    public Map<IntentType, List<SlotFiller.SlotDef>> intentDefinitions() {
        return slotFiller.slotDefinitions();
    }

    // ==================== 内部工具 ====================

    private ChatOutcome outcome(String reply, List<Map<String, Object>> toolCalls, List<String> evidence) {
        return new ChatOutcome(reply, toolCalls, List.of(), evidence, false);
    }

    private Map<String, Object> toolCall(String tool, Map<String, Object> arguments, String summary) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool);
        call.put("arguments", arguments);
        call.put("summary", summary);
        return call;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private void persistAssistant(String sessionId, String reply, IntentType intent) {
        chatMessageRepository.save(new AiChatMessage(sessionId, "assistant", reply, intent));
    }

    /** 槽位缺失时的追问话术 */
    private String buildFollowUpQuestion(IntentType intent, List<String> missingSlots) {
        String missing = String.join("、", missingSlots);
        return switch (intent) {
            case DIAGNOSE_RUN -> "要诊断哪次运行?请给我运行 ID(例如「诊断运行 123」或只回复运行号),"
                    + "也可以先问我「最近有失败的运行吗」。(缺失:" + missing + ")";
            case TRIGGER_PIPELINE -> "要触发哪条管道?请提供管道 ID 或管道名(缺失:" + missing + ")。";
            case QUERY_RUN_DETAIL -> "要查看哪次运行的详情?请提供运行 ID。(缺失:" + missing + ")";
            case GENERATE_PIPELINE -> "请描述要生成的管道做什么,例如「每天同步订单数据到数仓并质检」。";
            default -> "信息不足,请补充:" + missing;
        };
    }

    private String helpMenu() {
        return "我是 DataForge 平台运维助手,可以帮你:\n"
                + "1. 平台总览——「平台现在怎么样」\n"
                + "2. 失败查询——「最近有失败的运行吗」「近 48 小时失败情况」\n"
                + "3. 运行详情——「运行 123 的详情」\n"
                + "4. Agent 状态——「Agent 都在线吗」\n"
                + "5. 数据质量——「质量通过率怎么样」\n"
                + "6. 触发管道——「触发管道 1」(危险操作,需明确指令)\n"
                + "7. 运行诊断——「诊断运行 123」\n"
                + "8. 生成管道——「帮我创建一个每天同步订单数据并质检的管道」\n"
                + "9. 平台知识问答——「什么是数据质量」「管道失败了怎么排查」";
    }
}
