/* ============================================================
   页面 7:运行详情(#/runs/:id)
   节点状态时间线 + 行数统计 + 日志终端(级别着色)
   实时更新:优先 SSE /api/runs/stream,失败降级 2s 轮询
   ============================================================ */
window.Pages = window.Pages || {};

Pages.runDetail = {
  title: '运行详情',
  runId: null,
  run: null,
  logs: [],
  messages: [],        // Agent 消息总线
  msgTotal: 0,
  _es: null,
  _poll: null,
  _logFilter: '',
  _autoScroll: true,

  async render(el, params) {
    this.runId = params.id;
    this.run = null;
    this.logs = [];
    this.messages = [];
    this.msgTotal = 0;
    this._es = null;
    this._poll = null;
    this._logFilter = '';
    el.innerHTML = stateHTML('loading');
    document.getElementById('page-title').textContent = '运行详情 #' + params.id;
    try {
      this.run = await API.run(this.runId);
      // 日志与通信消息并行加载(消息接口失败不阻塞页面)
      const [logs, msgData] = await Promise.all([
        API.runLogs(this.runId).catch(() => []),
        API.messages({ runId: this.runId, page: 0, size: 100 }).catch(() => null)
      ]);
      this.logs = logs || [];
      this.messages = (msgData && msgData.content) || [];
      this.msgTotal = (msgData && msgData.totalElements !== undefined) ? msgData.totalElements : this.messages.length;
    } catch (e) {
      el.innerHTML = stateHTML('error', '运行详情加载失败:' + e.message);
      return;
    }
    this.renderPage(el);
    this.renderRun(el);
    this.renderTimeline(el);
    this.renderLogs(el);
    this.renderMessages(el);
    this.startRealtime(el);
  },

  renderPage(el) {
    el.innerHTML = `
      <div class="dag-toolbar">
        <a class="btn" href="#/runs">← 返回列表</a>
        <span class="sep"></span>
        <span id="rd-actions"></span>
        <span class="sep"></span>
        <span id="rd-live" class="badge badge-gray">连接中…</span>
      </div>
      <div class="card mb-3" id="rd-header"></div>
      <div class="grid grid-2">
        <div class="card">
          <div class="card-title">节点运行时间线</div>
          <div id="rd-timeline" class="timeline"></div>
        </div>
        <div class="card">
          <div class="card-title">
            实时日志
            <span class="flex gap-1">
              <select id="rd-logfilter" class="select btn-sm" style="width:auto"><option value="">全部节点</option></select>
              <button class="btn btn-sm" id="rd-scroll-btn">跟随滚动:开</button>
            </span>
          </div>
          <div id="rd-logs" class="log-terminal"></div>
        </div>
      </div>
      <div class="card mt-3">
        <div class="card-title">
          Agent 通信(消息总线)
          <span class="sub" id="rd-msg-count"></span>
        </div>
        <div id="rd-msgs" class="msg-list"></div>
      </div>`;
    el.querySelector('#rd-logfilter').addEventListener('change', (e) => {
      this._logFilter = e.target.value;
      this.renderLogs(el);
    });
    el.querySelector('#rd-scroll-btn').addEventListener('click', (e) => {
      this._autoScroll = !this._autoScroll;
      e.target.textContent = '跟随滚动:' + (this._autoScroll ? '开' : '关');
      if (this._autoScroll) this.scrollLogs(el);
    });
    const term = el.querySelector('#rd-logs');
    term.addEventListener('scroll', () => {
      // 用户上翻时暂停跟随
      const nearBottom = term.scrollTop + term.clientHeight >= term.scrollHeight - 30;
      if (!nearBottom && this._autoScroll) {
        this._autoScroll = false;
        el.querySelector('#rd-scroll-btn').textContent = '跟随滚动:关';
      }
    });
  },

  /* ---------- 头部信息 ---------- */
  renderRun(el) {
    const r = this.run;
    if (!r) return;
    const header = el.querySelector('#rd-header');
    if (!header) return;
    header.innerHTML = `
      <div class="flex between wrap gap-3">
        <div>
          <div class="flex gap-2" style="align-items:baseline">
            <span style="font-size:17px;font-weight:700">Run #${r.id}</span>
            <a href="#/pipelines/${r.pipelineId}">${h(r.pipelineName)}</a>
            ${badge('runStatus', r.status)}
            <span class="badge badge-gray">${h(enumLabel('triggerType', r.triggerType))}</span>
          </div>
          <div class="small text-2 mt-2">
            开始 ${fmtTime(r.startTime)} · 结束 ${fmtTime(r.endTime)} · 耗时 ${fmtDuration(r.durationMs)}
          </div>
          ${r.errorMessage ? `<div class="small mt-2" style="color:var(--red)">错误:${h(r.errorMessage)}</div>` : ''}
        </div>
        <div style="min-width:280px;flex:1;max-width:420px">
          <div class="flex between small text-2 mb-2"><span>整体进度</span><span class="mono">${r.progress === null || r.progress === undefined ? '—' : r.progress + '%'}</span></div>
          <div class="progress" style="height:12px"><div style="width:${Math.min(100, Math.max(0, r.progress || 0))}%"></div></div>
          <div class="flex gap-3 mt-3 small">
            <span>读取 <b class="mono" style="color:var(--cyan)">${fmtNum(r.rowsRead)}</b></span>
            <span>写入 <b class="mono" style="color:var(--green)">${fmtNum(r.rowsWritten)}</b></span>
            <span>过滤 <b class="mono" style="color:var(--orange)">${fmtNum(r.rowsFiltered)}</b></span>
          </div>
        </div>
      </div>`;
    // 操作按钮
    const act = el.querySelector('#rd-actions');
    if (act) {
      const running = r.status === 'RUNNING' || r.status === 'PENDING';
      act.innerHTML = `
        ${running ? '<button class="btn btn-danger" id="rd-cancel">取消运行</button>' : ''}
        <button class="btn" id="rd-rerun">重跑</button>`;
      const cancelBtn = act.querySelector('#rd-cancel');
      if (cancelBtn) cancelBtn.addEventListener('click', async () => {
        const ok = await confirmBox(`确定取消运行 #${r.id} 吗?`, { danger: true, okText: '取消运行' });
        if (!ok) return;
        const rr = await apiTry('/api/runs/' + r.id + '/cancel', { method: 'POST' });
        if (rr !== undefined) { toast('已发送取消指令', 'success'); this.refreshOnce(el); }
      });
      act.querySelector('#rd-rerun').addEventListener('click', async () => {
        try {
          const rr = await API.rerunRun(r.id);
          toast('已触发重跑' + (rr && rr.runId ? '(Run #' + rr.runId + ')' : ''), 'success');
          if (rr && rr.runId && rr.runId !== r.id) setTimeout(() => { location.hash = '#/runs/' + rr.runId; }, 600);
        } catch (e) { toast('重跑失败:' + e.message, 'error'); }
      });
    }
  },

  /* ---------- 节点时间线 ---------- */
  renderTimeline(el) {
    const r = this.run;
    const tl = el.querySelector('#rd-timeline');
    if (!tl || !r) return;
    const nodes = r.nodeRuns || [];
    if (!nodes.length) { tl.innerHTML = '<div class="text-3 small">暂无节点运行记录</div>'; return; }
    const colorOf = (s) => ({
      SUCCESS: 'var(--green)', FAILED: 'var(--red)', RUNNING: 'var(--primary)',
      PENDING: 'var(--gray)', CANCELED: 'var(--orange)', PARTIAL_SUCCESS: 'var(--orange)', SKIPPED: 'var(--gray)'
    })[s] || 'var(--gray)';
    tl.innerHTML = nodes.map(n => `
      <div class="tl-item ${n.status === 'RUNNING' ? 'running' : ''}" style="--tl-color:${colorOf(n.status)}">
        <div class="flex gap-2 wrap">
          <b>${h(n.nodeName || n.nodeKey)}</b>
          ${agentTypeTag(n.agentType)}
          ${badge('runStatus', n.status)}
          ${n.retryAttempt ? `<span class="badge badge-orange">重试 ${n.retryAttempt} 次</span>` : ''}
        </div>
        <div class="small text-2 mt-2">
          ${h(n.agentName || '')} · ${fmtTime(n.startTime)} → ${fmtTime(n.endTime)} · 耗时 ${fmtDuration(n.durationMs)}
        </div>
        <div class="small mt-2">
          输入 <b class="mono" style="color:var(--cyan)">${fmtNum(n.rowsIn)}</b> 行 →
          输出 <b class="mono" style="color:var(--green)">${fmtNum(n.rowsOut)}</b> 行
          <button class="btn btn-sm" data-nodelogs="${n.id}" style="margin-left:10px">查看日志</button>
        </div>
        ${n.errorMessage ? `<div class="small mt-2" style="color:var(--red)">${h(n.errorMessage)}</div>` : ''}
      </div>`).join('');
    tl.querySelectorAll('button[data-nodelogs]').forEach(b => {
      b.addEventListener('click', () => {
        this._logFilter = b.dataset.nodelogs;
        const sel = el.querySelector('#rd-logfilter');
        if (sel) sel.value = this._logFilter;
        this.renderLogs(el);
      });
    });
    // 更新日志过滤下拉
    const sel = el.querySelector('#rd-logfilter');
    if (sel) {
      const cur = sel.value;
      sel.innerHTML = '<option value="">全部节点</option>' +
        nodes.map(n => `<option value="${n.id}">${h(n.nodeName || n.nodeKey)}</option>`).join('');
      sel.value = cur;
    }
  },

  /* ---------- 日志终端 ---------- */
  renderLogs(el) {
    const term = el.querySelector('#rd-logs');
    if (!term) return;
    const logs = this._logFilter
      ? this.logs.filter(l => String(l.nodeRunId) === String(this._logFilter))
      : this.logs;
    if (!logs.length) {
      term.innerHTML = '<div class="text-3 small">暂无日志…</div>';
      return;
    }
    term.innerHTML = logs.map(l => {
      const lv = l.level || 'INFO';
      return `<div class="log-line">
        <span class="log-time">${h(l.timestamp || '')}</span>
        <span class="log-level log-${h(lv)}">${h(lv)}</span>
        <span>${h(l.message)}</span>
      </div>`;
    }).join('');
    if (this._autoScroll) this.scrollLogs(el);
  },

  scrollLogs(el) {
    const term = el.querySelector('#rd-logs');
    if (term) term.scrollTop = term.scrollHeight;
  },

  appendLogs(el, newLogs) {
    const seen = new Set(this.logs.map(l => l.id));
    let added = false;
    newLogs.forEach(l => {
      if (l.id === undefined || !seen.has(l.id)) { this.logs.push(l); if (l.id !== undefined) seen.add(l.id); added = true; }
    });
    if (added) {
      this.logs.sort((a, b) => String(a.timestamp).localeCompare(String(b.timestamp)));
      this.renderLogs(el);
    }
  },

  /* ---------- Agent 通信(消息总线) ---------- */
  msgTypeBadge(t) {
    if (t === 'DATA_HANDOFF') return '<span class="badge badge-blue">⇄ 交接</span>';
    if (t === 'DATA_READY') return '<span class="badge badge-cyan">📢 广播</span>';
    return `<span class="badge badge-gray">${h(t || '—')}</span>`;
  },

  renderMessages(el) {
    const box = el.querySelector('#rd-msgs');
    if (!box) return;
    const cnt = el.querySelector('#rd-msg-count');
    if (cnt) cnt.textContent = '共 ' + fmtNum(this.msgTotal) + ' 条';
    if (!this.messages.length) {
      box.innerHTML = '<div class="text-3 small" style="padding:16px;text-align:center">本次运行暂无通信消息</div>';
      return;
    }
    box.innerHTML = this.messages.map(m => `
      <div class="msg-row">
        <span class="log-time mono">${h(m.createdAt || '')}</span>
        ${this.msgTypeBadge(m.type)}
        <span class="msg-flow"><b>${h(m.fromAgent || '—')}</b><span class="text-3"> → </span><b>${h(m.toAgent === null || m.toAgent === undefined ? '全体' : m.toAgent)}</b></span>
        ${m.rows !== null && m.rows !== undefined ? `<span class="badge badge-green">${fmtNum(m.rows)} 行</span>` : ''}
        ${m.payload ? `<span class="msg-payload">${h(m.payload)}</span>` : ''}
      </div>`).join('');
    this.scrollMsgs(el);
  },

  scrollMsgs(el) {
    const box = el.querySelector('#rd-msgs');
    if (box) box.scrollTop = box.scrollHeight;
  },

  /* 合并新消息:按 id 去重,追加到末尾并保持 id 升序;返回新增条数 */
  appendMessages(el, newMsgs, totalFromServer) {
    const hasServerTotal = totalFromServer !== undefined && totalFromServer !== null;
    if (hasServerTotal) this.msgTotal = totalFromServer;
    const seen = new Set(this.messages.map(m => m.id));
    let added = 0;
    newMsgs.forEach(m => {
      if (!m) return;
      if (m.id === undefined || !seen.has(m.id)) {
        this.messages.push(m);
        if (m.id !== undefined) seen.add(m.id);
        added++;
      }
    });
    if (added) {
      if (!hasServerTotal) this.msgTotal += added; // 纯 SSE 追加时本地累加;服务端总数已给定则以服务端为准
      this.messages.sort((a, b) => {
        if (typeof a.id === 'number' && typeof b.id === 'number') return a.id - b.id;
        return String(a.createdAt).localeCompare(String(b.createdAt));
      });
      // 追加前贴近底部则自动跟随最新;否则保持用户阅读位置
      const box = el.querySelector('#rd-msgs');
      const prevTop = box ? box.scrollTop : 0;
      const nearBottom = !box || (box.scrollTop + box.clientHeight >= box.scrollHeight - 40);
      this.renderMessages(el); // 内部默认滚到底
      if (!nearBottom && box) box.scrollTop = prevTop;
    } else if (totalFromServer !== undefined) {
      this.renderMessages(el); // 总数可能变化
    }
    return added;
  },

  /* ---------- 实时:SSE 优先,降级 2s 轮询 ---------- */
  startRealtime(el) {
    if (this.run && FINAL_RUN_STATUS.includes(this.run.status)) {
      this.setLiveBadge(el, '运行已结束', 'gray');
      return; // 终态无需订阅
    }
    try {
      this._es = new EventSource('/api/runs/stream');
      this._es.onopen = () => this.setLiveBadge(el, 'SSE 实时推送', 'green');
      const onEvent = (ev) => {
        let evt;
        try { evt = JSON.parse(ev.data); } catch (e) { return; }
        this.handleEvent(el, evt);
      };
      this._es.onmessage = onEvent;                      // 兼容未命名事件
      this._es.addEventListener('etl-event', onEvent);   // 后端发送的是命名事件 etl-event
      // 兜底:即使 SSE 正常,也每 5s 静默校准一次,避免个别事件丢失导致界面停滞
      this.startPolling(el, 5000);
      this._es.onerror = () => {
        // SSE 断开 → 降级为 2s 轮询
        if (this._es) { this._es.close(); this._es = null; }
        if (this._poll) { clearInterval(this._poll); this._poll = null; }
        this.setLiveBadge(el, 'SSE 不可用,已降级 2s 轮询', 'orange');
        this.startPolling(el, 2000);
      };
    } catch (e) {
      this.setLiveBadge(el, 'SSE 不可用,已降级 2s 轮询', 'orange');
      this.startPolling(el);
    }
  },

  setLiveBadge(el, text, color) {
    const b = el.querySelector('#rd-live');
    if (b) { b.className = 'badge badge-' + color; b.textContent = text; }
  },

  handleEvent(el, evt) {
    const runId = Number(this.runId);
    if (!evt || !evt.type) return;
    if (evt.type === 'RUN_UPDATE' && evt.payload && Number(evt.payload.id) === runId) {
      const prev = this.run || {};
      this.run = Object.assign({}, prev, evt.payload, { nodeRuns: prev.nodeRuns });
      this.renderRun(el);
      this.refreshRunDetail(el); // 拉一次完整详情刷新节点时间线
      this.maybeStop(el, evt.payload.status);
    } else if (evt.type === 'NODE_UPDATE') {
      // 节点状态变化,刷新时间线(节流:直接拉详情)
      this.refreshRunDetail(el);
    } else if (evt.type === 'LOG' && evt.payload) {
      const nodeRunIds = new Set(((this.run && this.run.nodeRuns) || []).map(n => Number(n.id)));
      if (evt.payload.nodeRunId === null || evt.payload.nodeRunId === undefined || nodeRunIds.size === 0 || nodeRunIds.has(Number(evt.payload.nodeRunId))) {
        this.appendLogs(el, [evt.payload]);
      }
    } else if (evt.type === 'MESSAGE' && evt.payload) {
      // 消息总线事件:仅接收本 run 的消息,按 id 去重后追加
      if (Number(evt.payload.runId) === runId) {
        this.appendMessages(el, [evt.payload]);
      }
    }
  },

  _refreshing: false,
  async refreshRunDetail(el) {
    if (this._refreshing) return;
    this._refreshing = true;
    try {
      const detail = await API.run(this.runId);
      this.run = detail;
      this.renderRun(el);
      this.renderTimeline(el);
      this.maybeStop(el, detail.status);
    } catch (e) { /* 忽略瞬时错误 */ }
    finally { this._refreshing = false; }
  },

  async refreshOnce(el) {
    await this.refreshRunDetail(el);
    try {
      const logs = await API.runLogs(this.runId);
      if (logs) { this.logs = logs; this.renderLogs(el); }
    } catch (e) {}
  },

  startPolling(el, intervalMs) {
    if (this._poll) return;
    this._poll = setInterval(async () => {
      if (!document.getElementById('rd-logs')) { this.stopRealtime(); return; }
      try {
        const detail = await API.run(this.runId);
        this.run = detail;
        this.renderRun(el);
        this.renderTimeline(el);
        const logs = await API.runLogs(this.runId);
        if (logs) { this.logs = logs; this.renderLogs(el); }
        // 顺带刷新消息总线(与日志同一兜底节奏)
        try {
          const md = await API.messages({ runId: this.runId, page: 0, size: 100 });
          if (md) this.mergeMessagesFromServer(el, md);
        } catch (e) { /* 消息接口失败不影响主流程 */ }
        this.maybeStop(el, detail.status);
      } catch (e) { /* 保持轮询 */ }
    }, intervalMs || 2000);
  },

  maybeStop(el, status) {
    if (status && FINAL_RUN_STATUS.includes(status)) {
      this.stopRealtime();
      this.setLiveBadge(el, '运行已结束', 'gray');
      // 终态后最后拉一次日志与消息,保证完整
      API.runLogs(this.runId).then(logs => {
        if (logs) { this.logs = logs; this.renderLogs(document.getElementById('page-content')); }
      }).catch(() => {});
      API.messages({ runId: this.runId, page: 0, size: 100 }).then(md => {
        if (md) this.mergeMessagesFromServer(document.getElementById('page-content'), md);
      }).catch(() => {});
    }
  },

  /* 服务端分页结果合并进消息列表(权威 totalElements) */
  mergeMessagesFromServer(el, md) {
    this.appendMessages(el, md.content || [], md.totalElements);
  },

  stopRealtime() {
    if (this._es) { try { this._es.close(); } catch (e) {} this._es = null; }
    if (this._poll) { clearInterval(this._poll); this._poll = null; }
  },

  destroy() { this.stopRealtime(); }
};
