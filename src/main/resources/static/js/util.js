/* ============================================================
   DataForge 前端工具库:枚举映射 / 格式化 / Toast / 弹窗 / 确认框
   ============================================================ */

/* ---------- 枚举中文映射(与契约 §3 对齐) ---------- */
const ENUM_LABEL = {
  agentType: { EXTRACTOR: '抽取', TRANSFORMER: '转换', LOADER: '加载', QUALITY_CHECKER: '质量检查', ORCHESTRATOR: '编排' },
  agentStatus: { ONLINE: '在线', OFFLINE: '离线', MAINTENANCE: '维护中' },
  datasourceType: { MYSQL: 'MySQL', H2: 'H2', CSV: 'CSV', REST_API: 'REST API', KAFKA: 'Kafka', HDFS_SIM: 'HDFS 模拟' },
  datasourceStatus: { ACTIVE: '启用', DISABLED: '禁用' },
  pipelineStatus: { DRAFT: '草稿', ENABLED: '已启用', DISABLED: '已禁用' },
  runStatus: { PENDING: '等待中', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', CANCELED: '已取消', PARTIAL_SUCCESS: '部分成功', SKIPPED: '已跳过' },
  triggerType: { MANUAL: '手动触发', SCHEDULED: '定时调度', API: 'API 调用', RETRY: '失败重试' },
  checkType: { NULL_CHECK: '非空检查', RANGE_CHECK: '范围检查', UNIQUE_CHECK: '唯一性检查', REGEX_CHECK: '正则检查', ENUM_CHECK: '枚举检查', ROW_COUNT: '行数检查' },
  severity: { INFO: '提示', WARN: '警告', ERROR: '错误', CRITICAL: '严重' },
  failureStrategy: { FAIL_FAST: '快速失败', CONTINUE: '继续执行' }
};

/* 枚举 -> 徽标颜色 */
const ENUM_COLOR = {
  agentStatus: { ONLINE: 'green', OFFLINE: 'gray', MAINTENANCE: 'orange' },
  datasourceStatus: { ACTIVE: 'green', DISABLED: 'gray' },
  pipelineStatus: { DRAFT: 'gray', ENABLED: 'green', DISABLED: 'orange' },
  runStatus: { PENDING: 'gray', RUNNING: 'blue', SUCCESS: 'green', FAILED: 'red', CANCELED: 'orange', PARTIAL_SUCCESS: 'orange', SKIPPED: 'gray' },
  severity: { INFO: 'blue', WARN: 'orange', ERROR: 'red', CRITICAL: 'red' },
  logLevel: { DEBUG: 'gray', INFO: 'cyan', WARN: 'orange', ERROR: 'red' },
  agentType: { EXTRACTOR: 'blue', TRANSFORMER: 'purple', LOADER: 'green', QUALITY_CHECKER: 'orange', ORCHESTRATOR: 'cyan' },
  datasourceType: { MYSQL: 'blue', H2: 'cyan', CSV: 'green', REST_API: 'purple', KAFKA: 'orange', HDFS_SIM: 'gray' },
  triggerType: { MANUAL: 'blue', SCHEDULED: 'cyan', API: 'purple', RETRY: 'orange' }
};

/* AgentType -> DAG 节点颜色 */
const AGENT_TYPE_COLOR = {
  EXTRACTOR: '#4f8cff', TRANSFORMER: '#9a6bff', LOADER: '#2fd08c',
  QUALITY_CHECKER: '#f5a623', ORCHESTRATOR: '#21c8d7'
};

/* 运行终态 */
const FINAL_RUN_STATUS = ['SUCCESS', 'FAILED', 'CANCELED', 'PARTIAL_SUCCESS'];

function enumLabel(kind, value) {
  if (value === null || value === undefined || value === '') return '—';
  const m = ENUM_LABEL[kind];
  return (m && m[value]) || value;
}
function enumColor(kind, value) {
  const m = ENUM_COLOR[kind];
  return (m && m[value]) || 'gray';
}

/* ---------- HTML 工具 ---------- */
function h(s) {
  if (s === null || s === undefined) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function badge(kind, value, withDot) {
  if (value === null || value === undefined || value === '') return '<span class="text-3">—</span>';
  const color = enumColor(kind, value);
  const label = enumLabel(kind, value);
  const dot = withDot === false ? '' : `<span class="dot${value === 'RUNNING' || value === 'PENDING' ? ' pulse' : ''}"></span>`;
  return `<span class="badge badge-${color}">${dot}${h(label)}</span>`;
}

function agentTypeTag(type) {
  const c = AGENT_TYPE_COLOR[type] || '#7a8499';
  return `<span class="badge" style="background:${c}22;color:${c};border-color:${c}55">${h(enumLabel('agentType', type))}</span>`;
}

/* ---------- 格式化 ---------- */
function fmtTime(s) {
  if (!s) return '—';
  // 契约:yyyy-MM-dd HH:mm:ss(本地时间),转 Date 后本地化显示
  const d = new Date(String(s).replace(' ', 'T'));
  if (isNaN(d.getTime())) return h(String(s));
  return d.toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function fmtNum(n) {
  if (n === null || n === undefined || n === '') return '—';
  if (typeof n !== 'number') n = Number(n);
  if (isNaN(n)) return '—';
  return n.toLocaleString('zh-CN');
}

function fmtDuration(ms) {
  if (ms === null || ms === undefined || isNaN(Number(ms))) return '—';
  ms = Number(ms);
  if (ms < 1000) return ms + ' ms';
  const s = ms / 1000;
  if (s < 60) return s.toFixed(1) + ' s';
  const m = Math.floor(s / 60);
  const rs = Math.round(s % 60);
  if (m < 60) return `${m} 分 ${rs} 秒`;
  const hr = Math.floor(m / 60);
  return `${hr} 时 ${m % 60} 分`;
}

function fmtPercent(v, digits) {
  if (v === null || v === undefined || isNaN(Number(v))) return '—';
  let p = Number(v);
  if (p <= 1) p = p * 100; // 兼容 0-1 小数与 0-100 百分数
  return p.toFixed(digits === undefined ? 1 : digits) + '%';
}

function timeAgo(s) {
  if (!s) return '—';
  const d = new Date(String(s).replace(' ', 'T'));
  if (isNaN(d.getTime())) return '—';
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff < 60) return '刚刚';
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
  return Math.floor(diff / 86400) + ' 天前';
}

/* ---------- 状态占位 ---------- */
function stateHTML(type, msg) {
  if (type === 'loading') return `<div class="state-box"><div class="spinner"></div><div>加载中…</div></div>`;
  if (type === 'empty') return `<div class="state-box"><div class="icon">📭</div><div>${h(msg || '暂无数据')}</div></div>`;
  return `<div class="state-box"><div class="icon">⚠️</div><div>${h(msg || '加载失败')}</div><button class="btn btn-sm" onclick="Router.reload()">重试</button></div>`;
}

/* ---------- Toast ---------- */
function toast(msg, type, duration) {
  const root = document.getElementById('toast-root');
  const el = document.createElement('div');
  el.className = 'toast ' + (type || 'info');
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => {
    el.style.transition = 'opacity .3s, transform .3s';
    el.style.opacity = '0';
    el.style.transform = 'translateX(30px)';
    setTimeout(() => el.remove(), 320);
  }, duration || 2800);
}

/* ---------- 弹窗 ---------- */
function openModal(opts) {
  const root = document.getElementById('modal-root');
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal ${opts.size === 'lg' ? 'modal-lg' : ''}" role="dialog">
      <div class="modal-header">
        <span>${h(opts.title || '')}</span>
        <span class="modal-close" data-close>&times;</span>
      </div>
      <div class="modal-body"></div>
      ${opts.footer === null ? '' : '<div class="modal-footer"></div>'}
    </div>`;
  const body = overlay.querySelector('.modal-body');
  if (typeof opts.body === 'string') body.innerHTML = opts.body;
  else if (opts.body instanceof HTMLElement) body.appendChild(opts.body);
  const footer = overlay.querySelector('.modal-footer');
  if (footer && opts.footer) {
    if (typeof opts.footer === 'string') footer.innerHTML = opts.footer;
    else if (opts.footer instanceof HTMLElement) footer.appendChild(opts.footer);
  }
  const close = () => { overlay.remove(); document.removeEventListener('keydown', onKey); };
  overlay.querySelector('[data-close]').addEventListener('click', close);
  overlay.addEventListener('mousedown', (e) => { if (e.target === overlay && opts.maskClose !== false) close(); });
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  root.appendChild(overlay);
  if (opts.onOpen) opts.onOpen(overlay, close);
  return { el: overlay, close };
}

/* ---------- 确认框 ---------- */
function confirmBox(message, opts) {
  opts = opts || {};
  return new Promise((resolve) => {
    let resolved = false;
    const done = (v) => { if (!resolved) { resolved = true; resolve(v); } };
    openModal({
      title: opts.title || '确认操作',
      body: `<div style="font-size:13.5px;line-height:1.7;color:var(--text)">${h(message)}</div>`,
      footer: `<button class="btn" data-act="cancel">取消</button>
               <button class="btn ${opts.danger ? 'btn-danger' : 'btn-primary'}" data-act="ok">${h(opts.okText || '确定')}</button>`,
      onOpen(overlay, closeFn) {
        overlay.querySelector('[data-act="cancel"]').onclick = () => { done(false); closeFn(); };
        overlay.querySelector('[data-act="ok"]').onclick = () => { done(true); closeFn(); };
        // Esc / 点击遮罩关闭视为取消:包装 overlay.remove
        const origRemove = overlay.remove.bind(overlay);
        overlay.remove = () => { origRemove(); done(false); };
      }
    });
  });
}

/* ---------- 表单工具 ---------- */
// 从弹窗内收集表单值并做必填校验;fields: [{name, label, required, type}]
function collectForm(overlay, fields) {
  const values = {};
  let ok = true;
  for (const f of fields) {
    const el = overlay.querySelector(`[name="${f.name}"]`);
    if (!el) continue;
    let v = el.type === 'checkbox' ? el.checked : el.value.trim();
    el.classList.remove('invalid');
    const errEl = overlay.querySelector(`[data-err="${f.name}"]`);
    if (errEl) errEl.textContent = '';
    if (f.required && (v === '' || v === null || v === undefined)) {
      el.classList.add('invalid');
      if (errEl) errEl.textContent = `请填写${f.label}`;
      ok = false;
      continue;
    }
    if (f.type === 'number' && v !== '') {
      v = Number(v);
      if (isNaN(v)) { el.classList.add('invalid'); if (errEl) errEl.textContent = `${f.label}必须为数字`; ok = false; continue; }
    }
    if (f.type === 'json' && v !== '') {
      try { JSON.parse(v); } catch (e) { el.classList.add('invalid'); if (errEl) errEl.textContent = 'JSON 格式错误:' + e.message; ok = false; continue; }
    }
    values[f.name] = v;
  }
  return ok ? values : null;
}

function formRow(name, label, inner, required) {
  return `<div class="form-row">
    <label class="form-label">${h(label)}${required ? '<span class="req">*</span>' : ''}</label>
    ${inner}
    <div class="form-err" data-err="${name}"></div>
  </div>`;
}

function optionsHTML(list, selected, labelKind) {
  return list.map(v => {
    const val = typeof v === 'object' ? v.value : v;
    const lab = typeof v === 'object' ? v.label : (labelKind ? enumLabel(labelKind, v) : v);
    return `<option value="${h(val)}"${String(val) === String(selected) ? ' selected' : ''}>${h(lab)}</option>`;
  }).join('');
}

/* ---------- 分页 ---------- */
function pagerHTML(page, totalPages, totalElements) {
  if (!totalPages || totalPages <= 0) return '';
  const pages = [];
  const start = Math.max(0, Math.min(page - 2, totalPages - 5));
  const end = Math.min(totalPages, start + 5);
  for (let i = start; i < end; i++) {
    pages.push(`<button class="btn btn-sm ${i === page ? 'btn-primary' : ''}" data-page="${i}">${i + 1}</button>`);
  }
  return `<div class="pager">
    <span>共 ${fmtNum(totalElements)} 条</span>
    <button class="btn btn-sm" data-page="${page - 1}" ${page <= 0 ? 'disabled' : ''}>上一页</button>
    ${pages.join('')}
    <button class="btn btn-sm" data-page="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>下一页</button>
  </div>`;
}

function bindPager(container, currentPage, onGo) {
  container.querySelectorAll('.pager [data-page]').forEach(btn => {
    btn.addEventListener('click', () => {
      const p = Number(btn.dataset.page);
      if (!isNaN(p) && p >= 0 && p !== currentPage) onGo(p);
    });
  });
}

/* ---------- 通用数据加载渲染(加载/空/错误三态) ---------- */
async function loadInto(el, fetcher, renderer, emptyMsg) {
  el.innerHTML = stateHTML('loading');
  try {
    const data = await fetcher();
    const isEmpty = !data || (Array.isArray(data) && data.length === 0) ||
      (data.content && data.content.length === 0);
    if (isEmpty) { el.innerHTML = stateHTML('empty', emptyMsg); return null; }
    renderer(data);
    return data;
  } catch (e) {
    el.innerHTML = stateHTML('error', e.message || '加载失败');
    return null;
  }
}
