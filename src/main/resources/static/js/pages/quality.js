/* ============================================================
   页面 8:数据质量(#/quality)
   规则 CRUD + 检查结果表格(通过率徽标、分页、按规则筛选)
   ============================================================ */
window.Pages = window.Pages || {};

Pages.quality = {
  title: '数据质量',
  _rules: [],
  state: { ruleId: '', page: 0, size: 20 },

  render(el) {
    el.innerHTML = `
      <div class="card mb-3">
        <div class="card-title">
          质量规则
          <button class="btn btn-sm btn-primary" id="qr-add">+ 新增规则</button>
        </div>
        <div id="qr-rules"></div>
      </div>
      <div class="card">
        <div class="card-title">
          检查结果
          <span class="flex gap-1">
            <select id="qr-rule-filter" class="select btn-sm" style="width:auto"><option value="">全部规则</option></select>
          </span>
        </div>
        <div id="qr-results"></div>
        <div id="qr-pager"></div>
      </div>`;
    el.querySelector('#qr-add').addEventListener('click', () => this.editModal(null));
    el.querySelector('#qr-rule-filter').addEventListener('change', (e) => {
      this.state.ruleId = e.target.value; this.state.page = 0; this.loadResults(el);
    });
    this.loadRules(el);
    this.loadResults(el);
  },

  async loadRules(el) {
    const box = el.querySelector('#qr-rules');
    if (!box) return;
    box.innerHTML = stateHTML('loading');
    try {
      this._rules = await API.qualityRules() || [];
      if (!this._rules.length) { box.innerHTML = stateHTML('empty', '暂无质量规则'); return; }
      box.innerHTML = `<div class="table-wrap" style="border:none"><table class="tbl">
        <thead><tr>
          <th>ID</th><th>规则名</th><th>目标表</th><th>检查类型</th><th>字段</th><th>表达式</th><th>严重级别</th><th>启用</th><th>描述</th><th>操作</th>
        </tr></thead>
        <tbody>${this._rules.map(r => `<tr>
          <td class="mono">${r.id}</td>
          <td style="font-weight:600">${h(r.name)}</td>
          <td class="mono small">${h(r.targetTable)}</td>
          <td><span class="badge badge-cyan">${h(enumLabel('checkType', r.checkType))}</span></td>
          <td class="mono small">${h(r.field || '—')}</td>
          <td class="mono small text-2" style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${h(r.expression || '')}">${h(r.expression || '—')}</td>
          <td>${badge('severity', r.severity)}</td>
          <td>${r.enabled ? '<span class="badge badge-green">启用</span>' : '<span class="badge badge-gray">停用</span>'}</td>
          <td class="small text-2">${h(r.description || '—')}</td>
          <td class="nowrap">
            <button class="btn btn-sm" data-act="edit" data-id="${r.id}">编辑</button>
            <button class="btn btn-sm btn-danger" data-act="del" data-id="${r.id}">删除</button>
          </td>
        </tr>`).join('')}</tbody></table></div>`;
      // 更新筛选下拉
      const sel = el.querySelector('#qr-rule-filter');
      if (sel) {
        const cur = sel.value;
        sel.innerHTML = '<option value="">全部规则</option>' +
          this._rules.map(r => `<option value="${r.id}">${h(r.name)}</option>`).join('');
        sel.value = cur;
      }
      box.querySelectorAll('button[data-act]').forEach(btn => {
        btn.addEventListener('click', () => {
          const rule = this._rules.find(x => x.id === Number(btn.dataset.id));
          if (btn.dataset.act === 'edit') this.editModal(rule);
          else this.remove(rule);
        });
      });
    } catch (e) {
      box.innerHTML = stateHTML('error', e.message);
    }
  },

  async loadResults(el) {
    const box = el.querySelector('#qr-results');
    if (!box) return;
    box.innerHTML = stateHTML('loading');
    try {
      const data = await API.qualityResults({ ruleId: this.state.ruleId, page: this.state.page, size: this.state.size });
      const rows = (data && data.content) || [];
      if (!rows.length) {
        box.innerHTML = stateHTML('empty', '暂无检查结果');
        el.querySelector('#qr-pager').innerHTML = '';
        return;
      }
      const passed = rows.filter(r => r.passed).length;
      box.innerHTML = `
        <div class="small text-2 mb-2">本页通过率:
          <span class="badge ${passed === rows.length ? 'badge-green' : 'badge-orange'}">${passed}/${rows.length} 通过</span>
        </div>
        <div class="table-wrap" style="border:none"><table class="tbl">
        <thead><tr>
          <th>ID</th><th>规则</th><th>检查类型</th><th>Run / 节点</th><th>期望值</th><th>实际值</th><th>结果</th><th>检查时间</th><th>详情</th>
        </tr></thead>
        <tbody>${rows.map(r => `<tr>
          <td class="mono">${r.id}</td>
          <td>${h(r.ruleName)}</td>
          <td><span class="badge badge-cyan">${h(enumLabel('checkType', r.checkType))}</span></td>
          <td class="small"><a href="#/runs/${r.runId}">#${r.runId}</a> <span class="text-3">${h(r.nodeName || '')}</span></td>
          <td class="mono small">${h(r.expected || '—')}</td>
          <td class="mono small">${h(r.actual || '—')}</td>
          <td>${r.passed ? '<span class="badge badge-green">✓ 通过</span>' : '<span class="badge badge-red">✗ 未通过</span>'}</td>
          <td class="small text-2 nowrap">${fmtTime(r.checkedAt)}</td>
          <td class="small text-2" style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${h(r.detail || '')}">${h(r.detail || '—')}</td>
        </tr>`).join('')}</tbody></table></div>`;
      const pagerEl = el.querySelector('#qr-pager');
      pagerEl.innerHTML = pagerHTML(data.page, data.totalPages, data.totalElements);
      bindPager(pagerEl, data.page, (p) => { this.state.page = p; this.loadResults(el); });
    } catch (e) {
      box.innerHTML = stateHTML('error', e.message);
    }
  },

  editModal(rule) {
    const isEdit = !!rule;
    const r = rule || { checkType: 'NULL_CHECK', severity: 'WARN', enabled: true };
    const body = `
      <div class="form-2col">
        ${formRow('name', '规则名', `<input class="input" name="name" value="${h(r.name || '')}" placeholder="如:用户邮箱非空">`, true)}
        ${formRow('targetTable', '目标表', `<input class="input mono" name="targetTable" value="${h(r.targetTable || '')}" placeholder="如:dwd_users">`, true)}
        ${formRow('checkType', '检查类型', `<select class="select" name="checkType">${optionsHTML(Object.keys(ENUM_LABEL.checkType), r.checkType, 'checkType')}</select>`, true)}
        ${formRow('field', '字段', `<input class="input mono" name="field" value="${h(r.field || '')}" placeholder="如:email(行数检查可留空)">`)}
        ${formRow('severity', '严重级别', `<select class="select" name="severity">${optionsHTML(Object.keys(ENUM_LABEL.severity), r.severity, 'severity')}</select>`)}
        ${formRow('enabled', '是否启用', `<select class="select" name="enabled">
            <option value="true"${r.enabled !== false ? ' selected' : ''}>启用</option>
            <option value="false"${r.enabled === false ? ' selected' : ''}>停用</option></select>`)}
      </div>
      ${formRow('expression', '表达式', `<input class="input mono" name="expression" value="${h(r.expression || '')}" placeholder="如:0-150 / ^[a-z]+@[a-z]+\\.[a-z]+$ / >=1000">`)}
      ${formRow('description', '描述', `<textarea class="textarea" name="description">${h(r.description || '')}</textarea>`)}`;
    openModal({
      title: isEdit ? '编辑质量规则' : '新增质量规则',
      size: 'lg',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">保存</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = async () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '规则名', required: true },
            { name: 'targetTable', label: '目标表', required: true },
            { name: 'checkType', label: '检查类型', required: true },
            { name: 'field', label: '字段' },
            { name: 'severity', label: '严重级别' },
            { name: 'enabled', label: '是否启用' },
            { name: 'expression', label: '表达式' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          v.enabled = v.enabled === 'true';
          const res = isEdit
            ? await apiTry('/api/quality/rules/' + r.id, { method: 'PUT', body: v })
            : await apiTry('/api/quality/rules', { method: 'POST', body: v });
          if (res !== undefined) { close(); toast(isEdit ? '规则已更新' : '规则已创建', 'success'); this.refreshAll(); }
        };
      }
    });
  },

  async remove(rule) {
    const ok = await confirmBox(`确定删除质量规则「${rule.name}」吗?`, { danger: true, okText: '删除' });
    if (!ok) return;
    const r = await apiTry('/api/quality/rules/' + rule.id, { method: 'DELETE' });
    if (r !== undefined) { toast('规则已删除', 'success'); this.refreshAll(); }
  },

  refreshAll() {
    const el = document.querySelector('#page-content');
    if (el && el.querySelector('#qr-rules')) { this.loadRules(el); this.loadResults(el); }
  },

  destroy() {}
};
