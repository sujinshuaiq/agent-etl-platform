# AGENT_SPEC — DataForge 多 Agent ETL / 数据管道编排平台

> 本文件是并行开发的**共享契约**。所有实现者必须严格遵守其中的 API 形状、枚举值、
> 包结构与文件边界。任何人不得修改 `pom.xml`、`application.yml`、`DataForgeApplication.java`。

## 1. 项目概况

- 名称:**DataForge** 多 Agent ETL / 数据管道编排平台
- 技术栈:Java 17 + Spring Boot 3.3.5 + Spring Data JPA + H2(文件库,MySQL 模式)+ 静态前端(原生 JS SPA + ECharts,已下载至 `src/main/resources/static/vendor/echarts.min.js`)
- 根包:`com.dataforge.etl`
- 启动:`DataForgeApplication.main()`,访问 http://localhost:8080(前端)与 http://localhost:8080/swagger-ui.html
- 中文界面、中文注释、中文日志消息

## 2. 统一 API 约定

- 所有 `/api/**` 返回统一包装:`{"code":0,"message":"ok","data":...}`;业务错误 `code!=0`,HTTP 状态尽量保持 200/400/404 语义
- 分页请求参数:`page`(0 起)、`size`;分页数据:`{"content":[...],"totalElements":n,"totalPages":n,"page":p,"size":s}`
- 实体 ID 均为自增 Long;时间字段 Jackson 已配置 `yyyy-MM-dd HH:mm:ss`
- 统一响应类:`com.dataforge.etl.common.api.ApiResponse`(静态 `ok(data)` / `error(msg)`)
- 统一异常:`com.dataforge.etl.common.exception.BizException` + `GlobalExceptionHandler`
- 分页工具:`com.dataforge.etl.common.api.PageResult<T>`(可由 Spring `Page<T>` 转换)

## 3. 枚举契约(前后端一致,字符串大写)

- AgentType:`EXTRACTOR`(抽取) `TRANSFORMER`(转换) `LOADER`(加载) `QUALITY_CHECKER`(质量检查) `ORCHESTRATOR`(编排)
- AgentStatus:`ONLINE` `OFFLINE` `MAINTENANCE`
- DataSourceType:`MYSQL` `H2` `CSV` `REST_API` `KAFKA` `HDFS_SIM`
- DataSourceStatus:`ACTIVE` `DISABLED`
- PipelineStatus:`DRAFT` `ENABLED` `DISABLED`
- FailureStrategy:`FAIL_FAST` `CONTINUE`
- RunStatus:`PENDING` `RUNNING` `SUCCESS` `FAILED` `CANCELED` `PARTIAL_SUCCESS`
- NodeRunStatus:同 RunStatus(外加 `SKIPPED`)
- TriggerType:`MANUAL` `SCHEDULED` `API` `RETRY`
- CheckType:`NULL_CHECK` `RANGE_CHECK` `UNIQUE_CHECK` `REGEX_CHECK` `ENUM_CHECK` `ROW_COUNT`
- Severity / AlertLevel:`INFO` `WARN` `ERROR` `CRITICAL`
- LogLevel:`DEBUG` `INFO` `WARN` `ERROR`

## 4. REST API 契约(Base: `/api`)

### 4.1 Dashboard
- `GET /api/dashboard/overview` → `{agentTotal,agentOnline,datasourceTotal,pipelineTotal,pipelineEnabled,runTotal,runRunning,runSuccessToday,runFailedToday,successRate7d,avgDurationMs7d,rowsProcessedToday,alertUnread}`
- `GET /api/dashboard/run-trend?days=14` → `[{date:"yyyy-MM-dd",success,failed,total,rowsProcessed}]`
- `GET /api/dashboard/status-distribution` → `{"SUCCESS":n,"FAILED":n,"RUNNING":n,...}`
- `GET /api/dashboard/agent-load` → `[{agentId,agentName,type,runningTasks,totalExecuted,successRate,avgDurationMs}]`
- `GET /api/dashboard/pipeline-rank?limit=5` → `[{pipelineId,pipelineName,runCount,successRate,avgRows}]`
- `GET /api/dashboard/recent-runs?limit=10` → 运行摘要数组(结构同 4.4 列表项)
- `GET /api/dashboard/quality-summary` → `{totalChecks,passed,failed,passRate}`

