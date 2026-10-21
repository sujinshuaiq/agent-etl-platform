package com.dataforge.etl.ai.retrieve;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置知识库:平台功能说明、枚举字典、API 速查、故障排查 FAQ、ETL/Agent 概念。
 * 启动时静态装配,供混合检索与 RAG 使用。
 */
@Component
public class KnowledgeBase {

    /** 知识块 */
    public record Chunk(String id, String title, String content, List<String> tags) {
    }

    private final List<Chunk> chunks;

    public KnowledgeBase() {
        this.chunks = buildChunks();
    }

    public List<Chunk> getChunks() {
        return chunks;
    }

    private List<Chunk> buildChunks() {
        return List.of(
                chunk("kb-001", "平台简介", "DataForge 是一个多 Agent ETL / 数据管道编排平台,支持以 DAG 方式编排数据管道,由不同类型的 Agent(抽取、转换、加载、质量检查、编排)协同执行,并提供运行监控、告警、调度与数据质量能力。", List.of("平台", "简介")),
                chunk("kb-002", "Agent 类型说明", "平台有五类 Agent:EXTRACTOR(抽取,从数据源读取数据)、TRANSFORMER(转换,清洗/脱敏/过滤)、LOADER(加载,写入目标表)、QUALITY_CHECKER(质量检查,执行质量规则)、ORCHESTRATOR(编排,流程入口与汇合协调)。", List.of("agent", "类型", "枚举")),
                chunk("kb-003", "Agent 状态说明", "Agent 状态有 ONLINE(在线,可接收任务)、OFFLINE(离线)、MAINTENANCE(维护中,不参与心跳超时判定)。心跳服务每 10 秒刷新在线 Agent 的心跳,超过 45 秒未上报会被标记为离线。", List.of("agent", "状态", "心跳")),
                chunk("kb-004", "管道状态说明", "管道状态:DRAFT(草稿,可编辑不可调度)、ENABLED(已启用,可触发)、DISABLED(已停用,不能触发运行)。", List.of("管道", "状态", "枚举")),
                chunk("kb-005", "运行状态说明", "运行状态:PENDING(排队中)、RUNNING(运行中)、SUCCESS(成功)、FAILED(失败)、CANCELED(已取消)、PARTIAL_SUCCESS(部分成功,CONTINUE 策略下部分分支失败)。节点状态另有 SKIPPED(因上游失败被跳过)。", List.of("运行", "状态", "枚举")),
                chunk("kb-006", "失败策略", "管道支持两种失败策略:FAIL_FAST(任一节点失败立即中止后续调度)与 CONTINUE(失败节点的下游跳过,其余分支继续执行,最终状态为 PARTIAL_SUCCESS)。节点级失败按 retryCount 与 retryIntervalSeconds 自动重试。", List.of("失败", "重试", "策略")),
                chunk("kb-007", "管道 DAG 编排", "管道由节点(nodes)与有向边(edges)构成 DAG。保存时后端做拓扑校验(节点键唯一、边端点存在、无环);执行时按拓扑分层,同层节点并行执行。", List.of("管道", "dag", "拓扑")),
                chunk("kb-008", "节点配置-抽取", "EXTRACTOR 节点配置示例:{\"sourceTable\":\"ods_users\",\"batchSize\":1000,\"whereClause\":\"...\"}。执行时真实统计 H2 源表行数并分批抽取。", List.of("抽取", "配置")),
                chunk("kb-009", "节点配置-转换", "TRANSFORMER 节点配置示例:{\"rules\":[\"trim\",\"upper:email\",\"mask:phone\",\"dedup\"],\"filterRatio\":0.05}。filterRatio 表示过滤掉的行数比例。", List.of("转换", "配置")),
                chunk("kb-010", "节点配置-加载", "LOADER 节点配置示例:{\"targetTable\":\"dwd_users\",\"writeMode\":\"INSERT\"}。writeMode 支持 INSERT(追加)与 OVERWRITE(先清空再写入);目标表不存在时自动创建。", List.of("加载", "配置")),
                chunk("kb-011", "节点配置-质量检查", "QUALITY_CHECKER 节点配置示例:{\"ruleIds\":[1,2]}。逐条执行规则并把结果写入质量检查结果表;ERROR/CRITICAL 级别未通过会导致节点失败。", List.of("质量", "配置")),
                chunk("kb-012", "数据质量检查类型", "质量规则支持六种检查:NULL_CHECK(空值)、RANGE_CHECK(数值范围,表达式 min,max)、UNIQUE_CHECK(唯一性)、REGEX_CHECK(正则)、ENUM_CHECK(枚举值,表达式 A,B,C)、ROW_COUNT(行数,表达式 >=1000)。", List.of("质量", "检查类型", "枚举")),
                chunk("kb-013", "数据质量维度", "数据质量常见维度:完整性(不缺失)、准确性(值正确)、一致性(跨系统一致)、及时性(按时到达)、唯一性(不重复)、有效性(符合格式/范围)。本平台规则引擎覆盖空值、范围、唯一、正则、枚举与行数检查。", List.of("质量", "概念")),
                chunk("kb-014", "调度与 cron", "调度任务使用六位 cron 表达式(秒 分 时 日 月 周),支持 *、?、*/n、数字、逗号列表与区间。调度器每秒扫描一次,到点以 SCHEDULED 方式触发管道。", List.of("调度", "cron")),
                chunk("kb-015", "告警机制", "管道运行 FAILED 产生 ERROR 级告警,PARTIAL_SUCCESS 产生 WARN 级告警;告警支持已读/未读管理与全部已读操作。", List.of("告警")),
                chunk("kb-016", "运行取消与重跑", "运行中的任务可随时取消:引擎在节点边界响应取消标志,当前节点标记 CANCELED、未执行节点标记 SKIPPED。失败/取消的运行可一键重跑(RETRY 触发)。", List.of("取消", "重跑")),
                chunk("kb-017", "API 速查-仪表盘", "GET /api/dashboard/overview 总览统计;GET /api/dashboard/run-trend?days=14 运行趋势;GET /api/dashboard/status-distribution 状态分布;GET /api/dashboard/agent-load Agent 负载;GET /api/dashboard/pipeline-rank 管道排行。", List.of("api", "仪表盘")),
                chunk("kb-018", "API 速查-管道", "GET /api/pipelines 列表;GET /api/pipelines/{id} 详情含 nodes/edges;POST /api/pipelines/{id}/trigger 触发运行;POST /api/pipelines/{id}/validate 拓扑校验;POST /api/pipelines/{id}/enable 启用。", List.of("api", "管道")),
                chunk("kb-019", "API 速查-运行", "GET /api/runs?status=&pipelineId=&page=0&size=20 分页查询;GET /api/runs/{id} 详情含 nodeRuns;GET /api/runs/{id}/logs 日志;POST /api/runs/{id}/cancel 取消;POST /api/runs/{id}/rerun 重跑;GET /api/runs/stream SSE 实时事件。", List.of("api", "运行")),
                chunk("kb-020", "FAQ-管道失败怎么排查", "排查步骤:1) 打开运行详情页查看哪个节点 FAILED;2) 查看该节点的 errorMessage 与 ERROR 级日志;3) 若是抽取节点,检查源表是否存在、数据源是否可用;4) 若是质量节点,查看未通过的规则;5) 修复后点击重跑。也可以直接对我说「诊断运行 <id>」。", List.of("faq", "排查", "失败")),
                chunk("kb-021", "FAQ-Agent 显示离线", "Agent 离线常见原因:1) 心跳超时(超过 45 秒未上报);2) 被手动停止;3) 维护中。可在 Agent 页点击启动/重启恢复;心跳服务每 10 秒自动刷新在线 Agent。", List.of("faq", "agent", "离线")),
                chunk("kb-022", "FAQ-管道无法触发", "管道无法触发的常见原因:状态为 DISABLED(已停用)——请先在管道详情页启用;DRAFT 与 ENABLED 均可手动触发,但只有 ENABLED 会被调度器执行。", List.of("faq", "触发", "管道")),
                chunk("kb-023", "FAQ-质量检查不通过", "质量检查未通过时:1) 在质量页查看检查结果的 expected/actual;2) NULL_CHECK 不通过说明有空值数据;3) UNIQUE_CHECK 不通过说明有重复主键;4) ERROR/CRITICAL 级规则未通过会导致质量节点失败,WARN/INFO 仅记录。", List.of("faq", "质量")),
                chunk("kb-024", "概念-什么是 ETL", "ETL 是 Extract(抽取)、Transform(转换)、Load(加载)的缩写,是把数据从业务系统搬运到数据仓库的标准流程。本平台用多 Agent 协作方式实现:抽取 Agent、转换 Agent、加载 Agent 各司其职,由 DAG 编排。", List.of("概念", "etl")),
                chunk("kb-025", "概念-什么是 Agent", "Agent(智能体)是能感知环境、自主决策并执行动作的计算实体。经典循环为 感知-决策-行动;在 LLM 场景常见 ReAct(推理+行动交替)与 Plan-and-Execute(先规划后执行)模式。本平台的 Agent 是 ETL 角色的执行单元。", List.of("概念", "agent")),
                chunk("kb-026", "概念-多 Agent 编排", "多 Agent 编排常见模式:orchestrator-worker(编排者分发任务)、pipeline(流水线串联)、blackboard(共享黑板协作)。本平台采用 DAG 编排:节点即 Agent 任务,边即依赖,拓扑分层并行执行。", List.of("概念", "编排", "多agent")),
                chunk("kb-027", "概念-什么是 RAG", "RAG(检索增强生成)是先检索相关知识片段、再让大模型基于资料回答的技术,可显著减少幻觉。本助手内置混合检索(关键词 BM25 简化版 + 向量余弦),回答时强制引用资料编号。", List.of("概念", "rag", "检索")),
                chunk("kb-028", "概念-幻觉治理", "幻觉指模型编造不存在的事实。本平台助手的治理手段:1) 工具调用拿真实数据;2) RAG 强制资料引用;3) 回复中的数字必须在工具/检索证据中溯源,无法溯源则替换为保守表述;4) 输出置信度与 guardNotes 审计。", List.of("概念", "幻觉", "治理")),
                chunk("kb-029", "概念-幂等与断点续传", "ETL 幂等指重复执行不产生副作用,常用手段:唯一键去重、OVERWRITE 覆盖写、按批次提交。断点续传指失败后从断点继续而非从头再来;本平台通过重试策略与整 run 重跑提供基础容错。", List.of("概念", "幂等", "etl")),
                chunk("kb-030", "内置演示数据", "平台首次启动自动生成演示数据:10 个 Agent、8 个数据源、6 条管道、30 天约 860 条历史运行、8 条质量规则、220 条检查结果、30 条告警、5 条调度;业务源表 ods_users(1万行)、ods_orders(5万行)、ods_events(10万行)。", List.of("数据", "演示", "种子")),
                chunk("kb-031", "SSE 实时推送", "运行监控通过 SSE(Server-Sent Events)实时推送:GET /api/runs/stream 订阅,事件类型 RUN_UPDATE(运行状态)、NODE_UPDATE(节点状态)、LOG(日志)。前端也可降级为 2 秒轮询。", List.of("sse", "推送", "api")),
                chunk("kb-032", "AI 助手能力", "AI 助手支持:平台总览查询、失败运行查询、运行详情、Agent 状态、质量通过率、触发管道(危险操作需明确指令)、运行诊断、自然语言生成管道、平台知识问答(RAG)。支持多轮对话与槽位补全:缺少参数时会主动追问。", List.of("ai", "助手", "能力"))
        );
    }

    private Chunk chunk(String id, String title, String content, List<String> tags) {
        return new Chunk(id, title, content, tags);
    }
}
