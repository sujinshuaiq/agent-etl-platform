/* ============================================================
   页面 11:AI 助手(#/ai)
   聊天界面 + NLU 可视化(意图/槽位/置信度/治理)+ 工具调用徽标
   + RAG 引用 + 检索调试面板(混合检索分数)+ 能力面板(意图清单)
   契约:Base /api/ai,统一 {code,message,data} 包装
   ============================================================ */
window.Pages = window.Pages || {};

Pages.ai = {
  title: 'AI 助手',

  SESSION_KEY: 'df_ai_session',
  sessionId: null,
  status: null,        // /status 结果
  intents: null,       // /intents 结果
  _sending: false,

  /* 意图中文名(后端枚举未知,命中映射则显示中文,否则原样) */
  INTENT_CN: {
    STATUS_QUERY: '平台状态', OVERVIEW: '平台状态', PLATFORM_STATUS: '平台状态',
    FAILED_QUERY: '失败查询', FAILED_RUNS: '失败查询', QUERY_FAILED: '失败查询',
    DIAGNOSE: '故障诊断', DIAGNOSE_RUN: '故障诊断',
    GENERATE_PIPELINE: '生成管道', CREATE_PIPELINE: '生成管道',
    RUN_QUERY: '运行查询', QUERY_RUN: '运行查询',
    RETRIEVE: '知识检索', KNOWLEDGE_QUERY: '知识检索',
    CHITCHAT: '闲聊', GENERAL: '通用问答', UNKNOWN: '未识别', OTHER: '其他'
  },

  /* ================= 页面骨架 ================= */
  render(el) {
    this.sessionId = sessionStorage.getItem(this.SESSION_KEY) || null;
    this._sending = false;
    el.innerHTML = `
      <div class="ai-wrap">
        <div class="ai-main">
          <div class="ai-statusbar" id="ai-statusbar">
            <span class="spinner" style="width:16px;height:16px;border-width:2px"></span>
            <span class="text-3 small">正在获取模型状态…</span>
          </div>
          <div id="ai-mock-banner"></div>
          <div class="ai-msgs" id="ai-msgs"></div>
          <div class="ai-chips" id="ai-chips">
            <span class="ai-chip" data-chip="platform">📊 平台现在怎么样</span>
            <span class="ai-chip" data-chip="failed">❌ 最近有失败的运行吗</span>
            <span class="ai-chip" data-chip="diagnose">🩺 诊断失败任务</span>
            <span class="ai-chip" data-chip="generate">🛠 生成管道:每天同步订单数据到数仓</span>
          </div>
          <div class="ai-inputbar">
            <textarea id="ai-input" rows="1" placeholder="向 DataForge AI 助手提问…(Enter 发送,Shift+Enter 换行)"></textarea>
            <button class="btn btn-primary" id="ai-send" style="height:40px">发送</button>
          </div>
        </div>
        <div class="ai-side">
          <div class="ai-side-tabs">
            <div class="ai-side-tab active" data-tab="retrieve">🔍 检索调试</div>
            <div class="ai-side-tab" data-tab="intents">🧠 能力面板</div>
          </div>
          <div class="ai-side-body" id="ai-side-body"></div>
        </div>
      </div>`;

    // 事件绑定
    const input = el.querySelector('#ai-input');
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.sendCurrent(el); }
    });
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(120, input.scrollHeight) + 'px';
    });
    el.querySelector('#ai-send').addEventListener('click', () => this.sendCurrent(el));
    el.querySelectorAll('.ai-chip').forEach(chip => {
      chip.addEventListener('click', () => this.handleChip(el, chip.dataset.chip));
    });
    el.querySelectorAll('.ai-side-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        el.querySelectorAll('.ai-side-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        this.renderSideTab(el, tab.dataset.tab);
      });
    });

    // 初始化
    this.loadStatus(el);
    this.renderSideTab(el, 'retrieve');
    this.loadIntents();
    this.restoreHistory(el);
  },

  /* ================= 状态条 ================= */
  async loadStatus(el) {
    const bar = el.querySelector('#ai-statusbar');
    try {
      this.status = await API.aiStatus();
    } catch (e) {
      bar.innerHTML = `<span class="badge badge-red">AI 服务不可用</span>
        <span class="small text-3">${h(e.message)}</span>
        <span class="grow"></span>${this.statusBarActions()}`;
      this.bindStatusActions(el);
      return;
    }
    const s = this.status || {};
    bar.innerHTML = `
      <span class="badge ${s.enabled ? 'badge-green' : 'badge-gray'}">${s.enabled ? '● 在线' : '○ 未启用'}</span>
      <span class="small">模型 <b class="mono" style="color:var(--cyan)">${h(s.model || '—')}</b></span>
      ${(s.capabilities || []).map(c => `<span class="badge badge-blue" style="font-size:11px">${h(c)}</span>`).join('')}
      <span class="grow"></span>${this.statusBarActions()}`;
    const banner = el.querySelector('#ai-mock-banner');
    if (s.mockMode) {
      banner.innerHTML = `<div class="ai-mock-banner">⚠️ 未配置 API Key,当前为内置模拟模式(配置 <span class="mono">DATAFORGE_LLM_API_KEY</span> 后切换真实模型)</div>`;
    } else {
      banner.innerHTML = '';
    }
    this.bindStatusActions(el);
  },

  statusBarActions() {
    return `<button class="btn btn-sm" id="ai-new-session">✚ 新会话</button>`;
  },

  bindStatusActions(el) {
    const btn = el.querySelector('#ai-new-session');
    if (btn) btn.addEventListener('click', async () => {
      const ok = await confirmBox('开始新会话?当前会话记录将从界面清空(服务端历史仍保留)。');
      if (!ok) return;
      sessionStorage.removeItem(this.SESSION_KEY);
      this.sessionId = null;
      const msgs = el.querySelector('#ai-msgs');
      msgs.innerHTML = '';
      this.renderWelcome(el);
      toast('已开始新会话', 'success');
    });
  },

  /* ================= 会话恢复 ================= */
  async restoreHistory(el) {
    const msgs = el.querySelector('#ai-msgs');
    if (!this.sessionId) { this.renderWelcome(el); return; }
    msgs.innerHTML = stateHTML('loading');
    try {
      const history = await API.aiHistory(this.sessionId);
      msgs.innerHTML = '';
      if (!history || !history.length) { this.renderWelcome(el); return; }
      history.forEach(m => {
        if (m.role === 'user') this.appendUserBubble(el, m.content, m.createdAt);
        else this.appendAssistantBubble(el, { reply: m.content, intent: m.intent }, m.createdAt);
      });
      this.scrollBottom(el);
    } catch (e) {
      msgs.innerHTML = '';
      this.renderWelcome(el);
    }
  },

  renderWelcome(el) {
    const msgs = el.querySelector('#ai-msgs');
    this.appendAssistantBubble(el, {
      reply: '你好,我是 **DataForge AI 助手** ✨\n\n我可以帮你:\n- 查询平台运行状态与失败任务\n- 诊断失败运行的根因\n- 用一句话生成 ETL 管道\n- 检索平台知识库\n\n点击下方快捷芯片或直接输入问题开始。'
    });
    void msgs;
  },

  /* ================= 消息渲染 ================= */
  /* 轻量 markdown:先整体转义防 XSS,再做结构化替换 */
  mdLite(text) {
    let t = h(String(text === null || text === undefined ? '' : text));
    // 代码块 ```...``` 先提取为占位符,避免后续换行替换污染 <pre>
    const preBlocks = [];
    t = t.replace(/```([\s\S]*?)```/g, (m, code) => {
      preBlocks.push('<pre><code>' + code.replace(/^\n+|\n+$/g, '') + '</code></pre>');
      return '\u0000PRE' + (preBlocks.length - 1) + '\u0000';
    });
    // 行内代码 `x`
    t = t.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    // 加粗 **x**
    t = t.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    // 列表:按行处理
    const lines = t.split('\n');
    const out = [];
    let inUl = false, inOl = false;
    const closeLists = () => {
      if (inUl) { out.push('</ul>'); inUl = false; }
      if (inOl) { out.push('</ol>'); inOl = false; }
    };
    for (const line of lines) {
      const ul = line.match(/^\s*[-*]\s+(.*)$/);
      const ol = line.match(/^\s*\d+[.、]\s+(.*)$/);
      if (ul) {
        if (inOl) { out.push('</ol>'); inOl = false; }
        if (!inUl) { out.push('<ul>'); inUl = true; }
        out.push('<li>' + ul[1] + '</li>');
      } else if (ol) {
        if (inUl) { out.push('</ul>'); inUl = false; }
        if (!inOl) { out.push('<ol>'); inOl = true; }
        out.push('<li>' + ol[1] + '</li>');
      } else {
        closeLists();
        out.push(line);
      }
    }
    closeLists();
    t = out.join('\n').replace(/\n/g, '<br>');
    // 还原代码块
    t = t.replace(/\u0000PRE(\d+)\u0000/g, (m, i) => preBlocks[Number(i)]);
    return t;
  },

  intentLabel(intent) {
    if (!intent) return null;
    return this.INTENT_CN[intent] ? `${this.INTENT_CN[intent]}(${intent})` : intent;
  },

  confColor(p) { return p >= 85 ? 'var(--green)' : p >= 60 ? 'var(--orange)' : 'var(--red)'; },

  /* NLU 可视化条:意图徽标 + 置信度百分比 + 槽位 chips + 缺槽提示 + 三色进度条 + 治理提示 */
  nluBarHTML(d) {
    const parts = [];
    if (d.intent) {
      const pct = d.intentConfidence !== null && d.intentConfidence !== undefined
        ? ' · ' + fmtPercent(d.intentConfidence, 0) : '';
      parts.push(`<span class="nlu-intent">🎯 ${h(this.intentLabel(d.intent))}${pct}</span>`);
    }
    if (d.confidence !== null && d.confidence !== undefined) {
      let p = Number(d.confidence);
      if (p <= 1) p = p * 100;
      p = Math.max(0, Math.min(100, p));
      parts.push(`<span class="nlu-conf">置信度
        <span class="bar"><div style="width:${p}%;background:${this.confColor(p)}"></div></span>
        <span class="mono">${p.toFixed(0)}%</span></span>`);
    }
    if (d.slots && Object.keys(d.slots).length) {
      Object.entries(d.slots).forEach(([k, v]) => {
        if (v !== null && v !== undefined && v !== '') parts.push(`<span class="nlu-slot">${h(k)}=${h(typeof v === 'object' ? JSON.stringify(v) : String(v))}</span>`);
      });
    }
    if (d.missingSlots && d.missingSlots.length) {
      parts.push(`<span class="nlu-missing">⏳ 等待补充参数:${d.missingSlots.map(h).join('、')}</span>`);
    }
    if (d.guardNotes && d.guardNotes.length) {
      d.guardNotes.forEach(g => parts.push(`<span class="nlu-guard">🛡 ${h(g)}</span>`));
    }
    return parts.length ? `<div class="nlu-bar">${parts.join('')}</div>` : '';
  },

  /* 工具调用徽标(点击展开 arguments) */
  toolCallsHTML(d, msgId) {
    if (!d.toolCalls || !d.toolCalls.length) return '';
    const items = d.toolCalls.map((tc, i) => {
      const argsId = `tc-args-${msgId}-${i}`;
      return `<span class="tool-chip" data-args="${argsId}">🔧 ${h(tc.tool || 'tool')}${tc.summary ? ' · ' + h(tc.summary) : ''}</span>
        <div class="tool-args hidden" id="${argsId}">${h(typeof tc.arguments === 'string' ? tc.arguments : JSON.stringify(tc.arguments, null, 2))}</div>`;
    }).join('');
    return `<div class="tool-calls">${items}</div>`;
  },

  /* RAG 引用列表 */
  citationsHTML(d, msgId) {
    if (!d.citations || !d.citations.length) return '';
    const items = d.citations.map((c, i) => {
      const snipId = `cite-${msgId}-${i}`;
      return `<div class="citation-item" data-cite="${snipId}">[资料${i + 1}] ${h(c.title || c.id || '未命名资料')}</div>
        <div class="citation-snippet hidden" id="${snipId}">${h(c.snippet || '')}</div>`;
    }).join('');
    return `<div class="citations"><div class="c-title">📚 参考资料</div>${items}</div>`;
  },

  appendUserBubble(el, content, time) {
    const msgs = el.querySelector('#ai-msgs');
    const div = document.createElement('div');
    div.className = 'ai-msg user';
    div.innerHTML = `
      <div class="msg-col">
        <div class="bubble">${h(content).replace(/\n/g, '<br>')}</div>
        <div class="msg-time">${h(fmtTime(time) === '—' ? new Date().toLocaleString('zh-CN', { hour12: false }) : fmtTime(time))}</div>
      </div>
      <div class="avatar">👤</div>`;
    msgs.appendChild(div);
  },

  /* 助手气泡(支持完整 chat 响应的 NLU/工具/引用展示) */
  appendAssistantBubble(el, d, time) {
    const msgs = el.querySelector('#ai-msgs');
    const msgId = ++Pages.ai._msgSeq;
    const div = document.createElement('div');
    div.className = 'ai-msg assistant';
    div.innerHTML = `
      <div class="avatar">✨</div>
      <div class="msg-col">
        ${this.nluBarHTML(d)}
        <div class="bubble">${this.mdLite(d.reply || '')}${this.toolCallsHTML(d, msgId)}${this.citationsHTML(d, msgId)}</div>
        <div class="msg-time">${h(fmtTime(time) === '—' ? new Date().toLocaleString('zh-CN', { hour12: false }) : fmtTime(time))}</div>
      </div>`;
    msgs.appendChild(div);
    // 工具参数展开
    div.querySelectorAll('.tool-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const t = div.querySelector('#' + CSS.escape(chip.dataset.args));
        if (t) t.classList.toggle('hidden');
      });
    });
    // 引用展开
    div.querySelectorAll('.citation-item').forEach(c => {
      c.addEventListener('click', () => {
        const t = div.querySelector('#' + CSS.escape(c.dataset.cite));
        if (t) t.classList.toggle('hidden');
      });
    });
    return div;
  },

  /* 系统卡片消息(诊断结果 / 生成管道结果等) */
  appendCardBubble(el, innerHTML) {
    const msgs = el.querySelector('#ai-msgs');
    const div = document.createElement('div');
    div.className = 'ai-msg assistant';
    div.innerHTML = `
      <div class="avatar">✨</div>
      <div class="msg-col"><div class="bubble" style="width:100%">${innerHTML}</div></div>`;
    msgs.appendChild(div);
    return div;
  },

  appendTyping(el) {
    const msgs = el.querySelector('#ai-msgs');
    const div = document.createElement('div');
    div.className = 'ai-msg assistant';
    div.id = 'ai-typing';
    div.innerHTML = `<div class="avatar">✨</div>
      <div class="msg-col"><div class="bubble"><span class="typing"><span></span><span></span><span></span></span></div></div>`;
    msgs.appendChild(div);
    this.scrollBottom(el);
  },

  removeTyping(el) {
    const t = el.querySelector('#ai-typing');
    if (t) t.remove();
  },

  scrollBottom(el) {
    const msgs = el.querySelector('#ai-msgs');
    if (msgs) msgs.scrollTop = msgs.scrollHeight;
  },

  /* ================= 发送 ================= */
  sendCurrent(el) {
    const input = el.querySelector('#ai-input');
    const text = input.value.trim();
    if (!text || this._sending) return;
    input.value = '';
    input.style.height = 'auto';
    this.send(el, text);
  },

  async send(el, text) {
    if (this._sending) return;
    this._sending = true;
    this.appendUserBubble(el, text);
    this.appendTyping(el);
    try {
      const d = await API.aiChat({ message: text, sessionId: this.sessionId || undefined });
      this.removeTyping(el);
      if (d && d.sessionId) {
        this.sessionId = d.sessionId;
        sessionStorage.setItem(this.SESSION_KEY, d.sessionId);
      }
      this.appendAssistantBubble(el, d || { reply: '(空回复)' });
      // mockMode 横幅可能随首次响应才明确
      if (d && d.mockMode && this.status && !this.status.mockMode) {
        this.status.mockMode = true;
        this.loadStatus(el);
      }
    } catch (e) {
      this.removeTyping(el);
      this.appendCardBubble(el, `<span style="color:var(--red)">⚠️ 请求失败:${h(e.message)}</span>`);
    } finally {
      this._sending = false;
      this.scrollBottom(el);
    }
  },

  /* ================= 快捷芯片 ================= */
  handleChip(el, kind) {
    if (this._sending) return;
    if (kind === 'platform') this.send(el, '平台现在怎么样');
    else if (kind === 'failed') this.send(el, '最近有失败的运行吗');
    else if (kind === 'diagnose') this.pickFailedRun(el);
    else if (kind === 'generate') this.generatePipeline(el, '每天同步订单数据到数仓');
  },

  /* 诊断失败任务:先列最近失败 run 供选择 */
  async pickFailedRun(el) {
    this.appendUserBubble(el, '帮我诊断失败任务');
    this.appendTyping(el);
    let runs;
    try {
      const data = await API.runs({ status: 'FAILED', page: 0, size: 5 });
      runs = (data && data.content) || [];
    } catch (e) {
      this.removeTyping(el);
      this.appendCardBubble(el, `<span style="color:var(--red)">⚠️ 获取失败运行列表失败:${h(e.message)}</span>`);
      this.scrollBottom(el);
      return;
    }
    this.removeTyping(el);
    if (!runs.length) {
      this.appendAssistantBubble(el, { reply: '最近没有失败的运行,平台运行良好 ✅' });
      this.scrollBottom(el);
      return;
    }
    const card = this.appendCardBubble(el, `
      <div class="ai-card">
        <div class="card-h">🩺 请选择要诊断的失败运行</div>
        ${runs.map(r => `
          <div class="flex gap-2 mb-2" style="align-items:center">
            <button class="btn btn-sm btn-danger" data-diag-run="${r.id}">诊断 #${r.id}</button>
            <span>${h(r.pipelineName)}</span>
            <span class="small text-3">${h(fmtTime(r.startTime))}</span>
          </div>`).join('')}
      </div>`);
    card.querySelectorAll('[data-diag-run]').forEach(btn => {
      btn.addEventListener('click', () => {
        card.querySelectorAll('[data-diag-run]').forEach(b => b.disabled = true);
        this.diagnoseRun(el, Number(btn.dataset.diagRun));
      });
    });
    this.scrollBottom(el);
  },

  async diagnoseRun(el, runId) {
    this.appendTyping(el);
    try {
      const d = await API.aiDiagnose(runId);
      this.removeTyping(el);
      const card = this.appendCardBubble(el, `
        <div class="ai-card">
          <div class="card-h">🩺 诊断报告 — Run #${d.runId}
            ${badge('runStatus', d.status)}
            ${d.mockMode ? '<span class="badge badge-orange">模拟模式</span>' : ''}
          </div>
          <div class="small text-2">管道:${h(d.pipelineName || '—')}</div>
          <div class="rootcause"><b>根因:</b>${h(d.rootCause || '未定位到明确根因')}</div>
          <div style="line-height:1.7">${this.mdLite(d.diagnosis || '')}</div>
          ${(d.suggestions && d.suggestions.length) ? `
            <div class="card-h mt-2" style="font-size:12.5px">💡 修复建议</div>
            <ol>${d.suggestions.map(s => `<li>${h(s)}</li>`).join('')}</ol>` : ''}
          <div class="mt-2"><a class="btn btn-sm" href="#/runs/${d.runId}">查看运行详情 →</a></div>
        </div>`);
      void card;
    } catch (e) {
      this.removeTyping(el);
      this.appendCardBubble(el, `<span style="color:var(--red)">⚠️ 诊断失败:${h(e.message)}</span>`);
    }
    this.scrollBottom(el);
  },

  async generatePipeline(el, description) {
    this.appendUserBubble(el, '生成管道:' + description);
    this.appendTyping(el);
    try {
      const d = await API.aiGeneratePipeline({ description });
      this.removeTyping(el);
      this.appendCardBubble(el, `
        <div class="ai-card">
          <div class="card-h">🛠 管道已生成
            ${d.mockMode ? '<span class="badge badge-orange">模拟模式</span>' : ''}
          </div>
          <div>名称:<b>${h(d.name || '—')}</b> · 节点数:<b class="mono">${fmtNum(d.nodeCount)}</b></div>
          ${d.explanation ? `<div class="small text-2 mt-2" style="line-height:1.7">${this.mdLite(d.explanation)}</div>` : ''}
          <div class="mt-2"><a class="btn btn-sm btn-primary" href="#/pipelines/${d.pipelineId}">查看管道 →</a></div>
        </div>`);
    } catch (e) {
      this.removeTyping(el);
      this.appendCardBubble(el, `<span style="color:var(--red)">⚠️ 生成失败:${h(e.message)}</span>`);
    }
    this.scrollBottom(el);
  },

  /* ================= 侧栏:检索调试 / 能力面板 ================= */
  renderSideTab(el, tab) {
    const body = el.querySelector('#ai-side-body');
    if (!body) return;
    if (tab === 'retrieve') this.renderRetrievePanel(el, body);
    else this.renderIntentsPanel(el, body);
  },

  renderRetrievePanel(el, body) {
    body.innerHTML = `
      <div class="form-label">混合检索调试(关键词 + 向量)</div>
      <div class="flex gap-1 mb-3">
        <input class="input" id="ai-rq" placeholder="输入检索 query,如:如何配置重试策略" style="flex:1">
        <button class="btn btn-primary btn-sm" id="ai-rq-btn" style="height:34px">检索</button>
      </div>
      <div id="ai-rq-result"><div class="small text-3">输入 query 查看查询改写、扩展词与各 chunk 的三列打分</div></div>`;
    const doSearch = async () => {
      const q = body.querySelector('#ai-rq').value.trim();
      if (!q) return;
      const box = body.querySelector('#ai-rq-result');
      box.innerHTML = stateHTML('loading');
      try {
        const d = await API.aiRetrieve({ query: q, topK: 8 });
        if (!d) { box.innerHTML = stateHTML('empty', '无结果'); return; }
        const maxScore = Math.max(0.001, ...(d.chunks || []).map(c => Number(c.hybridScore) || 0));
        const bar = (v, color) => {
          const p = Math.max(0, Math.min(100, (Number(v) || 0) / maxScore * 100));
          return `<div class="score-cell">${(Number(v) || 0).toFixed(3)}</div>
            <div class="score-bar"><div style="width:${p}%;background:${color}"></div></div>`;
        };
        box.innerHTML = `
          ${d.rewrittenQuery ? `<div class="mb-2"><span class="small text-3">改写后:</span><div class="mono small" style="color:var(--cyan)">${h(d.rewrittenQuery)}</div></div>` : ''}
          ${(d.expansions && d.expansions.length) ? `<div class="mb-3"><span class="small text-3">查询扩展:</span><div class="flex gap-1 wrap mt-2">${d.expansions.map(x => `<span class="cap-tag">${h(x)}</span>`).join('')}</div></div>` : ''}
          ${(d.chunks && d.chunks.length) ? `<div class="table-wrap"><table class="tbl" style="font-size:12px">
            <thead><tr><th>文档块</th><th>关键词</th><th>向量</th><th>混合分</th></tr></thead>
            <tbody>${d.chunks.map(c => `<tr>
              <td style="max-width:130px">
                <div style="font-weight:600">${h(c.title || c.id || '')}</div>
                <div class="small text-3" style="overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical">${h(c.snippet || '')}</div>
              </td>
              <td>${bar(c.keywordScore, 'var(--orange)')}</td>
              <td>${bar(c.vectorScore, 'var(--purple)')}</td>
              <td>${bar(c.hybridScore, 'var(--primary)')}</td>
            </tr>`).join('')}</tbody></table></div>`
          : '<div class="small text-3">无命中文档块</div>'}`;
      } catch (e) {
        box.innerHTML = stateHTML('error', e.message);
      }
    };
    body.querySelector('#ai-rq-btn').addEventListener('click', doSearch);
    body.querySelector('#ai-rq').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); doSearch(); }
    });
  },

  async loadIntents() {
    try { this.intents = await API.aiIntents(); } catch (e) { this.intents = null; }
  },

  renderIntentsPanel(el, body) {
    const render = () => {
      if (!this.intents) { body.innerHTML = stateHTML('loading'); return; }
      if (!this.intents.length) { body.innerHTML = stateHTML('empty', '未获取到意图清单'); return; }
      body.innerHTML = this.intents.map(it => `
        <div class="intent-card">
          <div class="ic-name">🎯 ${h(this.intentLabel(it.intent) || it.intent)}</div>
          <div class="ic-desc">${h(it.description || '')}</div>
          <div class="flex gap-1 wrap mb-2">
            ${(it.requiredSlots || []).map(s => `<span class="nlu-slot" title="必填槽位">必填 ${h(s)}</span>`).join('')}
            ${(it.optionalSlots || []).map(s => `<span class="nlu-slot" style="opacity:.65" title="可选槽位">可选 ${h(s)}</span>`).join('')}
          </div>
          <div>${(it.examples || []).map(x => `<span class="ic-ex" data-ex="${h(x)}">💬 ${h(x)}</span>`).join('')}</div>
        </div>`).join('');
      body.querySelectorAll('.ic-ex').forEach(ex => {
        ex.addEventListener('click', () => {
          if (!this._sending) this.send(el, ex.dataset.ex);
        });
      });
    };
    if (this.intents === null) {
      body.innerHTML = stateHTML('loading');
      // 等待 loadIntents 完成后重绘
      const wait = setInterval(() => {
        if (this.intents !== null) {
          clearInterval(wait);
          if (document.getElementById('ai-side-body') === body) render();
        }
      }, 300);
      setTimeout(() => clearInterval(wait), 10000);
    } else render();
  },

  _msgSeq: 0,

  destroy() {}
};
