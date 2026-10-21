/* ============================================================
   页面 3:数据源管理(#/datasources)
   表格 + 类型徽标 + 测试连接(显示延迟)+ 表清单弹窗 + CRUD
   ============================================================ */
window.Pages = window.Pages || {};

Pages.datasources = {
  title: '数据源管理',
  _data: [],

  render(el) {
    el.innerHTML = `
      <div class="page-head">
        <div class="filters">
          <select id="ds-filter-type" class="select">
            <option value="">全部类型</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.datasourceType), '', 'datasourceType')}
          </select>
          <select id="ds-filter-status" class="select">
            <option value="">全部状态</option>
            ${optionsHTML(Object.keys(ENUM_LABEL.datasourceStatus), '', 'datasourceStatus')}
          </select>
        </div>
        <button class="btn btn-primary" id="ds-add">+ 新增数据源</button>
      </div>
      <div id="ds-list"></div>`;
    el.querySelector('#ds-add').addEventListener('click', () => this.editModal(null));
    el.querySelector('#ds-filter-type').addEventListener('change', () => this.renderTable(el));
    el.querySelector('#ds-filter-status').addEventListener('change', () => this.renderTable(el));
    this.load(el);
  },

  async load(el) {
    const listEl = el.querySelector('#ds-list');
    if (!listEl) return;
    listEl.innerHTML = stateHTML('loading');
    try {
      this._data = await API.datasources() || [];
      this.renderTable(el);
    } catch (e) {
      listEl.innerHTML = stateHTML('error', e.message);
    }
  },

  renderTable(el) {
    const listEl = el.querySelector('#ds-list');
    if (!listEl) return;
    const ft = el.querySelector('#ds-filter-type').value;
    const fs = el.querySelector('#ds-filter-status').value;
    const rows = this._data.filter(d => (!ft || d.type === ft) && (!fs || d.status === fs));
    if (!rows.length) { listEl.innerHTML = stateHTML('empty', '没有符合条件的数据源'); return; }
    listEl.innerHTML = `<div class="table-wrap"><table class="tbl">
      <thead><tr>
        <th>ID</th><th>名称 / 编码</th><th>类型</th><th>状态</th><th>最近测试</th><th>测试结果</th><th>创建时间</th><th>操作</th>
      </tr></thead>
      <tbody>${rows.map(d => `<tr>
        <td class="mono">${d.id}</td>
        <td><div style="font-weight:600">${h(d.name)}</div><div class="small text-3 mono">${h(d.code)}</div></td>
        <td><span class="ds-icon">${h((enumLabel('datasourceType', d.type) || '?').slice(0, 2))}</span> ${badge('datasourceType', d.type, false)}</td>
        <td>${badge('datasourceStatus', d.status)}</td>
        <td class="small text-2 nowrap">${fmtTime(d.lastTestAt)}</td>
        <td>${d.lastTestSuccess === null || d.lastTestSuccess === undefined ? '<span class="text-3">—</span>'
          : d.lastTestSuccess ? '<span class="badge badge-green">成功</span>' : '<span class="badge badge-red">失败</span>'}</td>
        <td class="small text-2 nowrap">${fmtTime(d.createdAt)}</td>
        <td class="nowrap">
          <button class="btn btn-sm" data-act="test" data-id="${d.id}">测试连接</button>
          <button class="btn btn-sm" data-act="tables" data-id="${d.id}">表清单</button>
          <button class="btn btn-sm" data-act="edit" data-id="${d.id}">编辑</button>
          <button class="btn btn-sm btn-danger" data-act="del" data-id="${d.id}">删除</button>
        </td>
      </tr>`).join('')}</tbody></table></div>`;

    listEl.querySelectorAll('button[data-act]').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = Number(btn.dataset.id);
        const ds = this._data.find(x => x.id === id);
        const act = btn.dataset.act;
        if (act === 'edit') this.editModal(ds);
        else if (act === 'del') this.remove(ds);
        else if (act === 'test') this.test(ds, btn);
        else if (act === 'tables') this.showTables(ds);
      });
    });
  },

  async test(ds, btn) {
    btn.disabled = true;
    btn.textContent = '测试中…';
    try {
      const r = await API.testDatasource(ds.id);
      if (r && r.success) toast(`「${ds.name}」连接成功,延迟 ${fmtNum(r.latencyMs)} ms`, 'success');
      else toast(`「${ds.name}」连接失败:${(r && r.message) || '未知原因'}`, 'error', 4500);
      this.refresh();
    } catch (e) {
      toast('测试失败:' + e.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '测试连接';
    }
  },

  async showTables(ds) {
    const { el: overlay } = openModal({
      title: `表清单 — ${ds.name}`,
      body: stateHTML('loading'),
      footer: `<button class="btn" data-act="close">关闭</button>`,
      onOpen: (ov, closeFn) => { ov.querySelector('[data-act="close"]').onclick = closeFn; }
    });
    const bodyEl = overlay.querySelector('.modal-body');
    try {
      const tables = await API.datasourceTables(ds.id);
      if (!tables || !tables.length) { bodyEl.innerHTML = stateHTML('empty', '未发现数据表'); return; }
      bodyEl.innerHTML = `<div class="table-wrap" style="border:none"><table class="tbl">
        <thead><tr><th>表名</th><th>行数</th><th>大小</th></tr></thead>
        <tbody>${tables.map(t => `<tr>
          <td class="mono">${h(t.name)}</td>
          <td class="mono">${fmtNum(t.rows)}</td>
          <td class="text-2">${h(t.sizeDesc || '—')}</td>
        </tr>`).join('')}</tbody></table></div>`;
    } catch (e) {
      bodyEl.innerHTML = stateHTML('error', e.message);
    }
  },

  editModal(ds) {
    const isEdit = !!ds;
    const d = ds || { type: 'MYSQL', status: 'ACTIVE' };
    let configPretty = d.connectionConfig || '';
    if (configPretty) { try { configPretty = JSON.stringify(JSON.parse(configPretty), null, 2); } catch (e) {} }
    const body = `
      <div class="form-2col">
        ${formRow('name', '名称', `<input class="input" name="name" value="${h(d.name || '')}" placeholder="如:业务 MySQL 主库">`, true)}
        ${formRow('code', '编码', `<input class="input mono" name="code" value="${h(d.code || '')}" placeholder="如:ds-mysql-main" ${isEdit ? 'disabled' : ''}>`, true)}
        ${formRow('type', '类型', `<select class="select" name="type">${optionsHTML(Object.keys(ENUM_LABEL.datasourceType), d.type, 'datasourceType')}</select>`, true)}
        ${formRow('status', '状态', `<select class="select" name="status">${optionsHTML(Object.keys(ENUM_LABEL.datasourceStatus), d.status, 'datasourceStatus')}</select>`)}
      </div>
      ${formRow('connectionConfig', '连接配置(JSON)', `<textarea class="textarea mono" name="connectionConfig" rows="5" placeholder='{"url":"jdbc:mysql://...","username":"...","password":"..."}'>${h(configPretty)}</textarea>`)}
      ${formRow('description', '描述', `<textarea class="textarea" name="description">${h(d.description || '')}</textarea>`)}`;
    openModal({
      title: isEdit ? '编辑数据源' : '新增数据源',
      size: 'lg',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">保存</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = async () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '名称', required: true },
            { name: 'code', label: '编码', required: !isEdit },
            { name: 'type', label: '类型', required: true },
            { name: 'status', label: '状态' },
            { name: 'connectionConfig', label: '连接配置', type: 'json' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          if (isEdit) delete v.code;
          const r = isEdit
            ? await apiTry('/api/datasources/' + ds.id, { method: 'PUT', body: v })
            : await apiTry('/api/datasources', { method: 'POST', body: v });
          if (r !== undefined) { close(); toast(isEdit ? '数据源已更新' : '数据源已创建', 'success'); this.refresh(); }
        };
      }
    });
  },

  async remove(ds) {
    const ok = await confirmBox(`确定要删除数据源「${ds.name}」吗?引用它的管道节点可能受影响。`, { danger: true, okText: '删除' });
    if (!ok) return;
    const r = await apiTry('/api/datasources/' + ds.id, { method: 'DELETE' });
    if (r !== undefined) { toast('数据源已删除', 'success'); this.refresh(); }
  },

  refresh() {
    const el = document.querySelector('#page-content');
    if (el && el.querySelector('#ds-list')) this.load(el);
  },

  destroy() {}
};
