/* ============================================================
   DataForge 图表封装:基于本地 ECharts 5.5.1(vendor/echarts.min.js)
   ============================================================ */

const Charts = {
  _instances: [],

  _base() {
    return {
      backgroundColor: 'transparent',
      textStyle: { color: '#97a3b6', fontSize: 12 },
      tooltip: {
        backgroundColor: '#1b2230',
        borderColor: '#303a4a',
        textStyle: { color: '#dbe2ec', fontSize: 12 }
      }
    };
  },

  create(el, option) {
    if (!window.echarts) { el.innerHTML = '<div class="state-box">ECharts 未加载</div>'; return null; }
    const c = echarts.init(el, null, { renderer: 'canvas' });
    c.setOption(option);
    this._instances.push(c);
    return c;
  },

  disposeAll() {
    this._instances.forEach(c => { try { c.dispose(); } catch (e) {} });
    this._instances = [];
  },

  resizeAll() {
    this._instances.forEach(c => { try { c.resize(); } catch (e) {} });
  },

  /* 14 天运行趋势折线:data: [{date, success, failed, total, rowsProcessed}] */
  trendLine(el, data) {
    const dates = (data || []).map(d => d.date);
    const option = Object.assign(this._base(), {
      legend: { data: ['成功', '失败', '总数'], textStyle: { color: '#97a3b6' }, top: 0 },
      grid: { left: 44, right: 20, top: 34, bottom: 28 },
      xAxis: {
        type: 'category', data: dates,
        axisLine: { lineStyle: { color: '#303a4a' } },
        axisLabel: { color: '#67718a', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: '#1e2634' } },
        axisLabel: { color: '#67718a' }
      },
      series: [
        { name: '成功', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
          data: (data || []).map(d => d.success), lineStyle: { color: '#2fd08c', width: 2 },
          itemStyle: { color: '#2fd08c' }, areaStyle: { color: 'rgba(47,208,140,0.08)' } },
        { name: '失败', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
          data: (data || []).map(d => d.failed), lineStyle: { color: '#f25555', width: 2 },
          itemStyle: { color: '#f25555' }, areaStyle: { color: 'rgba(242,85,85,0.08)' } },
        { name: '总数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
          data: (data || []).map(d => d.total), lineStyle: { color: '#4f8cff', width: 2 },
          itemStyle: { color: '#4f8cff' } }
      ]
    });
    return this.create(el, option);
  },

  /* 运行状态分布饼图:data: {SUCCESS:n, FAILED:n, ...} */
  statusPie(el, dist) {
    const colorMap = {
      SUCCESS: '#2fd08c', FAILED: '#f25555', RUNNING: '#4f8cff',
      PENDING: '#7a8499', CANCELED: '#f5a623', PARTIAL_SUCCESS: '#f5a623', SKIPPED: '#7a8499'
    };
    const data = Object.entries(dist || {})
      .filter(([, v]) => v > 0)
      .map(([k, v]) => ({ name: enumLabel('runStatus', k), value: v, itemStyle: { color: colorMap[k] || '#7a8499' } }));
    const option = Object.assign(this._base(), {
      legend: { orient: 'vertical', right: 6, top: 'middle', textStyle: { color: '#97a3b6', fontSize: 12 } },
      series: [{
        type: 'pie', radius: ['46%', '70%'], center: ['38%', '50%'],
        avoidLabelOverlap: true,
        label: { color: '#97a3b6', fontSize: 11, formatter: '{b}\n{c}' },
        labelLine: { lineStyle: { color: '#303a4a' } },
        itemStyle: { borderColor: '#0d1117', borderWidth: 2 },
        data
      }]
    });
    return this.create(el, option);
  },

  /* Agent 负载柱图:data: [{agentId, agentName, type, runningTasks, totalExecuted, successRate, avgDurationMs}] */
  agentBar(el, data) {
    const names = (data || []).map(d => d.agentName);
    const option = Object.assign(this._base(), {
      legend: { data: ['运行中任务', '累计执行'], textStyle: { color: '#97a3b6' }, top: 0 },
      grid: { left: 44, right: 20, top: 34, bottom: 40 },
      xAxis: {
        type: 'category', data: names,
        axisLine: { lineStyle: { color: '#303a4a' } },
        axisLabel: { color: '#67718a', fontSize: 10.5, rotate: names.length > 6 ? 24 : 0, interval: 0 }
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: '#1e2634' } },
        axisLabel: { color: '#67718a' }
      },
      series: [
        { name: '运行中任务', type: 'bar', barMaxWidth: 22,
          data: (data || []).map(d => d.runningTasks),
          itemStyle: { color: '#21c8d7', borderRadius: [4, 4, 0, 0] } },
        { name: '累计执行', type: 'bar', barMaxWidth: 22,
          data: (data || []).map(d => d.totalExecuted),
          itemStyle: { color: '#4f8cff', borderRadius: [4, 4, 0, 0] } }
      ]
    });
    return this.create(el, option);
  },

  /* 质量通过率仪表盘:value 0-100 或 0-1 */
  passGauge(el, value) {
    let v = Number(value);
    if (isNaN(v)) v = 0;
    if (v <= 1) v = v * 100;
    v = Math.max(0, Math.min(100, v));
    const color = v >= 95 ? '#2fd08c' : v >= 80 ? '#4f8cff' : v >= 60 ? '#f5a623' : '#f25555';
    const option = Object.assign(this._base(), {
      series: [{
        type: 'gauge', startAngle: 210, endAngle: -30,
        min: 0, max: 100,
        progress: { show: true, width: 12, itemStyle: { color } },
        axisLine: { lineStyle: { width: 12, color: [[1, '#1e2634']] } },
        axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
        pointer: { show: false }, anchor: { show: false },
        title: { show: true, offsetCenter: [0, '32%'], color: '#67718a', fontSize: 12 },
        detail: {
          valueAnimation: true, offsetCenter: [0, '-4%'],
          formatter: '{value}%', color, fontSize: 30, fontWeight: 700
        },
        data: [{ value: Number(v.toFixed(1)), name: '质量通过率' }]
      }]
    });
    return this.create(el, option);
  }
};

window.addEventListener('resize', () => Charts.resizeAll());
