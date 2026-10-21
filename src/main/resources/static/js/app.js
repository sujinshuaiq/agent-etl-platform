/* ============================================================
   DataForge 应用入口:侧边栏导航、路由注册、顶部栏时钟与告警徽标
   ============================================================ */

const App = {
  _clockTimer: null,
  _badgeTimer: null,

  NAV: [
    { hash: '#/dashboard',   route: '/dashboard',   icon: '📊', label: '总览' },
    { hash: '#/ai',          route: '/ai',          icon: '✨', label: 'AI 助手' },
    { hash: '#/agents',      route: '/agents',      icon: '🤖', label: 'Agent 管理' },
    { hash: '#/datasources', route: '/datasources', icon: '🗄️', label: '数据源' },
    { hash: '#/pipelines',   route: '/pipelines',   icon: '🔀', label: '管道编排' },
    { hash: '#/runs',        route: '/runs',        icon: '▶️', label: '运行记录' },
    { hash: '#/quality',     route: '/quality',     icon: '✅', label: '数据质量' },
    { hash: '#/alerts',      route: '/alerts',      icon: '🔔', label: '告警中心' },
    { hash: '#/schedules',   route: '/schedules',   icon: '⏰', label: '调度管理' }
  ],

  init() {
    // 侧边栏
    document.getElementById('nav').innerHTML = App.NAV.map(n =>
      `<div class="nav-item" data-route="${n.route}" onclick="location.hash='${n.hash}'">
        <span class="nav-icon">${n.icon}</span><span>${n.label}</span>
      </div>`).join('');

    // 路由注册(顺序即匹配优先级,带参数的路由放后)
    Router.register('/dashboard', Pages.dashboard);
    Router.register('/ai', Pages.ai);
    Router.register('/agents', Pages.agents);
    Router.register('/datasources', Pages.datasources);
    Router.register('/pipelines', Pages.pipelines);
    Router.register('/pipelines/:id', Pages.pipelineDetail);
    Router.register('/runs', Pages.runs);
    Router.register('/runs/:id', Pages.runDetail);
    Router.register('/quality', Pages.quality);
    Router.register('/alerts', Pages.alerts);
    Router.register('/schedules', Pages.schedules);

    // 时钟
    const tick = () => {
      const el = document.getElementById('clock');
      if (el) el.textContent = new Date().toLocaleString('zh-CN', { hour12: false });
    };
    tick();
    App._clockTimer = setInterval(tick, 1000);

    // 未读告警徽标(15s 轮询)
    App.refreshAlertBadge();
    App._badgeTimer = setInterval(() => App.refreshAlertBadge(), 15000);

    Router.start();
  },

  setAlertBadge(count) {
    const b = document.getElementById('alert-badge');
    if (!b) return;
    const n = Number(count) || 0;
    b.textContent = n > 99 ? '99+' : String(n);
    b.classList.toggle('hidden', n <= 0);
  },

  async refreshAlertBadge() {
    try {
      const d = await api('/api/alerts', { params: { read: false, page: 0, size: 1 } });
      App.setAlertBadge(d ? d.totalElements : 0);
    } catch (e) { /* 后端未就绪时静默 */ }
  }
};

document.addEventListener('DOMContentLoaded', App.init);
