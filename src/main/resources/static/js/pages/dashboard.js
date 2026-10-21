/* ============================================================
   页面 1:总览 Dashboard(#/dashboard)
   统计卡片 + 14 天趋势 + 状态分布 + Agent 负载 + 管道排行 + 最近运行 + 质量仪表
   15s 自动刷新
   ============================================================ */
window.Pages = window.Pages || {};

Pages.dashboard = {
  title: '总览',
  _timer: null,

  render(el) {
    el.innerHTML = `
      <div id="db-stats" class="grid grid-6"></div>
      <div class="grid grid-3 mt-3">
        <div class="card" style="grid-column: span 2;">
          <div class="card-title">近 14 天运行趋势 <span class="sub">成功 / 失败 / 总数</span></div>
          <div id="db-trend" class="chart"></div>
        </div>
        <div class="card">
          <div class="card-title">运行状态分布</div>
          <div id="db-pie" class="chart"></div>
        </div>
      </div>
      <div class="grid grid-3 mt-3">
        <div class="card" style="grid-column: span 2;">
          <div class="card-title">Agent 负载 <span class="sub">运行中任务 / 累计执行</span></div>
          <div id="db-agent-bar" class="chart"></div>
        </div>
        <div class="card">
          <div class="card-title">数据质量通过率</div>
          <div id="db-gauge" class="chart chart-sm"></div>
          <div id="db-quality-text" class="small text-2" style="text-align:center;margin-top:4px"></div>
        </div>
      </div>
      <div class="grid grid-2 mt-3">
        <div class="card">
          <div class="card-title">管道运行排行 <span class="sub">按运行次数 Top 5</span></div>
          <div id="db-rank"></div>
        </div>
        <div class="card">
          <div class="card-title">最近运行 <span class="sub"><a href="#/runs">查看全部 →</a></span></div>
          <div id="db-recent"></div>
        </div>
      </div>`;
    this.load(el);
    this._timer = setInterval(() => this.load(el, true), 15000);
  },

  async load(el, silent) {
    const statsEl = el.querySelector('#db-stats');
    if (!statsEl) return; // 已离开页面

    // 统计卡片
    const ov = await apiTry('/api/dashboard/overview', {}, true);
    if (ov) {
      const cards = [
        { label: 'Agent 总数', value: ov.agentTotal, color: '#4f8cff', extra: `在线 ${fmtNum(ov.agentOnline)}` },
        { label: '数据源', value: ov.datasourceTotal, color: '#21c8d7', extra: '已接入数据源' },
        { label: '管道总数', value: ov.pipelineTotal, color: '#9a6bff', extra: `已启用 ${fmtNum(ov.pipelineEnabled)}` },
        { label: '运行总数', value: ov.runTotal, color: '#2fd08c', extra: `运行中 ${fmtNum(ov.runRunning)}` },
        { label: '今日成功 / 失败', value: `${fmtNum(ov.runSuccessToday)} / ${fmtNum(ov.runFailedToday)}`, color: '#f5a623', extra: `今日处理行数 ${fmtNum(ov.rowsProcessedToday)}` },
        { label: '7 日成功率', value: fmtPercent(ov.successRate7d), color: '#2fd08c', extra: `平均耗时 ${fmtDuration(ov.avgDurationMs7d)}` }
      ];
      statsEl.innerHTML = cards.map(c => `
        <div class="stat-card" style="--stat-color:${c.color}">
          <div class="stat-label">${c.label}</div>
          <div class="stat-value">${c.value === undefined || c.value === null ? '—' : c.value}</div>
          <div class="stat-extra">${c.extra || ''}</div>
        </div>`).join('');
      // 未读告警徽标
      if (typeof App !== 'undefined' && App.setAlertBadge) App.setAlertBadge(ov.alertUnread);
    } else if (!silent) {
      statsEl.innerHTML = stateHTML('error', '总览数据加载失败');
    }

    // 图表(并行加载)
    const [trend, dist, agentLoad, quality] = await Promise.all([
      apiTry('/api/dashboard/run-trend', { params: { days: 14 } }, true),
      apiTry('/api/dashboard/status-distribution', {}, true),
      apiTry('/api/dashboard/agent-load', {}, true),
      apiTry('/api/dashboard/quality-summary', {}, true)
    ]);
    if (!el.querySelector('#db-trend')) return;
    const trendEl = el.querySelector('#db-trend');
    trendEl.innerHTML = ''; Charts.disposeAll();
    if (trend && trend.length) Charts.trendLine(trendEl, trend);
    else trendEl.innerHTML = stateHTML('empty', '暂无趋势数据');
    if (dist && Object.keys(dist).length) Charts.statusPie(el.querySelector('#db-pie'), dist);
    else el.querySelector('#db-pie').innerHTML = stateHTML('empty', '暂无数据');
    if (agentLoad && agentLoad.length) Charts.agentBar(el.querySelector('#db-agent-bar'), agentLoad);
    else el.querySelector('#db-agent-bar').innerHTML = stateHTML('empty', '暂无 Agent 数据');
    if (quality) {
      Charts.passGauge(el.querySelector('#db-gauge'), quality.passRate);
      el.querySelector('#db-quality-text').textContent =
        `共 ${fmtNum(quality.totalChecks)} 次检查 · 通过 ${fmtNum(quality.passed)} · 未通过 ${fmtNum(quality.failed)}`;
    }

    // 管道排行
    const rankEl = el.querySelector('#db-rank');
    const rank = await apiTry('/api/dashboard/pipeline-rank', { params: { limit: 5 } }, true);
    if (rank && rank.length) {
      rankEl.innerHTML = `<table class="tbl"><thead><tr>
        <th>#</th><th>管道</th><th>运行次数</th><th>成功率</th><th>平均行数</th></tr></thead>
        <tbody>${rank.map((r, i) => `<tr class="clickable" onclick="location.hash='#/pipelines/${r.pipelineId}'">
          <td class="text-3">${i + 1}</td>
          <td>${h(r.pipelineName)}</td>
          <td>${fmtNum(r.runCount)}</td>
          <td>${fmtPercent(r.successRate)}</td>
          <td>${fmtNum(r.avgRows)}</td>
        </tr>`).join('')}</tbody></table>`;
    } else rankEl.innerHTML = stateHTML('empty', '暂无排行数据');

    // 最近运行
    const recentEl = el.querySelector('#db-recent');
    const recent = await apiTry('/api/dashboard/recent-runs', { params: { limit: 10 } }, true);
    if (recent && recent.length) {
      recentEl.innerHTML = `<table class="tbl"><thead><tr>
        <th>ID</th><th>管道</th><th>状态</th><th>触发</th><th>开始时间</th><th>耗时</th></tr></thead>
        <tbody>${recent.map(r => `<tr class="clickable" onclick="location.hash='#/runs/${r.id}'">
          <td class="mono">#${r.id}</td>
          <td>${h(r.pipelineName)}</td>
          <td>${badge('runStatus', r.status)}</td>
          <td class="text-2">${h(enumLabel('triggerType', r.triggerType))}</td>
          <td class="text-2 nowrap">${fmtTime(r.startTime)}</td>
          <td class="text-2 nowrap">${fmtDuration(r.durationMs)}</td>
        </tr>`).join('')}</tbody></table>`;
    } else recentEl.innerHTML = stateHTML('empty', '暂无运行记录');
  },

  destroy() {
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
  }
};
