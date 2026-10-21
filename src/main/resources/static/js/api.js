/* ============================================================
   DataForge API 封装:fetch 包装,处理统一响应 {code,message,data}
   ============================================================ */

async function api(path, opts) {
  opts = opts || {};
  const method = opts.method || 'GET';
  let url = path;
  if (opts.params) {
    const qs = new URLSearchParams();
    Object.entries(opts.params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') qs.append(k, v);
    });
    const s = qs.toString();
    if (s) url += (url.includes('?') ? '&' : '?') + s;
  }
  let res;
  try {
    res = await fetch(url, {
      method,
      headers: opts.body !== undefined ? { 'Content-Type': 'application/json' } : {},
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined
    });
  } catch (e) {
    throw new Error('网络错误:无法连接后端服务(' + e.message + ')');
  }
  let json = null;
  try { json = await res.json(); } catch (e) { /* 非 JSON 响应 */ }
  if (!res.ok) {
    const msg = (json && json.message) || ('请求失败 HTTP ' + res.status);
    throw new Error(msg);
  }
  // 统一包装:{code:0,message:"ok",data:...}
  if (json && typeof json === 'object' && 'code' in json) {
    if (json.code !== 0) throw new Error(json.message || ('业务错误 code=' + json.code));
    return json.data;
  }
  return json;
}

/* 带错误 Toast 的便捷调用;失败返回 undefined */
async function apiTry(path, opts, silent) {
  try {
    return await api(path, opts);
  } catch (e) {
    if (!silent) toast(e.message || '操作失败', 'error');
    return undefined;
  }
}

/* ---------- 契约 §4 全部端点 ---------- */
const API = {
  // 4.1 Dashboard
  dashOverview: () => api('/api/dashboard/overview'),
  dashRunTrend: (days) => api('/api/dashboard/run-trend', { params: { days: days || 14 } }),
  dashStatusDist: () => api('/api/dashboard/status-distribution'),
  dashAgentLoad: () => api('/api/dashboard/agent-load'),
  dashPipelineRank: (limit) => api('/api/dashboard/pipeline-rank', { params: { limit: limit || 5 } }),
  dashRecentRuns: (limit) => api('/api/dashboard/recent-runs', { params: { limit: limit || 10 } }),
  dashQualitySummary: () => api('/api/dashboard/quality-summary'),

  // 4.2 Agents
  agents: () => api('/api/agents'),
  agent: (id) => api('/api/agents/' + id),
  createAgent: (body) => api('/api/agents', { method: 'POST', body }),
  updateAgent: (id, body) => api('/api/agents/' + id, { method: 'PUT', body }),
  deleteAgent: (id) => api('/api/agents/' + id, { method: 'DELETE' }),
  agentStart: (id) => api('/api/agents/' + id + '/start', { method: 'POST' }),
  agentStop: (id) => api('/api/agents/' + id + '/stop', { method: 'POST' }),
  agentRestart: (id) => api('/api/agents/' + id + '/restart', { method: 'POST' }),

  // 4.3 DataSources
  datasources: () => api('/api/datasources'),
  datasource: (id) => api('/api/datasources/' + id),
  createDatasource: (body) => api('/api/datasources', { method: 'POST', body }),
  updateDatasource: (id, body) => api('/api/datasources/' + id, { method: 'PUT', body }),
  deleteDatasource: (id) => api('/api/datasources/' + id, { method: 'DELETE' }),
  testDatasource: (id) => api('/api/datasources/' + id + '/test', { method: 'POST' }),
  datasourceTables: (id) => api('/api/datasources/' + id + '/tables'),

  // 4.4 Pipelines
  pipelines: () => api('/api/pipelines'),
  pipeline: (id) => api('/api/pipelines/' + id),
  createPipeline: (body) => api('/api/pipelines', { method: 'POST', body }),
  updatePipeline: (id, body) => api('/api/pipelines/' + id, { method: 'PUT', body }),
  deletePipeline: (id) => api('/api/pipelines/' + id, { method: 'DELETE' }),
  enablePipeline: (id) => api('/api/pipelines/' + id + '/enable', { method: 'POST' }),
  disablePipeline: (id) => api('/api/pipelines/' + id + '/disable', { method: 'POST' }),
  validatePipeline: (id) => api('/api/pipelines/' + id + '/validate', { method: 'POST' }),
  triggerPipeline: (id) => api('/api/pipelines/' + id + '/trigger', { method: 'POST' }),

  // 4.5 Runs
  runs: (params) => api('/api/runs', { params }),
  run: (id) => api('/api/runs/' + id),
  runLogs: (id, nodeRunId) => api('/api/runs/' + id + '/logs', { params: { nodeRunId } }),
  cancelRun: (id) => api('/api/runs/' + id + '/cancel', { method: 'POST' }),
  rerunRun: (id) => api('/api/runs/' + id + '/rerun', { method: 'POST' }),

  // 4.6 Quality
  qualityRules: () => api('/api/quality/rules'),
  createQualityRule: (body) => api('/api/quality/rules', { method: 'POST', body }),
  updateQualityRule: (id, body) => api('/api/quality/rules/' + id, { method: 'PUT', body }),
  deleteQualityRule: (id) => api('/api/quality/rules/' + id, { method: 'DELETE' }),
  qualityResults: (params) => api('/api/quality/results', { params }),

  // 4.7 Alerts
  alerts: (params) => api('/api/alerts', { params }),
  markAlertRead: (id) => api('/api/alerts/' + id + '/read', { method: 'POST' }),
  markAllAlertsRead: () => api('/api/alerts/read-all', { method: 'POST' }),
  deleteAlert: (id) => api('/api/alerts/' + id, { method: 'DELETE' }),

  // 4.8 Schedules
  schedules: () => api('/api/schedules'),
  createSchedule: (body) => api('/api/schedules', { method: 'POST', body }),
  updateSchedule: (id, body) => api('/api/schedules/' + id, { method: 'PUT', body }),
  deleteSchedule: (id) => api('/api/schedules/' + id, { method: 'DELETE' }),
  enableSchedule: (id) => api('/api/schedules/' + id + '/enable', { method: 'POST' }),
  disableSchedule: (id) => api('/api/schedules/' + id + '/disable', { method: 'POST' }),

  // 4.9 Meta
  metaEnums: () => api('/api/meta/enums'),

  // AI 助手(契约见任务书,Base /api/ai)
  aiStatus: () => api('/api/ai/status'),
  aiChat: (body) => api('/api/ai/chat', { method: 'POST', body }),
  aiHistory: (sessionId) => api('/api/ai/chat/history', { params: { sessionId } }),
  aiRetrieve: (body) => api('/api/ai/retrieve', { method: 'POST', body }),
  aiDiagnose: (runId) => api('/api/ai/diagnose/' + runId, { method: 'POST' }),
  aiGeneratePipeline: (body) => api('/api/ai/generate-pipeline', { method: 'POST', body }),
  aiIntents: () => api('/api/ai/intents'),

  // Agent 消息总线(Base /api/messages)
  messages: (params) => api('/api/messages', { params })
};
