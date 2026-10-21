/* ============================================================
   页面 9:告警中心(#/alerts)
   级别筛选 + 已读/未读 + 全部已读 + 删除,分页
   ============================================================ */
window.Pages = window.Pages || {};

Pages.alerts = {
  title: '告警中心',
  state: { read: '', level: '', page: 0, size: 20 },

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters">
          <select id="al-read" class="select">
            <option value="">全部</option>
            <option value="false">未读</option>
            <option value="true">已读</option>
          </select>
          <select id="al-level" class="select">
            <option value="">全部级别</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.severity), '', 'severity')}
          </select>
        </div>
        <button class="btn btn-primary" id="al-read-all">✓ 全部标记已读</button>
      </div>
      <div id="al-list"></div>
      <div id="al-pager"></div>`;
    el.querySelector('#al-read').addEventListener('change', (e) => {
      this.state.read = e.target.value; this.state.page = 0; this.load(el);
    });
    // 级别前端过滤(契约未提供 level 参数)
    el.querySelector('#al-level').addEventListener('change', (e) => {
      this.state.level = e.target.value; this.state.page = 0; this.load(el);
    });
    el.querySelector('#al-read-all').addEventListener('click', async () => {
      const ok = await confirmBox('确定将所有告警标记为已读吗?');
      if (!ok) return;
      const r = await apiTry('/api/alerts/read-all', { method: 'POST' });
      if (r !== undefined) { toast('已全部标记为已读', 'success'); this.load(el); App.refreshAlertBadge(); }
    });
    this.load(el);
  },

  async load(el) {
    const listEl = el.querySelector('#al-list');
    if (!listEl) return;
    listEl.innerHTML = stateHTML('loading');
    try {
      const data = await API.alerts({ read: this.state.read, page: this.state.page, size: this.state.size });
      let rows = (data && data.content) || [];
      if (this.state.level) rows = rows.filter(a => a.level === this.state.level);
      if (!rows.length) {
        listEl.innerHTML = stateHTML('empty', '暂无告警');
        el.querySelector('#al-pager').innerHTML = '';
        return;
      }
      const colorOf = { INFO: 'var(--primary)', WARN: 'var(--orange)', ERROR: 'var(--red)', CRITICAL: 'var(--red)' };
      listEl.innerHTML = rows.map(a => `
        <div class="alert-item ${a.read ? '' : 'unread'}" style="--alert-color:${colorOf[a.level] || 'var(--gray)'}">
          <div>${badge('severity', a.level)}</div>
          <div class="a-body">
            <div class="a-title">${h(a.title)}${a.read ? '' : ' <span class="badge badge-blue" style="margin-left:6px">未读</span>'}</div>
            <div class="a-content">${h(a.content || '')}</div>
            <div class="a-meta">
              来源 ${h(a.source || '—')} · ${fmtTime(a.createdAt)}
              ${a.runId ? ` · <a href="#/runs/${a.runId}">查看运行 #${a.runId}</a>` : ''}
            </div>
          </div>
          <div class="flex gap-1 nowrap">
            ${a.read ? '' : `<button class="btn btn-sm" data-act="read" data-id="${a.id}">标记已读</button>`}
            <button class="btn btn-sm btn-danger" data-act="del" data-id="${a.id}">删除</button>
          </div>
        </div>`).join('');

      listEl.querySelectorAll('button[data-act="read"]').forEach(b =>
        b.addEventListener('click', async () => {
          const r = await apiTry('/api/alerts/' + b.dataset.id + '/read', { method: 'POST' });
          if (r !== undefined) { this.load(el); App.refreshAlertBadge(); }
        }));
      listEl.querySelectorAll('button[data-act="del"]').forEach(b =>
        b.addEventListener('click', async () => {
          const ok = await confirmBox('确定删除该告警吗?', { danger: true, okText: '删除' });
          if (!ok) return;
          const r = await apiTry('/api/alerts/' + b.dataset.id, { method: 'DELETE' });
          if (r !== undefined) { toast('告警已删除', 'success'); this.load(el); App.refreshAlertBadge(); }
        }));

      const pagerEl = el.querySelector('#al-pager');
      pagerEl.innerHTML = pagerHTML(data.page, data.totalPages, data.totalElements);
      bindPager(pagerEl, data.page, (p) => { this.state.page = p; this.load(el); });
    } catch (e) {
      listEl.innerHTML = stateHTML('error', e.message);
    }
  },

  destroy() {}
};
