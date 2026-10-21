/* ============================================================
   页面 6:运行记录(#/runs)
   筛选(状态/管道)+ 分页表格 + 取消/重跑,15s 自动刷新
   ============================================================ */
window.Pages = window.Pages || {};

Pages.runs = {
  title: '运行记录',
  _timer: null,
  _pipelines: [],
  state: { status: '', pipelineId: '', page: 0, size: 20 },

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters">
          <select id="rn-status" class="select">
            <option value="">全部状态</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.runStatus).filter(k => k !== 'SKIPPED'), '', 'runStatus')}
          </select>
          <select id="rn-pipeline" class="select"><option value="">全部管道</option></select>
          <button class="btn btn-sm" id="rn-refresh">⟳ 刷新</button>
          <span class="small text-3">每 15s 自动刷新</span>
        </div>
      </div>
      <div id="rn-list"></div>
      <div id="rn-pager"></div>`;
    el.querySelector('#rn-status').addEventListener('change', (e) => {
      this.state.status = e.target.value; this.state.page = 0; this.load(el);
    });
    el.querySelector('#rn-pipeline').addEventListener('change', (e) => {
      this.state.pipelineId = e.target.value; this.state.page = 0; this.load(el);
    });
    el.querySelector('#rn-refresh').addEventListener('click', () => this.load(el));
    // 管道下拉
    API.pipelines().then(list => {
      const sel = el.querySelector('#rn-pipeline');
      if (!sel) return;
      this._pipelines = list || [];
      sel.innerHTML = '<option value="">全部管道</option>' +
        this._pipelines.map(p => `<option value="${p.id}">${h(p.name)}</option>`).join('');
    }).catch(() => {});
    this.load(el);
    this._timer = setInterval(() => this.load(el, true), 15000);
  },

  async load(el, silent) {
    const listEl = el.querySelector('#rn-list');
    if (!listEl) return;
    if (!silent) listEl.innerHTML = stateHTML('loading');
    try {
      const data = await API.runs({
        status: this.state.status,
        pipelineId: this.state.pipelineId,
        page: this.state.page,
        size: this.state.size
      });
      const rows = (data && data.content) || [];
      if (!rows.length) {
        listEl.innerHTML = stateHTML('empty', '暂无运行记录');
        el.querySelector('#rn-pager').innerHTML = '';
        return;
      }
      listEl.innerHTML = `<div class="table-wrap"><table class="tbl">
        <thead><tr>
          <th>ID</th><th>管道</th><th>状态</th><th>触发方式</th><th>开始时间</th><th>耗时</th>
          <th>进度</th><th>读 / 写 / 过滤行数</th><th>错误信息</th><th>操作</th>
        </tr></thead>
        <tbody>${rows.map(r => `<tr class="clickable" data-id="${r.id}">
          <td class="mono">#${r.id}</td>
          <td>${h(r.pipelineName)}</td>
          <td>${badge('runStatus', r.status)}</td>
          <td class="text-2">${h(enumLabel('triggerType', r.triggerType))}</td>
          <td class="small text-2 nowrap">${fmtTime(r.startTime)}</td>
          <td class="small text-2 nowrap">${fmtDuration(r.durationMs)}</td>
          <td style="min-width:110px">
            <div class="progress"><div style="width:${Math.min(100, Math.max(0, r.progress || 0))}%"></div></div>
            <div class="small text-3" style="margin-top:2px">${r.progress === null || r.progress === undefined ? '—' : r.progress + '%'}</div>
          </td>
          <td class="small mono nowrap">${fmtNum(r.rowsRead)} / ${fmtNum(r.rowsWritten)} / ${fmtNum(r.rowsFiltered)}</td>
          <td class="small" style="max-width:180px;color:var(--red)">${h(this.trunc(r.errorMessage, 40) || '—')}</td>
          <td class="nowrap">
            ${(r.status === 'RUNNING' || r.status === 'PENDING') ? `<button class="btn btn-sm btn-danger" data-act="cancel" data-id="${r.id}">取消</button>` : ''}
            <button class="btn btn-sm" data-act="rerun" data-id="${r.id}">重跑</button>
          </td>
        </tr>`).join('')}</tbody></table></div>`;

      listEl.querySelectorAll('tr.clickable').forEach(tr => {
        tr.addEventListener('click', (e) => {
          if (e.target.closest('button')) return;
          location.hash = '#/runs/' + tr.dataset.id;
        });
      });
      listEl.querySelectorAll('button[data-act="cancel"]').forEach(b =>
        b.addEventListener('click', async () => {
          const ok = await confirmBox(`确定取消运行 #${b.dataset.id} 吗?`, { danger: true, okText: '取消运行' });
          if (!ok) return;
          const r = await apiTry('/api/runs/' + b.dataset.id + '/cancel', { method: 'POST' });
          if (r !== undefined) { toast('已发送取消指令', 'success'); this.load(el, true); }
        }));
      listEl.querySelectorAll('button[data-act="rerun"]').forEach(b =>
        b.addEventListener('click', async () => {
          try {
            const r = await API.rerunRun(b.dataset.id);
            toast('已触发重跑' + (r && r.runId ? '(Run #' + r.runId + ')' : ''), 'success');
            this.load(el, true);
          } catch (e) { toast('重跑失败:' + e.message, 'error'); }
        }));

      const pagerEl = el.querySelector('#rn-pager');
      pagerEl.innerHTML = pagerHTML(data.page, data.totalPages, data.totalElements);
      bindPager(pagerEl, data.page, (p) => { this.state.page = p; this.load(el); });
    } catch (e) {
      if (!silent) listEl.innerHTML = stateHTML('error', e.message);
    }
  },

  trunc(s, n) {
    if (!s) return '';
    s = String(s);
    return s.length > n ? s.slice(0, n) + '…' : s;
  },

  destroy() { if (this._timer) { clearInterval(this._timer); this._timer = null; } }
};
