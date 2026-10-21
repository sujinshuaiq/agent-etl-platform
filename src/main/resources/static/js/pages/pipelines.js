/* ============================================================
   页面 4:管道列表(#/pipelines)
   卡片列表(状态、节点数、最近运行),进入 DAG 详情
   ============================================================ */
window.Pages = window.Pages || {};

Pages.pipelines = {
  title: '管道编排',
  _data: [],

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters">
          <select id="pl-filter-status" class="select">
            <option value="">全部状态</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.pipelineStatus), '', 'pipelineStatus')}
          </select>
        </div>
        <button class="btn btn-primary" id="pl-add">+ 新建管道</button>
      </div>
      <div id="pl-list"></div>`;
    el.querySelector('#pl-add').addEventListener('click', () => this.createModal());
    el.querySelector('#pl-filter-status').addEventListener('change', () => this.renderCards(el));
    this.load(el);
  },

  async load(el) {
    const listEl = el.querySelector('#pl-list');
    if (!listEl) return;
    listEl.innerHTML = stateHTML('loading');
    try {
      this._data = await API.pipelines() || [];
      this.renderCards(el);
    } catch (e) {
      listEl.innerHTML = stateHTML('error', e.message);
    }
  },

  renderCards(el) {
    const listEl = el.querySelector('#pl-list');
    if (!listEl) return;
    const fs = el.querySelector('#pl-filter-status').value;
    const rows = this._data.filter(p => !fs || p.status === fs);
    if (!rows.length) { listEl.innerHTML = stateHTML('empty', '暂无管道,点击右上角「新建管道」开始编排'); return; }
    listEl.innerHTML = `<div class="grid grid-3">${rows.map(p => `
      <div class="pipeline-card" data-id="${p.id}">
        <div class="flex between">
          <div class="pc-name">${h(p.name)}</div>
          ${badge('pipelineStatus', p.status)}
        </div>
        <div class="pc-desc">${h(p.description || '暂无描述')}</div>
        <div class="flex gap-2 mb-2 wrap">
          <span class="badge badge-purple">节点 ${fmtNum(p.nodeCount)}</span>
          ${p.category ? `<span class="badge badge-cyan">${h(p.category)}</span>` : ''}
          ${p.lastRunStatus ? badge('runStatus', p.lastRunStatus) : '<span class="badge badge-gray">未运行</span>'}
        </div>
        <div class="pc-meta">
          <span>最近运行:${fmtTime(p.lastRunAt)}</span>
          <span>创建:${fmtTime(p.createdAt)}</span>
        </div>
        <div class="flex gap-1 mt-3">
          <button class="btn btn-sm btn-primary" data-act="open" data-id="${p.id}">打开编辑器</button>
          <button class="btn btn-sm btn-danger" data-act="del" data-id="${p.id}">删除</button>
        </div>
      </div>`).join('')}</div>`;

    listEl.querySelectorAll('.pipeline-card').forEach(card => {
      card.addEventListener('click', (e) => {
        if (e.target.closest('button')) return;
        location.hash = '#/pipelines/' + card.dataset.id;
      });
    });
    listEl.querySelectorAll('button[data-act="open"]').forEach(b =>
      b.addEventListener('click', () => { location.hash = '#/pipelines/' + b.dataset.id; }));
    listEl.querySelectorAll('button[data-act="del"]').forEach(b =>
      b.addEventListener('click', async () => {
        const p = this._data.find(x => x.id === Number(b.dataset.id));
        const ok = await confirmBox(`确定要删除管道「${p.name}」吗?其节点、边与调度将一并删除,历史运行记录保留。`, { danger: true, okText: '删除' });
        if (!ok) return;
        const r = await apiTry('/api/pipelines/' + p.id, { method: 'DELETE' });
        if (r !== undefined) { toast('管道已删除', 'success'); this.refresh(); }
      }));
  },

  createModal() {
    const body = `
      <div class="form-2col">
        ${formRow('name', '管道名称', `<input class="input" name="name" placeholder="如:用户数据日终同步">`, true)}
        ${formRow('code', '管道编码', `<input class="input mono" name="code" placeholder="如:pl-user-daily">`, true)}
        ${formRow('category', '分类', `<input class="input" name="category" placeholder="如:用户域">`)}
        ${formRow('failureStrategy', '失败策略', `<select class="select" name="failureStrategy">${optionsHTML(Object.keys(ENUM_LABEL.failureStrategy), 'FAIL_FAST', 'failureStrategy')}</select>`)}
        ${formRow('retryCount', '重试次数', `<input class="input" name="retryCount" type="number" value="1">`)}
        ${formRow('retryIntervalSeconds', '重试间隔(秒)', `<input class="input" name="retryIntervalSeconds" type="number" value="30">`)}
        ${formRow('timeoutMinutes', '超时(分钟)', `<input class="input" name="timeoutMinutes" type="number" value="30">`)}
      </div>
      ${formRow('description', '描述', `<textarea class="textarea" name="description"></textarea>`)}
      <div class="small text-3">创建后将进入 DAG 编辑器添加节点与连线。</div>`;
    openModal({
      title: '新建管道',
      size: 'lg',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">创建并打开编辑器</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = async () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '管道名称', required: true },
            { name: 'code', label: '管道编码', required: true },
            { name: 'category', label: '分类' },
            { name: 'failureStrategy', label: '失败策略' },
            { name: 'retryCount', label: '重试次数', type: 'number' },
            { name: 'retryIntervalSeconds', label: '重试间隔', type: 'number' },
            { name: 'timeoutMinutes', label: '超时', type: 'number' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          v.status = 'DRAFT';
          v.nodes = [];
          v.edges = [];
          try {
            const r = await API.createPipeline(v);
            close();
            toast('管道已创建', 'success');
            if (r && r.id) location.hash = '#/pipelines/' + r.id;
            else this.refresh();
          } catch (e) {
            toast(e.message, 'error');
          }
        };
      }
    });
  },

  refresh() {
    const el = document.querySelector('#page-content');
    if (el && el.querySelector('#pl-list')) this.load(el);
  },

  destroy() {}
};