### 4.2 Agents
- `GET /api/agents` → 全部 Agent 数组(含运行指标)
- `GET /api/agents/{id}` → 详情
- `POST /api/agents` / `PUT /api/agents/{id}` / `DELETE /api/agents/{id}`
- `POST /api/agents/{id}/start` `/stop` `/restart` → 状态流转
- Agent JSON:`{id,name,code,type,status,host,port,version,description,capabilities:[],maxConcurrency,currentTasks,lastHeartbeatAt,totalExecuted,successCount,failCount,avgDurationMs,createdAt,updatedAt}`

### 4.3 DataSources
- CRUD:`GET /api/datasources`、`GET/POST/PUT/DELETE /{id}`
- `POST /api/datasources/{id}/test` → `{success,latencyMs,message}`(模拟测试,更新 lastTestAt/lastTestSuccess)
- `GET /api/datasources/{id}/tables` → `[{name,rows,sizeDesc}]`(H2 类型返回真实表,其余返回模拟表)
- DataSource JSON:`{id,name,code,type,status,connectionConfig,description,lastTestAt,lastTestSuccess,createdAt,updatedAt}`

### 4.4 Pipelines
- `GET /api/pipelines` → `[{id,name,code,description,category,status,nodeCount,lastRunStatus,lastRunAt,createdAt}]`
- `GET /api/pipelines/{id}` → 管道详情,含 `nodes[]`、`edges[]`
- `POST /api/pipelines` / `PUT /api/pipelines/{id}`:提交整棵 DAG(节点+边整体替换)
- `DELETE /api/pipelines/{id}`
- `POST /api/pipelines/{id}/enable` `/disable`
- `POST /api/pipelines/{id}/validate` → `{valid,errors:[],order:["nodeKey1",...]}`(拓扑排序+环检测)
- `POST /api/pipelines/{id}/trigger` → `{runId}`(异步启动)
- Pipeline JSON:`{id,name,code,description,category,status,failureStrategy,retryCount,retryIntervalSeconds,timeoutMinutes,nodes:[{id,nodeKey,name,agentType,datasourceId,config,positionX,positionY}],edges:[{id,fromNodeKey,toNodeKey}],createdAt,updatedAt}`
- 节点 `config` 为 JSON 字符串,按 agentType 约定:
  - EXTRACTOR:`{"sourceTable":"ods_users","batchSize":1000,"whereClause":"..."}`
  - TRANSFORMER:`{"rules":["trim","upper:email","mask:phone","dedup"],"filterRatio":0.05}`
  - LOADER:`{"targetTable":"dwd_users","writeMode":"INSERT"}`
  - QUALITY_CHECKER:`{"ruleIds":[1,2]}`
  - ORCHESTRATOR:`{"note":"..."}`

### 4.5 Runs
- `GET /api/runs?status=&pipelineId=&page=0&size=20` → 分页 `[{id,pipelineId,pipelineName,status,triggerType,startTime,endTime,durationMs,rowsRead,rowsWritten,rowsFiltered,progress,errorMessage}]`
- `GET /api/runs/{id}` → 详情 + `nodeRuns:[{id,nodeKey,nodeName,agentType,agentName,status,startTime,endTime,durationMs,rowsIn,rowsOut,retryAttempt,errorMessage}]`
- `GET /api/runs/{id}/logs?nodeRunId=` → `[{id,nodeRunId,timestamp,level,message}]`(按时间升序)
- `POST /api/runs/{id}/cancel`、`POST /api/runs/{id}/rerun` → `{runId}`
- `GET /api/runs/stream` → **SSE**(text/event-stream)。事件 JSON:`{type:"RUN_UPDATE"|"NODE_UPDATE"|"LOG",payload:{...}}`。运行中每 500ms 左右推送;同时允许前端轮询兜底

### 4.6 Quality
- `GET /api/quality/rules`、`POST`、`PUT /{id}`、`DELETE /{id}`
- Rule JSON:`{id,name,targetTable,checkType,field,expression,severity,enabled,description,createdAt}`
- `GET /api/quality/results?ruleId=&page=0&size=20` → 分页 `[{id,ruleId,ruleName,runId,nodeName,checkType,expected,actual,passed,checkedAt,detail}]`

### 4.7 Alerts
- `GET /api/alerts?read=&page=0&size=20` → 分页 `[{id,level,title,content,source,runId,read,createdAt}]`
- `POST /api/alerts/{id}/read`、`POST /api/alerts/read-all`、`DELETE /api/alerts/{id}`

