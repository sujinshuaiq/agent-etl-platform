package com.dataforge.etl.seed;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.repository.AgentRepository;
import com.dataforge.etl.alert.entity.Alert;
import com.dataforge.etl.alert.repository.AlertRepository;
import com.dataforge.etl.common.enums.*;
import com.dataforge.etl.datasource.entity.DataSource;
import com.dataforge.etl.datasource.repository.DataSourceRepository;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.entity.PipelineEdge;
import com.dataforge.etl.pipeline.entity.PipelineNode;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.quality.entity.QualityResult;
import com.dataforge.etl.quality.entity.QualityRule;
import com.dataforge.etl.quality.repository.QualityResultRepository;
import com.dataforge.etl.quality.repository.QualityRuleRepository;
import com.dataforge.etl.run.entity.NodeRun;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.entity.RunLog;
import com.dataforge.etl.run.repository.NodeRunRepository;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import com.dataforge.etl.run.repository.RunLogRepository;
import com.dataforge.etl.schedule.entity.ScheduleJob;
import com.dataforge.etl.schedule.repository.ScheduleJobRepository;
import com.dataforge.etl.schedule.service.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 数据初始化器:仅当数据库为空(无 Agent 记录)时执行完整种子初始化。
 *
 * <p>内容:业务源表(16 万行真实数据)、10 个 Agent、8 个数据源、
 * 6 条含完整 DAG 的管道、30 天历史运行 ≥800 条(含节点运行与日志)、
 * 8 条质量规则 + ≥200 条检查结果、30 条告警、5 条调度任务。</p>
 *
 * <p>全程使用固定随机种子,保证演示数据可复现;各阶段打印进度日志。</p>
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final long RANDOM_SEED = 20240815L;
    /** 运行逐条落库(需要立即拿到自增 id),nodeRuns/logs 攒批写入 */
    private static final int BATCH_FLUSH_SIZE = 1000;

    private final AgentRepository agentRepository;
    private final DataSourceRepository dataSourceRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;
    private final RunLogRepository runLogRepository;
    private final QualityRuleRepository qualityRuleRepository;
    private final QualityResultRepository qualityResultRepository;
    private final AlertRepository alertRepository;
    private final ScheduleJobRepository scheduleJobRepository;
    private final MockDataGenerator mockDataGenerator;
    private final CronParser cronParser;

    @Value("${dataforge.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${dataforge.seed.history-days:30}")
    private int historyDays;

    public DataInitializer(AgentRepository agentRepository,
                           DataSourceRepository dataSourceRepository,
                           PipelineRepository pipelineRepository,
                           PipelineRunRepository runRepository,
                           NodeRunRepository nodeRunRepository,
                           RunLogRepository runLogRepository,
                           QualityRuleRepository qualityRuleRepository,
                           QualityResultRepository qualityResultRepository,
                           AlertRepository alertRepository,
                           ScheduleJobRepository scheduleJobRepository,
                           MockDataGenerator mockDataGenerator,
                           CronParser cronParser) {
        this.agentRepository = agentRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.pipelineRepository = pipelineRepository;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.runLogRepository = runLogRepository;
        this.qualityRuleRepository = qualityRuleRepository;
        this.qualityResultRepository = qualityResultRepository;
        this.alertRepository = alertRepository;
        this.scheduleJobRepository = scheduleJobRepository;
        this.mockDataGenerator = mockDataGenerator;
        this.cronParser = cronParser;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("种子初始化已禁用(dataforge.seed.enabled=false),跳过");
            return;
        }
        if (agentRepository.count() > 0) {
            log.info("数据库已有数据,跳过种子初始化");
            return;
        }
        long begin = System.currentTimeMillis();
        log.info("========== 数据库为空,开始种子初始化 ==========");
        Random random = new Random(RANDOM_SEED);

        // 阶段 1:业务源表(16 万行真实数据)
        log.info("[1/9] 生成业务源表 ods_users(1万) / ods_orders(5万) / ods_events(10万)...");
        mockDataGenerator.generateBusinessTables();

        // 阶段 2:Agent
        log.info("[2/9] 生成 10 个 Agent...");
        List<Agent> agents = seedAgents(random);
        agentRepository.saveAll(agents);

        // 阶段 3:数据源
        log.info("[3/9] 生成 8 个数据源...");
        List<DataSource> dataSources = dataSourceRepository.saveAll(seedDataSources(random));

        // 阶段 4:质量规则
        log.info("[4/9] 生成 8 条质量规则...");
        List<QualityRule> rules = qualityRuleRepository.saveAll(seedQualityRules());

        // 阶段 5:管道(含完整 DAG)
        log.info("[5/9] 生成 6 条管道(含完整 DAG)...");
        List<Pipeline> pipelines = pipelineRepository.saveAll(seedPipelines(dataSources, rules));

        // 阶段 6:历史运行(≥800 条,含节点运行与日志)
        log.info("[6/9] 生成最近 {} 天的历史运行记录(≥800 条,含 nodeRuns 与日志)...", historyDays);
        List<Long> runIds = seedHistoryRuns(pipelines, agents, random);

        // 阶段 7:质量检查结果(≥200 条)
        log.info("[7/9] 生成质量检查结果(≥200 条)...");
        seedQualityResults(rules, runIds, random);

        // 阶段 8:告警(30 条)
        log.info("[8/9] 生成 30 条告警...");
        seedAlerts(runIds, random);

        // 阶段 9:调度任务(5 条)
        log.info("[9/9] 生成 5 条调度任务...");
        seedSchedules(pipelines, random);

        // 回填 Agent 运行指标
        log.info("回填 Agent 运行指标...");
        backfillAgentMetrics(agents, random);
        agentRepository.saveAll(agents);

        log.info("========== 种子初始化完成,总耗时 {}ms ==========", System.currentTimeMillis() - begin);
    }

    // ==================== Agent ====================

    private List<Agent> seedAgents(Random random) {
        List<Agent> agents = new ArrayList<>();
        agents.add(agent("抽取Agent-01", "extractor-01", AgentType.EXTRACTOR, AgentStatus.ONLINE,
                "node-01.dataforge.local", 9101, "MySQL,H2,CSV", 8, "负责关系型数据库与文件数据的全量/增量抽取"));
        agents.add(agent("抽取Agent-02", "extractor-02", AgentType.EXTRACTOR, AgentStatus.ONLINE,
                "node-02.dataforge.local", 9102, "MySQL,REST_API", 8, "负责业务库与开放接口数据抽取"));
        agents.add(agent("抽取Agent-03", "extractor-03", AgentType.EXTRACTOR, AgentStatus.ONLINE,
                "node-03.dataforge.local", 9103, "KAFKA,HDFS_SIM", 6, "负责消息队列与离线数仓数据接入"));
        agents.add(agent("抽取Agent-04", "extractor-04", AgentType.EXTRACTOR, AgentStatus.MAINTENANCE,
                "node-04.dataforge.local", 9104, "MySQL,H2", 4, "备用抽取节点,版本升级维护中"));
        agents.add(agent("转换Agent-01", "transformer-01", AgentType.TRANSFORMER, AgentStatus.ONLINE,
                "node-11.dataforge.local", 9201, "清洗,脱敏,去重", 8, "负责数据清洗、字段转换与脱敏"));
        agents.add(agent("转换Agent-02", "transformer-02", AgentType.TRANSFORMER, AgentStatus.ONLINE,
                "node-12.dataforge.local", 9202, "清洗,聚合,关联", 8, "负责多源数据关联与聚合加工"));
        agents.add(agent("加载Agent-01", "loader-01", AgentType.LOADER, AgentStatus.ONLINE,
                "node-21.dataforge.local", 9301, "H2,MySQL", 8, "负责把加工结果批量写入数仓明细层"));
        agents.add(agent("加载Agent-02", "loader-02", AgentType.LOADER, AgentStatus.OFFLINE,
                "node-22.dataforge.local", 9302, "HDFS_SIM,MySQL", 4, "离线加载节点,当前停机"));
        agents.add(agent("质量Agent-01", "quality-01", AgentType.QUALITY_CHECKER, AgentStatus.ONLINE,
                "node-31.dataforge.local", 9401, "空值,唯一,范围,正则", 4, "负责数据质量规则执行与判定"));
        agents.add(agent("编排Agent-01", "orchestrator-01", AgentType.ORCHESTRATOR, AgentStatus.ONLINE,
                "node-41.dataforge.local", 9501, "DAG调度,依赖协调", 2, "负责任务编排与依赖协调"));
        // 心跳时间初始化
        LocalDateTime now = LocalDateTime.now();
        for (Agent a : agents) {
            if (a.getStatus() == AgentStatus.ONLINE) {
                a.setLastHeartbeatAt(now.minusSeconds(random.nextInt(10)));
            }
        }
        return agents;
    }

    private Agent agent(String name, String code, AgentType type, AgentStatus status,
                        String host, int port, String capabilities, int maxConcurrency, String description) {
        Agent a = new Agent();
        a.setName(name);
        a.setCode(code);
        a.setType(type);
        a.setStatus(status);
        a.setHost(host);
        a.setPort(port);
        a.setVersion("2.4.1");
        a.setDescription(description);
        a.setCapabilities(List.of(capabilities.split(",")));
        a.setMaxConcurrency(maxConcurrency);
        a.setCurrentTasks(0);
        return a;
    }

    // ==================== 数据源 ====================

    private List<DataSource> seedDataSources(Random random) {
        List<DataSource> list = new ArrayList<>();
        list.add(dataSource("业务库-用户中心", "ds_user_center", DataSourceType.MYSQL, DataSourceStatus.ACTIVE,
                "{\"jdbcUrl\":\"jdbc:mysql://mysql-01.internal:3306/user_center\",\"username\":\"etl_ro\"}",
                "用户中心核心库,包含用户主数据", random));
        list.add(dataSource("业务库-订单中心", "ds_order_center", DataSourceType.MYSQL, DataSourceStatus.ACTIVE,
                "{\"jdbcUrl\":\"jdbc:mysql://mysql-02.internal:3306/order_center\",\"username\":\"etl_ro\"}",
                "订单交易核心库", random));
        list.add(dataSource("本地H2演示库", "ds_local_h2", DataSourceType.H2, DataSourceStatus.ACTIVE,
                "{\"jdbcUrl\":\"jdbc:h2:file:./data/dataforge\",\"username\":\"sa\"}",
                "平台内嵌 H2 库,存放 ods_*/dwd_* 演示数据", random));
        list.add(dataSource("日志CSV文件", "ds_log_csv", DataSourceType.CSV, DataSourceStatus.ACTIVE,
                "{\"path\":\"/data/logs/access/\",\"pattern\":\"access_*.csv\"}",
                "Nginx 访问日志归档目录", random));
        list.add(dataSource("第三方开放API", "ds_open_api", DataSourceType.REST_API, DataSourceStatus.ACTIVE,
                "{\"baseUrl\":\"https://open-api.partner.example.com\",\"authType\":\"AKSK\"}",
                "合作方开放数据接口", random));
        list.add(dataSource("消息队列-订单事件", "ds_order_kafka", DataSourceType.KAFKA, DataSourceStatus.ACTIVE,
                "{\"bootstrapServers\":\"kafka-01:9092,kafka-02:9092\",\"topics\":\"order.created,order.paid\"}",
                "订单域事件流", random));
        list.add(dataSource("离线数仓HDFS模拟", "ds_hdfs_sim", DataSourceType.HDFS_SIM, DataSourceStatus.ACTIVE,
                "{\"namenode\":\"hdfs://nn1:8020\",\"warehouseDir\":\"/warehouse\"}",
                "离线数仓(本地目录模拟 HDFS)", random));
        list.add(dataSource("CRM客户库", "ds_crm", DataSourceType.MYSQL, DataSourceStatus.DISABLED,
                "{\"jdbcUrl\":\"jdbc:mysql://mysql-03.internal:3306/crm\",\"username\":\"etl_ro\"}",
                "CRM 系统客户库,迁移中暂停使用", random));
        return list;
    }

    private DataSource dataSource(String name, String code, DataSourceType type, DataSourceStatus status,
                                  String config, String description, Random random) {
        DataSource ds = new DataSource();
        ds.setName(name);
        ds.setCode(code);
        ds.setType(type);
        ds.setStatus(status);
        ds.setConnectionConfig(config);
        ds.setDescription(description);
        ds.setLastTestAt(LocalDateTime.now().minusHours(random.nextInt(72)));
        ds.setLastTestSuccess(random.nextInt(100) < 95);
        return ds;
    }

    // ==================== 质量规则 ====================

    private List<QualityRule> seedQualityRules() {
        List<QualityRule> rules = new ArrayList<>();
        rules.add(rule("用户名非空检查", "ods_users", CheckType.NULL_CHECK, "username", null, Severity.ERROR,
                "用户名不允许为 NULL"));
        rules.add(rule("手机号格式检查", "ods_users", CheckType.REGEX_CHECK, "phone", "^1[3-9][0-9]{9}$", Severity.WARN,
                "手机号必须匹配大陆手机号格式"));
        rules.add(rule("邮箱格式检查", "ods_users", CheckType.REGEX_CHECK, "email",
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", Severity.WARN, "邮箱格式校验"));
        rules.add(rule("订单金额范围检查", "ods_orders", CheckType.RANGE_CHECK, "amount", "0,100000", Severity.ERROR,
                "订单金额必须在 0 ~ 100000 之间"));
        rules.add(rule("订单号唯一性检查", "ods_orders", CheckType.UNIQUE_CHECK, "order_no", null, Severity.CRITICAL,
                "订单号全表唯一"));
        rules.add(rule("用户年龄范围检查", "ods_users", CheckType.RANGE_CHECK, "age", "0,120", Severity.INFO,
                "年龄合理性检查"));
        rules.add(rule("事件类型枚举检查", "ods_events", CheckType.ENUM_CHECK, "event_type",
                "CLICK,VIEW,PURCHASE,LOGIN,LOGOUT", Severity.WARN, "事件类型必须属于预定义枚举"));
        rules.add(rule("用户表行数检查", "ods_users", CheckType.ROW_COUNT, null, ">=1000", Severity.INFO,
                "用户表行数不得低于 1000"));
        return rules;
    }

    private QualityRule rule(String name, String table, CheckType type, String field,
                             String expression, Severity severity, String description) {
        QualityRule r = new QualityRule();
        r.setName(name);
        r.setTargetTable(table);
        r.setCheckType(type);
        r.setField(field);
        r.setExpression(expression);
        r.setSeverity(severity);
        r.setEnabled(true);
        r.setDescription(description);
        return r;
    }

    // ==================== 管道(完整 DAG) ====================

    private List<Pipeline> seedPipelines(List<DataSource> dataSources, List<QualityRule> rules) {
        Long h2Id = dataSources.get(2).getId(); // 本地H2演示库

        List<Pipeline> pipelines = new ArrayList<>();

        // P1 用户数据日更管道:入口 → 抽取 → 转换 → 加载 → 质检
        pipelines.add(pipeline("用户数据日更管道", "pl_user_daily", "用户主数据每日全量抽取、清洗、入仓并质检",
                "用户域", PipelineStatus.ENABLED, FailureStrategy.FAIL_FAST, 1,
                List.of(
                        node("n_start", "编排入口", AgentType.ORCHESTRATOR, null, "{\"note\":\"用户日更流程入口\"}", 80, 200),
                        node("n_extract", "抽取用户表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_users\",\"batchSize\":1000}", 300, 200),
                        node("n_transform", "清洗用户数据", AgentType.TRANSFORMER, null,
                                "{\"rules\":[\"trim\",\"upper:email\",\"mask:phone\",\"dedup\"],\"filterRatio\":0.05}", 520, 200),
                        node("n_load", "写入用户明细", AgentType.LOADER, h2Id,
                                "{\"targetTable\":\"dwd_users\",\"writeMode\":\"OVERWRITE\"}", 740, 200),
                        node("n_check", "用户数据质检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(0).getId() + "," + rules.get(1).getId() + ","
                                        + rules.get(2).getId() + "]}", 960, 200)),
                new String[][]{{"n_start", "n_extract"}, {"n_extract", "n_transform"},
                        {"n_transform", "n_load"}, {"n_load", "n_check"}}));

        // P2 订单交易同步管道
        pipelines.add(pipeline("订单交易同步管道", "pl_order_sync", "订单数据准实时同步入仓(CONTINUE 策略)",
                "订单域", PipelineStatus.ENABLED, FailureStrategy.CONTINUE, 2,
                List.of(
                        node("n_start", "编排入口", AgentType.ORCHESTRATOR, null, "{\"note\":\"订单同步入口\"}", 80, 160),
                        node("n_extract", "抽取订单表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_orders\",\"batchSize\":2000}", 300, 160),
                        node("n_transform", "订单数据清洗", AgentType.TRANSFORMER, null,
                                "{\"rules\":[\"trim\",\"dedup\"],\"filterRatio\":0.02}", 520, 160),
                        node("n_load", "写入订单明细", AgentType.LOADER, h2Id,
                                "{\"targetTable\":\"dwd_orders\",\"writeMode\":\"INSERT\"}", 740, 160),
                        node("n_check", "订单数据质检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(3).getId() + "," + rules.get(4).getId() + "]}", 960, 160)),
                new String[][]{{"n_start", "n_extract"}, {"n_extract", "n_transform"},
                        {"n_transform", "n_load"}, {"n_load", "n_check"}}));

        // P3 事件日志采集管道
        pipelines.add(pipeline("事件日志采集管道", "pl_event_collect", "行为事件日志批量采集入仓",
                "日志域", PipelineStatus.ENABLED, FailureStrategy.FAIL_FAST, 1,
                List.of(
                        node("n_extract", "抽取事件表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_events\",\"batchSize\":5000}", 200, 180),
                        node("n_load", "写入事件明细", AgentType.LOADER, h2Id,
                                "{\"targetTable\":\"dwd_events\",\"writeMode\":\"INSERT\"}", 480, 180)),
                new String[][]{{"n_extract", "n_load"}}));

        // P4 全域数据整合管道:入口 → (用户抽取 ∥ 订单抽取) → 关联转换 → 加载 → 质检
        pipelines.add(pipeline("全域数据整合管道", "pl_global_integrate", "用户与订单双源并行抽取,关联整合成宽表",
                "数仓域", PipelineStatus.ENABLED, FailureStrategy.CONTINUE, 1,
                List.of(
                        node("n_start", "编排入口", AgentType.ORCHESTRATOR, null, "{\"note\":\"整合流程入口\"}", 80, 220),
                        node("n_extract_users", "抽取用户表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_users\",\"batchSize\":1000}", 300, 120),
                        node("n_extract_orders", "抽取订单表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_orders\",\"batchSize\":2000}", 300, 320),
                        node("n_merge", "用户订单关联", AgentType.TRANSFORMER, null,
                                "{\"rules\":[\"join:user_id\",\"dedup\"],\"filterRatio\":0.08}", 560, 220),
                        node("n_load", "写入宽表", AgentType.LOADER, h2Id,
                                "{\"targetTable\":\"dws_user_order\",\"writeMode\":\"OVERWRITE\"}", 800, 220),
                        node("n_check", "宽表质检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(5).getId() + "]}", 1020, 220)),
                new String[][]{{"n_start", "n_extract_users"}, {"n_start", "n_extract_orders"},
                        {"n_extract_users", "n_merge"}, {"n_extract_orders", "n_merge"},
                        {"n_merge", "n_load"}, {"n_load", "n_check"}}));

        // P5 数据质量巡检管道:入口 → (用户巡检 ∥ 订单巡检 ∥ 事件巡检) → 汇总
        pipelines.add(pipeline("数据质量巡检管道", "pl_quality_patrol", "定时对三张源表并行执行质量巡检",
                "质量域", PipelineStatus.ENABLED, FailureStrategy.CONTINUE, 0,
                List.of(
                        node("n_start", "编排入口", AgentType.ORCHESTRATOR, null, "{\"note\":\"巡检入口\"}", 80, 220),
                        node("n_check_users", "用户表巡检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(0).getId() + "," + rules.get(7).getId() + "]}", 340, 100),
                        node("n_check_orders", "订单表巡检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(3).getId() + "," + rules.get(4).getId() + "]}", 340, 220),
                        node("n_check_events", "事件表巡检", AgentType.QUALITY_CHECKER, null,
                                "{\"ruleIds\":[" + rules.get(6).getId() + "]}", 340, 340),
                        node("n_end", "巡检汇总", AgentType.ORCHESTRATOR, null, "{\"note\":\"巡检结果汇总\"}", 620, 220)),
                new String[][]{{"n_start", "n_check_users"}, {"n_start", "n_check_orders"},
                        {"n_start", "n_check_events"}, {"n_check_users", "n_end"},
                        {"n_check_orders", "n_end"}, {"n_check_events", "n_end"}}));

        // P6 用户行为宽表管道(草稿)
        pipelines.add(pipeline("用户行为宽表管道", "pl_behavior_wide", "行为事件加工成用户行为宽表(设计中)",
                "日志域", PipelineStatus.DRAFT, FailureStrategy.FAIL_FAST, 1,
                List.of(
                        node("n_extract", "抽取事件表", AgentType.EXTRACTOR, h2Id,
                                "{\"sourceTable\":\"ods_events\",\"batchSize\":5000}", 200, 180),
                        node("n_transform", "行为聚合加工", AgentType.TRANSFORMER, null,
                                "{\"rules\":[\"dedup\",\"agg:user_id\"],\"filterRatio\":0.03}", 460, 180),
                        node("n_load", "写入行为宽表", AgentType.LOADER, h2Id,
                                "{\"targetTable\":\"dwd_user_behavior\",\"writeMode\":\"OVERWRITE\"}", 720, 180)),
                new String[][]{{"n_extract", "n_transform"}, {"n_transform", "n_load"}}));
        return pipelines;
    }

    /** 节点规格简写 */
    private PipelineNode node(String key, String name, AgentType type, Long datasourceId,
                              String config, int x, int y) {
        PipelineNode n = new PipelineNode();
        n.setNodeKey(key);
        n.setName(name);
        n.setAgentType(type);
        n.setDatasourceId(datasourceId);
        n.setConfig(config);
        n.setPositionX(x);
        n.setPositionY(y);
        return n;
    }

    private Pipeline pipeline(String name, String code, String description, String category,
                              PipelineStatus status, FailureStrategy strategy, int retryCount,
                              List<PipelineNode> nodes, String[][] edgePairs) {
        Pipeline p = new Pipeline();
        p.setName(name);
        p.setCode(code);
        p.setDescription(description);
        p.setCategory(category);
        p.setStatus(status);
        p.setFailureStrategy(strategy);
        p.setRetryCount(retryCount);
        p.setRetryIntervalSeconds(5);
        p.setTimeoutMinutes(30);
        List<PipelineEdge> edges = new ArrayList<>();
        for (String[] pair : edgePairs) {
            edges.add(new PipelineEdge(pair[0], pair[1]));
        }
        nodes.forEach(n -> n.setPipeline(p));
        edges.forEach(e -> e.setPipeline(p));
        p.setNodes(nodes);
        p.setEdges(edges);
        return p;
    }

    // ==================== 历史运行 ====================

    /**
     * 生成 ≥800 条历史运行。run 逐条落库(需立即获得自增 id),
     * nodeRuns / logs 回填 runId 后攒批写入。
     */
    private List<Long> seedHistoryRuns(List<Pipeline> pipelines, List<Agent> agents, Random random) {
        // 每条管道的运行次数(合计 860 条)
        int[] runCounts = {260, 220, 140, 120, 80, 40};
        // 源表行数基数
        Map<String, Long> tableRows = Map.of("ods_users", 10000L, "ods_orders", 50000L, "ods_events", 100000L);
        Map<AgentType, List<Agent>> agentsByType = new EnumMap<>(AgentType.class);
        for (Agent a : agents) {
            agentsByType.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }

        List<Long> runIds = new ArrayList<>();
        List<NodeRun> nodeRunBuffer = new ArrayList<>(BATCH_FLUSH_SIZE);
        List<RunLog> logBuffer = new ArrayList<>(BATCH_FLUSH_SIZE);
        LocalDateTime now = LocalDateTime.now();

        for (int pi = 0; pi < pipelines.size(); pi++) {
            Pipeline pipeline = pipelines.get(pi);
            List<PipelineNode> nodes = pipeline.getNodes();
            for (int i = 0; i < runCounts[pi]; i++) {
                // 时间:均匀分布在过去 historyDays 天内
                LocalDateTime start = now.minusDays(random.nextInt(historyDays))
                        .minusHours(random.nextInt(24)).minusMinutes(random.nextInt(60))
                        .minusSeconds(random.nextInt(60));
                if (start.isAfter(now.minusMinutes(5))) {
                    start = now.minusMinutes(5 + random.nextInt(60));
                }
                // 状态分布:88% 成功 / 9% 失败 / 3% 取消
                int dice = random.nextInt(1000);
                RunStatus status = dice < 880 ? RunStatus.SUCCESS : (dice < 970 ? RunStatus.FAILED : RunStatus.CANCELED);
                // 触发方式:60% 调度 / 30% 手动 / 10% API
                int t = random.nextInt(100);
                TriggerType trigger = t < 60 ? TriggerType.SCHEDULED : (t < 90 ? TriggerType.MANUAL : TriggerType.API);

                // 先落库 run 拿 id(状态先按终态写入,种子数据不需要状态流转)
                PipelineRun run = new PipelineRun();
                run.setPipelineId(pipeline.getId());
                run.setPipelineName(pipeline.getName());
                run.setTriggerType(trigger);
                run.setStartTime(start);
                run.setStatus(status);
                run = runRepository.save(run);
                runIds.add(run.getId());

                // 失败点 / 取消点
                int failAt = status == RunStatus.FAILED ? random.nextInt(nodes.size()) : -1;
                int cancelAt = status == RunStatus.CANCELED ? 1 + random.nextInt(Math.max(1, nodes.size() - 1)) : -1;

                long rowsRead = 0, rowsWritten = 0, rowsFiltered = 0;
                LocalDateTime cursor = start;
                int completed = 0;
                String errorMessage = null;

                // 运行级开始日志
                logBuffer.add(timedLog(run.getId(), null, cursor, LogLevel.INFO,
                        "管道「" + pipeline.getName() + "」开始运行,触发方式 " + trigger + ",节点数 " + nodes.size()));

                for (int ni = 0; ni < nodes.size(); ni++) {
                    PipelineNode node = nodes.get(ni);
                    NodeRun nr = new NodeRun();
                    nr.setRunId(run.getId());
                    nr.setNodeKey(node.getNodeKey());
                    nr.setNodeName(node.getName());
                    nr.setAgentType(node.getAgentType());
                    List<Agent> candidates = agentsByType.getOrDefault(node.getAgentType(), List.of());
                    nr.setAgentName(candidates.isEmpty() ? "未分配"
                            : candidates.get(random.nextInt(candidates.size())).getName());
                    nr.setStartTime(cursor);

                    boolean nodeFailed = (ni == failAt);
                    boolean nodeCanceled = (ni == cancelAt);
                    boolean skipped = (failAt >= 0 && ni > failAt) || (cancelAt >= 0 && ni > cancelAt);

                    long rowsIn = estimateRowsIn(node, nodes, ni, tableRows);
                    long duration = 500 + random.nextInt(19500);
                    long rowsOut = rowsIn;
                    if (node.getAgentType() == AgentType.EXTRACTOR) {
                        rowsOut = tableRows.getOrDefault(extractTable(node), 10000L);
                    } else if (node.getAgentType() == AgentType.TRANSFORMER) {
                        rowsOut = Math.round(rowsIn * (1 - extractFilterRatio(node)));
                    }

                    if (skipped) {
                        nr.setStatus(NodeRunStatus.SKIPPED);
                        nr.setEndTime(cursor);
                        nr.setDurationMs(0L);
                        nr.setRowsIn(0L);
                        nr.setRowsOut(0L);
                        nr.setErrorMessage(status == RunStatus.CANCELED ? "运行被取消,本节点未执行" : "上游失败,本节点未执行");
                        logBuffer.add(timedLog(run.getId(), null, cursor, LogLevel.WARN,
                                "节点「" + node.getName() + "」跳过"));
                    } else if (nodeCanceled) {
                        nr.setStatus(NodeRunStatus.CANCELED);
                        nr.setEndTime(cursor.plus(duration / 2, ChronoUnit.MILLIS));
                        nr.setDurationMs(duration / 2);
                        nr.setRowsIn(rowsIn);
                        nr.setRowsOut(0L);
                        nr.setErrorMessage("运行被手动取消");
                        logBuffer.add(timedLog(run.getId(), null, cursor, LogLevel.INFO,
                                "节点「" + node.getName() + "」开始执行,输入 " + rowsIn + " 行"));
                        logBuffer.add(timedLog(run.getId(), null, nr.getEndTime(), LogLevel.WARN,
                                "节点「" + node.getName() + "」被手动取消"));
                        completed++;
                        cursor = nr.getEndTime();
                    } else if (nodeFailed) {
                        nr.setStatus(NodeRunStatus.FAILED);
                        nr.setEndTime(cursor.plus(duration, ChronoUnit.MILLIS));
                        nr.setDurationMs(duration);
                        nr.setRowsIn(rowsIn);
                        nr.setRowsOut(0L);
                        nr.setRetryAttempt(pipeline.getRetryCount() == null ? 0 : pipeline.getRetryCount());
                        errorMessage = "下游连接超时(模拟故障): " + node.getName();
                        nr.setErrorMessage(errorMessage);
                        logBuffer.add(timedLog(run.getId(), null, cursor, LogLevel.INFO,
                                "节点「" + node.getName() + "」开始执行,输入 " + rowsIn + " 行"));
                        logBuffer.add(timedLog(run.getId(), null, cursor.plus(duration / 2, ChronoUnit.MILLIS), LogLevel.WARN,
                                "第 1 次执行失败: 下游连接超时,准备重试"));
                        logBuffer.add(timedLog(run.getId(), null, nr.getEndTime(), LogLevel.ERROR,
                                "节点「" + node.getName() + "」执行失败: " + errorMessage));
                        completed++;
                        cursor = nr.getEndTime();
                    } else {
                        nr.setStatus(NodeRunStatus.SUCCESS);
                        nr.setEndTime(cursor.plus(duration, ChronoUnit.MILLIS));
                        nr.setDurationMs(duration);
                        nr.setRowsIn(rowsIn);
                        nr.setRowsOut(rowsOut);
                        nr.setRetryAttempt(random.nextInt(100) < 6 ? 1 : 0);
                        logBuffer.add(timedLog(run.getId(), null, cursor, LogLevel.INFO,
                                "节点「" + node.getName() + "」开始执行,输入 " + rowsIn + " 行"));
                        if (node.getAgentType() == AgentType.EXTRACTOR) {
                            logBuffer.add(timedLog(run.getId(), null, cursor.plus(duration / 2, ChronoUnit.MILLIS), LogLevel.INFO,
                                    "源表统计完成: " + extractTable(node) + " 共 " + rowsOut + " 行"));
                        }
                        logBuffer.add(timedLog(run.getId(), null, nr.getEndTime(), LogLevel.INFO,
                                "节点「" + node.getName() + "」执行成功,输出 " + rowsOut + " 行,耗时 " + duration + "ms"));
                        if (node.getAgentType() == AgentType.EXTRACTOR) {
                            rowsRead += rowsOut;
                        } else if (node.getAgentType() == AgentType.LOADER) {
                            rowsWritten += rowsOut;
                        } else if (node.getAgentType() == AgentType.TRANSFORMER) {
                            rowsFiltered += Math.max(0, rowsIn - rowsOut);
                        }
                        completed++;
                        cursor = nr.getEndTime();
                    }
                    nodeRunBuffer.add(nr);
                }

                // 补充节点级日志量:每个成功节点额外 0~3 条 DEBUG 日志,使每 run 总日志落在 10~30 条区间
                int extra = random.nextInt(4);
                for (int k = 0; k < extra; k++) {
                    LocalDateTime ts = start.plus(random.nextInt((int) Math.max(1,
                            java.time.Duration.between(start, cursor).toMillis())), ChronoUnit.MILLIS);
                    logBuffer.add(timedLog(run.getId(), null, ts, LogLevel.DEBUG,
                            "批次提交完成,缓冲区已刷新(种子生成明细日志 #" + (k + 1) + ")"));
                }

                LocalDateTime end = cursor;
                run.setEndTime(end);
                run.setDurationMs(java.time.Duration.between(start, end).toMillis());
                run.setRowsRead(rowsRead);
                run.setRowsWritten(rowsWritten);
                run.setRowsFiltered(rowsFiltered);
                run.setProgress(status == RunStatus.SUCCESS ? 100 : completed * 100 / nodes.size());
                run.setErrorMessage(status == RunStatus.FAILED ? errorMessage
                        : (status == RunStatus.CANCELED ? "运行被手动取消" : null));
                runRepository.save(run);
                logBuffer.add(timedLog(run.getId(), null, end,
                        status == RunStatus.SUCCESS ? LogLevel.INFO : LogLevel.WARN,
                        "管道「" + pipeline.getName() + "」运行结束,状态 " + status + ",读取 " + rowsRead
                                + " 行,写入 " + rowsWritten + " 行,过滤 " + rowsFiltered + " 行"));

                // 攒批落库
                if (nodeRunBuffer.size() >= BATCH_FLUSH_SIZE) {
                    nodeRunRepository.saveAll(nodeRunBuffer);
                    nodeRunBuffer.clear();
                }
                if (logBuffer.size() >= BATCH_FLUSH_SIZE) {
                    runLogRepository.saveAll(logBuffer);
                    logBuffer.clear();
                }
            }
            log.info("  管道「{}」历史运行生成完成: {} 条", pipeline.getName(), runCounts[pi]);
        }
        // 清空残余缓冲
        if (!nodeRunBuffer.isEmpty()) {
            nodeRunRepository.saveAll(nodeRunBuffer);
        }
        if (!logBuffer.isEmpty()) {
            runLogRepository.saveAll(logBuffer);
        }
        log.info("历史运行生成完成: 共 {} 条运行记录", runIds.size());
        return runIds;
    }

    private RunLog timedLog(Long runId, Long nodeRunId, LocalDateTime timestamp, LogLevel level, String message) {
        RunLog l = new RunLog(runId, nodeRunId, level, message);
        l.setTimestamp(timestamp);
        return l;
    }

    /** 估算节点输入行数:首个节点与抽取节点为 0,其余继承上游量级 */
    private long estimateRowsIn(PipelineNode node, List<PipelineNode> nodes, int index,
                                Map<String, Long> tableRows) {
        if (index == 0 || node.getAgentType() == AgentType.EXTRACTOR) {
            return 0;
        }
        for (int i = index - 1; i >= 0; i--) {
            if (nodes.get(i).getAgentType() == AgentType.EXTRACTOR) {
                return tableRows.getOrDefault(extractTable(nodes.get(i)), 10000L);
            }
        }
        return 0;
    }

    /** 从节点配置 JSON 里截取 sourceTable(种子数据结构固定) */
    private String extractTable(PipelineNode node) {
        String config = node.getConfig();
        if (config == null) {
            return "";
        }
        int idx = config.indexOf("\"sourceTable\":\"");
        if (idx < 0) {
            return "";
        }
        int start = idx + "\"sourceTable\":\"".length();
        return config.substring(start, config.indexOf('"', start));
    }

    /** 从节点配置 JSON 里截取 filterRatio */
    private double extractFilterRatio(PipelineNode node) {
        String config = node.getConfig();
        if (config == null) {
            return 0.05;
        }
        int idx = config.indexOf("\"filterRatio\":");
        if (idx < 0) {
            return 0.05;
        }
        int start = idx + "\"filterRatio\":".length();
        int end = start;
        while (end < config.length() && (Character.isDigit(config.charAt(end)) || config.charAt(end) == '.')) {
            end++;
        }
        try {
            return Double.parseDouble(config.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.05;
        }
    }

    // ==================== 质量检查结果 ====================

    private void seedQualityResults(List<QualityRule> rules, List<Long> runIds, Random random) {
        List<QualityResult> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 220; i++) {
            QualityRule rule = rules.get(random.nextInt(rules.size()));
            QualityResult r = new QualityResult();
            r.setRuleId(rule.getId());
            r.setRuleName(rule.getName());
            r.setCheckType(rule.getCheckType());
            r.setRunId(runIds.get(random.nextInt(runIds.size())));
            r.setNodeName("质量检查");
            boolean passed = random.nextInt(100) < 85;
            r.setPassed(passed);
            switch (rule.getCheckType()) {
                case NULL_CHECK -> {
                    r.setExpected(rule.getField() + " NULL 数 = 0");
                    r.setActual(passed ? "NULL 数 = 0" : "NULL 数 = " + (1 + random.nextInt(20)));
                }
                case UNIQUE_CHECK -> {
                    r.setExpected(rule.getField() + " 重复数 = 0");
                    r.setActual(passed ? "重复数 = 0" : "重复数 = " + (1 + random.nextInt(5)));
                }
                case RANGE_CHECK -> {
                    r.setExpected(rule.getField() + " 在 [" + rule.getExpression().replace(",", ", ") + "] 内");
                    r.setActual(passed ? "越界数 = 0" : "越界数 = " + (1 + random.nextInt(50)));
                }
                case REGEX_CHECK -> {
                    r.setExpected(rule.getField() + " 匹配 /" + rule.getExpression() + "/");
                    r.setActual(passed ? "不匹配数 = 0" : "不匹配数 = " + (1 + random.nextInt(30)));
                }
                case ENUM_CHECK -> {
                    r.setExpected(rule.getField() + " ∈ {" + rule.getExpression() + "}");
                    r.setActual(passed ? "非法值数 = 0" : "非法值数 = " + (1 + random.nextInt(10)));
                }
                case ROW_COUNT -> {
                    r.setExpected("行数 " + rule.getExpression());
                    r.setActual("行数 = 10000");
                }
            }
            r.setDetail(passed ? "巡检通过" : "巡检发现异常数据,请关注");
            r.setCheckedAt(now.minusDays(random.nextInt(historyDays)).minusHours(random.nextInt(24)));
            results.add(r);
        }
        qualityResultRepository.saveAll(results);
    }

    // ==================== 告警 ====================

    private void seedAlerts(List<Long> runIds, Random random) {
        String[][] templates = {
                {"ERROR", "管道运行失败: 订单交易同步管道", "节点「抽取订单表」连接源库超时,重试 2 次后仍失败", "执行引擎"},
                {"WARN", "管道部分成功: 全域数据整合管道", "订单抽取分支失败,用户分支已完成", "执行引擎"},
                {"CRITICAL", "质量检查未通过: 订单号唯一性检查", "ods_orders.order_no 发现重复值 3 条", "质量检查"},
                {"WARN", "质量检查未通过: 手机号格式检查", "ods_users.phone 存在 12 条格式异常", "质量检查"},
                {"INFO", "调度任务触发成功", "用户数据日更管道已按计划触发", "调度器"},
                {"WARN", "Agent 心跳超时", "loader-02 超过 45 秒未上报心跳,已标记离线", "心跳监控"},
                {"ERROR", "管道运行失败: 事件日志采集管道", "目标表写入磁盘空间不足", "执行引擎"},
                {"INFO", "管道手动触发", "用户行为宽表管道被手动触发", "执行引擎"},
        };
        List<Alert> alerts = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 30; i++) {
            String[] tpl = templates[i % templates.length];
            Alert a = new Alert();
            a.setLevel(Severity.valueOf(tpl[0]));
            a.setTitle(tpl[1]);
            a.setContent(tpl[2]);
            a.setSource(tpl[3]);
            a.setRunId("执行引擎".equals(tpl[3]) ? runIds.get(random.nextInt(runIds.size())) : null);
            a.setRead(random.nextInt(100) < 40);
            a.setCreatedAt(now.minusDays(random.nextInt(historyDays)).minusHours(random.nextInt(24)));
            alerts.add(a);
        }
        alertRepository.saveAll(alerts);
    }

    // ==================== 调度任务 ====================

    private void seedSchedules(List<Pipeline> pipelines, Random random) {
        LocalDateTime now = LocalDateTime.now();
        // cron / enabled / 描述 / 管道序号
        String[][] specs = {
                {"0 0 2 * * *", "true", "每日凌晨 2 点执行用户日更", "0"},
                {"0 */30 * * * *", "true", "每 30 分钟同步订单", "1"},
                {"0 0 */4 * * *", "true", "每 4 小时采集事件日志", "2"},
                {"0 0 3 * * 1", "true", "每周一凌晨 3 点全域整合", "3"},
                {"0 0 6 * * *", "false", "每日 6 点质量巡检(暂停中)", "4"},
        };
        List<ScheduleJob> jobs = new ArrayList<>();
        for (String[] spec : specs) {
            ScheduleJob job = new ScheduleJob();
            Pipeline pipeline = pipelines.get(Integer.parseInt(spec[3]));
            job.setPipelineId(pipeline.getId());
            job.setPipelineName(pipeline.getName());
            job.setCronExpression(spec[0]);
            job.setEnabled(Boolean.parseBoolean(spec[1]));
            job.setDescription(spec[2]);
            job.setLastFireAt(now.minusHours(1 + random.nextInt(24)));
            job.setNextFireAt(cronParser.nextFireTime(spec[0], now));
            jobs.add(job);
        }
        scheduleJobRepository.saveAll(jobs);
    }

    // ==================== Agent 指标回填 ====================

    private void backfillAgentMetrics(List<Agent> agents, Random random) {
        for (Agent a : agents) {
            long total = 300 + random.nextInt(1500);
            long fail = (long) (total * (0.01 + random.nextDouble() * 0.04));
            a.setTotalExecuted(total);
            a.setSuccessCount(total - fail);
            a.setFailCount(fail);
            a.setAvgDurationMs(500L + random.nextInt(4500));
            a.setCurrentTasks(0);
        }
    }
}
