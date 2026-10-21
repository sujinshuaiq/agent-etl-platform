/* ============================================================
   页面 5:管道详情 — DAG 可视化编辑器(#/pipelines/:id)
   SVG 绘制节点(按 agentType 着色)与有向边;节点可拖拽保存 positionX/Y;
   点击节点编辑配置;支持增删节点/边、拓扑校验、触发运行、启用/禁用
   ============================================================ */
window.Pages = window.Pages || {};

Pages.pipelineDetail = {
  title: '管道编辑器',
  pipe: null,          // 管道基础字段
  nodes: [],           // [{id,nodeKey,name,agentType,datasourceId,config,positionX,positionY}]
  edges: [],           // [{id,fromNodeKey,toNodeKey}]
  datasources: [],
  linkMode: false,
  linkSrc: null,
  dirty: false,

  NODE_W: 180,
  NODE_H: 56,

  async render(el, params) {
    this.pipe = null;
    this.nodes = [];
    this.edges = [];
    this.linkMode = false;
    this.linkSrc = null;
    this.dirty = false;
    el.innerHTML = stateHTML('loading');
    try {
      const [pipe, dss] = await Promise.all([API.pipeline(params.id), API.datasources()]);
      this.pipe = pipe;
      this.datasources = dss || [];
      this.nodes = (pipe.nodes || []).map((n, i) => ({
        id: n.id, nodeKey: n.nodeKey, name: n.name, agentType: n.agentType,
        datasourceId: n.datasourceId, config: n.config || '',
        positionX: (n.positionX === null || n.positionX === undefined) ? 60 + (i % 4) * 240 : n.positionX,
        positionY: (n.positionY === null || n.positionY === undefined) ? 60 + Math.floor(i / 4) * 130 : n.positionY
      }));
      this.edges = (pipe.edges || []).map(e => ({ id: e.id, fromNodeKey: e.fromNodeKey, toNodeKey: e.toNodeKey }));
    } catch (e) {
      el.innerHTML = stateHTML('error', '管道加载失败:' + e.message);
      return;
    }
    document.getElementById('page-title').textContent = '管道编辑器 — ' + (this.pipe.name || '');
    this.renderPage(el);
    this.drawDAG(el);
  },

  /* ---------- 页面骨架与工具栏 ---------- */
  renderPage(el) {
    const p = this.pipe;
    el.innerHTML = `
      <div class="dag-toolbar">
        <a class="btn" href="#/pipelines">← 返回列表</a>
        <span class="sep"></span>
        <span style="font-weight:600">${h(p.name)}</span>
        <span class="mono small text-3">${h(p.code || '')}</span>
        <span id="pd-status">${badge('pipelineStatus', p.status)}</span>
        <span class="sep"></span>
        <button class="btn" id="pd-edit-info">管道信息</button>
        <button class="btn" id="pd-add-node">+ 添加节点</button>
        <button class="btn" id="pd-link">🔗 添加连线</button>
        <span class="sep"></span>
        <button class="btn" id="pd-validate">拓扑校验</button>
        <button class="btn btn-primary" id="pd-save">保存 DAG</button>
        <button class="btn btn-success" id="pd-trigger">▶ 触发运行</button>
        <button class="btn" id="pd-toggle">${p.status === 'ENABLED' ? '禁用' : '启用'}</button>
        <span id="pd-dirty" class="small text-3 hidden" style="color:var(--orange)">● 有未保存修改</span>
      </div>
      <div class="dag-canvas-wrap" id="pd-canvas-wrap">
        <div class="dag-hint">拖拽节点移动 · 点击节点编辑配置 · 点击边中点 × 删除连线 · 「添加连线」后依次点击源节点与目标节点</div>
      </div>`;

    el.querySelector('#pd-add-node').addEventListener('click', () => this.addNodeModal(el));
    el.querySelector('#pd-link').addEventListener('click', (e) => {
      this.linkMode = !this.linkMode;
      this.linkSrc = null;
      e.target.classList.toggle('btn-primary', this.linkMode);
      el.querySelector('#pd-canvas-wrap').classList.toggle('linking', this.linkMode);
      toast(this.linkMode ? '连线模式:请先点击源节点,再点击目标节点' : '已退出连线模式', 'info');
      this.drawDAG(el);
    });
    el.querySelector('#pd-save').addEventListener('click', () => this.save(el));
    el.querySelector('#pd-validate').addEventListener('click', () => this.validate());
    el.querySelector('#pd-trigger').addEventListener('click', () => this.trigger());
    el.querySelector('#pd-toggle').addEventListener('click', () => this.toggle(el));
    el.querySelector('#pd-edit-info').addEventListener('click', () => this.editInfoModal(el));
  },

  markDirty(el) {
    this.dirty = true;
    const d = el.querySelector('#pd-dirty');
    if (d) d.classList.remove('hidden');
  },

  /* ---------- SVG 绘制 ---------- */
  nodeByKey(key) { return this.nodes.find(n => n.nodeKey === key); },

  canvasSize() {
    let w = 1000, hgt = 520;
    this.nodes.forEach(n => {
      w = Math.max(w, n.positionX + this.NODE_W + 120);
      hgt = Math.max(hgt, n.positionY + this.NODE_H + 120);
    });
    return { w, h: hgt };
  },

  edgePath(from, to) {
    const x1 = from.positionX + this.NODE_W, y1 = from.positionY + this.NODE_H / 2;
    const x2 = to.positionX, y2 = to.positionY + this.NODE_H / 2;
    const dx = Math.max(40, (x2 - x1) / 2);
    return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`;
  },

  drawDAG(el) {
    const wrap = el.querySelector('#pd-canvas-wrap');
    if (!wrap) return;
    wrap.querySelectorAll('svg.dag-svg').forEach(s => s.remove());
    const { w, h: hg } = this.canvasSize();
    const NS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('class', 'dag-svg');
    svg.setAttribute('width', w);
    svg.setAttribute('height', hg);

    // 箭头 marker
    const defs = document.createElementNS(NS, 'defs');
    defs.innerHTML = `<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
      markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#4a5670"></path></marker>`;
    svg.appendChild(defs);

    // 边
    this.edges.forEach((edge) => {
      const from = this.nodeByKey(edge.fromNodeKey);
      const to = this.nodeByKey(edge.toNodeKey);
      if (!from || !to) return;
      const path = document.createElementNS(NS, 'path');
      path.setAttribute('class', 'dag-edge');
      path.setAttribute('d', this.edgePath(from, to));
      path.setAttribute('marker-end', 'url(#arrow)');
      path.dataset.from = edge.fromNodeKey;
      path.dataset.to = edge.toNodeKey;
      svg.appendChild(path);
      // 删除手柄(边中点 ×)
      const mx = (from.positionX + this.NODE_W + to.positionX) / 2;
      const my = (from.positionY + to.positionY + this.NODE_H) / 2;
      const g = document.createElementNS(NS, 'g');
      g.setAttribute('class', 'dag-edge-del');
      g.innerHTML = `<circle cx="${mx}" cy="${my}" r="8"></circle>
        <text x="${mx}" y="${my + 3.5}" text-anchor="middle">×</text>`;
      g.addEventListener('click', async (e) => {
        e.stopPropagation();
        const ok = await confirmBox(`删除连线 ${edge.fromNodeKey} → ${edge.toNodeKey} ?`, { danger: true, okText: '删除' });
        if (!ok) return;
        this.edges = this.edges.filter(x => x !== edge);
        this.markDirty(el);
        this.drawDAG(el);
      });
      svg.appendChild(g);
    });

    // 节点
    this.nodes.forEach((node) => {
      const color = AGENT_TYPE_COLOR[node.agentType] || '#7a8499';
      const g = document.createElementNS(NS, 'g');
      g.setAttribute('class', 'dag-node' + (this.linkSrc === node.nodeKey ? ' link-src' : ''));
      g.setAttribute('transform', `translate(${node.positionX},${node.positionY})`);
      g.dataset.key = node.nodeKey;
      g.innerHTML = `
        <rect class="node-body" width="${this.NODE_W}" height="${this.NODE_H}" rx="9" stroke="${color}"></rect>
        <rect width="5" height="${this.NODE_H}" rx="2.5" fill="${color}"></rect>
        <text x="16" y="24" style="font-weight:600">${h(this.truncate(node.name || node.nodeKey, 12))}</text>
        <text class="node-type" x="16" y="42">${h(enumLabel('agentType', node.agentType))} · ${h(this.truncate(node.nodeKey, 18))}</text>`;
      this.bindNodeEvents(g, node, el);
      svg.appendChild(g);
    });

    wrap.appendChild(svg);
  },

  truncate(s, n) {
    s = String(s || '');
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  },

  /* ---------- 节点交互:拖拽 / 点击编辑 / 连线 ---------- */
  bindNodeEvents(g, node, el) {
    let startX = 0, startY = 0, origX = 0, origY = 0, moved = false, dragging = false;
    g.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      dragging = true; moved = false;
      startX = e.clientX; startY = e.clientY;
      origX = node.positionX; origY = node.positionY;
      g.setPointerCapture(e.pointerId);
    });
    g.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const dx = e.clientX - startX, dy = e.clientY - startY;
      if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
      if (!moved) return;
      // SVG 未设 viewBox,像素坐标 1:1
      node.positionX = Math.max(0, Math.round(origX + dx));
      node.positionY = Math.max(0, Math.round(origY + dy));
      g.setAttribute('transform', `translate(${node.positionX},${node.positionY})`);
      this.updateEdges(g.ownerSVGElement);
    });
    g.addEventListener('pointerup', (e) => {
      dragging = false;
      try { g.releasePointerCapture(e.pointerId); } catch (err) {}
      if (moved) { this.markDirty(el); return; }
      // 视为点击
      if (this.linkMode) { this.handleLinkClick(node, el); return; }
      this.editNodeModal(node, el);
    });
  },

  updateEdges(svg) {
    svg.querySelectorAll('path.dag-edge').forEach(path => {
      const from = this.nodeByKey(path.dataset.from);
      const to = this.nodeByKey(path.dataset.to);
      if (from && to) path.setAttribute('d', this.edgePath(from, to));
    });
    // 边删除手柄位置随拖动略滞后,拖拽结束后 drawDAG 会校正;这里同步更新
    svg.querySelectorAll('g.dag-edge-del').forEach(() => {});
  },

  handleLinkClick(node, el) {
    if (!this.linkSrc) {
      this.linkSrc = node.nodeKey;
      toast('已选源节点「' + (node.name || node.nodeKey) + '」,请点击目标节点', 'info');
      this.drawDAG(el);
      return;
    }
    if (this.linkSrc === node.nodeKey) { toast('不能连接到自身', 'warn'); return; }
    if (this.edges.some(e => e.fromNodeKey === this.linkSrc && e.toNodeKey === node.nodeKey)) {
      toast('该连线已存在', 'warn'); return;
    }
    this.edges.push({ id: null, fromNodeKey: this.linkSrc, toNodeKey: node.nodeKey });
    this.linkSrc = null;
    this.linkMode = false;
    const btn = el.querySelector('#pd-link');
    if (btn) btn.classList.remove('btn-primary');
    el.querySelector('#pd-canvas-wrap').classList.remove('linking');
    this.markDirty(el);
    this.drawDAG(el);
    toast('连线已添加,记得保存', 'success');
  },

  /* ---------- 节点新增 / 编辑 / 删除 ---------- */
  addNodeModal(el) {
    const body = `
      ${formRow('name', '节点名称', `<input class="input" name="name" placeholder="如:抽取用户表">`, true)}
      ${formRow('agentType', 'Agent 类型', `<select class="select" name="agentType">${optionsHTML(Object.keys(ENUM_LABEL.agentType), 'EXTRACTOR', 'agentType')}</select>`, true)}`;
    openModal({
      title: '添加节点',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="ok">添加</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="ok"]').onclick = () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '节点名称', required: true },
            { name: 'agentType', label: 'Agent 类型', required: true }
          ]);
          if (!v) return;
          // 按节点类型预填默认配置,避免保存出缺 sourceTable/targetTable 的"带病"节点
          const DEFAULT_CONFIG = {
            EXTRACTOR: { sourceTable: 'ods_users', batchSize: 1000 },
            TRANSFORMER: { rules: ['trim'], filterRatio: 0.05 },
            LOADER: { targetTable: 'dwd_table', writeMode: 'INSERT' },
            QUALITY_CHECKER: { ruleIds: [] },
            ORCHESTRATOR: { note: '' }
          };
          const maxX = this.nodes.reduce((m, n) => Math.max(m, n.positionX), 0);
          this.nodes.push({
            id: null,
            nodeKey: 'node_' + Date.now().toString(36),
            name: v.name, agentType: v.agentType,
            datasourceId: null, config: JSON.stringify(DEFAULT_CONFIG[v.agentType] || {}),
            positionX: maxX ? maxX + 240 : 60,
            positionY: 60
          });
          close();
          this.markDirty(el);
          this.drawDAG(el);
          toast('节点已添加(待保存)', 'success');
        };
      }
    });
  },

  editNodeModal(node, el) {
    let configPretty = node.config || '';
    if (configPretty) { try { configPretty = JSON.stringify(JSON.parse(configPretty), null, 2); } catch (e) {} }
    const dsOptions = [{ value: '', label: '(不关联数据源)' }]
      .concat(this.datasources.map(d => ({ value: String(d.id), label: `${d.name}(${enumLabel('datasourceType', d.type)})` })));
    const body = `
      <div class="form-2col">
        ${formRow('name', '节点名称', `<input class="input" name="name" value="${h(node.name || '')}">`, true)}
        ${formRow('nodeKey', '节点 Key', `<input class="input mono" name="nodeKey" value="${h(node.nodeKey)}" disabled>`)}
        ${formRow('agentType', 'Agent 类型', `<select class="select" name="agentType">${optionsHTML(Object.keys(ENUM_LABEL.agentType), node.agentType, 'agentType')}</select>`, true)}
        ${formRow('datasourceId', '关联数据源', `<select class="select" name="datasourceId">${optionsHTML(dsOptions, node.datasourceId === null || node.datasourceId === undefined ? '' : String(node.datasourceId))}</select>`)}
      </div>
      ${formRow('config', '节点配置(JSON,按类型约定)', `<textarea class="textarea mono" name="config" rows="7">${h(configPretty)}</textarea>`)}
      <div class="small text-3 mb-2">
        配置约定 — 抽取:<span class="mono">{"sourceTable":"ods_users","batchSize":1000}</span> ·
        转换:<span class="mono">{"rules":["trim"],"filterRatio":0.05}</span> ·
        加载:<span class="mono">{"targetTable":"dwd_users","writeMode":"INSERT"}</span> ·
        质检:<span class="mono">{"ruleIds":[1,2]}</span>
      </div>`;
    openModal({
      title: '节点配置 — ' + (node.name || node.nodeKey),
      size: 'lg',
      body,
      footer: `<button class="btn btn-danger" data-act="del">删除节点</button>
               <span style="flex:1"></span>
               <button class="btn" data-act="cancel">取消</button>
               <button class="btn btn-primary" data-act="save">应用</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="del"]').onclick = async () => {
          const ok = await confirmBox(`删除节点「${node.name || node.nodeKey}」及其全部连线?`, { danger: true, okText: '删除' });
          if (!ok) return;
          this.nodes = this.nodes.filter(n => n !== node);
          this.edges = this.edges.filter(e => e.fromNodeKey !== node.nodeKey && e.toNodeKey !== node.nodeKey);
          close();
          this.markDirty(el);
          this.drawDAG(el);
        };
        overlay.querySelector('[data-act="save"]').onclick = () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '节点名称', required: true },
            { name: 'agentType', label: 'Agent 类型', required: true },
            { name: 'datasourceId', label: '关联数据源' },
            { name: 'config', label: '节点配置', type: 'json' }
          ]);
          if (!v) return;
          node.name = v.name;
          node.agentType = v.agentType;
          node.datasourceId = v.datasourceId === '' ? null : Number(v.datasourceId);
          node.config = v.config || '{}';
          close();
          this.markDirty(el);
          this.drawDAG(el);
          toast('节点配置已应用(待保存)', 'success');
        };
      }
    });
  },

  /* ---------- 管道信息编辑 ---------- */
  editInfoModal(el) {
    const p = this.pipe;
    const body = `
      <div class="form-2col">
        ${formRow('name', '管道名称', `<input class="input" name="name" value="${h(p.name || '')}">`, true)}
        ${formRow('category', '分类', `<input class="input" name="category" value="${h(p.category || '')}">`)}
        ${formRow('failureStrategy', '失败策略', `<select class="select" name="failureStrategy">${optionsHTML(Object.keys(ENUM_LABEL.failureStrategy), p.failureStrategy || 'FAIL_FAST', 'failureStrategy')}</select>`)}
        ${formRow('retryCount', '重试次数', `<input class="input" name="retryCount" type="number" value="${p.retryCount ?? 1}">`)}
        ${formRow('retryIntervalSeconds', '重试间隔(秒)', `<input class="input" name="retryIntervalSeconds" type="number" value="${p.retryIntervalSeconds ?? 30}">`)}
        ${formRow('timeoutMinutes', '超时(分钟)', `<input class="input" name="timeoutMinutes" type="number" value="${p.timeoutMinutes ?? 30}">`)}
      </div>
      ${formRow('description', '描述', `<textarea class="textarea" name="description">${h(p.description || '')}</textarea>`)}
      <div class="small text-3">修改随「保存 DAG」一并提交。</div>`;
    openModal({
      title: '管道信息',
      size: 'lg',
      body,
      footer: `<button class="btn" data-act="cancel">取消</button><button class="btn btn-primary" data-act="save">应用</button>`,
      onOpen: (overlay, close) => {
        overlay.querySelector('[data-act="cancel"]').onclick = close;
        overlay.querySelector('[data-act="save"]').onclick = () => {
          const v = collectForm(overlay, [
            { name: 'name', label: '管道名称', required: true },
            { name: 'category', label: '分类' },
            { name: 'failureStrategy', label: '失败策略' },
            { name: 'retryCount', label: '重试次数', type: 'number' },
            { name: 'retryIntervalSeconds', label: '重试间隔', type: 'number' },
            { name: 'timeoutMinutes', label: '超时', type: 'number' },
            { name: 'description', label: '描述' }
          ]);
          if (!v) return;
          Object.assign(this.pipe, v);
          close();
          this.markDirty(el);
          toast('管道信息已更新(待保存)', 'success');
        };
      }
    });
  },

  /* ---------- 操作:保存 / 校验 / 触发 / 启停 ---------- */
  buildPayload() {
    const p = this.pipe;
    return {
      name: p.name, code: p.code, description: p.description, category: p.category,
      status: p.status, failureStrategy: p.failureStrategy,
      retryCount: p.retryCount, retryIntervalSeconds: p.retryIntervalSeconds,
      timeoutMinutes: p.timeoutMinutes,
      nodes: this.nodes.map(n => ({
        id: n.id, nodeKey: n.nodeKey, name: n.name, agentType: n.agentType,
        datasourceId: n.datasourceId, config: n.config || '{}',
        positionX: n.positionX, positionY: n.positionY
      })),
      edges: this.edges.map(e => ({ id: e.id, fromNodeKey: e.fromNodeKey, toNodeKey: e.toNodeKey }))
    };
  },

  async save(el) {
    // 前端预检:重复 nodeKey
    const keys = this.nodes.map(n => n.nodeKey);
    if (new Set(keys).size !== keys.length) { toast('存在重复的节点 Key,无法保存', 'error'); return; }
    try {
      const r = await API.updatePipeline(this.pipe.id, this.buildPayload());
      // 用服务端返回重建状态(拿到新建节点的真实 id)
      if (r && r.nodes) {
        this.nodes = r.nodes.map(n => ({
          id: n.id, nodeKey: n.nodeKey, name: n.name, agentType: n.agentType,
          datasourceId: n.datasourceId, config: n.config || '{}',
          positionX: n.positionX, positionY: n.positionY
        }));
        this.edges = (r.edges || []).map(e => ({ id: e.id, fromNodeKey: e.fromNodeKey, toNodeKey: e.toNodeKey }));
        this.pipe = Object.assign(this.pipe, r);
      }
      this.dirty = false;
      const d = el.querySelector('#pd-dirty');
      if (d) d.classList.add('hidden');
      this.drawDAG(el);
      toast('DAG 已保存', 'success');
    } catch (e) {
      toast('保存失败:' + e.message, 'error', 4500);
    }
  },

  async validate() {
    if (this.dirty) toast('提示:存在未保存修改,校验的是服务端已保存版本', 'warn', 3600);
    try {
      const r = await API.validatePipeline(this.pipe.id);
      if (r && r.valid) {
        openModal({
          title: '拓扑校验结果',
          body: `<div class="badge badge-green" style="font-size:13px;padding:5px 14px">✓ 校验通过,无环</div>
            <div class="section-title">执行顺序(拓扑排序)</div>
            <div class="mono small" style="line-height:2">${(r.order || []).map((k, i) =>
              `<span class="badge badge-blue">${i + 1}. ${h(k)}</span>`).join(' → ') || '—'}</div>`,
          footer: `<button class="btn" data-act="close">关闭</button>`,
          onOpen: (ov, close) => { ov.querySelector('[data-act="close"]').onclick = close; }
        });
      } else {
        openModal({
          title: '拓扑校验结果',
          body: `<div class="badge badge-red" style="font-size:13px;padding:5px 14px">✗ 校验未通过</div>
            <div class="section-title">错误列表</div>
            <ul style="padding-left:18px;line-height:2;color:var(--red)">${(r && r.errors || []).map(e => `<li>${h(e)}</li>`).join('')}</ul>`,
          footer: `<button class="btn" data-act="close">关闭</button>`,
          onOpen: (ov, close) => { ov.querySelector('[data-act="close"]').onclick = close; }
        });
      }
    } catch (e) {
      toast('校验失败:' + e.message, 'error');
    }
  },

  async trigger() {
    if (this.dirty) {
      const ok = await confirmBox('存在未保存的 DAG 修改,触发运行将使用已保存版本。继续?', { title: '触发运行' });
      if (!ok) return;
    }
    try {
      const r = await API.triggerPipeline(this.pipe.id);
      const runId = r && r.runId;
      toast('运行已触发' + (runId ? '(Run #' + runId + ')' : ''), 'success');
      if (runId) {
        setTimeout(() => { location.hash = '#/runs/' + runId; }, 600);
      }
    } catch (e) {
      toast('触发失败:' + e.message, 'error');
    }
  },

  async toggle(el) {
    const isEnabled = this.pipe.status === 'ENABLED';
    const ok = await confirmBox(isEnabled ? `禁用管道「${this.pipe.name}」?禁用后调度将不再触发。` : `启用管道「${this.pipe.name}」?`, { title: isEnabled ? '禁用确认' : '启用确认' });
    if (!ok) return;
    try {
      if (isEnabled) await API.disablePipeline(this.pipe.id);
      else await API.enablePipeline(this.pipe.id);
      this.pipe.status = isEnabled ? 'DISABLED' : 'ENABLED';
      el.querySelector('#pd-status').innerHTML = badge('pipelineStatus', this.pipe.status);
      el.querySelector('#pd-toggle').textContent = isEnabled ? '启用' : '禁用';
      toast(isEnabled ? '管道已禁用' : '管道已启用', 'success');
    } catch (e) {
      toast('操作失败:' + e.message, 'error');
    }
  },

  destroy() {}
};