### 4.8 Schedules
- CRUD + `POST /api/schedules/{id}/enable` `/disable`
- Schedule JSON:`{id,pipelineId,pipelineName,cronExpression,enabled,description,lastFireAt,nextFireAt,createdAt}`
- 调度执行器:基于 Spring `@Scheduled` 每秒扫描 + 简化 cron 解析(支持 `秒 分 时 日 月 周` 六位,可用 `*`、`*/n`、数字、逗号;`?` 视同 `*`),fire 后更新 lastFireAt/nextFireAt

### 4.9 Meta
- `GET /api/meta/enums` → `{agentTypes:[],agentStatuses:[],datasourceTypes:[],pipelineStatuses:[],runStatuses:[],checkTypes:[],severities:[],failureStrategies:[],triggerTypes:[]}`

## 5. 后端包结构(后端实现者)

```
com.dataforge.etl
├── common/        # api(ApiResponse,PageResult) exception(BizException,GlobalExceptionHandler) util
├── config/        # EngineThreadPoolConfig, WebConfig(静态资源/CORS), OpenApiConfig
├── agent/         # entity/Agent.java, repository, service(心跳/指标), controller
├── datasource/    # entity/DataSource.java, repository, service(模拟测试/表清单), controller
├── pipeline/      # entity/Pipeline.java, PipelineNode.java, PipelineEdge.java, repository, DagService(拓扑/环检测), PipelineService, controller
├── engine/        # PipelineEngine(编排执行), NodeExecutor 策略(每种 AgentType 一个), RunEventPublisher(SSE), RetryPolicy
├── run/           # entity/PipelineRun.java, NodeRun.java, RunLog.java, repository, RunService, controller
├── quality/       # entity/QualityRule.java, QualityResult.java, repository, QualityService(真实检查 H2 源表), controller
├── alert/         # entity/Alert.java, repository, AlertService, controller
├── schedule/      # entity/ScheduleJob.java, repository, CronParser, ScheduleExecutor, controller
├── dashboard/     # DashboardService, DashboardController
├── meta/          # MetaController
└── seed/          # MockDataGenerator(业务源表 ods_users 1万 / ods_orders 5万 / ods_events 10万,用 JdbcTemplate 批量插入 H2) + DataInitializer(仅库空时:10 agents/8 datasources/6 pipelines(含完整 DAG)/30天历史 runs≥800 条含 nodeRuns 与日志/8 quality rules + results/30 alerts/5 schedules)
```

执行引擎要求:
- `PipelineEngine.trigger(pipelineId, triggerType)` 异步执行,返回 runId
- 按拓扑层并行执行节点(`ThreadPoolTaskExecutor`,配置见 application.yml `dataforge.engine`)
- 节点执行:模拟耗时(application.yml `dataforge.simulation` 区间随机)+ 真实行数流转(上一节点 rowsOut 作为下一节点 rowsIn;EXTRACTOR 真实 count H2 源表;TRANSFORMER 按 filterRatio 过滤;LOADER 真实写入 `dwd_*` 目标表或模拟)
- 失败处理:FAIL_FAST 中止后续,CONTINUE 跳过下游;按 `retryCount`/`retryIntervalSeconds` 重试
- 全程写 RunLog(节点开始/结束/行数/错误),发布 SSE 事件,失败产生 Alert
- 心跳:`HeartbeatService` 定时把 ONLINE agent 的 lastHeartbeatAt 刷新,超过阈值标记 OFFLINE(MAINTENANCE 除外);运行中 agent 的 currentTasks 动态变化
- 取消:Cancel 标志位,节点循环检查

测试:`src/test/java` 至少包含 DagServiceTest(拓扑排序、环检测)与 CronParserTest,可 `@SpringBootTest` 亦可纯单测

## 6. 前端契约(前端实现者)

