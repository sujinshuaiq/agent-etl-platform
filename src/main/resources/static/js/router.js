/* ============================================================
   DataForge Hash 路由器
   ============================================================ */

const Router = {
  routes: [],
  currentPage: null,
  currentHash: '',

  register(pattern, page) {
    // pattern 形如 '/pipelines/:id'
    Router.routes.push({ pattern, page, segments: pattern.split('/').filter(Boolean) });
  },

  _match(hash) {
    const path = (hash || '#/dashboard').replace(/^#/, '') || '/dashboard';
    const parts = path.split('/').filter(Boolean);
    for (const r of Router.routes) {
      if (r.segments.length !== parts.length) continue;
      const params = {};
      let ok = true;
      for (let i = 0; i < r.segments.length; i++) {
        const seg = r.segments[i];
        if (seg.startsWith(':')) params[seg.slice(1)] = decodeURIComponent(parts[i]);
        else if (seg !== parts[i]) { ok = false; break; }
      }
      if (ok) return { page: r.page, params };
    }
    return null;
  },

  navigate(hash) { location.hash = hash; },

  reload() { Router._dispatch(location.hash, true); },

  _dispatch(hash, force) {
    if (hash === Router.currentHash && !force) return;
    Router.currentHash = hash;
    // 销毁上一页(清理定时器 / SSE / 图表)
    if (Router.currentPage && typeof Router.currentPage.destroy === 'function') {
      try { Router.currentPage.destroy(); } catch (e) { console.error('页面销毁失败', e); }
    }
    Charts.disposeAll();
    Router.currentPage = null;

    const m = Router._match(hash);
    const content = document.getElementById('page-content');
    const titleEl = document.getElementById('page-title');
    if (!m) {
      titleEl.textContent = '页面不存在';
      content.innerHTML = stateHTML('empty', '页面不存在:<span class="mono">' + h(hash) + '</span>');
      return;
    }
    Router.currentPage = m.page;
    titleEl.textContent = m.page.title || '';
    // 高亮侧边栏
    const top = '/' + ((hash.replace(/^#/, '').split('/').filter(Boolean))[0] || 'dashboard');
    document.querySelectorAll('#nav .nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.route === top);
    });
    try {
      m.page.render(content, m.params || {});
    } catch (e) {
      console.error('页面渲染失败', e);
      content.innerHTML = stateHTML('error', '页面渲染失败:' + e.message);
    }
  },

  start() {
    window.addEventListener('hashchange', () => Router._dispatch(location.hash));
    if (!location.hash) location.hash = '#/dashboard';
    Router._dispatch(location.hash);
  }
};
