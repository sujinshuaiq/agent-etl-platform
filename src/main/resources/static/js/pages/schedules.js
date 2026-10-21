/* ============================================================
   页面 10:调度管理(#/schedules)
   CRUD + cron 表达式 + 上次/下次触发时间 + 启用开关
   ============================================================ */
window.Pages = window.Pages || {};

Pages.schedules = {
  title: '调度管理',
  _data: [],
  _pipelines: [],

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters"><span class="small text-3">cron 六位格式:秒 分 时 日 月 周,支持 * */n 数字 逗号</span></div>
        <button class="btn btn-primary" id="sc-add">+ 新增调度</button>
      </div>
      <div id="sc-list"></div>`;
    el.querySelector('#sc-add').addEventListener('click', () => this.editModal(null));
    this.load(el);
  },

  async load(el) {
    const listEl = el.querySelector('#sc-list');
    if (!listEl) return;
    listEl.innerHTML = stateHTML('loading');
    try {
      const [list, pipelines] = await Promise.all([
        API.schedules(),
        this._pipelines.length ? Promise.resolve(this._pipelines) : API.pipelines()
      ]);
      this._data = list || [];
      this._pipelines = pipelines || [];
      this.renderTable(el);
    } catch (e) {
      listEl.innerHTML = stateHTML('error', e.message);
    }
  },

  renderTable(el) {
    const listEl = el.querySelector('#sc-list');
    if (!listEl) return;
    if (!this._data.length) { listEl.innerHTML = stateHTML('empty', '暂无调度任务'); return; }
    listEl.innerHTML = `<div class="table-wrap"><table class="tbl">
      <thead><tr>
        <th>ID</th><th>管道</th><th>cron 表达式</th><th>启用</th><th>上次触发</th><th>下次触发</th><th>描述</th><th>创建时间</th><th>操作</th>
      </tr></thead>
      <tbody>${this._data.map(s => `<tr>
        <td class="mono">${s.id}</td>
        <td><a href="#/pipelines/${s.pipelineId}">${h(s.pipelineName || ('#' + s.pipelineId))}</a></td>
        <td class="mono" style="color:var(--cyan)">${h(s.cronExpression)}</td>
        <td>
          <label class="switch">
            <input type="checkbox" data-toggle="${s.id}" ${s.enabled ? 'checked' : ''}>
            <span class="slider"></span>
          </label>
        </td>
        <td class="small text-2 nowrap">${fmtTime(s.lastFireAt)}</td>
        <td class="small nowrap" style="color:${s.enabled ? 'var(--green)' : 'var(--text-3)'}">${fmtTime(s.nextFireAt)}</td>
        <td class="small text-2">${h(s.description || '—')}</td>
        <td class="small text-2 nowrap">${fmtTime(s.createdAt)}</td>
        <td class="nowrap">
          <button class="btn btn-sm" data-act="edit" data-id="${s.id}">编辑</button>
          <button class="btn btn-sm btn-danger" data-act="del" data-id="${s.id}">删除</button>
        </td>
      </tr>`).join('')}</tbody></table></div>`;

    listEl.querySelectorAll('input[data-toggle]').forEach(cb => {
      cb.addEventListener('change', async () => {
        const s = this._data.find(x => x.id === Number(cb.dataset.toggle));
        const target = cb.checked;
        try {
          if (target) await API.enableSchedule(s.id);
          else await API.disableSchedule(s.id);
          toast(`调度「${s.pipelineName || s.id}」已${target ? '启用' : '禁用'}`, 'success');
          this.refresh();
        } catch (e) {
          cb.checked = !target;
          toast('操作失败:' + e.message, 'error');
        }
      });
    });
    listEl.querySelectorAll('button[data-act="edit"]').forEach(b =>
      b.addEventListener('click', () => this.editModal(this._data.find(x => x.id === Number(b.dataset.id)))));
    listEl.querySelectorAll('button[data-act="del"]').forEach(b =>
      b.addEventListener('click', async () => {
        const s = this._data.find(x => x.id === Number(b.dataset.id));
        const ok = await confirmBox(`确定删除管道「${s.pipelineName || s.pipelineId}」的调度(${s.cronExpression})吗?`, { danger: true, okText: '删除' });
        if (!ok) return;
        const r = await apiTry('/api/schedules/' + s.id, { method: 'DELETE' });
        if (r !== undefined) { toast('调度已删除', 'success'); this.refresh(); }
      }));
  },

  editModal(sc) {
    const isEdit = !!sc;
    const s = sc || { enabled: true };
    const plOptions = this._pipelines.map(p => ({ value: String(p.id), label: `${p.name}(${enumLabel('pipelineStatus', p.status)})` }));
    const body = `
      ${formRow('pipelineId', '管道', `<select class="select" name="pipelineId">
        <option value="">请选择管道</option>${optionsHTML(plOptions, s.pipelineId ? String(s.pipelineId) : '')}</select>`, true)}
      ${formRow('cronExpression', 'cron 表达式(秒 分 时 日 月 周)', `<input class="input mono" name="cronExpression" value="${h(s.cronExpression || '')}" placeholder="如:0 */5 * * * * 表示每 5 分钟">`, true)}
      ${formRow('enabled', '是否启用', `<select class="select" name="enabled">
        <option value="true"${s.enabled !== false ? ' selected' : ''}>启用</option>
        <option value="false"${s.enabled === false ? ' selected' : ''}>禁用</option></select>`)}
      ${formRow('description', '描述', `<textarea class="textarea" name="description">${h(s.description || '')}</textarea>`)}`;
    openModal({
      title: isEdit ? '编辑调度' : '新增调度',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">保存</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = async () => {
          const v = collectForm(overlay, [
            { name: 'pipelineId', label: '管道', required: true },
            { name: 'cronExpression', label: 'cron 表达式', required: true },
            { name: 'enabled', label: '是否启用' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          // 简单校验 cron 六位
          const parts = v.cronExpression.trim().split(/\s+/);
          if (parts.length !== 6) {
            const errEl = overlay.querySelector('[data-err="cronExpression"]');
            if (errEl) errEl.textContent = 'cron 表达式必须为 6 段:秒 分 时 日 月 周';
            overlay.querySelector('[name="cronExpression"]').classList.add('invalid');
            return;
          }
          v.pipelineId = Number(v.pipelineId);
          v.enabled = v.enabled === 'true';
          const r = isEdit
            ? await apiTry('/api/schedules/' + s.id, { method: 'PUT', body: v })
            : await apiTry('/api/schedules', { method: 'POST', body: v });
          if (r !== undefined) { close(); toast(isEdit ? '调度已更新' : '调度已创建', 'success'); this.refresh(); }
        };
      }
    });
  },

  refresh() {
    const el = document.querySelector('#page-content');
    if (el && el.querySelector('#sc-list')) this.load(el);
  },

  destroy() {}
};