- 目录:`src/main/resources/static/`。**只能**写该目录
- 单页应用,hash 路由;入口 `index.html`,引 `vendor/echarts.min.js`(已存在,本地引用,禁止 CDN)
- 文件建议:`index.html`、`css/app.css`、`js/api.js`(fetch 封装,处理统一响应)、`js/router.js`、`js/util.js`、`js/charts.js`、`js/pages/*.js`(dashboard/agents/datasources/pipelines/pipeline-detail/runs/run-detail/quality/alerts/schedules)
- 页面:
  1. `#/dashboard` 总览:统计卡片、14 天运行趋势折线、状态分布饼图、Agent 负载柱图、管道排行、最近运行表格、质量通过率仪表
  2. `#/agents`:卡片/表格、状态徽标、新增/编辑弹窗、启动/停止/重启按钮、能力标签、心跳时间
  3. `#/datasources`:表格、类型图标/徽标、测试连接按钮(显示延迟)、查看表清单弹窗、CRUD
  4. `#/pipelines`:卡片列表(状态、节点数、最近运行),进入详情
  5. `#/pipelines/{id}`:**DAG 可视化编辑器**——Canvas/SVG 绘制节点与连线,节点可拖拽(保存 positionX/Y),点击节点编辑配置表单,支持添加/删除节点与边,拓扑校验按钮,触发运行按钮,启用/禁用开关
  6. `#/runs`:筛选(状态/管道)+ 分页表格 + 取消/重跑
  7. `#/runs/{id}`:节点运行时间线、行数统计、实时日志终端(优先 SSE `/api/runs/stream`,降级 2s 轮询),日志级别着色
  8. `#/quality`:规则 CRUD + 检查结果表格(通过率徽标)
  9. `#/alerts`:级别筛选、已读/未读、全部已读
  10. `#/schedules`:CRUD、cron 表达式、上次/下次触发时间、启用开关
- 左侧深色侧边栏 + 顶部栏布局;整体深色科技风(背景 #0d1117 系,主色 #4f8cff / 青色系),中文界面,表格/卡片/徽标/弹窗/Toast 组件自研(不引其它库)
- 每 15s 自动刷新 dashboard 与 runs 列表;时间显示本地化

## 7. 文档契约(文档实现者)

- 只能写 `README.md` 与 `docs/` 目录
- `README.md`:项目简介、功能清单、技术栈、架构图(mermaid)、**IDEA 打开与运行步骤**(JDK17、直接运行 main)、访问地址、默认数据说明、目录结构、API 一览、FAQ
- `docs/ARCHITECTURE.md`:详细架构设计——分层图、执行引擎时序(mermaid sequence)、线程模型、DAG 调度算法、重试/容错、心跳机制、SSE 推送、数据质量框架、扩展点(如何新增 AgentType)
- `docs/API.md`:按契约 §4 展开每个端点的请求/响应示例
- `docs/INTERVIEW.md`:**面试要点文档**(重点),包含:
  1. Agent 基础:定义、感知-决策-行动循环、ReAct、Plan-and-Execute、Tool Use/Function Calling
  2. Multi-Agent:编排模式(orchestrator-worker、pipeline、blackboard)、通信(A2A/MCP 简述)、任务分解、一致性与幂等
  3. 本项目映射:Agent 类型如何对应 ETL 角色、DAG 与 Agent 编排的关系、心跳与注册、背压与线程池
  4. 八股高频:LLM Agent 记忆(短期/长期/向量)、RAG、上下文工程、评估与可观测性
  5. Java/后端高频:线程池参数与拒绝策略、CompletableFuture、Spring 事件机制、@Transactional、JPA N+1、cron 解析、SSE vs WebSocket
  6. 系统设计:ETL 幂等、断点续传、限流、告警降噪、数据质量维度(完整性/准确性/一致性/及时性/唯一性/有效性)
  7. 行为面:如何用 STAR 讲这个项目(给出示例回答)
  - 每个知识点给「问题 → 简洁答案 → 追问方向」格式
- `docs/DEPLOYMENT.md`:打包(`mvn -DskipTests package`)、`java -jar`、H2 文件库备份、生产参数建议(线程池、超时)、从 H2 切 MySQL 的步骤

## 8. 文件边界(严禁越界)

| 实现者 | 可写 | 禁写 |
|---|---|---|
| 后端 | `src/main/java/**`、`src/test/java/**` | pom.xml、application.yml、static/、docs/、README.md |
| 前端 | `src/main/resources/static/**`(vendor/echarts.min.js 已存在,勿覆盖) | 其它一切 |
| 文档 | `README.md`、`docs/**` | 其它一切 |

构建验证(后端):`C:\Users\26036\Documents\kimi\workspace\.tools\apache-maven-3.9.16\bin\mvn.cmd -s C:\Users\26036\Documents\kimi\workspace\.tools\settings.xml -q compile`(JAVA_HOME 设为 `C:\Users\26036\.jdks\ms-17.0.20.1`)
