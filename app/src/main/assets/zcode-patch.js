/* ZCode Remote 补丁脚本（BUILD-007 r2）
   官方 Bundle 原样加载；本脚本只做"隐藏官方标题区 + 注入 zp-* 界面 + 事件拦截"。
   修复：①smartHide 渐进爬升+尺寸检查；②移除对面工具栏；③z-index 保证可点击；④标题去工作区后缀 */
(function () {
  'use strict';
  if (window.__zcodePatch) return;

  var native = window.ZCodeNative || null;
  var S = {
    page: 'unknown', title: '', lastReported: '',
    sideOpen: false, sideTab: null, tabs: [], seq: 0,
    bypass: false, forkBtn: null, bootLogged: false, lastSessionId: ''
  };
  var ZP_LAST_SESS_KEY = 'zp-last-session';

  function log(m) { try { native && native.postLog('[zp] ' + m); } catch (e) {} }
  function toast(m) { try { native && native.toast(m); } catch (e) {} }
  function qs(s, r) { return (r || document).querySelector(s); }
  function qsa(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  function inOur(el) { while (el) { if (el.id && el.id.indexOf('zp-') === 0) return true; el = el.parentElement; } return false; }
  function hide(el) { if (el && !inOur(el)) el.setAttribute('data-zp-hide', '1'); }

  /* ── 智能隐藏：从文本节点逐级向上，找到第一个高度<30%视口的祖先就隐藏 ── */
  function smartHide(re) {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = w.nextNode())) {
      if (!n.nodeValue || !re.test(n.nodeValue)) continue;
      if (inOur(n)) continue;
      var el = n.parentElement;
      var maxH = window.innerHeight * 0.3;
      for (var i = 0; i < 8 && el; i++) {
        var r = el.getBoundingClientRect();
        if (r.height > 0) {
          if (r.height <= maxH) { hide(el); return true; }
          /* 太高，不隐藏，继续向上找更小的兄弟不行——直接跳过 */
          log('smartHide skip h=' + Math.round(r.height) + ' for /' + re.source + '/ at level ' + i);
          return false;
        }
        el = el.parentElement;
      }
    }
    return false;
  }

  /* ── 图标 ── */
  var I = {
    back: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.64-6.36L21 8"/><path d="M21 3v5h-5"/>',
    remote: '<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>',
    nav: '<path d="M3 5h8M3 12h13M3 19h8"/><circle cx="19" cy="5" r="1.5"/><circle cx="19" cy="19" r="1.5"/>',
    artifact: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="M3.3 7 12 12l8.7-5M12 22V12"/>',
    side: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/>',
    theme: '<circle cx="13.5" cy="6.5" r="1.2"/><circle cx="17.5" cy="10.5" r="1.2"/><circle cx="8.5" cy="7.5" r="1.2"/><circle cx="6.5" cy="12.5" r="1.2"/><path d="M12 2A10 10 0 0 0 2 12c0 5.5 4.5 9 9 9 1.2 0 2-.8 2-2 0-.6-.3-1-.6-1.4-.3-.4-.6-.9-.6-1.6a2 2 0 0 1 2-2h2.4A5.2 5.2 0 0 0 22 9c0-4-4.5-7-10-7Z"/>',
    menu: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
    collapse: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18M9 9l-2 3 2 3"/>',
    plus: '<path d="M5 12h14M12 5v14"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/>',
    folder: '<path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>',
    chup: '<path d="m18 15-6-6-6 6"/>',
    chdown: '<path d="m6 9 6 6 6-6"/>'
  };
  function svg(p, size) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" style="width:' + (size || 20) + 'px;height:' + (size || 20) + 'px;pointer-events:none">' + p + '</svg>';
  }

  /* ── Toast ── */
  var toastEl = null, toastT = null;
  function showToast(m) {
    if (!toastEl) { toastEl = document.createElement('div'); toastEl.id = 'zp-toast'; document.body.appendChild(toastEl); }
    toastEl.textContent = m; toastEl.classList.add('zp-show');
    clearTimeout(toastT); toastT = setTimeout(function () { toastEl.classList.remove('zp-show'); }, 1900);
  }

  /* ── 官方锚点 ── */
  /* ── 品牌行隐藏：先用精确选择器命中官方首页品牌行（首帧即藏），再退回文字遍历兜底 ── */
  function hideBrandRow() {
    var exact = qs('header.shrink-0.border-b.border-border.bg-header') || qs('header.bg-header');
    if (exact && !inOur(exact)) { hide(exact); return true; }
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = w.nextNode())) {
      if (!n.nodeValue || !/ZCode\s*远程控制/.test(n.nodeValue)) continue;
      if (inOur(n)) continue;
      var el = n.parentElement, maxH = window.innerHeight * 0.3;
      for (var i = 0; i < 8 && el && el !== document.body; i++) {
        var r = el.getBoundingClientRect();
        if (r.height > 0) {
          if (r.height > maxH) return false;
          /* 父级若不高且含 svg（同行的调色板图标），提升到父级整行隐藏 */
          var p = el.parentElement;
          if (p && p !== document.body && p.querySelector('svg')) {
            var pr = p.getBoundingClientRect();
            if (pr.height > 0 && pr.height <= maxH) el = p;
          }
          hide(el);
          return true;
        }
        el = el.parentElement;
      }
      return false;
    }
    return false;
  }

  function cardTitleFrom(t) {
    var el = t;
    for (var i = 0; i < 8 && el && el !== document.body; i++) {
      if (inOur(el)) return '';
      var line = ((el.innerText || '').trim().split('\n')[0] || '').trim();
      if (line.length >= 2 && line.length <= 60) return line;
      el = el.parentElement;
    }
    return '';
  }
  function officialBackBtn() {
    return qs('button[aria-label*="返回任务首页"]') || qs('button[aria-label*="任务首页"]');
  }
  function chatSection() { return qs('section[data-mobile-page="chat"]'); }

  /* ── 提取会话标题（去掉工作区后缀） ── */
  function chatTitleText(sec) {
    var back = officialBackBtn();
    if (!back) return '任务会话';
    var h1 = back.closest('div');
    if (!h1 || h1.parentElement !== sec) return '任务会话';
    var h2 = h1.nextElementSibling;
    if (!h2) return '任务会话';
    /* 优先找含最大文本量的非按钮子元素 */
    var kids = qsa('span,div,a,p', h2);
    var best = '';
    for (var i = 0; i < kids.length; i++) {
      if (inOur(kids[i])) continue;
      if (kids[i].closest('button')) continue;
      var t = (kids[i].textContent || '').trim();
      if (t.length > best.length && t.length < 200) best = t;
    }
    if (best) {
      /* 去掉末尾工作区名：只在工作区名是已知默认值（default / 当前工作区路径末段）时移除 */
      var wsDefaults = ['default', 'Default'];
      for (var w = 0; w < wsDefaults.length; w++) {
        if (best.length > wsDefaults[w].length && best.slice(-wsDefaults[w].length) === wsDefaults[w]) {
          best = best.slice(0, -wsDefaults[w].length);
          break;
        }
      }
      return best;
    }
    return '任务会话';
  }

  /* ── 注入 UI ── */
  function btn(id, icon, label) {
    return '<button class="zp-ibtn" id="' + id + '" aria-label="' + label + '">' + svg(I[icon]) + '</button>';
  }
  function toolBtn(act, icon, label) {
    return '<button class="zp-tbtn" data-zp-act="' + act + '">' + svg(I[icon], 19) + '<span>' + label + '</span></button>';
  }
  function buildChatBar() {
    var bar = document.createElement('div');
    bar.id = 'zp-root'; bar.setAttribute('data-zp-role', 'chatbar');
    bar.innerHTML =
      '<div class="zp-bar">' + btn('zp-back', 'back', '返回') +
      '<div class="zp-title" id="zp-title">' + S.title + '</div>' +
      btn('zp-menu', 'menu', '会话菜单') + '</div>' +
      '<div class="zp-tools">' + toolBtn('refresh', 'refresh', '刷新') + toolBtn('search', 'search', '搜索') +
      toolBtn('nav', 'nav', '导航') + toolBtn('artifact', 'artifact', '产物') + toolBtn('side', 'side', '侧栏') + '</div>';
    return bar;
  }
  function buildHomeBar() {
    var bar = document.createElement('div');
    bar.id = 'zp-root'; bar.setAttribute('data-zp-role', 'homebar');
    bar.innerHTML =
      '<div class="zp-bar"><div class="zp-brand"><b>ZCode远程控制</b><em>已连接到电脑智能体</em></div>' +
      btn('zp-gear', 'gear', '推送通知设置') + '</div>' +
      '<div class="zp-tools">' + toolBtn('hrefresh', 'refresh', '刷新') + toolBtn('hsearch', 'search', '搜索') + toolBtn('hnew', 'plus', '新建') + toolBtn('remote', 'remote', '远控') + toolBtn('theme', 'theme', '主题') + '</div>';
    return bar;
  }
  function removeBar(role) {
    var old = qs('#zp-root[data-zp-role="' + role + '"]');
    if (old) old.remove();
  }

  function pointerOpts(el) {
    var r = el.getBoundingClientRect();
    var x = r.left + Math.max(8, r.width / 2), y = r.top + Math.max(8, r.height / 2);
    return { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, buttons: 1, pointerId: 1, isPrimary: true, pointerType: 'touch' };
  }
  function firePointer(el) {
    if (!el) return;
    try {
      var opts = pointerOpts(el);
      el.dispatchEvent(new PointerEvent('pointerdown', opts));
      el.dispatchEvent(new MouseEvent('mousedown', opts));
      el.dispatchEvent(new PointerEvent('pointerup', opts));
      el.dispatchEvent(new MouseEvent('mouseup', opts));
    } catch (e) { }
  }
  function fireTap(el) {
    firePointer(el);
    try { el.click(); } catch (e) { }
  }
  function playPageSlide(mode) {
    try {
      /* 首页刷新等场景：不做任何滑走动画 */
      if (mode === 'none') return;
      var old = qs('#zp-slide-ghost'); if (old) old.remove();
      /* push（进会话）：会话窗口从右向左滑入 */
      if (mode === 'push') {
        var tries = 0;
        var tick = setInterval(function () {
          tries++;
          var sec = chatSection();
          if (sec) {
            clearInterval(tick);
            sec.style.setProperty('transition', 'none', 'important');
            sec.style.setProperty('transform', 'translateX(100%)', 'important');
            requestAnimationFrame(function () {
              requestAnimationFrame(function () {
                sec.style.setProperty('transition', 'transform .3s ease', 'important');
                sec.style.setProperty('transform', 'translateX(0)', 'important');
                setTimeout(function () {
                  sec.style.removeProperty('transition');
                  sec.style.removeProperty('transform');
                }, 320);
              });
            });
          } else if (tries > 30) {
            clearInterval(tick);
          }
        }, 16);
      }
      /* pop（返回首页）：先克隆当前会话做滑出层，再切首页。
         官方 popstate 会立刻拆掉会话节点，直接给原节点加动画会看不到。 */
      if (mode === 'pop') {
        var sec = chatSection();
        if (!sec) return;
        var ghost = document.createElement('div');
        ghost.id = 'zp-slide-ghost';
        var clone = sec.cloneNode(true);
        clone.style.height = '100%';
        ghost.appendChild(clone);
        document.body.appendChild(ghost);
        ghost.style.transform = 'translateX(0)';
        requestAnimationFrame(function () {
          ghost.style.transition = 'transform .3s ease';
          ghost.style.transform = 'translateX(100%)';
        });
        setTimeout(function () { if (ghost.parentNode) ghost.remove(); }, 360);
      }
    } catch (e) { }
  }
  function goHome(opts) {
    opts = opts || {};
    /* 官方监听了 popstate 事件切换页面：
       window.addEventListener('popstate', e => { let t = _4(e.state) ? 'chat' : 'home'; _(t); });
       直接派发带 home state 的 popstate 事件，触发官方 React 状态机切回首页。 */
    /* 先预埋首页栏（会话仍盖着，用户看不见插入），再滑出会话，避免返回后栏闪一下 */
    S.homeBarKeep = true;
    try { ensureHomeBarReady(); } catch (ePre) { }
    if (!opts.noAnim) playPageSlide('pop');
    try {
      window.dispatchEvent(new PopStateEvent('popstate', { state: { zcodeMobilePage: 'home' } }));
      log('dispatched popstate to home');
    } catch (e) {
      log('popstate dispatch error: ' + e.message);
    }
  }

  /* ── 右侧多标签侧栏 ── */
  var sideEl = null, bodyEl = null, tabsEl = null;
  var PANE = {
    nav: { title: '导航', ico: 'nav' },
    artifact: { title: '产物', ico: 'artifact' },
    file: { title: '文件', ico: 'file' }
  };
  function ensureSide() {
    if (sideEl && sideEl.isConnected) return;
    sideEl = document.createElement('div');
    sideEl.id = 'zp-side';
    sideEl.innerHTML =
      '<div class="zp-side-mask" id="zp-side-mask"></div>' +
      '<div class="zp-side-panel"><div class="zp-tabbar">' +
      '<button class="zp-collapse" id="zp-collapse">' + svg(I.collapse, 16) + '</button>' +
      '<div class="zp-tabs" id="zp-tabs"></div>' +
      '<button class="zp-add" id="zp-add">' + svg(I.plus, 15) + '</button>' +
      '</div><div class="zp-body" id="zp-body"></div></div>';
    document.body.appendChild(sideEl);
    tabsEl = qs('#zp-tabs', sideEl); bodyEl = qs('#zp-body', sideEl);
    qs('#zp-side-mask', sideEl).addEventListener('click', closeSide);
    qs('#zp-collapse', sideEl).addEventListener('click', closeSide);
    qs('#zp-add', sideEl).addEventListener('click', function () { toast('官方侧栏还支持终端/浏览器等标签'); });
  }
  function openSide(tab) {
    ensureSide();
    if (S.tabs.indexOf(tab) < 0) S.tabs.push(tab);
    S.sideTab = tab; S.sideOpen = true;
    sideEl.classList.add('zp-open');
    renderTabs(); renderBody();
  }
  function closeSide() {
    S.sideOpen = false;
    if (sideEl) sideEl.classList.remove('zp-open');
  }
  window.__zpActivateTab = function (t) { S.sideTab = t; renderTabs(); renderBody(); };
  window.__zpCloseTab = function (t) {
    S.tabs = S.tabs.filter(function (x) { return x !== t; });
    if (S.sideTab === t) S.sideTab = S.tabs[S.tabs.length - 1] || null;
    if (!S.tabs.length) closeSide();
    renderTabs(); renderBody();
  };
  function renderTabs() {
    tabsEl.innerHTML = S.tabs.map(function (t) {
      var p = PANE[t];
      return '<div class="zp-tab' + (t === S.sideTab ? ' zp-active' : '') + '" onclick="__zpActivateTab(\'' + t + '\')">' +
        '<span class="tico">' + svg(I[p.ico], 13) + '</span><span class="tlabel">' + p.title + '</span>' +
        '<span class="tclose" onclick="event.stopPropagation();__zpCloseTab(\'' + t + '\')">' + svg(I.x, 11) + '</span></div>';
    }).join('');
  }
  function esc(s) { return (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function renderBody() {
    if (!bodyEl) return;
    if (!S.sideTab) { bodyEl.innerHTML = '<div class="zp-empty">没有打开的标签</div>'; return; }
    bodyEl.innerHTML = S.sideTab === 'nav' ? renderNav() : renderArtifact();
    bindPane();
    /* 打开导航时定位到当前阅读位置：让选中项在侧栏内居中，避免只滚到边缘 */
    if (S.sideTab === 'nav') {
      var cur = qs('.zp-nav .zp-cur', bodyEl);
      if (cur) setTimeout(function () {
        try { scrollToEl(cur); } catch (e) { try { cur.scrollIntoView({ block: 'center' }); } catch (e2) { } }
      }, 60);
    }
  }
  /* ── 导航：时间戳（fiber 探测）与格式化 ── */
  function rowTimestamp(el) {
    try {
      var fk = Object.keys(el).find(function (k) { return k.indexOf('__reactFiber$') === 0; });
      if (!fk) return null;
      var f = el[fk], d = 0;
      while (f && d < 40) {
        var p = f.memoizedProps;
        if (p && typeof p === 'object') {
          var ks = Object.keys(p);
          for (var i = 0; i < ks.length; i++) {
            var v = p[ks[i]];
            if (!v || typeof v !== 'object' || Array.isArray(v)) continue;
            var cands = [v.timestamp, v.createdAt, v.createdTime, v.time, v.updatedAt];
            for (var j = 0; j < cands.length; j++) {
              var ts = cands[j];
              if (typeof ts === 'number' && ts > 1e12 && ts < 4e12) return ts;
              if (typeof ts === 'string' && /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/.test(ts)) { var ms = Date.parse(ts); if (ms > 1e12) return ms; }
            }
          }
        }
        f = f.return; d++;
      }
    } catch (e) { }
    return null;
  }
  function fmtNavTime(ts) {
    if (!ts) return '';
    var d = new Date(ts), now = new Date();
    var hm = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    var day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    var today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    var diff = Math.round((today - day) / 86400000);
    if (diff === 0) return '今天 ' + hm;
    if (diff === 1) return '昨天 ' + hm;
    var md = ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2) + ' ' + hm;
    if (d.getFullYear() === now.getFullYear()) return md;
    return d.getFullYear() + '-' + md;
  }
  function renderNav() {
    var sec = chatSection();
    var rows = sec ? qsa('[class*="group/user-row"],[class*="group/assistant-row"]', sec) : [];
    /* 定位当前阅读位置：取视口中线最近的那条消息行（用户上滑到哪，导航就选到哪） */
    var viewMid = window.innerHeight / 2;
    var currentId = null, best = Infinity;
    for (var ri = 0; ri < rows.length; ri++) {
      var rr = rows[ri].getBoundingClientRect();
      var rowMid = rr.top + rr.height / 2;
      var dist = Math.abs(rowMid - viewMid);
      if (rr.height > 0 && rowMid > 0 && rowMid < window.innerHeight && dist < best) {
        best = dist;
        currentId = rows[ri].id || (rows[ri].id = 'zp-msg-cur-' + ri);
      }
    }
    /* 收集每行元数据 */
    var metas = [];
    for (var mi = 0; mi < rows.length; mi++) {
      var m = rows[mi];
      var isU = String(m.className).indexOf('user-row') >= 0;
      var clone = m.cloneNode(true);
      qsa('button,svg,nav', clone).forEach(function (n) { n.remove(); });
      var txt = ((clone.innerText || '') || clone.textContent || '').trim().replace(/\s+/g, ' ');
      if (!m.id) m.id = 'zp-msg-' + mi;
      if (!txt) continue;
      metas.push({ isU: isU, txt: txt, id: m.id, ts: rowTimestamp(m) });
    }
    /* 合并连续同类行：中断/分块的回答只视为一个回答（新4） */
    var groups = [];
    for (var gi = 0; gi < metas.length; gi++) {
      var it = metas[gi];
      var last = groups[groups.length - 1];
      if (last && last.isU === it.isU) {
        if (last.txt.length < 40) last.txt = (last.txt + ' ' + it.txt).slice(0, 60);
        last.ts = it.ts || last.ts;
        last.count = (last.count || 1) + 1;
      } else {
        groups.push({ isU: it.isU, txt: it.txt, id: it.id, ts: it.ts, count: 1 });
      }
    }
    var items = groups.map(function (g) {
      var short = g.txt.length > 34 ? g.txt.slice(0, 34) + '…' : g.txt;
      var when = fmtNavTime(g.ts);
      var cur = g.id === currentId ? ' zp-cur' : '';
      return '<button class="zp-nav-item g-' + (g.isU ? 'u' : 'a') + cur + '" data-zp-jump="' + g.id + '"><div class="nq">' + esc(short) + '</div>' +
        '<div class="nmeta"><span class="nrole ' + (g.isU ? 'u' : 'a') + '">' + (g.isU ? '用户提问' : '智能体回答') + '</span>' +
        (when ? '<span class="ntime">' + esc(when) + '</span>' : '') + '</div></button>';
    }).join('');
    return '<div class="zp-nav">' + (items || '<div class="zp-empty">暂无可导航的问答</div>') + '</div>';
  }
  function renderArtifact() {
    var f = svg(I.file, 15), d = svg(I.folder, 15);
    return '<div class="zp-pathbar">D: › ZCode › 项目 › <b>当前窗口</b></div>' +
      '<div class="zp-tree">' +
      '<div class="zp-tnode zp-doc">' + f + '<span>项目规则.md</span></div>' +
      '<div class="zp-tnode">' + d + '<span><b>项目记忆</b></span></div>' +
      '<div class="zp-tchildren">' +
      '<div class="zp-tnode zp-doc zp-click" data-zp-file="摘要和索引.md">' + f + '<span>摘要和索引.md</span></div>' +
      '<div class="zp-tnode zp-doc zp-click" data-zp-file="事实.md">' + f + '<span>事实.md</span></div>' +
      '<div class="zp-tnode zp-doc zp-click" data-zp-file="决策点.md">' + f + '<span>决策点.md</span></div>' +
      '<div class="zp-tnode zp-doc zp-click" data-zp-file="待办.md">' + f + '<span>待办.md</span></div>' +
      '</div>' +
      '<div class="zp-tnode">' + d + '<span><b>项目资源</b></span></div>' +
      '<div class="zp-tchildren">' +
      '<div class="zp-tnode">' + d + '<span>验收</span></div>' +
      '<div class="zp-tnode">' + d + '<span>source</span></div>' +
      '</div></div>' +
      '<div class="zp-note">静态结构演示；动态读取窗口文件夹需接入官方文件 RPC（二期）</div>';
  }
  function bindPane() {
    qsa('[data-zp-jump]', bodyEl).forEach(function (n) {
      n.addEventListener('click', function () {
        var m = document.getElementById(n.getAttribute('data-zp-jump'));
        closeSide();
        setTimeout(function () {
          if (!m) return;
          scrollToEl(m);
          m.classList.add('zp-hl'); setTimeout(function () { m.classList.remove('zp-hl'); }, 1700);
        }, 240);
      });
    });
    qsa('[data-zp-file]', bodyEl).forEach(function (n) {
      n.addEventListener('click', function () {
        var name = n.getAttribute('data-zp-file');
        PANE.file.title = name;
        if (S.tabs.indexOf('file') < 0) S.tabs.push('file');
        S.sideTab = 'file'; renderTabs();
        bodyEl.innerHTML = '<div class="zp-pathbar">项目记忆 › <b>' + esc(name) + '</b></div>' +
          '<div class="zp-empty">' + esc(name) + '<br>文件内容预览需接入官方文件 RPC（二期）</div>';
      });
    });
  }

  /* ── 分叉拦截 ── */
  document.addEventListener('click', function (e) {
    if (S.bypass) return;
    var btn = e.target && e.target.closest ? e.target.closest('button') : null;
    if (!btn || inOur(btn)) return;
    var label = (btn.getAttribute('aria-label') || '') + '|' + (btn.getAttribute('title') || '');
    if (label.indexOf('分叉') >= 0) {
      e.preventDefault(); e.stopImmediatePropagation();
      S.forkBtn = btn;
      if (native) native.confirmFork(label); else showToast('分叉需原生桥');
    }
  }, true);
  function forkConfirmed() {
    if (!S.forkBtn) return;
    var btn = S.forkBtn;
    S.bypass = true;
    fireTap(btn);
    toast('正在生成分叉会话…');
    /* 先留在当前会话把分叉 RPC 跑完，不要立刻回列表（回列表会打断生成） */
    setTimeout(function () { S.bypass = false; S.forkBtn = null; }, 4000);
    setTimeout(function () {
      var tries = 0;
      var t = setInterval(function () {
        tries++;
        var hc = qs(HOME_SCROLL_SEL);
        var fork = null;
        var roots = hc ? [hc] : [];
        if (!hc) roots.push(document.body);
        for (var r = 0; r < roots.length && !fork; r++) {
          var cands = qsa('a,[role="button"]', roots[r]);
          for (var i = 0; i < cands.length; i++) {
            var tx = cands[i].innerText || '';
            if (!inOur(cands[i]) && (/^Fork of /m.test(tx) || /分叉/.test(tx))) { fork = cands[i]; break; }
          }
        }
        if (fork) {
          clearInterval(t);
          playPageSlide('push');
          fireTap(fork);
        } else if (tries === 4) {
          playPageSlide('pop');
          try { window.dispatchEvent(new PopStateEvent('popstate', { state: { zcodeMobilePage: 'home' } })); } catch (e) { }
        } else if (tries > 16) { clearInterval(t); }
      }, 500);
    }, 1800);
  }
  function forkCancelled() { S.forkBtn = null; }

  /* ── 事件委托（capture 阶段，防 React 吞事件） ── */
  var HOME_SCROLL_SEL = 'div.min-h-0.flex-1.overflow-y-auto.px-3.py-3';
  document.addEventListener('click', function (e) {
    var t = e.target;
    /* 记录首页列表滚动位置（打开会话后返回时恢复）与所点卡片的标题（修"新建任务"标题滞后） */
    if (t && t.closest) {
      var hc = qs(HOME_SCROLL_SEL);
      if (hc && hc.contains(t)) {
        /* 只对真实任务卡片播进入动画；工作区分组头（含“147 个任务”）是折叠/展开，不能当卡片 */
        var tb = t.closest ? t.closest('button[data-state]') : null;
        var isGroupHeader = !!(tb && /\d+\s*个任务/.test(tb.textContent || ''));
        var isCard = !!(tb && tb.closest('[class*="min-h-12"]') && !isGroupHeader);
        if (isCard) {
          S.homeScroll = hc.scrollTop; S.homeScrollTries = 0;
          var cardTitle = cardTitleFrom(t);
          if (cardTitle) { S.homeTitle = cardTitle; S.homeTitleAt = Date.now(); playPageSlide('push'); }
        }
      }
      /* 点首页“+ / 新建”时清掉旧卡片标题，避免新建任务窗口被旧标题覆盖 */
      var maybeNew = t.closest('button,a,[role="button"]');
      if (maybeNew && !inOur(maybeNew)) {
        var nl = ((maybeNew.getAttribute('aria-label') || '') + (maybeNew.getAttribute('title') || '') + (maybeNew.textContent || '')).trim();
        if (/^(\+|＋)?\s*新建/.test(nl) || nl === '+' || nl === '＋' || /新建任务|新任务|New task/i.test(nl)) {
          S.homeTitle = ''; S.homeTitleAt = 0;
        }
      }
    }
    var act = t && t.closest ? t.closest('[data-zp-act]') : null;
    if (act) {
      e.preventDefault(); e.stopImmediatePropagation();
      var a = act.getAttribute('data-zp-act');
      if (a === 'refresh') { location.reload(); return; }
      /* 首页刷新：直接刷新，不做任何滑走动画（不走 goHome，避免 pop 动画误划走） */
      if (a === 'hrefresh') {
        try { history.replaceState({ zcodeMobilePage: 'home' }, ''); } catch (e2) { }
        setTimeout(function () { location.reload(); }, 60);
        return;
      }
      if (a === 'search') { openSearch('chat'); return; }
      if (a === 'hsearch') { openSearch('home'); return; }
      if (a === 'hnew') {
        S.homeTitle = ''; S.homeTitleAt = 0;
        var plus = findHomeNewBtn(true);
        if (plus) { fireTap(plus); toast('正在新建会话…'); }
        else toast('未找到新建入口，请刷新首页');
        return;
      }
      if (a === 'nav') { openSide('nav'); return; }
      if (a === 'artifact') { openSide('artifact'); return; }
      if (a === 'side') { if (S.sideOpen) closeSide(); else openSide(S.tabs[0] || 'nav'); return; }
      if (a === 'remote') {
        try { if (native && native.openRemoteDialog) native.openRemoteDialog(); else toast('远控桥未就绪'); }
        catch (eR) { toast('远控打开失败'); }
        return;
      }
      if (a === 'theme') {
        var html = document.documentElement;
        var isDark = html.classList.contains('theme-zai-dark') || html.classList.contains('dark');
        var nextTheme = isDark ? 'zai-light' : 'zai-dark';
        try { localStorage.setItem('zcode-theme', nextTheme); } catch (e3) { }
        /* 即时预览 */
        html.classList.toggle('dark', nextTheme === 'zai-dark');
        html.classList.toggle('theme-zai-light', nextTheme === 'zai-light');
        html.classList.toggle('theme-zai-dark', nextTheme === 'zai-dark');
        toast(nextTheme === 'zai-dark' ? '已切换至深色主题' : '已切换至浅色主题');
        reportStatusBar();
        /* 官方 React 内部主题状态与 CSS 类是两层；重载让官方整体按新主题重新初始化，
           否则 text 框等组件的字色会停留在旧主题（浅底浅字不可读）。
           必须在重载前把 history 状态归为首页，否则官方按残留会话态恢复到刚才的会话窗口。 */
        try { history.replaceState({ zcodeMobilePage: 'home' }, ''); } catch (e3) { }
        goHome();
        setTimeout(function () { location.reload(); }, 250);
        return;
      }
    }
    if (t && t.closest && t.closest('#zp-gear')) {
      e.preventDefault(); e.stopImmediatePropagation();
      try {
        if (native && native.openPushSettings) native.openPushSettings();
        else toast('设置入口未就绪，请刷新页面');
      } catch (eG) { toast('打开设置失败'); }
      return;
    }
    if (t && t.closest && t.closest('#zp-back')) {
      e.preventDefault(); e.stopImmediatePropagation();
      goHome(); return;
    }
    if (t && t.closest && t.closest('#zp-menu')) {
      e.preventDefault(); e.stopImmediatePropagation();
      openChatMenu();
      return;
    }
  }, true);
  function placeMenuNear(anchor) {
    var menu = qs('[role="menu"]') || qs('[data-radix-menu-content]') || qs('[data-radix-popper-content-wrapper]');
    if (!menu || !anchor) return false;
    var wrap = menu.closest('[data-radix-popper-content-wrapper]') || menu;
    var ar = anchor.getBoundingClientRect();
    var mw = wrap.offsetWidth || 220;
    var left = Math.max(8, Math.min(window.innerWidth - mw - 8, ar.right - mw));
    var top = ar.bottom + 6;
    wrap.style.setProperty('position', 'fixed', 'important');
    wrap.style.setProperty('left', left + 'px', 'important');
    wrap.style.setProperty('top', top + 'px', 'important');
    wrap.style.setProperty('transform', 'none', 'important');
    wrap.style.setProperty('z-index', '2147483600', 'important');
    return true;
  }
  function openChatMenu() {
    var more = qs('[data-testid="workspace-more-button"]') || qs('header [data-testid*="more"]');
    var our = qs('#zp-menu');
    if (!more) { toast('未找到官方会话菜单'); return; }
    /* 官方栏必须临时可定位（display:none 时子按钮点不到），但视觉上高度为 0、全透明，避免露出第二标题栏 */
    var hdr = more.closest('header');
    var r = our ? our.getBoundingClientRect() : { left: window.innerWidth - 48, top: 8, width: 36, height: 36 };
    if (hdr) {
      hdr.style.setProperty('display', 'flex', 'important');
      hdr.style.setProperty('height', '0', 'important');
      hdr.style.setProperty('min-height', '0', 'important');
      hdr.style.setProperty('overflow', 'visible', 'important');
      hdr.style.setProperty('opacity', '0', 'important');
      hdr.style.setProperty('border', '0', 'important');
      hdr.style.setProperty('padding', '0', 'important');
      hdr.style.setProperty('margin', '0', 'important');
      hdr.style.setProperty('pointer-events', 'none', 'important');
    }
    more.style.setProperty('position', 'fixed', 'important');
    more.style.setProperty('left', r.left + 'px', 'important');
    more.style.setProperty('top', r.top + 'px', 'important');
    more.style.setProperty('width', Math.max(36, r.width) + 'px', 'important');
    more.style.setProperty('height', Math.max(36, r.height) + 'px', 'important');
    more.style.setProperty('opacity', '0', 'important');
    more.style.setProperty('pointer-events', 'auto', 'important');
    more.style.setProperty('z-index', '2147483600', 'important');
    more.style.setProperty('visibility', 'visible', 'important');
    firePointer(more);
    var waited = 0;
    var t1 = setInterval(function () {
      waited++;
      if (placeMenuNear(our || more) || waited > 20) {
        clearInterval(t1);
        var t2 = setInterval(function () {
          var m2 = qs('[role="menu"]');
          if (!m2 || !m2.isConnected) {
            clearInterval(t2);
            more.style.removeProperty('position'); more.style.removeProperty('left');
            more.style.removeProperty('top'); more.style.removeProperty('width');
            more.style.removeProperty('height'); more.style.removeProperty('opacity');
            more.style.removeProperty('pointer-events'); more.style.removeProperty('z-index');
            more.style.removeProperty('visibility');
            if (hdr) {
              ['display', 'height', 'min-height', 'overflow', 'opacity', 'border', 'padding', 'margin', 'pointer-events']
                .forEach(function (k) { hdr.style.removeProperty(k); });
            }
          } else {
            placeMenuNear(our || more);
          }
        }, 200);
      }
    }, 50);
  }
  function reportStatusBar() {
    try {
      var html = document.documentElement;
      var dark = html.classList.contains('theme-zai-dark') || html.classList.contains('dark');
      if (native && native.setStatusBarDark) native.setStatusBarDark(dark ? 1 : 0);
    } catch (e) { }
  }

  /* ── 搜索（词级高亮 + 精确跳转，基于 CSS Custom Highlight API） ──
     虚拟滚动会卸载视口外消息行，因此：
     - 高亮/计数只针对当前已渲染文本，新滚入的内容自动重扫补高亮
     - 目标词所在行被卸载时，朝方向滚动一屏触发官方渲染后重扫 */
  var searchState = { q: '', scope: 'chat', ranges: [], idx: -1, rescanPending: false };
  var searchEl = null;
  function closeSearch() {
    clearSearchHighlights();
    searchState.q = '';
    if (searchEl) { searchEl.remove(); }
    searchEl = null;
  }
  function searchScopeRoot() {
    return searchState.scope === 'chat' ? (chatSection() || document.body) : (qs(HOME_SCROLL_SEL) || document.body);
  }
  function clearSearchHighlights() {
    try { CSS.highlights.delete('zp-search'); CSS.highlights.delete('zp-searchcur'); } catch (e) { }
    searchState.ranges = [];
  }
  function scanAndHighlight() {
    clearSearchHighlights();
    var q = searchState.q;
    if (!q || !('Highlight' in window) || !window.CSS || !CSS.highlights) { updateSearchCnt(); return; }
    var ql = q.toLowerCase(), ranges = [];
    var w = document.createTreeWalker(searchScopeRoot(), NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = w.nextNode())) {
      if (inOur(n)) continue;
      var p = n.parentElement;
      if (!p || p.closest('textarea,input,[contenteditable="true"],script,style')) continue;
      var txt = n.nodeValue;
      if (!txt || txt.length > 20000) continue;
      var low = txt.toLowerCase(), pos = 0;
      while ((pos = low.indexOf(ql, pos)) >= 0) {
        try {
          var r = document.createRange();
          r.setStart(n, pos); r.setEnd(n, pos + q.length);
          ranges.push(r);
        } catch (e) { }
        pos += q.length;
      }
    }
    searchState.ranges = ranges;
    try { CSS.highlights.set('zp-search', new Highlight.apply(null, ranges)); } catch (e) { }
    if (searchState.idx >= ranges.length) searchState.idx = ranges.length - 1;
    markCurrentRange();
    updateSearchCnt();
  }
  function markCurrentRange() {
    try {
      var r = searchState.ranges[searchState.idx];
      CSS.highlights.set('zp-searchcur', r ? new Highlight(r) : new Highlight());
    } catch (e) { }
  }
  function updateSearchCnt() {
    var el = searchEl && qs('.zp-scnt', searchEl);
    if (el) el.textContent = searchState.ranges.length ? (searchState.idx + 1) + '/' + searchState.ranges.length : (searchState.q ? '0' : '');
  }
  function searchScrollContainer(fromEl) {
    if (searchState.scope === 'home') {
      var hc = qs(HOME_SCROLL_SEL);
      if (hc) return hc;
    }
    var c = fromEl && fromEl.parentElement;
    while (c && c !== document.body) {
      var st = window.getComputedStyle(c);
      if ((st.overflowY === 'auto' || st.overflowY === 'scroll' || c.scrollHeight > c.clientHeight + 40) && c.clientHeight > 40) return c;
      c = c.parentElement;
    }
    return qs(HOME_SCROLL_SEL) || searchScopeRoot();
  }
  function jumpToRange(i) {
    var rs = searchState.ranges;
    if (!rs.length) return;
    searchState.idx = ((i % rs.length) + rs.length) % rs.length;
    var r = rs[searchState.idx];
    markCurrentRange(); updateSearchCnt();
    if (!r || !r.startContainer || !r.startContainer.isConnected) {
      var cont0 = searchScrollContainer(document.body);
      try { cont0.scrollTop += cont0.clientHeight * 0.8 * (searchState.forward === false ? -1 : 1); } catch (e) { }
      toast('正在加载附近内容…');
      return;
    }
    var node = r.startContainer.nodeType === 3 ? r.startContainer.parentElement : r.startContainer;
    var card = node && node.closest ? (node.closest('a,[role="button"],li,article') || node) : node;
    var cont = searchScrollContainer(card);
    var rect = (card && card.getBoundingClientRect) ? card.getBoundingClientRect() : r.getBoundingClientRect();
    if (cont) {
      var cr = cont.getBoundingClientRect();
      var nextTop = cont.scrollTop + (rect.top - cr.top) - Math.min(80, cr.height * 0.2);
      try { cont.scrollTo({ top: nextTop, behavior: 'smooth' }); }
      catch (e2) { cont.scrollTop = nextTop; }
    } else if (card && card.scrollIntoView) {
      try { card.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (e3) { }
    }
  }
  function openSearch(scope) {
    closeSearch();
    searchState = { q: '', scope: scope, ranges: [], idx: -1, rescanPending: false };
    searchEl = document.createElement('div');
    searchEl.id = 'zp-searchbar';
    searchEl.innerHTML =
      '<input type="text" placeholder="' + (scope === 'chat' ? '在会话中搜索…' : '搜索会话标题…') + '" />' +
      '<span class="zp-scnt"></span>' +
      '<button class="zp-snav" data-dir="prev" aria-label="上一个结果">' + svg(I.chup, 15) + '</button>' +
      '<button class="zp-snav" data-dir="next" aria-label="下一个结果">' + svg(I.chdown, 15) + '</button>' +
      '<button class="zp-sclose" aria-label="关闭搜索">' + svg(I.x, 14) + '</button>';
    var anchor = scope === 'chat' ? qs('#zp-root[data-zp-role="chatbar"]') : qs('#zp-root[data-zp-role="homebar"]');
    if (!anchor || !anchor.parentElement) { toast('未找到注入栏'); return; }
    anchor.parentElement.insertBefore(searchEl, anchor.nextSibling);
    var input = qs('input', searchEl);
    qs('.zp-sclose', searchEl).addEventListener('click', closeSearch);
    qsa('.zp-snav', searchEl).forEach(function (b) {
      b.addEventListener('click', function () {
        if (!searchState.ranges.length) return;
        var next = b.getAttribute('data-dir') === 'prev' ? searchState.idx - 1 : searchState.idx + 1;
        searchState.forward = b.getAttribute('data-dir') !== 'prev';
        jumpToRange(next);
      });
    });
    input.addEventListener('input', function () {
      searchState.q = (input.value || '').trim();
      searchState.idx = 0;
      scanAndHighlight();
      if (searchState.ranges.length) jumpToRange(0); else updateSearchCnt();
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && searchState.ranges.length) {
        searchState.forward = true;
        jumpToRange(searchState.idx + 1);
      }
      if (e.key === 'Escape') closeSearch();
    });
    setTimeout(function () { input.focus(); }, 80);
  }

  /* 在实际滚动容器内居中定位元素（scrollIntoView 会滚到外层容器，把注入栏滚出屏幕） */
  function scrollToEl(m) {
    var c = m.parentElement;
    while (c && c !== document.body) {
      if (c.scrollHeight > c.clientHeight + 40) break;
      c = c.parentElement;
    }
    if (!c || c === document.body) { try { m.scrollIntoView({ block: 'center' }); } catch (e) { } return; }
    var cr = c.getBoundingClientRect(), mr = m.getBoundingClientRect();
    c.scrollTop += (mr.top + mr.height / 2) - (cr.top + cr.height / 2);
  }
  /* ── 图片预览增强：单击退出、长按保存 ── */
  function previewCandidates() {
    var all = qsa('img');
    var out = [];
    for (var i = 0; i < all.length; i++) {
      var img = all[i];
      if (inOur(img) || !img.isConnected) continue;
      var r = img.getBoundingClientRect();
      if (r.width < window.innerWidth * 0.45 || r.height < window.innerHeight * 0.35) continue;
      if (r.bottom < 0 || r.top > window.innerHeight) continue;
      out.push(img);
    }
    out.sort(function (a, b) {
      var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return (br.width * br.height) - (ar.width * ar.height);
    });
    return out;
  }
  function previewImg() {
    var list = previewCandidates();
    return list.length ? list[0] : null;
  }
  function previewOpen() { return !!previewImg(); }
  function dismissPreview() {
    /* 退出预览同时关掉“保存到本地”按钮，留在会话窗口 */
    hideSaveConfirm();
    return closePreview();
  }
  function closePreview() {
    var img = previewImg();
    var roots = [];
    if (img) {
      var p = img.parentElement;
      for (var i = 0; i < 8 && p; i++) { roots.push(p); p = p.parentElement; }
    }
    roots.push(document);
    var labels = /关闭|Close|Dismiss|关闭预览|关闭图片|关闭对话框/;
    for (var r = 0; r < roots.length; r++) {
      var btns = qsa('button', roots[r] === document ? document : roots[r]);
      for (var j = 0; j < btns.length; j++) {
        var b = btns[j];
        if (inOur(b)) continue;
        var al = ((b.getAttribute('aria-label') || '') + '|' + (b.getAttribute('title') || '') + '|' + (b.textContent || '')).trim();
        if (labels.test(al)) { fireTap(b); return true; }
      }
    }
    /* 兜底：Esc / Escape 键，很多对话框监听这个 */
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
      document.dispatchEvent(new KeyboardEvent('keyup', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    } catch (e) { }
    return false;
  }
  function blobToNative(blob, baseName) {
    var extMap = { 'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif' };
    var ext = extMap[(blob.type || '').toLowerCase()] || 'png';
    var reader = new FileReader();
    reader.onload = function () {
      var b64 = String(reader.result).split(',')[1] || '';
      if (!b64) { toast('图片数据为空'); return; }
      var CH = 262144, idx = 0;
      for (var off = 0; off < b64.length; off += CH, idx++) {
        native.saveImageChunk(idx, b64.substr(off, CH));
      }
      var base = (baseName || 'image').replace(/\.[a-zA-Z0-9]+$/, '').replace(/[\\/:*?"<>|]/g, '_') || 'image';
      native.saveImageCommit(base + '.' + ext);
      log('save commit name=' + base + '.' + ext + ' bytes~' + Math.round(b64.length * 0.75));
    };
    reader.onerror = function () { toast('读取图片失败'); };
    reader.readAsDataURL(blob);
  }
  function canvasFallbackSave(imgEl, baseName) {
    try {
      var c = document.createElement('canvas');
      c.width = imgEl.naturalWidth || imgEl.width;
      c.height = imgEl.naturalHeight || imgEl.height;
      if (!c.width || !c.height) throw new Error('图片尺寸无效');
      var ctx = c.getContext('2d');
      ctx.drawImage(imgEl, 0, 0);
      c.toBlob(function (blob) {
        if (!blob) { toast('导出图片失败'); return; }
        blobToNative(blob, baseName);
      }, 'image/jpeg', 0.95);
    } catch (e) { toast('保存失败：' + e.message); log('canvas save fail ' + e.message); }
  }
  function saveImageViaNative(src, baseName) {
    try {
      if (!native || !native.saveImageChunk || !native.saveImageCommit) { toast('原生桥不可用'); return; }
      toast('正在保存…');
      log('save start src=' + String(src || '').slice(0, 120));
      var imgEl = previewImg();
      fetch(src, { credentials: 'include' }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.blob();
      }).then(function (blob) {
        blobToNative(blob, baseName);
      }).catch(function (e) {
        log('fetch save fail ' + e.message + '; try canvas');
        if (imgEl) canvasFallbackSave(imgEl, baseName);
        else { toast('保存失败：' + e.message); }
      });
    } catch (e) { toast('保存失败：' + e.message); }
  }
  function showSaveConfirm(x, y, src, baseName) {
    hideSaveConfirm();
    S.saveSrc = src;
    S.saveBase = baseName || 'image';
    var b = document.createElement('button');
    b.id = 'zp-savebtn';
    b.type = 'button';
    b.textContent = '保存到本地';
    /* 固定在屏幕下方中间；持久显示，直到点按钮保存，或点空白/图片/系统返回关闭 */
    b.style.left = '50%';
    b.style.top = 'auto';
    b.style.bottom = '120px';
    b.style.transform = 'translateX(-50%)';
    b.style.minWidth = '168px';
    b.style.minHeight = '48px';
    var saved = false;
    function doSave(ev) {
      if (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
      }
      if (saved) return;
      saved = true;
      log('save btn tapped');
      hideSaveConfirm();
      saveImageViaNative(S.saveSrc || src, S.saveBase || baseName);
    }
    ['pointerdown', 'pointerup', 'click', 'touchstart', 'touchend'].forEach(function (evn) {
      b.addEventListener(evn, doSave, true);
    });
    document.body.appendChild(b);
  }
  function hideSaveConfirm() {
    var b = qs('#zp-savebtn'); if (b) b.remove();
    S.saveBtnUntil = 0;
  }
  var pvDown = null, pvLongTimer = null, pvLongFired = false;
  document.addEventListener('pointerdown', function (e) {
    if (e.target && e.target.closest && e.target.closest('#zp-savebtn')) return;
    if (!previewOpen() || !e.isPrimary) return;
    /* 预览态：图片区域和图片外空白区域都可点；仅保存按钮本身不走这套 */
    pvDown = { x: e.clientX, y: e.clientY, t: Date.now() };
    pvLongFired = false;
    pvLongTimer = setTimeout(function () {
      pvLongTimer = null; pvLongFired = true;
      var cur = previewImg();
      if (!cur) return;
      var base = ((cur.alt || 'image').replace(/\.[a-zA-Z0-9]+$/, '').replace(/[\\/:*?"<>|]/g, '_') || 'image');
      showSaveConfirm(pvDown.x, pvDown.y, cur.currentSrc || cur.src, base);
    }, 480);
  }, true);
  document.addEventListener('pointermove', function (e) {
    if (pvLongTimer && pvDown) {
      if (Math.abs(e.clientX - pvDown.x) > 14 || Math.abs(e.clientY - pvDown.y) > 14) {
        clearTimeout(pvLongTimer); pvLongTimer = null;
      }
    }
  }, true);
  document.addEventListener('pointerup', function (e) {
    if (e.target && e.target.closest && e.target.closest('#zp-savebtn')) return;
    if (pvLongTimer) { clearTimeout(pvLongTimer); pvLongTimer = null; }
    if (pvLongFired) { pvDown = null; return; }
    if (!previewOpen() || !pvDown || !e.isPrimary) return;
    var quick = Date.now() - pvDown.t < 380 &&
      Math.abs(e.clientX - pvDown.x) < 14 && Math.abs(e.clientY - pvDown.y) < 14;
    pvDown = null;
    if (!quick) return;
    /* 单击图片或图片外空白：退出预览并关闭保存按钮 */
    dismissPreview();
  }, true);
  document.addEventListener('contextmenu', function (e) {
    if (previewOpen()) e.preventDefault();
  }, true);

  /* ── 列表卡片时间格式化（新6）：官方相对时间 → 今天/昨天/日期 + 时间 ──
     列表数据不在 fiber props 里（虚拟化数据层），用相对时间估算绝对时间：
     刚刚/分/小时精确，"N天"按 24h 近似可能有 ±1 天误差 */
  function estimateTs(v) {
    var now = Date.now(), m;
    if (/^刚刚/.test(v)) return now;
    if ((m = v.match(/^(\d+)\s*分/))) return now - parseInt(m[1], 10) * 60000;
    if ((m = v.match(/^(\d+)\s*小时/))) return now - parseInt(m[1], 10) * 3600000;
    if ((m = v.match(/^(\d+)\s*天/))) return now - parseInt(m[1], 10) * 86400000;
    try {
      if ((m = v.match(/^昨天\s*(\d{1,2}):(\d{2})$/))) {
        var d1 = new Date(); d1.setDate(d1.getDate() - 1); d1.setHours(+m[1], +m[2], 0, 0); return d1.getTime();
      }
      if ((m = v.match(/^今天\s*(\d{1,2}):(\d{2})$/))) {
        var d2 = new Date(); d2.setHours(+m[1], +m[2], 0, 0); return d2.getTime();
      }
    } catch (e) { }
    return null;
  }
  function fixCardTimes() {
    var hc = qs(HOME_SCROLL_SEL);
    if (!hc) return;
    var w = document.createTreeWalker(hc, NodeFilter.SHOW_TEXT, null, false);
    var n, fixes = 0;
    while ((n = w.nextNode())) {
      if (fixes > 30) break;
      var v = (n.nodeValue || '').trim();
      if (!v || !/^(刚刚|just now|\d+\s*分钟前|\d+\s*分(钟)?|\d+\s*小时(前)?|\d+\s*天(前)?|昨天\s*\d{1,2}:\d{2}|今天\s*\d{1,2}:\d{2}|\d+天)$/.test(v)) continue;
      if (inOur(n)) continue;
      var ts = estimateTs(v);
      if (ts) {
        var nf = fmtNavTime(ts);
        if (nf && nf !== v) { n.nodeValue = n.nodeValue.replace(v, nf); fixes++; }
      }
    }
  }

  /* ── 图片预览时隐藏工具栏（新7），关闭后恢复 ── */
  function applyPreviewChrome() {
    var tools = qs('#zp-root[data-zp-role="chatbar"] .zp-tools');
    if (!tools) return;
    if (previewOpen()) {
      if (tools.style.display !== 'none') { tools.style.display = 'none'; log('preview: toolbar hidden'); }
    } else if (tools.style.display === 'none') {
      tools.style.display = '';
    }
  }

  /* ── 输入框旁显示模型名 / 思考级别名（新9；官方窄屏用 @xl/@2xl 藏了文字） ── */
  var THOUGHT_LABEL = {
    off: '关闭', disabled: '关闭', false: '关闭', no: '关闭', none: '关闭',
    nothink: '不思考', 'no-think': '不思考', no_think: '不思考',
    on: '开启', enable: '开启', enabled: '开启', true: '开启',
    low: '低', light: '低', minimal: '低', shallow: '低',
    medium: '中', normal: '中', default: '中', balanced: '中', standard: '中',
    high: '高', deep: '高',
    'extra-high': '很高', extra_high: '很高', 'very-high': '很高', very_high: '很高',
    xhigh: '很高', max: '最高', maximum: '最高'
  };
  function clip15(s) {
    s = String(s || '').replace(/\s+/g, ' ').trim();
    if (!s) return '';
    return s.length > 15 ? (s.slice(0, 14) + '...') : s;
  }
  function prettyModel(raw) {
    raw = String(raw || '').trim();
    if (!raw) return '';
    var last = raw.split(/[/:]/).pop() || raw;
    return last.replace(/[-_]/g, ' ').replace(/\s+/g, ' ').trim() || raw;
  }
  function ensureLabelAfter(btn, id, text) {
    if (!btn || !text) return;
    var el = document.getElementById(id);
    if (el && el.parentElement !== btn) { el.remove(); el = null; }
    if (!el) {
      el = document.createElement('span');
      el.id = id;
      el.className = 'zp-clabel';
      btn.appendChild(el);
    }
    if (el.textContent !== text) el.textContent = text;
  }
  function applyComposerLabels() {
    if (S.page !== 'chat' && !chatSection()) return;
    var meta = qs('[data-model],[data-thought],[data-thought-levels]');
    var modelRaw = meta ? (meta.getAttribute('data-model') || '') : '';
    var thoughtRaw = meta ? (meta.getAttribute('data-thought') || '') : '';
    var modelBtn = qs('button[data-model-current-value]');
    if (!modelBtn) {
      var all = qsa('button[data-chat-toolbar-popover-trigger]');
      for (var i = 0; i < all.length; i++) {
        var al = (all[i].getAttribute('aria-label') || '') + (all[i].getAttribute('title') || '');
        if (/模型|Model|切换模型/.test(al)) { modelBtn = all[i]; break; }
      }
    }
    if (!modelRaw && modelBtn) modelRaw = modelBtn.getAttribute('data-model-current-value') || '';
    var thoughtBtn = qs('[data-thought-level-fixed]');
    if (!thoughtBtn) {
      var tbs = qsa('button[data-chat-toolbar-popover-trigger],button[aria-label]');
      for (var j = 0; j < tbs.length; j++) {
        if (tbs[j] === modelBtn) continue;
        var tl = (tbs[j].getAttribute('aria-label') || '') + (tbs[j].getAttribute('title') || '');
        if (/思考|Thought|推理/.test(tl)) { thoughtBtn = tbs[j]; break; }
      }
    }
    if (!thoughtRaw && thoughtBtn) thoughtRaw = (thoughtBtn.getAttribute('aria-label') || '').trim();
    var modelName = clip15(prettyModel(modelRaw));
    var thoughtKey = String(thoughtRaw || '').trim().toLowerCase();
    var thoughtName = clip15(THOUGHT_LABEL[thoughtKey] || thoughtRaw.replace(/思考(级别|深度)?[:：]?\s*/i, ''));
    if (modelBtn && modelName) ensureLabelAfter(modelBtn, 'zp-mname', modelName);
    if (thoughtBtn && thoughtName && thoughtBtn !== modelBtn) ensureLabelAfter(thoughtBtn, 'zp-tname', thoughtName);
  }

  /* ── 软键盘弹出后输入框可内滚（新13） ── */
  function applyComposerScroll() {
    var nodes = qsa('textarea, [data-testid="chat-input"], [contenteditable="true"]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (inOur(el)) continue;
      el.style.setProperty('touch-action', 'pan-y', 'important');
      el.style.setProperty('overflow-y', 'auto', 'important');
      el.style.setProperty('overscroll-behavior', 'contain', 'important');
      el.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
    }
  }

  /* ── 应用补丁 ── */
  function ensureHomeBarReady() {
    /* 返回动画开始前，在会话页仍盖着时就把首页栏插好，滑出时直接露出，不闪 */
    hideBrandRow();
    var host = document.body.firstElementChild || document.body;
    if (!qs('#zp-root[data-zp-role="homebar"]')) {
      host.insertBefore(buildHomeBar(), host.firstChild);
      log('home bar pre-armed');
    }
    try { tuneHomeExtras(); } catch (eHE) { }
  }
  function applyChat() {
    var sec = chatSection();
    if (!sec) return;
    /* 返回过程中先预埋了首页栏，此时不要删掉，否则又会闪 */
    if (!S.homeBarKeep) removeBar('homebar');
    /* 隐藏官方第二层标题栏（含官方开发ZCo...、⋯、以及右侧侧栏按钮的整行） */
    var headerContainer = qs('header[data-testid], .\\@container\\/workspace-header', sec) || qs('div.border-b > header', sec);
    if (headerContainer) {
      var rh = headerContainer.getBoundingClientRect().height;
      if (rh > 0 && rh <= window.innerHeight * 0.3) hide(headerContainer);
    }
    /* 隐藏官方第一层标题：通过返回按钮锚定第一层。
       新建任务时 DOM 更扁，h2 可能是消息区+输入区整块——必须限高，否则整页被藏成“只剩标题栏工具栏”（新14） */
    var back = officialBackBtn();
    if (back) {
      var h1 = back.closest('div');
      if (h1 && h1.parentElement === sec) {
        var rh1 = h1.getBoundingClientRect().height;
        if (rh1 > 0 && rh1 <= window.innerHeight * 0.3) hide(h1);
        var h2 = h1.nextElementSibling;
        if (h2 && (h2.tagName === 'DIV' || h2.tagName === 'HEADER')) {
          var rh2 = h2.getBoundingClientRect().height;
          if (rh2 > 0 && rh2 <= window.innerHeight * 0.3) hide(h2);
        }
      }
    }
    var t = chatTitleText(sec);
    /* 官方 chat 内标题常滞后显示"新建任务"，此时用列表卡片标题（实时更新）兜底 */
    if (t === '新建任务' && S.homeTitle) t = S.homeTitle;
    if (t !== S.title) { S.title = t; var el = qs('#zp-title'); if (el) el.textContent = t; }
    if (!qs('#zp-root', sec)) {
      sec.insertBefore(buildChatBar(), sec.firstChild);
      log('chat bar injected');
    }
    if (S.page !== 'chat') { S.page = 'chat'; report(); reportStatusBar(); }
  }
  function applyHome() {
    if (qs('section[data-mobile-page="chat"]')) {
      if (!S.homeBarKeep) removeBar('homebar');
      return;
    }
    S.homeBarKeep = false;
    removeBar('chatbar');
    smartHide(/二维码失效/);
    smartHide(/已连接到当前桌面窗口/);
    /* 隐藏官方品牌标题行（"ZCode 远程控制"+同行调色板图标），注入标题栏已替代 */
    hideBrandRow();
    var host = document.body.firstElementChild || document.body;
    if (!qs('#zp-root[data-zp-role="homebar"]')) {
      host.insertBefore(buildHomeBar(), host.firstChild);
      log('home bar injected');
    }
    /* 恢复列表滚动位置（重试至内容高度足够） */
    if (S.homeScroll != null) {
      var hc = qs(HOME_SCROLL_SEL);
      if (hc && hc.scrollHeight > hc.clientHeight) {
        hc.scrollTop = S.homeScroll;
        S.homeScrollTries = (S.homeScrollTries || 0) + 1;
        if (Math.abs(hc.scrollTop - S.homeScroll) < 8 || S.homeScrollTries > 12) S.homeScroll = null;
      }
    }
    if (S.page !== 'home') { S.page = 'home'; S.title = ''; report(); reportStatusBar(); }
    try { tuneHomeExtras(); } catch (eHE) { }
  }
  function report() { try { native && native.postState(S.page, S.title); } catch (e) {} }

  /* 只识别首页独立的小型“＋/新建”按钮。工作区分组头（含“147 个任务”）内部也有＋图标，
     宽高远大于 100px，绝不能被当成新建按钮隐藏。
     forClick=true 时允许找已被我们隐藏的按钮（隐藏后尺寸为 0，顶部“新建”仍要代点它）。 */
  function isHomeNewBtn(el, forClick) {
    if (!el || inOur(el)) return false;
    var tag = el.tagName;
    if (tag !== 'BUTTON' && tag !== 'A' && el.getAttribute('role') !== 'button') return false;
    var label = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.textContent || '')).trim();
    if (/\d+\s*个任务/.test(label) || label.length > 30) return false;
    var marked = el.getAttribute('data-zp-hidden') === '1';
    if (!(forClick && marked)) {
      var rect = el.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0 || rect.width > 100 || rect.height > 100) return false;
    }
    if (/新建|New task/i.test(label) || label === '+' || label === '＋') return true;
    var paths = el.querySelectorAll('svg path');
    for (var i = 0; i < paths.length; i++) {
      var d = paths[i].getAttribute('d') || '';
      if (d.indexOf('M5 12h14') >= 0 || d.indexOf('M12 5v14') >= 0) return true;
    }
    return false;
  }
  function findHomeNewBtn(forClick) {
    var hc = qs(HOME_SCROLL_SEL);
    if (!hc) return null;
    var cands = qsa('button,[role="button"]', hc);
    for (var i = 0; i < cands.length; i++) {
      if (isHomeNewBtn(cands[i], !!forClick)) return cands[i];
    }
    return null;
  }

  /* ④隐藏独立＋；⑤工作区栏上下边距减半；⑥右侧三按钮只留中间筛选。 */
  function tuneHomeExtras() {
    if (qs('section[data-mobile-page="chat"]')) return;
    var hc = qs(HOME_SCROLL_SEL);
    if (hc) {
      var bs = qsa('button,[role="button"]', hc);
      for (var r0 = 0; r0 < bs.length; r0++) {
        if (/\d+\s*个任务/.test(bs[r0].textContent || '')) {
          bs[r0].removeAttribute('data-zp-hide');
          bs[r0].removeAttribute('data-zp-hidden');
        }
      }
      for (var i = 0; i < bs.length; i++) {
        if (!bs[i].getAttribute('data-zp-hidden') && isHomeNewBtn(bs[i], false)) {
          bs[i].setAttribute('data-zp-hidden', '1');
          hide(bs[i]);
        }
      }
    }
    var xp = document.evaluate(
      '//text()[normalize-space()="当前设备上的工作区和任务"]',
      document.body, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null
    ).singleNodeValue;
    if (!xp) return;
    var row = xp.parentElement;
    for (var k = 0; k < 5 && row && row !== document.body; k++) {
      if (row.querySelector && row.querySelector('button')) break;
      row = row.parentElement;
    }
    if (!row || row === document.body) return;
    var rb = qsa('button', row).filter(function (b) { return !inOur(b) && !b.getAttribute('data-zp-hide'); });
    rb.sort(function (a, b) { return a.getBoundingClientRect().left - b.getBoundingClientRect().left; });
    if (rb.length >= 3) { hide(rb[0]); hide(rb[2]); }
    var box = row;
    for (var k2 = 0; k2 < 4 && box && box !== document.body; k2++) {
      if (box.getAttribute('data-zp-pad')) break;
      var cs = getComputedStyle(box);
      var pt = parseFloat(cs.paddingTop) || 0, pb = parseFloat(cs.paddingBottom) || 0;
      if (pt > 16 || pb > 16) {
        box.setAttribute('data-zp-pad', '1');
        if (pt > 16) box.style.setProperty('padding-top', Math.max(8, pt / 2) + 'px', 'important');
        if (pb > 16) box.style.setProperty('padding-bottom', Math.max(8, pb / 2) + 'px', 'important');
        break;
      }
      box = box.parentElement;
    }
  }

  var applyT = null;
  function schedule() {
    clearTimeout(applyT);
    applyT = setTimeout(apply, 40);
    /* 搜索激活时，新滚入的内容延迟重扫补高亮 */
    if (searchState.q && !searchState.rescanPending) {
      searchState.rescanPending = true;
      setTimeout(function () {
        searchState.rescanPending = false;
        if (searchState.q && searchEl) scanAndHighlight();
      }, 600);
    }
  }
  function apply() {
    try { applyChat(); } catch (e) { log('chat err ' + e.message); }
    try { applyHome(); } catch (e) { log('home err ' + e.message); }
    try { applyPreviewChrome(); } catch (e) { }
    try { applyComposerLabels(); } catch (e) { }
    try { applyComposerScroll(); } catch (e) { }
    /* 列表时间格式化低频执行即可 */
    if (S.page === 'home' && !S.timeFixPending) {
      S.timeFixPending = true;
      setTimeout(function () {
        S.timeFixPending = false;
        if (S.page === 'home') { try { fixCardTimes(); } catch (e) { } }
      }, 800);
    }
  }

  var mo = new MutationObserver(schedule);
  function boot() {
    if (!document.body) { setTimeout(boot, 120); return; }
    apply();
    S.bootLogged = true;
    mo.observe(document.body, { childList: true, subtree: true });
    log('patch ready');
    reportStatusBar();
    /* 记住最近一次进过的会话 ID：齿轮在首页，设置页要拿它拼测试跳转链接 */
    try { S.lastSessionId = localStorage.getItem(ZP_LAST_SESS_KEY) || ''; } catch (e0) { }
    setInterval(zpRememberSession, 2000);
    /* 记录官方最近点击的 file input，供附件桥接注入使用 */
    try {
      var _click = HTMLInputElement.prototype.click;
      HTMLInputElement.prototype.click = function () {
        if (this.type === 'file') S.lastFileInput = this;
        return _click.apply(this, arguments);
      };
    } catch (e) { }
  }

  /* 附件注入：必须在 fileChooser 回调尚未 onReceiveValue 时注入到等待中的 input */
  function b64ToBytes(b64) {
    var bin = atob(b64), n = bin.length, arr = new Uint8Array(n);
    for (var i = 0; i < n; i++) arr[i] = bin.charCodeAt(i);
    return arr;
  }
  function liveFileInput() {
    if (S.lastFileInput && S.lastFileInput.isConnected) return S.lastFileInput;
    var all = qsa('input[type="file"]');
    for (var i = 0; i < all.length; i++) if (all[i].isConnected) return all[i];
    return null;
  }
  function clearFailedCards() {
    /* 只清理输入区附近的失败附件，避免误点聊天记录里的按钮 */
    var root = composerRoot();
    if (!root) return;
    qsa('button', root).forEach(function (b) {
      if (inOur(b)) return;
      var al = ((b.getAttribute('aria-label') || '') + (b.getAttribute('title') || '') + (b.textContent || '')).trim();
      var cardText = '';
      try {
        var p = b.parentElement;
        for (var i = 0; i < 3 && p && p !== root; i++) {
          cardText += (p.textContent || '');
          p = p.parentElement;
        }
      } catch (e) { }
      if (/上传失败|缺少可读取内容/.test(cardText) && /移除|删除|重试|remove|retry/i.test(al)) {
        try { b.click(); } catch (e) { }
      }
    });
  }
  function composerRoot() {
    var ta = qs('textarea') || qs('[contenteditable="true"]');
    if (!ta) return null;
    var root = ta;
    for (var i = 0; i < 5 && root.parentElement; i++) {
      root = root.parentElement;
      if (root.tagName === 'FORM' || /composer|prompt|editor/i.test(String(root.className || ''))) break;
    }
    return root;
  }
  function composerStatusText() {
    var root = composerRoot();
    if (!root) return 'nostat';
    var text = (root.innerText || '').replace(/\s+/g, ' ').trim();
    var m = text.match(/(正在等待会话|等待上传|正在上传[\d% ]*|正在完成上传|上传完成|上传失败：[^ \n]*|运行时已重启[^ \n]*|远端附件未完成物化[^ \n]*)/);
    return m ? m[1] : ('nostat:' + text.slice(0, 80));
  }
  function needsBinaryBridge(mime, name) {
    var m = (mime || '').toLowerCase();
    var n = (name || '').toLowerCase();
    if (m.indexOf('image/') === 0 || m.indexOf('video/') === 0 || m.indexOf('text/') === 0) return false;
    if (/\.(png|jpe?g|gif|webp|bmp|svg|mp4|webm|mov|txt|md|json|csv|xml|html?|css|js|ts|tsx|jsx)$/i.test(n)) return false;
    return true;
  }
  /* 官方 Web 路径：非图片/视频/文本的 File 不会生成 dataBase64，最终报“附件缺少可读取内容”。
     对这类文件临时把 mime 改成 video/mp4，逼出 Sj→dataBase64 上传（官方唯一能带完整字节的通道）；
     真实类型（PDF/Office/音频等）由电脑端补丁按原文件名扩展名识别落盘与标签，无需手机端恢复。 */
  function uploadMime(file) {
    if (!needsBinaryBridge(file.type, file.name)) return file.type || 'application/octet-stream';
    return 'video/mp4';
  }
  function patchAttachmentObject(it, file) {
    if (!it || typeof it !== 'object') return false;
    if (!(it.filename === file.name || ('uploadStatus' in it))) return false;
    it.file = file;
    if (!it.sizeBytes) it.sizeBytes = file.size;
    it.mimeType = uploadMime(file);
    if (needsBinaryBridge(file.type, file.name)) it.__zpBinary = 1;
    /* 桥接文件全程保持 video/mp4 外壳：官方仅 image/video 会读字节，且发送时
       以该外壳身份上报；真实类型由电脑端补丁按原文件名扩展名识别（video-*.pdf 等），
       手机端不再尝试恢复原 mime（恢复对发送身份无效，反而制造混淆）。 */
    return true;
  }
  function reattachFile(file) {
    var root = composerRoot() || document.body;
    var fixed = 0;
    function visit(el, depth) {
      if (!el || depth > 10 || fixed > 8) return;
      var fk = Object.keys(el).find(function (k) { return k.indexOf('__reactFiber$') === 0; });
      if (fk) {
        var f = el[fk], d = 0;
        while (f && d < 50 && fixed < 8) {
          var p = f.memoizedProps;
          if (p && typeof p === 'object') {
            Object.keys(p).forEach(function (k) {
              var v = p[k];
              if (!v) return;
              if (typeof v === 'object' && !Array.isArray(v) && patchAttachmentObject(v, file)) fixed++;
              if (Array.isArray(v)) v.forEach(function (it) { if (patchAttachmentObject(it, file)) fixed++; });
            });
          }
          f = f.return; d++;
        }
      }
      for (var i = 0; i < (el.children ? el.children.length : 0) && fixed < 8; i++) visit(el.children[i], depth + 1);
    }
    visit(root, 0);
    return fixed;
  }
  function clickRetryIfFailed() {
    var root = composerRoot();
    if (!root) return false;
    var btns = qsa('button', root);
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var al = (b.getAttribute('aria-label') || '') + (b.textContent || '');
      if (/重试上传|重试/.test(al)) { b.click(); return true; }
    }
    return false;
  }
  function injectPickedAttachment(name, mime, total) {
    try {
      log('PICK name=' + name + ' mime=' + mime + ' total=' + total);
      S.pendingQueue = S.pendingQueue || [];
      S.pendingQueue.push({ name: name });
      clearFailedCards();
      var CH = 262144, parts = [];
      for (var off = 0; off < total; off += CH) {
        var len = Math.min(CH, total - off);
        var b64 = native && native.readAttachmentChunk ? native.readAttachmentChunk(off, len) : null;
        if (!b64) { log('PICK chunk null at ' + off); return 'chunk-null'; }
        parts.push(b64ToBytes(b64));
      }
      var forcedMime = uploadMime({ type: mime || '', name: name });
      var file = new File(parts, name, { type: forcedMime, lastModified: Date.now() });
      S.pendingFile = file;
      var input = liveFileInput();
      if (!input) { log('PICK no live input'); return 'no-input'; }
      var dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      log('PICK injected size=' + file.size + ' mime=' + forcedMime);
      /* 官方异步上传前，反复把 File 挂回附件对象，防止状态副本丢掉 file；
         上传完成后再收尾当前文件（否则后选的文件会提前覆盖 S.pendingFile） */
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        reattachFile(file);
        var st = composerStatusText();
        if (/上传失败/.test(st)) clickRetryIfFailed();
        if (/上传完成|正在上传|正在完成上传/.test(st) || tries >= 15) {
          clearInterval(timer);
          S.pendingQueue = (S.pendingQueue || []).filter(function (p) { return p.name !== name; });
          if (!S.pendingQueue.length) S.pendingFile = null;
        }
      }, 400);
      return 'ok';
    } catch (e) { log('PICK err ' + e.message); return 'err'; }
  }

  /* ══ Deep Link：按会话 ID 打开指定会话（推送通知点击进入） ══
     链接形如 zcode://session?sess=<会话ID>&event=done|error|ask。
     原生层等页面就绪后调用这里；返回 false 表示页面还没准备好，由原生层稍后重试。
     一律按官方 DOM 上的 data-session-id 匹配，不用标题文字代替会话 ID。 */
  function zpSessionIdOf(el) {
    if (!el || !el.getAttribute) return '';
    var v = el.getAttribute('data-session-id') || '';
    return v === 'draft' ? '' : v;
  }
  /* 当前正在会话页时返回该会话 ID；不在会话页返回空 */
  function zpLiveSessionId() {
    try {
      var sec = chatSection();
      if (!sec) return '';
      var host = sec.closest ? sec.closest('[data-session-id]') : null;
      if (host) return zpSessionIdOf(host);
      return zpSessionIdOf(sec.querySelector('[data-session-id]'));
    } catch (e) { return ''; }
  }
  /* 齿轮在首页，而会话 ID 只有进过会话才知道：记住最近一次，持久化以便重载后仍在 */
  function zpRememberSession() {
    try {
      var id = zpLiveSessionId();
      if (id && id !== S.lastSessionId) {
        S.lastSessionId = id;
        try { localStorage.setItem(ZP_LAST_SESS_KEY, id); } catch (e2) { }
        log('SESSION remember ' + id);
      }
    } catch (e) { }
  }
  /* 供设置页取用：优先当前会话，其次最近一次进过的会话 */
  function currentSessionId() {
    var live = zpLiveSessionId();
    if (live) return live;
    if (S.lastSessionId) return S.lastSessionId;
    try { return localStorage.getItem(ZP_LAST_SESS_KEY) || ''; } catch (e) { return ''; }
  }
  /*
   * 按会话 ID 找列表行。
   * 真机实测（177 行全渲染）：首页列表行是 <button class="...min-h-12...">，
   * 会话 ID 挂在 data-testid="task-item-<sessionId>" 上；
   * data-session-id 只出现在会话详情容器，首页列表里没有，不能用来定位。
   */
  function zpFindSessionCard(sessionId) {
    try {
      if (!/^[A-Za-z0-9_-]+$/.test(sessionId)) return null;
      var hc = qs(HOME_SCROLL_SEL) || document;
      var direct = qs('[data-testid="task-item-' + sessionId + '"]', hc);
      if (direct) return direct;
      /* 兜底一：前缀扫描（防前缀写法变化） */
      var all = qsa('[data-testid^="task-item-"]', hc);
      for (var i = 0; i < all.length; i++) {
        var v = all[i].getAttribute('data-testid') || '';
        if (v.slice('task-item-'.length) === sessionId) return all[i];
      }
      /* 兜底二：旧写法 data-session-id（仅详情容器有，留作兼容） */
      var legacy = qsa('[data-session-id]', hc);
      for (var j = 0; j < legacy.length; j++) {
        if (zpSessionIdOf(legacy[j]) === sessionId) return legacy[j];
      }
    } catch (e) { }
    return null;
  }

  /* event=ask 时尽量定位到请决策区域；定位不到就滚到底部（决策卡通常在最下方） */
  var zpAskTarget = null;
  function zpScrollToInteraction(interactionId) {
    try {
      if (interactionId) {
        var hit = qs('[data-interaction-id="' + interactionId + '"]');
        if (hit) { hit.scrollIntoView({ block: 'center' }); return true; }
      }
      var sec = chatSection();
      if (!sec) return false;
      var sc = qs('div.overflow-y-auto', sec) || sec;
      sc.scrollTop = sc.scrollHeight;
      return true;
    } catch (e) { return false; }
  }
  /* 进入会话后要等官方内容渲染出来才能定位；有限次尝试，绝不刷新页面 */
  function zpArmAskScroll() {
    if (!zpAskTarget) return;
    if (zpAskTarget.tries++ > 12) { zpAskTarget = null; return; }
    setTimeout(function () {
      if (!zpAskTarget) return;
      if (currentSessionId() === zpAskTarget.sessionId &&
        zpScrollToInteraction(zpAskTarget.interactionId)) {
        zpAskTarget = null;
        return;
      }
      zpArmAskScroll();
    }, 400);
  }
  /*
   * 卡片里有多个可点元素（置顶/更多/状态标签…），按 DOM 顺序取第一个会点错。
   * 打开会话的可点区域是整张卡片，所以选**面积最大**的那个，通常就是卡片主体。
   */
  function zpCardClickable(card) {
    var cands = qsa('button, [role="button"]', card);
    var best = null, bestArea = 0;
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = cands[i]; }
    }
    if (best) return best;
    var cr = card.getBoundingClientRect();
    return cr.width > 0 ? card : null;
  }
  /* 找不到会话卡片时的诊断：把首页列表区实际存在的 data-* 属性统计出来（只打一次） */
  function openSessionById(sessionId, event, interactionId) {
    try {
      if (!sessionId) return false;
      /* 只认“实时”会话：不能用最近会话回退值判断，否则在首页也会被误判成已打开 */
      var live = zpLiveSessionId();
      if (live && live === sessionId) {
        if (event === 'ask') zpScrollToInteraction(interactionId);
        return true;
      }
      var card = zpFindSessionCard(sessionId);
      if (!card) return false;
      var clickable = zpCardClickable(card);
      if (!clickable) return false;
      if (event === 'ask') {
        zpAskTarget = { sessionId: sessionId, interactionId: interactionId || '', tries: 0 };
        zpArmAskScroll();
      }
      log('DEEPLINK open ' + sessionId + ' aria=' + (clickable.getAttribute('aria-label') || ''));
      fireTap(clickable);
      return true;
    } catch (e) { return false; }
  }

  window.__zcodePatch = {
    goHome: goHome,
    onSystemBack: function () {
      if (S.sideOpen) { closeSide(); return 'ok'; }
      /* 图片预览（含保存按钮显示中）：系统返回只关预览，留在会话，不回列表 */
      if (previewOpen() || qs('#zp-savebtn')) {
        dismissPreview();
        return 'ok';
      }
      if (qs('section[data-mobile-page="chat"]')) { goHome(); return 'ok'; }
      return 'exit';
    },
    forkConfirmed: forkConfirmed,
    forkCancelled: forkCancelled,
    onAttachmentPicked: injectPickedAttachment,
    openSessionById: openSessionById,
    currentSessionId: currentSessionId,
    state: function () { return JSON.stringify(S); }
  };

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || inOur(a)) return;
    var href = a.getAttribute('href') || '';
    if (!/^https?:\/\//i.test(href)) return;
    try {
      var host = (new URL(href, location.href)).hostname;
      if (host === 'zcode.z.ai') return;
    } catch (err) { return; }
    e.preventDefault();
    e.stopImmediatePropagation();
    try {
      if (native && native.openExternal) native.openExternal(href);
      else window.open(href, '_blank');
    } catch (e2) { }
  }, true);

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
