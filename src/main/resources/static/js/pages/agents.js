/* ============================================================
   页面 2:Agent 管理(#/agents)
   表格 + 状态徽标 + 新增/编辑弹窗 + 启动/停止/重启 + 能力标签 + 心跳时间
   ============================================================ */
window.Pages = window.Pages || {};

Pages.agents = {
  title: 'Agent 管理',
  _data: [],
  _timer: null,

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters">
          <select id="ag-filter-type" class="select">
            <option value="">全部类型</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.agentType), '', 'agentType')}
          </select>
          <select id="ag-filter-status" class="select">
            <option value="">全部状态</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.agentStatus), '', 'agentStatus')}
          </select>
        </div>
        <button class="btn btn-primary" id="ag-add">+ 新增 Agent</button>
      </div>
      <div id="ag-list"></div>`;
    el.querySelector('#ag-add').addEventListener('click', () => this.editModal(null));
    el.querySelector('#ag-filter-type').addEventListener('change', () => this.renderTable(el));
    el.querySelector('#ag-filter-status').addEventListener('change', () => this.renderTable(el));
    this.load(el);
    this._timer = setInterval(() => this.load(el, true), 15000);
  },

  async load(el) {
    const listEl = el.querySelector('#ag-list');
    if (!listEl) return;
    const data = await API.agents().catch(e => { listEl.innerHTML = stateHTML('error', e.message); return null; });
    if (data === null) return;
    this._data = data || [];
    this.renderTable(el);
  },

  renderTable(el) {
    const listEl = el.querySelector('#ag-list');
    if (!listEl) return;
    const ft = el.querySelector('#ag-filter-type').value;
    const fs = el.querySelector('#ag-filter-status').value;
    const rows = this._data.filter(a => (!ft || a.type === ft) && (!fs || a.status === fs));
    if (!rows.length) { listEl.innerHTML = stateHTML('empty', '没有符合条件的 Agent'); return; }
    listEl.innerHTML = `<div class="table-wrap"><table class="tbl">
      <thead><tr>
        <th>ID</th><th>名称 / 编码</th><th>类型</th><th>状态</th><th>地址</th><th>版本</th>
        <th>能力</th><th>负载</th><th>累计执行(成/败)</th><th>平均耗时</th><th>最近心跳</th><th>操作</th>
      </tr></thead>
      <tbody>${rows.map(a => `<tr>
        <td class="mono">${a.id}</td>
        <td><div style="font-weight:600">${h(a.name)}</div><div class="small text-3 mono">${h(a.code)}</div></td>
        <td>${agentTypeTag(a.type)}</td>
        <td>${badge('agentStatus', a.status)}</td>
        <td class="mono small">${h(a.host || '—')}${a.port ? ':' + a.port : ''}</td>
        <td class="small text-2">${h(a.version || '—')}</td>
        <td>${(a.capabilities || []).map(c => `<span class="cap-tag">${h(c)}</span>`).join('') || '<span class="text-3">—</span>'}</td>
        <td><span class="mono">${fmtNum(a.currentTasks)}</span><span class="text-3"> / ${fmtNum(a.maxConcurrency)}</span></td>
        <td class="small"><span class="mono">${fmtNum(a.totalExecuted)}</span> <span class="text-3">(${fmtNum(a.successCount)}/${fmtNum(a.failCount)})</span></td>
        <td class="small text-2 nowrap">${fmtDuration(a.avgDurationMs)}</td>
        <td class="small text-2 nowrap" title="${h(fmtTime(a.lastHeartbeatAt))}">${timeAgo(a.lastHeartbeatAt)}</td>
        <td class="nowrap">
          <button class="btn btn-sm" data-act="edit" data-id="${a.id}">编辑</button>
          <button class="btn btn-sm btn-success" data-act="start" data-id="${a.id}" ${a.status === 'ONLINE' ? 'disabled' : ''}>启动</button>
          <button class="btn btn-sm" data-act="stop" data-id="${a.id}" ${a.status !== 'ONLINE' ? 'disabled' : ''}>停止</button>
          <button class="btn btn-sm" data-act="restart" data-id="${a.id}">重启</button>
          <button class="btn btn-sm btn-danger" data-act="del" data-id="${a.id}">删除</button>
        </td>
      </tr>`).join('')}</tbody></table></div>`;

    listEl.querySelectorAll('button[data-act]').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = Number(btn.dataset.id);
        const agent = this._data.find(x => x.id === id);
        const act = btn.dataset.act;
        if (act === 'edit') this.editModal(agent);
        else if (act === 'del') this.remove(agent);
        else this.lifecycle(agent, act);
      });
    });
  },

  async lifecycle(agent, act) {
    const names = { start: '启动', stop: '停止', restart: '重启' };
    if (act !== 'start') {
      const ok = await confirmBox(`确定要${names[act]} Agent「${agent.name}」吗?`, { title: names[act] + '确认' });
      if (!ok) return;
    }
    const r = await apiTry('/api/agents/' + agent.id + '/' + act, { method: 'POST' });
    if (r !== undefined) { toast(`Agent「${agent.name}」${names[act]}指令已发送`, 'success'); this.refresh(); }
  },

  editModal(agent) {
    const isEdit = !!agent;
    const a = agent || { type: 'EXTRACTOR', status: 'OFFLINE', maxConcurrency: 4, capabilities: [] };
    const body = `
      <div class="form-2col">
        ${formRow('name', '名称', `<input class="input" name="name" value="${h(a.name || '')}" placeholder="如:用户数据抽取器">`, true)}
        ${formRow('code', '编码', `<input class="input mono" name="code" value="${h(a.code || '')}" placeholder="如:agent-extractor-01" ${isEdit ? 'disabled' : ''}>`, true)}
        ${formRow('type', '类型', `<select class="select" name="type">${optionsHTML(Object.keys(ENUM_LABEL.agentType), a.type, 'agentType')}</select>`, true)}
        ${formRow('version', '版本', `<input class="input" name="version" value="${h(a.version || '')}" placeholder="如:1.0.0">`)}
        ${formRow('host', '主机', `<input class="input" name="host" value="${h(a.host || '')}" placeholder="127.0.0.1">`)}
        ${formRow('port', '端口', `<input class="input" name="port" type="number" value="${a.port || ''}" placeholder="9001">`)}
        ${formRow('maxConcurrency', '最大并发', `<input class="input" name="maxConcurrency" type="number" value="${a.maxConcurrency || 4}">`)}
        ${formRow('capabilities', '能力标签(逗号分隔)', `<input class="input" name="capabilities" value="${h((a.capabilities || []).join(','))}" placeholder="mysql,csv,batch">`)}
      </div>
      ${formRow('description', '描述', `<textarea class="textarea" name="description" placeholder="Agent 职责说明">${h(a.description || '')}</textarea>`)}`;
    openModal({
      title: isEdit ? '编辑 Agent' : '新增 Agent',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">保存</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = async () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '名称', required: true },
            { name: 'code', label: '编码', required: !isEdit },
            { name: 'type', label: '类型', required: true },
            { name: 'version', label: '版本' },
            { name: 'host', label: '主机' },
            { name: 'port', label: '端口', type: 'number' },
            { name: 'maxConcurrency', label: '最大并发', type: 'number' },
            { name: 'capabilities', label: '能力标签' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          v.capabilities = v.capabilities ? v.capabilities.split(/[,,\s]+/).filter(Boolean) : [];
          if (isEdit) delete v.code;
          const r = isEdit
            ? await apiTry('/api/agents/' + agent.id, { method: 'PUT', body: v })
            : await apiTry('/api/agents', { method: 'POST', body: v });
          if (r !== undefined) { close(); toast(isEdit ? 'Agent 已更新' : 'Agent 已创建', 'success'); this.refresh(); }
        };
      }
    });
  },

  async remove(agent) {
    const ok = await confirmBox(`确定要删除 Agent「${agent.name}」吗?此操作不可恢复。`, { danger: true, okText: '删除' });
    if (!ok) return;
    const r = await apiTry('/api/agents/' + agent.id, { method: 'DELETE' });
    if (r !== undefined) { toast('Agent 已删除', 'success'); this.refresh(); }
  },

  refresh() {
    const el = document.querySelector('#page-content');
    if (el && el.querySelector('#ag-list')) this.load(el);
  },

  destroy() { if (this._timer) { clearInterval(this._timer); this._timer = null; } }
};
