/*
 * pi carry 桌面 UI 状态机（0.3.2 设计对齐，Phase B）
 *
 * 约束：
 * - 只依赖 index.html 已提供的 id / data-* 契约；不新增/修改 HTML、CSS。
 * - 后端命令/事件契约见 crates/desktop/src/main.rs、update.rs、selfcheck.rs。
 * - 设计依据：plan/0.3.2_design.md D.1/D.5/D.8/D.9/D.10、F-31/F-33/F-34。
 */
(() => {
  'use strict';

  // ── 图标与 DOM 小工具 ──────────────────────────────────────────────
  const icons = {
    grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    qr: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14h1M14 20h1M18 18h3v3h-3z"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/>',
    chat: '<path d="M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/>',
    sliders: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h10M18 18h2"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
    terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    shield: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    trash: '<path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13"/>',
    link: '<path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1"/><path d="M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1"/>',
    download: '<path d="M12 4v11M7 11l5 5 5-5M5 20h14"/>',
    alert: '<path d="M12 4l9 16H3z"/><path d="M12 10v4M12 17h.01"/>',
  };

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (name, size = 16) => `<svg class="ico" viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || ''}</svg>`;
  const on = (el, type, fn, opts) => { if (el) el.addEventListener(type, fn, opts); };
  const setText = (sel, text) => { const el = $(sel); if (el) el.textContent = text; };
  const hideRow = (sel) => { const el = $(sel); const row = el && el.closest('.setting-row'); if (row) row.hidden = true; };

  const api = window.__TAURI__;
  const invoke = api?.core?.invoke;
  const listen = api?.event?.listen;

  // ── 全局状态 ─────────────────────────────────────────────────────
  let status = { running: false };
  let devices = [];
  let sessions = [];
  let settings = null;
  let selfCheck = null;          // self_check 结果
  let selfCheckFailed = '';      // self_check invoke 失败原因（DESK-16：不得卡在「检测中」）
  let pendingPair = null;        // 深链预填 payload
  let pairing = null;            // start_pairing payload
  let pairingBusy = false;
  let pollTimer = null;          // get_status 轮询
  let pairTimer = null;          // 配对倒计时/状态轮询
  let lockTimer = null;          // 三错锁定倒计时
  let updateTimer = null;        // 24h 自动检查调度
  let cooldownTimer = null;      // 更新检查 60s 冷却
  let themeMode = 'auto';

  // 更新状态机：所有渲染只读这一份，避免横幅/对话框/设置卡互相矛盾。
  let updateState = null;        // 最近一次 check_update / get_last_update 的 payload
  let updatePhase = null;        // null | downloading | verifying | installing | installed
  let updateProgress = { downloaded: 0, total: null, retry: null };
  let updateSpeed = { samples: [], bytesPerSec: null };
  let updateError = null;        // { kind, message, version, expectedSignature, downloadUrl }
  let updateCheckError = '';     // 最近一次检查失败原因（设置卡）
  let installFailures = 0;       // 连续安装失败次数（DE-96：3 次后提示稍后重试）
  let manualCheckAt = 0;
  let checkCooldownUntil = 0;
  let suspendedBanner = false;   // 「稍后」仅隐藏本次会话
  let bannersHiddenUntil = 0;

  const UPDATES = {
    SELF_CHECK_KEY: 'pi_carry_selfcheck_done_v1',
    SKIP_KEY: 'pi_carry_skip_version',
    AUTO_KEY: 'pi_carry_update_last_auto_check',
    SECRET_DISMISS_KEY: 'pi_carry_secret_banner_dismissed',
    FIRST_DELAY: 10000,
    THROTTLE: 24 * 60 * 60 * 1000,
    FAIL_COOLDOWN: 60000,
  };

  const titles = {
    overview: '概览',
    pairing: '配对',
    devices: '设备',
    sessions: '会话',
    settings: '设置',
    logs: '日志',
  };

  // ── 模态框管理：焦点陷阱 / Esc 规则 / 背景 inert ─────────────────
  const modalStack = [];

  function focusables(root) {
    return $$('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])', root)
      .filter((el) => !el.disabled && el.getClientRects().length > 0);
  }

  function modalTop() { return modalStack[modalStack.length - 1] || null; }

  function setModalEsc(overlay, mode) {
    const rec = modalStack.find((m) => m.overlay === overlay);
    if (rec) rec.esc = mode;
  }

  function openModal(overlay, opts = {}) {
    if (!overlay || modalStack.some((m) => m.overlay === overlay)) return;
    const rec = {
      overlay,
      esc: opts.esc || 'close',
      backdrop: opts.backdrop !== false,
      onClose: opts.onClose || null,
      prevFocus: document.activeElement,
      onBackdrop: null,
    };
    overlay.hidden = false;
    overlay.classList.add('show');
    modalStack.push(rec);
    const appRoot = $('.app');
    if (appRoot) appRoot.setAttribute('inert', '');
    if (rec.backdrop) {
      rec.onBackdrop = (e) => { if (e.target === overlay) closeModal(overlay); };
      overlay.addEventListener('click', rec.onBackdrop);
    }
    const selector = typeof opts.initial === 'string' ? opts.initial : null;
    const target = selector ? overlay.querySelector(selector) : opts.initial || focusables(overlay)[0];
    if (target && target.focus) target.focus();
  }

  function closeModal(overlay) {
    const idx = modalStack.findIndex((m) => m.overlay === overlay);
    if (idx < 0) return;
    const rec = modalStack[idx];
    modalStack.splice(idx, 1);
    overlay.classList.remove('show');
    overlay.hidden = true;
    if (rec.onBackdrop) overlay.removeEventListener('click', rec.onBackdrop);
    if (rec.onClose) rec.onClose();
    const top = modalTop();
    if (!top) {
      const appRoot = $('.app');
      if (appRoot) appRoot.removeAttribute('inert');
    }
    const restore = rec.prevFocus;
    if (restore && restore.isConnected && restore.focus) restore.focus();
    else if (top) { const f = focusables(top.overlay)[0]; if (f) f.focus(); }
  }

  function trapFocus(overlay, event) {
    const list = focusables(overlay);
    if (!list.length) return;
    const first = list[0];
    const last = list[list.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    else if (!overlay.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
  }

  document.addEventListener('keydown', (event) => {
    const rec = modalTop();
    if (!rec) return;
    if (event.key === 'Escape') {
      if (rec.esc === 'ignore') { event.preventDefault(); return; }
      event.preventDefault();
      event.stopPropagation();
      closeModal(rec.overlay);
      return;
    }
    if (event.key === 'Tab') trapFocus(rec.overlay, event);
  });

  // ── 基础工具 ─────────────────────────────────────────────────────
  function fmtBytes(value) {
    const n = Number(value);
    if (!Number.isFinite(n) || n < 0) return '—';
    if (n < 1024) return `${Math.round(n)} B`;
    if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1048576).toFixed(1)} MB`;
  }

  function versionText(version) {
    const text = String(version ?? '').trim();
    if (!text) return '';
    return /^v/i.test(text) ? text : `v${text}`;
  }

  function firstLine(text) {
    return String(text ?? '').split('\n').map((line) => line.trim()).filter(Boolean)[0] || '';
  }

  function notesList(text) {
    return String(text ?? '')
      .split('\n')
      .map((line) => line.replace(/^[-*·•]\s*/, '').trim())
      .filter(Boolean)
      .slice(0, 5);
  }

  function relTime(value) {
    if (!value) return '—';
    const t = Date.parse(value);
    if (!Number.isFinite(t)) return String(value);
    const diff = Math.max(0, Date.now() - t);
    if (diff < 60_000) return '刚刚';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
    return `${Math.floor(diff / 86_400_000)} 天前`;
  }

  function fmtDate(value) {
    const text = String(value || '').slice(0, 10);
    return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : '';
  }

  function statusLabel(name) {
    return {
      streaming: '运行中', running: '运行中', waiting_approval: '等待审批', waiting_input: '等待输入',
      completed: '已完成', done: '已完成', error: '失败', failed: '失败', idle: '空闲',
    }[name] || name || '';
  }

  function statusTone(name) {
    if (name === 'streaming' || name === 'running') return 'cyan';
    if (name === 'waiting_approval' || name === 'error' || name === 'failed') return 'rose';
    if (name === 'waiting_input') return 'amber';
    return '';
  }

  function toast(message, isError = false) {
    const el = $('#toast');
    if (!el) return;
    el.textContent = String(message ?? '');
    el.classList.toggle('err', !!isError);
    el.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove('show'), 3200);
  }

  async function copyText(text, label, successMessage) {
    try {
      await navigator.clipboard.writeText(String(text ?? ''));
      toast(successMessage || `${label}已复制`);
    } catch {
      toast('复制失败', true);
    }
  }

  // DE-22：顶栏 pill 状态变化用 sr-only live region 播报（不改静态 DOM）。
  function announce(text) {
    if (!text) return;
    let el = $('#sr-live');
    if (!el) {
      el = document.createElement('span');
      el.id = 'sr-live';
      el.className = 'sr-only';
      el.setAttribute('aria-live', 'polite');
      document.body.appendChild(el);
    }
    if (el.textContent !== text) el.textContent = text;
  }

  // ── 导航 ─────────────────────────────────────────────────────────
  function go(next) {
    if (!titles[next]) return;
    $$('.page').forEach((el) => el.classList.toggle('active', el.id === `page-${next}`));
    $$('.nav button').forEach((el) => {
      const active = el.dataset.page === next;
      el.classList.toggle('active', active);
      if (active) el.setAttribute('aria-current', 'page');
      else el.removeAttribute('aria-current');
    });
    setText('#page-title', titles[next]);
    setText('#page-sub', '');
    if (next === 'settings') loadSettings();
    if (next === 'logs') loadLogs();
    if (next === 'devices') renderDevices();
    if (next === 'sessions') renderSessions();
    if (next === 'pairing') renderPairing();
  }

  // ── 通用确认框（DE-33 / DE-133 / E.5） ───────────────────────────
  function askConfirm(title, body, opts = {}) {
    const overlay = $('#confirm-overlay');
    const okBtn = $('#confirm-ok');
    const cancelBtn = $('#confirm-cancel');
    if (!overlay || !okBtn || !cancelBtn) return Promise.resolve(false);
    setText('#confirm-title', title);
    setText('#confirm-body', body);
    okBtn.textContent = opts.ok || '确定';
    okBtn.className = `btn ${opts.danger === false ? 'primary' : 'danger'}`;
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        okBtn.removeEventListener('click', onOk);
        cancelBtn.removeEventListener('click', onCancel);
        closeModal(overlay);
        resolve(value);
      };
      const onOk = () => finish(true);
      const onCancel = () => finish(false);
      okBtn.addEventListener('click', onOk);
      cancelBtn.addEventListener('click', onCancel);
      openModal(overlay, {
        esc: 'cancel',
        backdrop: false,
        initial: cancelBtn,
        onClose: () => finish(false),
      });
    });
  }

  // ── 状态渲染（概览 / 侧栏 / 顶栏 pill） ──────────────────────────
  function renderStatus() {
    const running = !!status.running;
    const dot = $('#side-dot');
    if (dot) dot.className = `dot ${running ? 'on' : status.lastError ? 'err' : ''}`;
    setText('#side-state', running ? '节点在线' : status.lastError ? '节点离线 · 检查连接' : '连接中');
    setText('#side-endpoint', status.endpointId || '');
    const ver = $('.side-node-ver');
    if (ver && status.version) ver.textContent = String(status.version);

    setText('#top-relay', `relay · ${status.relayMode || '—'}`);
    const relayPill = $('#top-relay');
    if (relayPill) relayPill.className = `pill ${status.relayMode === 'disabled' ? 'amber' : 'violet'}`;
    setText('#top-pi', status.piInstalled ? 'pi · 已安装' : 'pi · 未找到');
    const piPill = $('#top-pi');
    if (piPill) piPill.className = `pill ${status.piInstalled ? 'green' : 'rose'}`;

    // DE-20 / DESK-04：hero 标题与副标题固定为设计文案，节点名不覆盖标题。
    setText('#hero-title', '把手机接到这台电脑的 pi');
    setText('#hero-sub', '手机扫码后，可以在这里发起会话并处理审批。');
    setText('#hero-endpoint', status.endpointId || '—');
    setText('#hero-state', running ? '● 节点在线' : status.lastError ? '● 节点离线' : '● 连接中');
    const heroState = $('#hero-state');
    if (heroState) heroState.className = `pill ${running ? 'green' : status.lastError ? 'rose' : 'amber'}`;
    setText('#hero-version', status.version ? `${status.nodeName ? `${status.nodeName} · ` : ''}core ${status.version}` : '');
    setText('#pair-card-endpoint', status.endpointId || '—');
    setText('#pair-endpoint', status.endpointId || '—');
    setText('#set-endpoint-id', status.endpointId || '—');

    const mDevices = $('#m-devices');
    if (mDevices) mDevices.innerHTML = `${status.deviceCount || 0}<small>台</small>`;
    const mOnline = $('#m-online');
    if (mOnline) mOnline.innerHTML = `${status.onlineDevices || 0}<small>台</small>`;
    const mSessions = $('#m-sessions');
    if (mSessions) mSessions.innerHTML = `${status.sessionCount || 0}<small>个</small>`;
    setText('#m-running', `${status.runningSessions || 0} 个运行中`);
    const mApprovals = $('#m-approvals');
    if (mApprovals) mApprovals.innerHTML = `${status.pendingApprovals || 0}<small>待处理</small>`;
    setText('#nav-devices', String(status.deviceCount || 0));
    setText('#nav-sessions', String(status.sessionCount || 0));

    setText('#relay-pill', status.relayMode === 'disabled' ? '仅局域网' : status.relayMode === 'custom' ? '自托管 relay' : 'n0 relay');
    const addrs = Array.isArray(status.directAddresses) ? status.directAddresses : [];
    const addressEl = $('#addresses');
    if (addressEl) {
      addressEl.innerHTML = addrs.length
        ? addrs.map((a) => `<span class="address">${esc(a)}</span>`).join('')
        : '<span class="address">relay 就绪后显示直连地址</span>';
    }
    const transfer = $('#transfer-state');
    if (transfer) {
      transfer.textContent = running ? '已连接' : '未连接';
      transfer.className = `pill ${running ? 'green' : 'amber'}`;
    }
  }

  function connBadge(connection) {
    if (!connection || !connection.type) return '';
    const tone = connection.type === 'relay' ? 'amber' : connection.type === 'p2p' ? 'violet' : 'green';
    const label = { relay: 'relay 转发', lan: '局域网', v4: 'v4 直连', v6: 'v6 直连', p2p: 'p2p 直连' }[connection.type] || connection.type;
    return `<span class="pill ${tone}" title="${esc(connection.remoteAddr || '')}">${esc(label)}</span>`;
  }

  function renderDevices() {
    const list = devices || [];
    const active = list.filter((d) => !d.revokedAt);
    setText('#devices-caption', `${active.length} 台`);
    setText('#nav-devices', String(active.length));
    const rows = list.length ? list.map((d) => {
      const revoked = !!d.revokedAt;
      const offline = !d.online && !revoked;
      return `<div class="device${offline ? ' offline' : ''}${revoked ? ' revoked' : ''}">
        <div class="avatar${d.deviceClass === 'desktop' ? '' : ' cyan'}">${d.deviceClass === 'desktop' ? '▣' : '▯'}</div>
        <div style="min-width:0;flex:1">
          <div class="name">${esc(d.name || '未命名设备')}${d.online && !revoked ? '<span class="pill green" style="margin-left:6px">在线</span>' : ''}${d.online && !revoked ? connBadge(d.connection) : ''}</div>
          <div class="id">${esc(String(d.id || '').slice(0, 22))}… · ${esc(d.platform || '')}</div>
        </div>
        <div class="right">
          <span class="pill ${revoked ? '' : d.capability === 'full' ? 'violet' : ''}">${esc(revoked ? '已撤销' : d.capability || '—')}</span>
          <span class="time">${esc(relTime(d.lastSeen))}</span>
          ${revoked ? '' : `<button class="btn danger" data-revoke="${esc(d.id)}">${icon('trash', 14)}撤销</button>`}
        </div>
      </div>`;
    }).join('') : '<div class="empty"><b>还没有配对设备</b><p>配对后，这台手机的会话与审批会出现在这里。</p><button type="button" class="btn primary" data-go="pairing">扫码配对</button></div>';
    const el = $('#devices-list');
    if (el) el.innerHTML = rows;
    $$('[data-revoke]').forEach((btn) => on(btn, 'click', () => revokeDevice(btn.dataset.revoke)));
    const recent = $('#recent-devices');
    if (recent) {
      recent.innerHTML = active.length ? active.slice(0, 3).map((d) => `<div class="row">
        <div class="avatar${d.deviceClass === 'desktop' ? '' : ' cyan'}">${d.deviceClass === 'desktop' ? '▣' : '▯'}</div>
        <div class="main-col"><div class="title">${esc(d.name || '未命名设备')}${d.online ? '<span class="dot on" style="display:inline-block;margin-left:5px"></span>' : ''}</div><div class="meta">${esc(d.platform || '')} · ${esc(d.capability || '')}</div></div>
        <div class="time">${esc(relTime(d.lastSeen))}</div>
      </div>`).join('') : '';
    }
  }

  function renderSessions() {
    const list = sessions || [];
    setText('#nav-sessions', String(list.length));
    const cards = list.length ? list.map((s) => `<div class="session-card">
      <div class="top"><span class="dot ${s.status === 'streaming' ? 'on' : s.status === 'waiting_approval' ? 'warn' : ''}"></span><span class="title">${esc(s.name || s.preview || '未命名会话')}</span>${s.status && s.status !== 'idle' ? `<span class="pill ${statusTone(s.status)}" style="margin-left:auto">${esc(statusLabel(s.status))}</span>` : ''}</div>
      <div class="cwd">${esc(s.cwd || '—')}</div>
      <div class="foot"><span>${Number(s.messageCount) || 0} 条消息</span><span>·</span><span>${esc(relTime(s.updatedAt))}</span>${s.pendingApprovals ? `<span class="pill rose" style="margin-left:auto">${esc(s.pendingApprovals)} 待审批</span>` : ''}</div>
    </div>`).join('') : '<div class="empty" style="grid-column:1/-1"><b>还没有会话</b><p>在手机上发起，或打开 pi 终端。</p><button class="btn primary" data-go="pairing">查看配对</button></div>';
    const el = $('#sessions-list');
    if (el) el.innerHTML = cards;
    const recent = $('#recent-sessions');
    if (recent) {
      recent.innerHTML = list.length ? list.slice(0, 4).map((s) => `<div class="row">
        <div class="avatar">▤</div>
        <div class="main-col"><div class="title">${esc(s.name || s.preview || '未命名会话')}</div><div class="meta">${esc(s.cwd || '')}</div></div>
        ${s.status && s.status !== 'idle' ? `<span class="pill ${statusTone(s.status)}">${esc(statusLabel(s.status))}</span>` : ''}
        <div class="time">${esc(relTime(s.updatedAt))}</div>
      </div>`).join('') : '<div class="empty"><b>还没有会话</b><p>在手机上发起，或打开 pi 终端。</p></div>';
    }
  }

  async function refresh() {
    if (!invoke) {
      setText('#hero-title', '桌面运行时不可用');
      setText('#hero-sub', '请通过 pi carry 桌面应用启动。');
      return;
    }
    try {
      const next = await invoke('get_status');
      status = next || { running: false };
      renderStatus();
      if (status.running) {
        devices = status.devices || [];
        sessions = status.sessions || [];
        renderDevices();
        renderSessions();
      }
      renderSecret();
    } catch (err) {
      setText('#side-state', '节点离线');
      console.error(err);
    }
  }

  // ── 配对（DE-50..DE-59，F-31） ───────────────────────────────────
  function renderPairing() {
    const digits = String(pairing?.code || '').padStart(6, '·').split('').slice(0, 6);
    const codeRow = $('#code-row');
    if (codeRow) codeRow.innerHTML = digits.map((d) => `<div class="code-digit">${esc(d)}</div>`).join('');
    const active = !!pairing;
    const confirmed = !!pairing?.localConfirmed || !!pairing?.verified;
    const tag = $('#pair-state-tag');
    if (tag) {
      tag.textContent = confirmed ? '已配对' : active ? '等待设备' : '未连接';
      tag.className = `pill ${confirmed ? 'green' : active ? 'violet' : ''}`;
    }
    const confirmBtn = $('#btn-confirm-pair');
    if (confirmBtn) {
      // Q1 裁决（core 契约）：桌面 6 格只读展示、手机端输入核对；按钮只在拿到完整码且未确认时可用（DE-54）。
      // R2-04：后端锁定（3 次错码 60s）期间同样禁用，避免锁定期间仍可触发确认。
      const locked = !!(pairing?.lockedUntil && Date.parse(pairing.lockedUntil) > Date.now());
      confirmBtn.disabled = !active || confirmed || pairingBusy || locked || String(pairing?.code || '').length !== 6;
      confirmBtn.textContent = pairingBusy ? '配对中…' : confirmed ? '已配对' : '完成配对';
    }
    const cancelBtn = $('#btn-cancel-pair');
    if (cancelBtn) cancelBtn.disabled = !active;
    const copyUri = $('#copy-uri');
    if (copyUri) copyUri.disabled = !pairing?.uri;
    const steps = $$('.steps .step');
    steps.forEach((step, i) => step.classList.toggle('current', i === (confirmed ? 2 : active ? 1 : 0)));

    const btnPair = $('#btn-pair');
    if (btnPair) {
      // DESK-20：去掉独立的「重新生成二维码」动作，主按钮统一为「扫码配对」。
      // R2-09：配对进行中隐藏该入口，避免出现禁用的重复按钮。
      btnPair.textContent = '扫码配对';
      btnPair.hidden = active;
      btnPair.disabled = active;
    }
    renderPairLock();
  }

  function renderPairLock() {
    const lock = $('#pair-lock');
    if (!lock) return;
    clearInterval(lockTimer);
    const until = pairing?.lockedUntil ? Date.parse(pairing.lockedUntil) : NaN;
    const tick = () => {
      const left = Number.isFinite(until) ? Math.max(0, Math.ceil((until - Date.now()) / 1000)) : 0;
      if (left <= 0) {
        lock.hidden = true;
        clearInterval(lockTimer);
        const confirmBtn = $('#btn-confirm-pair');
        if (confirmBtn) confirmBtn.disabled = !pairing || !!pairing.localConfirmed || !!pairing.verified;
        const pairBtn = $('#btn-pair');
        if (pairBtn) pairBtn.disabled = false;
        return;
      }
      lock.hidden = false;
      setText('#pair-lock-count', String(left));
    };
    if (Number.isFinite(until) && until > Date.now()) {
      tick();
      lockTimer = setInterval(tick, 1000);
    } else {
      lock.hidden = true;
    }
  }

  async function startPairing() {
    if (!invoke || pairingBusy) return;
    const btn = $('#btn-pair');
    if (btn?.disabled) return;
    pairingBusy = true;
    if (btn) btn.disabled = true;
    try {
      const payload = await invoke('start_pairing', { relay: null });
      pairing = payload || null;
      setText('#pair-expiry', '二维码 5 分钟内有效');
      setText('#pair-status-text', '等待手机扫码');
      const dot = $('#pair-dot');
      if (dot) dot.className = 'dot warn';
      const frame = $('#qr-frame');
      if (frame) frame.innerHTML = payload?.qrSvg || '<div class="qr-placeholder">二维码生成失败</div>';
      renderPairing();
      clearInterval(pairTimer);
      pairTimer = setInterval(() => { updatePairExpiry(); pollPairing(); }, 1000);
      toast('配对已开始，二维码 5 分钟内有效');
    } catch (err) {
      toast(String(err), true);
    } finally {
      pairingBusy = false;
      if (btn) btn.disabled = false;
      renderPairing();
    }
  }

  function updatePairExpiry() {
    if (!pairing?.expiresAt) return;
    const left = Math.max(0, Math.floor((Date.parse(pairing.expiresAt) - Date.now()) / 1000));
    const mm = String(Math.floor(left / 60)).padStart(2, '0');
    const ss = String(left % 60).padStart(2, '0');
    // DESK-20：有效期文案固定为设计单行，倒计时只放进 title，不再用倒计时替换固定行。
    const expiry = $('#pair-expiry');
    if (expiry) {
      expiry.textContent = left > 0 ? '二维码 5 分钟内有效' : '二维码已过期';
      expiry.title = left > 0 ? `剩余 ${mm}:${ss}` : '';
    }
    if (left <= 0) {
      clearInterval(pairTimer);
      pairing = null;
      const dot = $('#pair-dot');
      if (dot) dot.className = 'dot err';
      setText('#pair-status-text', '二维码已过期，请刷新');
      const frame = $('#qr-frame');
      if (frame) frame.innerHTML = '<div class="qr-placeholder">二维码已过期，请刷新</div>';
      renderPairing();
    }
  }

  async function pollPairing() {
    if (!invoke || !pairing) return;
    try {
      const res = await invoke('pairing_status');
      const st = res?.status || {};
      pairing = { ...(pairing || {}), ...st, active: !!res?.active };
      if (!res?.active) {
        clearInterval(pairTimer);
        const dot = $('#pair-dot');
        if (dot) dot.className = 'dot on';
        setText('#pair-status-text', st.verified ? '已配对' : '配对会话已结束');
        pairing = null;
        renderPairing();
        refresh();
        return;
      }
      const dot = $('#pair-dot');
      if (dot) dot.className = 'dot on';
      // DE-56 短句：等待/已扫码/已确认，不叠加教程。
      if (st.verified) setText('#pair-status-text', '已配对');
      else if (st.localConfirmed) setText('#pair-status-text', '已确认，等待手机接入…');
      else if (Number(st.attemptsRemaining) < 5) setText('#pair-status-text', '手机已扫码，正在核对…');
      else setText('#pair-status-text', '等待手机扫码');
      renderPairing();
    } catch { /* 轮询失败不打扰用户 */ }
  }

  async function confirmPairing() {
    if (!invoke || !pairing || pairingBusy) return;
    pairingBusy = true;
    renderPairing();
    try {
      // DESK-27：confirm_pairing 返回 bool，false 时保留等待态并提示重试（不静默置已确认）。
      const confirmed = await invoke('confirm_pairing');
      if (!confirmed) {
        toast('配对未完成，请重试', true);
        setText('#pair-status-text', '配对失败，请重试');
        return;
      }
      pairing = { ...(pairing || {}), localConfirmed: true };
      setText('#pair-status-text', '已配对');
      toast('已配对，可以开始了');
      renderPairing();
      setTimeout(refresh, 600);
    } catch (err) {
      toast(String(err), true);
      const confirmBtn = $('#btn-confirm-pair');
      if (confirmBtn) confirmBtn.focus();
    } finally {
      pairingBusy = false;
      renderPairing();
    }
  }

  async function cancelPairing() {
    try {
      await invoke('cancel_pairing');
    } catch (err) { toast(String(err), true); }
    pairing = null;
    clearInterval(pairTimer);
    const frame = $('#qr-frame');
    if (frame) frame.innerHTML = '<div class="qr-placeholder"><span data-icon="qr" data-size="40"></span><span>扫码开始配对</span></div>';
    setText('#pair-expiry', '二维码 5 分钟内有效');
    setText('#pair-status-text', '等待手机扫码');
    const dot = $('#pair-dot');
    if (dot) dot.className = 'dot';
    renderPairing();
    toast('已取消配对');
  }

  async function revokeDevice(id) {
    const device = (devices || []).find((d) => String(d.id) === String(id));
    const name = device?.name || '该设备';
    const ok = await askConfirm(`撤销 ${name}？`, '该设备将无法再连接；重新连接需要重新扫码配对。', { ok: '撤销', danger: true });
    if (!ok) return;
    try {
      await invoke('revoke_device', { id });
      toast('设备已撤销');
      refresh();
    } catch (err) { toast(String(err), true); }
  }

  function renderPairPrefill() {
    const el = $('#pair-prefill');
    if (!el) return;
    const payload = pendingPair;
    if (!payload || payload.valid === undefined) { el.hidden = true; return; }
    const body = $('#pair-prefill-body');
    const actions = $('#pair-prefill-actions');
    el.hidden = false;
    el.className = `banner ${payload.valid ? 'violet' : 'rose'}`;
    if (!payload.valid) {
      body.innerHTML = `<b>配对链接无效 <span class="pill rose">未确认</span></b><div class="banner-sub">${esc(payload.error || '无法解析这条配对链接，请让手机端重新生成。')}</div>`;
      actions.innerHTML = '<button class="btn ghost" data-pair-act="dismiss">忽略</button>';
      return;
    }
    const rows = [`<div class="banner-sub mono">Endpoint ${esc(payload.endpointId || '—')}</div>`];
    if (payload.name) rows.push(`<div class="banner-sub">名称 ${esc(payload.name)}</div>`);
    if (payload.uri) rows.push(`<details><summary>查看完整链接</summary><div class="banner-sub mono">${esc(payload.uri)}</div></details>`);
    body.innerHTML = `<b>已从链接填入配对信息 <span class="pill amber">未确认</span></b>${rows.join('')}`;
    actions.innerHTML = '<button class="btn ghost" data-pair-act="dismiss">忽略</button>';
  }

  function dismissPairPrefill() {
    pendingPair = null;
    renderPairPrefill();
  }

  // ── 设置 ─────────────────────────────────────────────────────────
  async function loadSettings() {
    if (!invoke) return;
    try {
      settings = await invoke('get_settings');
      const s = settings || {};
      const nodeName = $('#set-node-name');
      if (nodeName) nodeName.value = s.node?.name || '';
      const piPath = $('#set-pi-path');
      if (piPath) piPath.value = s.node?.piPath || '';
      const timeout = $('#set-approval-timeout');
      if (timeout) timeout.value = s.approvals?.timeoutSecs ?? 120;
      const roots = $('#set-allowed-roots');
      if (roots) roots.value = (s.sessions?.allowedRoots || []).join('\n');
      const urls = $('#set-relay-urls');
      if (urls) urls.value = (s.transport?.relayUrls || []).join('\n');
      setSwitch('#set-close-tray', !!s.closeToTray);
      setSwitch('#set-autostart', !!s.service?.autostart);
      setSwitch('#set-start-minimized', !!s.service?.startMinimized);
      setSwitch('#set-check-update', !!s.updates?.checkOnStartup);
      const logLevel = String(s.service?.logLevel || '');
      const logSelect = $('#set-log-level');
      if (logSelect) logSelect.value = ['error', 'warn', 'info', 'debug', 'trace'].includes(logLevel) ? logLevel : 'info';
      setText('#data-dir', s.dataDir || '—');
      setText('#cache-size', '—');
      selectSegment('set-relay', s.transport?.relayMode || 'n0');
      updateRelayHint();
      selectSegment('set-capability', s.sessions?.defaultCapability || 'full');
      selectSegment('set-update-channel', s.updates?.channel === 'beta' ? 'beta' : 'stable');
      selectSegment('set-theme', themeMode);
      applyTheme(s.ui?.theme || localStorage.getItem('pi_carry_theme') || 'auto', false);
      renderUpdateSettings();
      renderSecurityCard();
      // DE-67：记录已落盘值，blur 时未变化不重复保存。
      ['#set-node-name', '#set-pi-path', '#set-approval-timeout', '#set-allowed-roots', '#set-relay-urls']
        .forEach((sel) => { const el = $(sel); if (el) el.dataset.savedValue = String(el.value); });
      // R2-03：设置加载后按 checkOnStartup 对齐自动检查定时器（关闭时清掉）。
      scheduleAutoCheck();
    } catch (err) { toast(String(err), true); }
  }

  function setSwitch(sel, value) {
    const el = $(sel);
    if (el) {
      el.classList.toggle('on', !!value);
      el.setAttribute('aria-checked', value ? 'true' : 'false');
    }
  }

  function selectSegment(id, value) {
    const root = document.getElementById(id);
    if (!root) return;
    root.dataset.value = value;
    $$('button', root).forEach((b) => {
      const active = b.dataset.value === value;
      b.classList.toggle('active', active);
      if (b.getAttribute('role') === 'radio') b.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  }

  function segmentValue(id) {
    return document.getElementById(id)?.dataset.value || '';
  }

  function applyTheme(mode, persist) {
    themeMode = ['auto', 'dark', 'light'].includes(mode) ? mode : 'auto';
    const dark = themeMode === 'dark' || (themeMode === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    try { localStorage.setItem('pi_carry_theme', themeMode); } catch { /* 忽略 */ }
    selectSegment('set-theme', themeMode);
    if (invoke) {
      invoke('set_window_theme', { theme: themeMode }).catch(() => {});
      if (persist) invoke('update_settings', { values: { theme: themeMode } }).catch((err) => toast(String(err), true));
    }
  }

  function updateRelayHint() {
    const mode = segmentValue('set-relay');
    const field = $('#relay-urls-field');
    if (field) field.style.display = mode === 'custom' ? '' : 'none';
    setText('#relay-hint', mode === 'disabled'
      ? '禁用中继后只能在同一局域网直连；跨网络将无法连接。'
      : mode === 'custom'
        ? '使用你信任的中继服务；直连仍然优先。'
        : 'n0 提供开箱即用的穿透，但中继节点会看到连接元数据。');
    const forced = $('#forced-relay-tag');
    if (forced) forced.hidden = mode !== 'disabled';
  }

  async function persistSwitch(sel, key, value) {
    const el = $(sel);
    if (!el) return;
    const next = value === undefined ? !el.classList.contains('on') : !!value;
    el.classList.toggle('on', next);
    el.setAttribute('aria-checked', next ? 'true' : 'false');
    try {
      await invoke('update_settings', { values: { [key]: next } });
      if (settings) {
        if (key === 'closeToTray') settings.closeToTray = next;
        else if (key === 'autoStart' || key === 'startMinimized') settings.service = { ...(settings.service || {}), [key]: next };
        else if (key === 'checkOnStartup') settings.updates = { ...(settings.updates || {}), checkOnStartup: next };
      }
      showInlineSaved(el);
      // R2-03：开机检查开关变化后立即重排/清除自动检查定时器。
      if (key === 'checkOnStartup') scheduleAutoCheck();
    } catch (err) {
      el.classList.toggle('on', !next);
      el.setAttribute('aria-checked', !next ? 'true' : 'false');
      toast(String(err), true);
    }
  }
  async function saveSettings() {
    if (!invoke) return;
    const roots = $('#set-allowed-roots');
    const urls = $('#set-relay-urls');
    const timeoutEl = $('#set-approval-timeout');
    const rawTimeout = Number(timeoutEl?.value);
    const approvalTimeoutSecs = Math.min(3600, Math.max(10, Number.isFinite(rawTimeout) ? Math.round(rawTimeout) : 120));
    if (timeoutEl) timeoutEl.value = String(approvalTimeoutSecs);
    const payload = {
      nodeName: $('#set-node-name')?.value.trim() || '',
      piPath: $('#set-pi-path')?.value.trim() || 'pi',
      approvalTimeoutSecs,
      allowedRoots: (roots?.value || '').split('\n').map((s) => s.trim()).filter(Boolean),
      relayMode: segmentValue('set-relay'),
      relayUrls: (urls?.value || '').split('\n').map((s) => s.trim()).filter(Boolean),
      closeToTray: $('#set-close-tray')?.classList.contains('on') || false,
      autoStart: $('#set-autostart')?.classList.contains('on') || false,
      startMinimized: $('#set-start-minimized')?.classList.contains('on') || false,
      logLevel: $('#set-log-level')?.value || 'info',
      defaultCapability: segmentValue('set-capability'),
      theme: themeMode,
    };
    try {
      const res = await invoke('update_settings', { values: payload });
      toast(res?.restartRequired ? '设置已保存；重启桥接后生效' : '设置已保存');
      refresh();
    } catch (err) {
      toast(String(err), true);
    }
  }

  // ── DE-67/DE-68 即时保存（设计无全局保存按钮） ─────────────────
  function settingsActionsRow() {
    const save = $('#btn-save-settings');
    return save ? save.closest('.actions-row') : null;
  }

  function setSettingsActionsVisible(visible) {
    const row = settingsActionsRow();
    if (row) row.style.display = visible ? '' : 'none';
  }

  /** 首帧隐藏整行（保存在 app.js 加载时立即执行，避免静态截图/生产出现按钮）。 */
  function hideSettingsActions() { setSettingsActionsVisible(false); }

  let saveErrorEl = null;

  function clearSaveError() {
    if (saveErrorEl && typeof saveErrorEl.remove === 'function') saveErrorEl.remove();
    saveErrorEl = null;
  }

  function showSaveError(message) {
    // 保存失败：就地玫色一行 + 露出保存/重新读取按钮作为重试入口（DE-67）。
    setSettingsActionsVisible(true);
    const card = $('#btn-save-settings')?.closest('.settings-card');
    if (!card || typeof card.appendChild !== 'function') return;
    clearSaveError();
    const row = document.createElement('div');
    row.className = 'row-error';
    row.textContent = `保存失败：${message}`;
    card.appendChild(row);
    saveErrorEl = row;
  }

  function applySettingsLocally(field, value) {
    if (!settings) return;
    if (field === 'nodeName') settings.node = { ...(settings.node || {}), name: value };
    else if (field === 'piPath') settings.node = { ...(settings.node || {}), piPath: value };
    else if (field === 'approvalTimeoutSecs') settings.approvals = { ...(settings.approvals || {}), timeoutSecs: value };
    else if (field === 'allowedRoots' || field === 'defaultCapability') settings.sessions = { ...(settings.sessions || {}), [field]: value };
    else if (field === 'relayMode' || field === 'relayUrls') settings.transport = { ...(settings.transport || {}), [field]: value };
    else if (field === 'logLevel') settings.service = { ...(settings.service || {}), logLevel: value };
  }

  /** DE-67 / DESK-05：即时保存成功在对应行内显示 1.5s「已保存」并淡出，不再用全局 toast。 */
  function showInlineSaved(element, message = '已保存') {
    const row = element && element.closest ? (element.closest('.setting-row') || element.closest('.settings-card')) : null;
    if (!row) { toast(message); return; }
    const old = row.querySelector('.saved-hint');
    if (old) old.remove();
    const hint = document.createElement('span');
    hint.className = 'row-sub saved-hint';
    hint.textContent = message;
    hint.style.cssText = 'margin-left:8px;opacity:1;transition:opacity .35s ease';
    row.appendChild(hint);
    setTimeout(() => {
      hint.style.opacity = '0';
      setTimeout(() => hint.remove(), 380);
    }, 1200);
  }

  async function instantSave(field, value, element) {
    if (!invoke) return;
    clearSaveError();
    try {
      const res = await invoke('update_settings', { values: { [field]: value } });
      if (element) element.dataset.savedValue = String(element.value ?? '');
      applySettingsLocally(field, value);
      showInlineSaved(element, res?.restartRequired ? '已保存，重启桥接后生效' : '已保存');
    } catch (err) {
      showSaveError(String(err));
    }
  }

  function bindInstantSettings() {
    // 文本：blur 保存（DE-67）；下拉：change 保存；开关与段控在各自交互处即时保存。
    const textFields = [
      ['#set-node-name', 'nodeName', (v) => v.trim()],
      ['#set-pi-path', 'piPath', (v) => v.trim() || 'pi'],
      ['#set-approval-timeout', 'approvalTimeoutSecs', (v) => {
        const n = Number(v);
        return Math.min(3600, Math.max(10, Number.isFinite(n) ? Math.round(n) : 120));
      }],
      ['#set-allowed-roots', 'allowedRoots', (v) => v.split('\n').map((s) => s.trim()).filter(Boolean)],
      ['#set-relay-urls', 'relayUrls', (v) => v.split('\n').map((s) => s.trim()).filter(Boolean)],
    ];
    textFields.forEach(([sel, field, parse]) => {
      const el = $(sel);
      if (!el) return;
      on(el, 'blur', () => {
        const next = parse(el.value);
        if (sel === '#set-approval-timeout') el.value = String(next);
        const marker = String(el.value);
        if (el.dataset.savedValue === marker) return;
        instantSave(field, next, el);
      });
    });
    const logSelect = $('#set-log-level');
    if (logSelect) on(logSelect, 'change', () => instantSave('logLevel', logSelect.value));
  }

  function renderSecurityCard() {
    const backend = selfCheck?.secretBackend ?? status?.secretBackend;
    const pill = $('#secret-state-pill');
    if (pill) {
      pill.textContent = backend === 'keyring' ? '系统钥匙串' : '文件';
      pill.className = `pill ${backend === 'keyring' ? 'green' : 'amber'}`;
    }
    // Q-02：core 无独立迁移命令（SecretStore::load_or_create 启动时自动迁移），
    // 按设计隐藏迁移按钮，不显示假的「迁移中」。
    const migrate = $('#btn-migrate-secret');
    if (migrate) { migrate.hidden = true; hideRow('#btn-migrate-secret'); }
    const unsupported = $('#security-unsupported');
    if (unsupported) unsupported.hidden = true;
    const banner = $('#secret-banner');
    if (banner) banner.hidden = backend !== 'file' || sessionStorage.getItem(UPDATES.SECRET_DISMISS_KEY) === '1';
    const topPill = $('#secret-pill');
    if (topPill) topPill.hidden = backend !== 'file';
  }

  function renderSecret() {
    renderSecurityCard();
  }

  async function loadLogs() {
    if (!invoke) return;
    try {
      const res = await invoke('get_logs', { lines: 300 });
      setText('#log-path', res?.path || 'desktop.log');
      renderLogLines(res?.lines || []);
    } catch (err) {
      const view = $('#log-view');
      if (view) view.innerHTML = `<div class="log-empty">${esc(String(err))}</div>`;
    }
  }

  let logLines = [];

  function renderLogLines(lines) {
    logLines = Array.isArray(lines) ? lines : [];
    const query = ($('#log-search')?.value || '').trim().toLowerCase();
    const level = segmentValue('log-level') || 'all';
    const rows = logLines
      .map((line) => {
        const text = String(line);
        const m = text.match(/^(\d{4}-\d{2}-\d{2}[T ][\d:.+Z-]+)\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+([\s\S]*)$/);
        return m ? { time: m[1], level: m[2], msg: m[3] } : { time: '', level: '', msg: text };
      })
      .filter((row) => {
        // 三档：全部 / 信息（INFO 及以上）/ 错误（仅 ERROR）。
        if (level === 'error' && row.level !== 'ERROR') return false;
        if (level === 'info' && !['INFO', 'WARN', 'ERROR'].includes(row.level)) return false;
        if (query && !row.msg.toLowerCase().includes(query)) return false;
        return true;
      });
    const view = $('#log-view');
    if (!view) return;
    view.innerHTML = rows.length
      ? rows.map((row) => {
        // R2-15：时间列只显示 HH:MM:SS，完整时间放 title，避免 88px 列折行。
        const shortTime = row.time ? row.time.replace(/^\d{4}-\d{2}-\d{2}[T ]/, '').replace(/Z$/, '') : '';
        return `<div class="log-line"><span class="log-time" title="${esc(row.time)}">${esc(shortTime)}</span>${row.level ? `<span class="log-level ${row.level === 'ERROR' ? 'err' : row.level === 'WARN' ? 'warn' : 'info'}">${esc(row.level)}</span>` : ''}<span class="log-msg">${esc(row.msg)}</span></div>`;
      }).join('')
      : '<div class="log-empty">暂无日志</div>';
    const autoscroll = $('#log-autoscroll');
    if (autoscroll?.classList.contains('on')) view.scrollTop = view.scrollHeight;
    // DESK-11：脚注左侧 #log-path 保留，只更新右侧字数说明，避免整块 textContent 覆盖把路径销毁。
    const countEl = $('#log-count');
    if (countEl) countEl.textContent = '最近 5000 行';
  }

  // ── 更新状态机（D.8） ───────────────────────────────────────────
  function rawVersion() { return String(updateState?.version ?? '').trim(); }

  function updatePayloadVersion() { return versionText(updateState?.version); }

  function updateSkippedVersion() {
    try { return localStorage.getItem(UPDATES.SKIP_KEY) || ''; } catch { return ''; }
  }

  function setSkippedVersion(version) {
    try {
      if (version) localStorage.setItem(UPDATES.SKIP_KEY, version);
      else localStorage.removeItem(UPDATES.SKIP_KEY);
    } catch { /* 忽略 */ }
    renderUpdateBanner();
    renderUpdateSettings();
    renderUpdatePill();
  }

  function updateSignatureText() {
    // DE-89 / DESK-25：下载前只承诺「下载后校验」，校验通过前绝不显示「已校验」。
    if (updatePhase === 'installing' || updatePhase === 'installed') return '发布签名 · 已校验';
    if (updatePhase === 'verifying') return '发布签名 · 校验中';
    if (updateError?.kind === 'signature') return '发布签名 · 校验失败';
    return '发布签名 · 下载后校验';
  }

  function renderUpdatePill() {
    const pill = $('#update-pill');
    if (!pill) return;
    if (selfCheckBlocking()) { pill.hidden = true; announce(''); return; }
    let text = '';
    let tone = 'violet';
    if (updatePhase === 'downloading' || updatePhase === 'verifying') text = updatePhase === 'verifying' ? '校验中' : `下载中 ${progressPercent()}%`;
    else if (updatePhase === 'installing') text = '待安装';
    else if (updatePhase === 'installed') text = '重启安装';
    else if (updateError?.kind === 'signature') { text = '更新被阻止'; tone = 'rose'; }
    else if (updateError?.kind === 'check') text = '';
    else if (updateState?.available && !isSkipped()) {
      if (updateState.suppressed) { text = '更新被阻止'; tone = 'amber'; }
      else text = '更新可用';
    }
    pill.hidden = !text;
    if (text) {
      pill.textContent = text;
      pill.className = `pill update-pill ${tone}`;
    }
    // DESK-22：只播报状态变化；下载百分比由进度条自己承担，避免 aria-live 刷屏。
    announce(updatePhase === 'downloading' ? '' : text);
  }

  function isSkipped() {
    return !!updateState?.version && updateSkippedVersion() === String(updateState.version);
  }

  function progressPercent() {
    const { downloaded, total } = updateProgress;
    if (!total) return updateState?.available ? 0 : 100;
    return Math.min(100, Math.max(0, Math.round((downloaded / total) * 100)));
  }

  /** 九种横幅状态：可用 / 下载中 / 校验中 / 安装中 / 已安装 / 检查失败 / 验签阻止 / 抑制 / 便携。 */
  function renderUpdateBanner() {
    const el = $('#update-banner');
    if (!el) return;
    renderUpdatePill();
    let tone;
    let iconName = 'download';
    let body = '';
    let actions = '';
    let show = true;

    // DE-82：首启自检阻断时隐藏更新横幅。
    if (selfCheckBlocking()) show = false;
    if (updateError && updateError.kind === 'check') {
      show = false;
    } else if (updateError && updateError.kind === 'signature') {
      tone = 'rose';
      iconName = 'alert';
      body = `<b>更新被阻止</b><div class="update-meta">${esc(updateError.message || '下载文件与发布签名不匹配，已阻止安装。')}</div>`;
      actions = '<button class="btn ghost" data-update-act="sigfail">查看原因</button><button class="btn ghost" data-update-act="download">手动下载</button>';
    } else if (!updatePhase && updateCheckError && !(updateState && updateState.available)) {
      // DE-82：最近一次检查失败（且没有可用更新）时用琥珀横幅说明。
      tone = 'amber';
      iconName = 'alert';
      body = `<b>检查更新失败</b><div class="update-meta">${esc(updateCheckError.slice(0, 140))}</div>`;
      actions = '<button class="btn ghost" data-update-act="check">重试</button>';
    } else if (updateError) {
      // DESK-06 / DE-96：失败就地展开为玫色原因行 + 重试/手动下载；连续 3 次提示检查网络并给日志入口。
      tone = 'rose';
      iconName = 'alert';
      const many = installFailures >= 3;
      body = `<b>${many ? '下载多次失败，请检查网络或代理。' : '下载失败，请检查网络或代理。'}</b>`
        + `<div class="update-meta">${esc(updateError.message || '未知错误')}</div>`;
      actions = many
        ? '<button class="btn ghost" data-update-act="logs">打开日志</button><button class="btn ghost" data-update-act="download">手动下载</button>'
        : '<button class="btn primary" data-update-act="retry">重试</button><button class="btn ghost" data-update-act="download">手动下载</button>';
    } else if (updatePhase === 'downloading' || updatePhase === 'verifying') {
      tone = 'cyan';
      iconName = 'download';
      if (updatePhase === 'verifying') {
        body = '<b>正在校验签名</b><div class="update-meta mono">校验中…</div>';
      } else {
        const pct = progressPercent();
        const retry = updateProgress.retry;
        body = `<b>正在下载更新 ${esc(updatePayloadVersion())}</b>`
          + (retry
            ? `<div class="update-meta mono">${retry.delaySeconds}s 后重试（第 ${retry.attempt}/3 次）</div>`
            : `<div class="update-meta mono">下载中 ${pct}%${updateSpeed.bytesPerSec ? ` · ${fmtBytes(updateSpeed.bytesPerSec)}/s` : ''}</div>`
              + `<div class="progress"><i style="width:${pct}%"></i></div>`);
      }
      actions = '<button class="btn ghost" data-update-act="details">查看进度</button>';
    } else if (updatePhase === 'installing') {
      tone = 'cyan';
      iconName = 'download';
      body = '<b>正在安装更新</b><div class="update-meta">安装中，请勿关闭应用</div>';
    } else if (updatePhase === 'installed') {
      tone = 'green';
      iconName = 'download';
      body = `<b>已更新到 ${esc(updatePayloadVersion() || '新版本')}</b><div class="update-meta">重启后生效</div>`;
      actions = '<button class="btn primary" data-update-act="restart">立即重启</button>';
    } else if (!updateState || !updateState.available) {
      show = false;
    } else if (isSkipped() || suspendedBanner) {
      show = false;
    } else if (updateState.suppressed) {
      // R2-14/DESK-36：设计内无「被阻止」横幅态，顶栏 pill 已能表达，横幅不渲染。
      show = false;
    } else if (updateState.portable || status?.portable) {
      tone = 'amber';
      iconName = 'download';
      body = `<b>发现新版本 ${esc(rawVersion())}（便携版）</b><div class="update-meta">便携模式不自动更新，请手动下载。</div>`;
      actions = '<button class="btn primary" data-update-act="download">手动下载</button><button class="btn ghost" data-update-act="details">查看详情</button>';
    } else {
      // DESK-02 / DE-83：可用态横幅只留「查看详情」+「稍后」+ 关闭 X，副标题只写 发布于/通道（跳过与安装入口在详情里）。
      tone = 'violet';
      const date = fmtDate(updateState.date);
      const channel = updateState.channel === 'beta' ? '预览通道' : '稳定通道';
      body = `<b>发现新版本 <span class="mono">${esc(rawVersion())}</span></b>`
        + `<div class="update-meta">${esc([date ? `发布于 ${date}` : '', channel].filter(Boolean).join(' · '))}</div>`;
      actions = '<button class="link-btn" data-update-act="details">查看详情</button>'
        + '<button class="link-btn" data-update-act="later">稍后</button>'
        + '<button class="icon-btn" data-update-act="later" aria-label="关闭"><span class="icon-x" aria-hidden="true"></span></button>';
    }

    if (!show) { el.hidden = true; return; }
    el.className = `banner ${tone}`;
    el.innerHTML = `${icon(iconName, 17)}<div class="grow">${body}</div><div class="actions">${actions}</div>`;
    el.hidden = false;
  }

  function renderUpdateProgressOnly() {
    const bar = $('#update-banner .progress > i');
    if (bar) bar.style.width = `${progressPercent()}%`;
    const meta = $('#update-banner .update-meta');
    if (meta && updatePhase === 'downloading') {
      meta.textContent = `下载中 ${progressPercent()}%${updateSpeed.bytesPerSec ? ` · ${fmtBytes(updateSpeed.bytesPerSec)}/s` : ''}`;
    }
    renderUpdatePill();
    renderUpdateDialogProgress();
  }

  function applyUpdateCheck(payload, opts = {}) {
    const previous = updateState;
    const sameVersion = !!(previous?.version && payload?.version && String(previous.version) === String(payload.version));
    updateState = payload && typeof payload === 'object' ? payload : null;
    updatePhase = null;
    updateError = null;
    updateCheckError = '';
    updateProgress = { downloaded: 0, total: null, retry: null };
    updateSpeed = { samples: [], bytesPerSec: null };
    // DESK-17：后端事件带来的自动结果不重置用户已选的「稍后」与失败计数；真正的手动/首查才重置。
    if (!opts.fromEvent || !sameVersion) suspendedBanner = false;
    if (!opts.fromEvent) installFailures = 0;
    manualCheckAt = opts.manual ? Date.now() : manualCheckAt;
    try {
      // 记录最近一次检查时间（手动/自动都算）：自动检查按 24h 节流（DE-80）。
      if (payload?.checkedAt) {
        const checked = Date.parse(payload.checkedAt);
        if (Number.isFinite(checked)) localStorage.setItem(UPDATES.AUTO_KEY, String(checked));
      } else {
        localStorage.setItem(UPDATES.AUTO_KEY, String(Date.now()));
      }
    } catch { /* 忽略 */ }
    renderUpdateBanner();
    renderUpdatePill();
    renderUpdateSettings();
    renderUpdateDialog();
  }

  function isThrottled() {
    try {
      const last = Number(localStorage.getItem(UPDATES.AUTO_KEY) || 0);
      return last > 0 && Date.now() - last < UPDATES.THROTTLE;
    } catch { return false; }
  }

  /** R2-03：自动检查统一由设置项控制（后端 30s 起步检查已删，这里是唯一入口）。 */
  function autoCheckEnabled() {
    return settings?.updates?.checkOnStartup !== false;
  }

  function scheduleAutoCheck(delay = UPDATES.FIRST_DELAY) {
    clearTimeout(updateTimer);
    if (!invoke || !autoCheckEnabled()) return;
    const remaining = (() => {
      try {
        const last = Number(localStorage.getItem(UPDATES.AUTO_KEY) || 0);
        if (!last) return 0;
        return Math.max(0, UPDATES.THROTTLE - (Date.now() - last));
      } catch { return 0; }
    })();
    const wait = Math.max(delay, remaining);
    updateTimer = setTimeout(() => { autoCheck(); }, wait);
  }

  async function autoCheck() {
    if (!autoCheckEnabled()) return;
    if (selfCheckBlocking()) return;
    if (isThrottled()) { scheduleAutoCheck(60_000); return; }
    try {
      const result = await invoke('check_update', { manual: false });
      applyUpdateCheck(result, { manual: false });
    } catch { /* 自动检查失败保持安静，设置卡显示最近一次手动原因 */ }
    scheduleAutoCheck(UPDATES.THROTTLE);
  }

  function progressLegend() {
    const { downloaded, total } = updateProgress;
    const left = total ? `${fmtBytes(downloaded)} / ${fmtBytes(total)}` : `${fmtBytes(downloaded)} / 未知`;
    if (updatePhase === 'verifying') return { left, right: '校验中…' };
    if (updatePhase === 'installing') return { left, right: '安装中…' };
    if (updateProgress.retry) return { left, right: `${updateProgress.retry.delaySeconds}s 后重试（第 ${updateProgress.retry.attempt}/3 次）` };
    const pct = progressPercent();
    let right = `${pct}%`;
    if (updateSpeed.bytesPerSec) right += ` · ${fmtBytes(updateSpeed.bytesPerSec)}/s`;
    if (total && updateSpeed.bytesPerSec) {
      const remain = Math.max(0, Math.round((total - downloaded) / updateSpeed.bytesPerSec));
      right += ` · 剩余 ${remain}s`;
    }
    return { left, right };
  }

  function renderUpdateStages() {
    const stages = $('#update-stages');
    if (!stages) return;
    const rows = $$('.stage', stages);
    const labels = ['下载', '校验签名', '安装'];
    const values = ['等待', '等待', '等待'];
    const states = ['pending', 'pending', 'pending'];
    const setState = (index, state, label) => { states[index] = state; if (label) values[index] = label; };
    if (updatePhase === 'downloading') {
      setState(0, 'active', updateProgress.retry ? '重试中' : '进行中');
    } else if (updatePhase === 'verifying') {
      setState(0, 'done', '完成');
      setState(1, 'active', '进行中');
    } else if (updatePhase === 'installing') {
      setState(0, 'done', '完成');
      setState(1, 'done', '完成');
      setState(2, 'active', '进行中');
    } else if (updatePhase === 'installed') {
      setState(0, 'done', '完成');
      setState(1, 'done', '完成');
      setState(2, 'done', '完成');
    } else if (updateError) {
      // 失败段：验签失败停在「校验签名」，安装类失败停在「安装」。
      setState(0, 'done', '完成');
      if (updateError.kind === 'signature') setState(1, 'failed', '失败');
      else { setState(1, 'done', '完成'); setState(2, 'failed', '失败'); }
    }
    rows.forEach((row, i) => {
      row.className = `stage ${states[i]}`;
      const labelEl = $('.stage-label', row);
      const valEl = $('.stage-val', row);
      if (labelEl) labelEl.textContent = labels[i];
      if (valEl) valEl.textContent = values[i];
    });
    stages.hidden = !(updatePhase || updateError);
  }

  function renderUpdateDialogProgress() {
    const block = $('#update-progress');
    if (!block) return;
    const downloading = updatePhase === 'downloading' || updatePhase === 'verifying';
    // DESK-15 / DE-97：安装阶段不再展示百分比与剩余时间（只保留阶段列表与「安装中，请勿关闭应用」）。
    block.hidden = !downloading;
    if (!downloading) return;
    const bar = $('.progress', block);
    if (bar) {
      const pct = progressPercent();
      bar.setAttribute('aria-valuenow', String(pct));
      const fill = $('i', bar);
      if (fill) fill.style.width = `${pct}%`;
    }
    const spans = $$('.progress-legend span', block);
    const legend = progressLegend();
    if (spans[0]) spans[0].textContent = legend.left;
    if (spans[1]) spans[1].textContent = legend.right;
    const cancel = $('#update-cancel-download');
    if (cancel) {
      cancel.hidden = false;
      cancel.disabled = false;
      cancel.textContent = '取消下载';
    }
  }

  function renderUpdateDialog() {
    const overlay = $('#update-overlay');
    if (!overlay) return;
    const installing = updatePhase === 'installing';
    const busy = updatePhase === 'downloading' || updatePhase === 'verifying' || installing;
    const progressVisible = updatePhase === 'downloading' || updatePhase === 'verifying' || installing;
    const sigError = updateError?.kind === 'signature';
    setText('#update-title', updateError
      ? (sigError ? '签名校验失败' : '更新失败')
      : updatePhase && updatePhase !== 'installed'
        ? `正在更新到 ${rawVersion()}`.trim()
        : updatePhase === 'installed' ? `已更新到 ${rawVersion()}`.trim() : `发现新版本 ${rawVersion()}`.trim());
    const date = fmtDate(updateState?.date);
    const channel = updateState?.channel === 'beta' ? '预览通道' : '稳定通道';
    setText('#update-sub', updateError
      ? String(updateError.message || '更新失败')
      : installing
        ? '安装中，请勿关闭应用'
        : updatePhase === 'installed'
          ? '重启后完成更新'
          : (date ? `${channel} · 发布于 ${date}` : channel));
    const dateEl = $('#update-date');
    if (dateEl) { dateEl.hidden = true; dateEl.textContent = ''; }

    const notes = $('#update-notes');
    if (notes) {
      const lines = notesList(updateState?.notes);
      notes.innerHTML = lines.length ? `<ul>${lines.map((line) => `<li>${esc(line)}</li>`).join('')}</ul>` : '';
    }
    const notesWrap = $('.notes-wrap', overlay);
    if (notesWrap) notesWrap.hidden = !!(updateState && !updateState.available && !updatePhase);

    const kv = $$('.update-kv .v', overlay);
    if (kv[0]) kv[0].textContent = updateState?.sizeBytes == null ? '未知' : fmtBytes(updateState.sizeBytes);
    if (kv[1]) kv[1].textContent = updateSignatureText();
    if (kv[2]) kv[2].textContent = updateState?.source || '—';

    const restartRow = $('label.check-row', overlay);
    // DESK-08/R2-05：勾选行在全部平台显示（隐藏时却可能仍触发自动重启，行为不一致）。
    if (restartRow) restartRow.hidden = progressVisible;

    renderUpdateStages();
    renderUpdateDialogProgress();

    const installBtn = $('#update-install');
    if (installBtn) {
      // DESK-14：下载/校验/安装期间一律禁用，避免重复触发并发下载。
      installBtn.disabled = busy;
      installBtn.setAttribute('aria-busy', busy ? 'true' : 'false');
      if (updatePhase === 'installed') {
        installBtn.textContent = '重启以完成更新';
        installBtn.disabled = false;
      } else if (installing) installBtn.textContent = '安装中…';
      else if (busy) installBtn.textContent = '正在更新…';
      else {
        const restart = $('#update-restart');
        const portable = !!(updateState?.portable || status?.portable);
        // R2-06：便携版不存在自动安装，「稍后安装」语义无效，固定为下载文案。
        installBtn.textContent = portable ? '下载新版本'
          : (restart && !restart.checked) ? '下载并稍后安装' : '立即安装';
      }
    }
    const manualBtn = $('#update-download');
    if (manualBtn) manualBtn.hidden = busy; // DESK-26：下载/校验中只保留取消下载。
    const closeBtn = $('#update-close');
    if (closeBtn) closeBtn.disabled = installing;
    const cancelBtn = $('#update-cancel');
    if (cancelBtn) {
      cancelBtn.disabled = installing;
      cancelBtn.textContent = busy ? '关闭' : '取消';
      cancelBtn.title = busy ? '关闭对话框不会取消下载' : '';
    }
    const skip = $('#update-skip');
    if (skip) skip.hidden = progressVisible || updatePhase === 'installed';
    setModalEsc(overlay, installing ? 'ignore' : 'close');
  }

  function openUpdateOverlay() {
    if (!updateState || (!updateState.available && !updatePhase && !updateError)) {
      toast('没有可用的更新信息', true);
      return;
    }
    renderUpdateDialog();
    const installing = updatePhase === 'installing';
    const overlay = $('#update-overlay');
    // R2-12：安装阶段所有按钮都禁用，焦点改落在详情容器上，避免聚焦失败落到 body。
    if (overlay) overlay.tabIndex = -1;
    openModal(overlay, {
      esc: installing ? 'ignore' : 'close',
      backdrop: false,
      initial: installing ? '#update-overlay' : '#update-install',
    });
  }

  function closeUpdateOverlay() {
    if (updatePhase === 'installing') return;
    closeModal($('#update-overlay'));
    // DESK-28：关闭详情后把焦点还给顶栏更新 pill，键盘用户不丢失位置。
    $('#update-pill')?.focus?.();
  }

  function openUpdatePage() {
    const url = String(updateState?.pageUrl || updateState?.url || '');
    if (!/^https?:\/\//i.test(url)) { toast('没有可用的下载页地址', true); return; }
    if (!invoke) return;
    invoke('open_external', { url }).catch((err) => toast(String(err), true));
  }

  function progressUpdateSpeed(downloaded) {
    const now = Date.now();
    const samples = updateSpeed.samples;
    samples.push({ t: now, bytes: Number(downloaded) || 0 });
    while (samples.length > 2 && now - samples[0].t > 3000) samples.shift();
    if (samples.length >= 2) {
      const first = samples[0];
      const last = samples[samples.length - 1];
      const dt = (last.t - first.t) / 1000;
      const db = last.bytes - first.bytes;
      if (dt > 0.15 && db > 0) updateSpeed.bytesPerSec = db / dt;
    }
  }

  function applyUpdateProgress(payload) {
    const phase = String(payload?.phase || '');
    if (phase === 'downloading') {
      const was = updatePhase === 'downloading';
      updatePhase = 'downloading';
      updateError = null;
      updateProgress = {
        downloaded: Number(payload.downloaded) || 0,
        total: payload.total == null ? null : Number(payload.total) || null,
        retry: null,
      };
      progressUpdateSpeed(updateProgress.downloaded);
      // downloaded >= total 且还没收到 installing：进入「校验签名」段（DE-93 由前端推导）。
      if (updateProgress.total && updateProgress.downloaded >= updateProgress.total) updatePhase = 'verifying';
      if (was && updatePhase === 'downloading') { renderUpdateProgressOnly(); renderUpdateBanner(); }
      else { renderUpdateBanner(); renderUpdateDialog(); }
      return;
    }
    if (phase === 'retrying') {
      updatePhase = 'downloading';
      updateError = null;
      updateProgress = {
        downloaded: 0,
        total: null,
        retry: { attempt: Number(payload.attempt) || 1, delaySeconds: Number(payload.delaySeconds) || 5 },
      };
      updateSpeed = { samples: [], bytesPerSec: null };
      renderUpdateBanner();
      renderUpdateDialog();
      return;
    }
    if (phase === 'cancelled') {
      updatePhase = null;
      updateProgress = { downloaded: 0, total: null, retry: null };
      renderUpdateBanner();
      renderUpdateDialog();
      toast('已取消下载');
      return;
    }
    if (phase === 'installing') {
      updatePhase = 'installing';
      updateError = null;
      renderUpdateBanner();
      renderUpdateDialog();
      return;
    }
    if (phase === 'installed') {
      updatePhase = 'installed';
      updateError = null;
      installFailures = 0;
      if (payload?.version) updateState = { ...(updateState || {}), version: payload.version };
      renderUpdateBanner();
      renderUpdateDialog();
      renderUpdateSettings();
      toast('更新已安装');
      maybeRestartAfterInstall();
    }
  }

  function maybeRestartAfterInstall() {
    const restart = $('#update-restart');
    if (!restart || !restart.checked || !invoke) return;
    setTimeout(() => { invoke('restart_app').catch(() => {}); }, 1200);
  }

  function applyUpdateError(payload) {
    const kind = String(payload?.kind || 'install');
    const message = String(payload?.message || '更新失败');
    if (kind === 'check') {
      updateCheckError = message;
      checkCooldownUntil = Date.now() + UPDATES.FAIL_COOLDOWN;
      renderUpdateBanner();
      renderUpdateSettings();
      startCooldownUi();
      return;
    }
    updatePhase = null;
    installFailures += 1;
    if (kind === 'signature' && settings?.updates) settings.updates.lastFailedVersion = String(payload?.version || '');
    updateError = { kind, message, version: payload?.version, expectedSignature: payload?.expectedSignature, downloadUrl: payload?.downloadUrl };
    if (kind === 'signature') {
      renderUpdateBanner();
      renderUpdateDialog();
      showSignatureFailure();
    } else {
      renderUpdateBanner();
      renderUpdateDialog();
      renderUpdateSettings();
      toast(message, true);
    }
  }

  function truncateSignature(value) {
    const text = String(value || '');
    if (!text) return '—';
    return text.length > 12 ? `${text.slice(0, 12)}…` : text;
  }

  function showSignatureFailure() {
    const overlay = $('#sigfail-overlay');
    if (!overlay) return;
    setText('#sigfail-title', '签名校验失败');
    setText('#sigfail-body', '下载文件与发布签名不匹配，已阻止安装。请重新检查或手动下载。');
    const values = $$('.kv .v', overlay);
    if (values[0]) values[0].textContent = versionText(updateError?.version || updateState?.version) || '—';
    if (values[1]) {
      const url = String(updateError?.downloadUrl || updateState?.url || '');
      values[1].textContent = url ? url.replace(/^https?:\/\//, '').slice(0, 36) + (url.length > 36 ? '…' : '') : '—';
      values[1].title = url;
    }
    if (values[2]) {
      const expected = String(updateError?.expectedSignature || updateState?.signature || '');
      values[2].textContent = truncateSignature(expected);
      values[2].title = expected;
    }
    if (values[3]) {
      values[3].textContent = '—';
      values[3].title = '插件未暴露实际签名';
    }
    renderUpdateSettings();
    openModal(overlay, { esc: 'close', initial: '[data-retry-update]' });
  }

  async function startInstall() {
    if (!invoke) return;
    // DESK-14：安装流程进行中（下载/校验/安装）忽略重复触发，避免并发下载。
    if (updatePhase === 'downloading' || updatePhase === 'verifying' || updatePhase === 'installing') return;
    if (updateState?.portable || status?.portable) {
      openUpdatePage();
      return;
    }
    updatePhase = 'downloading';
    updateError = null;
    updateProgress = { downloaded: 0, total: null, retry: null };
    updateSpeed = { samples: [], bytesPerSec: null };
    renderUpdateBanner();
    renderUpdateDialog();
    try {
      const result = await invoke('install_update');
      if (result?.cancelled) {
        updatePhase = null;
        updateProgress = { downloaded: 0, total: null, retry: null };
        renderUpdateBanner();
        renderUpdateDialog();
        return;
      }
      if (result?.installed) {
        updatePhase = 'installed';
        if (result.version) updateState = { ...(updateState || {}), version: result.version };
        renderUpdateBanner();
        renderUpdateDialog();
      }
    } catch (err) {
      updatePhase = null;
      if (!updateError) updateError = { kind: 'install', message: String(err) };
      renderUpdateBanner();
      renderUpdateDialog();
    }
  }

  function cancelInstall() {
    if (!invoke) return;
    invoke('cancel_update').catch((err) => toast(String(err), true));
  }

  function restartForUpdate() {
    if (!invoke) return;
    invoke('restart_app').catch((err) => toast(String(err), true));
  }

  function handleUpdateAction(action) {
    if (action === 'install') { openUpdateOverlay(); startInstall(); return; }
    if (action === 'cancel') { cancelInstall(); return; }
    if (action === 'download') { openUpdatePage(); return; }
    if (action === 'details') { openUpdateOverlay(); return; }
    if (action === 'sigfail') { showSignatureFailure(); return; }
    if (action === 'restart') { restartForUpdate(); return; }
    if (action === 'retry') { if (updateError) startInstall(); else checkUpdateManually(); return; }
    if (action === 'check') { checkUpdateManually(); return; }
    if (action === 'later') { suspendedBanner = true; renderUpdateBanner(); return; }
    if (action === 'logs') { go('logs'); return; }
  }

  function renderUpdateSettings() {
    const current = $('#update-current-version');
    if (!current) return;
    const version = selfCheck?.version || status?.version || '';
    current.textContent = version ? `当前 ${version}` : '当前版本 —';
    setText('#update-last-checked', updateState?.checkedAt ? relTime(updateState.checkedAt) : '尚未检查');
    const failedVersion = String(settings?.updates?.lastFailedVersion || '').trim();
    const failedEl = $('#update-last-failed');
    if (failedEl) {
      const detail = updateCheckError
        ? `检查更新失败：${updateCheckError.slice(0, 80)}`
        : failedVersion
          ? `${versionText(failedVersion)} 安装失败，已暂停自动提示`
          : '';
      failedEl.hidden = !detail;
      if (detail) {
        const retryable = updateCheckError && Date.now() >= checkCooldownUntil;
        failedEl.innerHTML = `<span>${esc(detail)}</span>${updateCheckError ? `<button class="btn ghost sm" data-update-act="check" ${retryable ? '' : 'disabled'}>${retryable ? '重试' : '稍后重试'}</button>` : ''}`;
      }
    }
    // DESK-09 / DE-103：预览通道在更新卡内给出风险提示（仅切换后出现，稳定通道隐藏）。
    let betaHint = $('#update-beta-hint');
    if (!betaHint) {
      const channelRow = $('#set-update-channel')?.closest('.setting-row');
      if (channelRow) {
        betaHint = document.createElement('p');
        betaHint.id = 'update-beta-hint';
        betaHint.className = 'row-sub';
        betaHint.style.color = 'var(--amber)';
        channelRow.appendChild(betaHint);
      }
    }
    if (betaHint) {
      betaHint.hidden = segmentValue('set-update-channel') !== 'beta';
      betaHint.textContent = '预览通道可能包含未充分验证的功能，请注意风险。';
    }
    const updateCard = $('#update-current-version')?.closest('.settings-card');
    if (updateCard) {
      // DE-105：便携模式隐藏自动检查开关（后端不支持自动替换）。
      const portable = !!selfCheck?.portable || !!status?.portable;
      const autoRow = $('#set-check-update')?.closest('.setting-row');
      if (autoRow) autoRow.hidden = portable;
      const hint = $('#update-portable-hint');
      if (hint) {
        hint.hidden = !portable;
        if (portable) hint.innerHTML = '<span>便携模式不自动更新，请手动下载。</span><button type="button" class="btn ghost sm" data-update-act="download">手动下载</button>';
      }
    }
    const skipVersion = updateSkippedVersion();
    const skippedRow = $('#update-skipped-row');
    if (skippedRow) {
      skippedRow.hidden = !skipVersion;
      const sub = $('.row-sub', skippedRow);
      if (sub) sub.textContent = `已跳过 ${skipVersion}`;
    }
    const checkBtn = $('#btn-check-update');
    if (checkBtn) {
      const remaining = Math.max(0, Math.ceil((checkCooldownUntil - Date.now()) / 1000));
      checkBtn.disabled = remaining > 0;
      checkBtn.innerHTML = `${icon('refresh', 13)}${remaining > 0 ? `${remaining}s 后重试` : '立即检查'}`;
    }
  }

  function startCooldownUi() {
    clearInterval(cooldownTimer);
    cooldownTimer = setInterval(() => {
      renderUpdateSettings();
      if (Date.now() >= checkCooldownUntil) clearInterval(cooldownTimer);
    }, 1000);
  }

  async function checkUpdateManually(open = true) {
    if (!invoke) return;
    if (Date.now() < checkCooldownUntil) { renderUpdateSettings(); return; }
    manualCheckAt = Date.now();
    try {
      const result = await invoke('check_update', { manual: true });
      applyUpdateCheck(result, { manual: true });
      if (!result || !result.available) { toast('已是最新版本'); return; }
      if (open) openUpdateOverlay();
    } catch (err) {
      // DESK-07 / DE-104：检查失败就地显示原因（截断）并进入 60s 冷却，不弹全局错误。
      updateCheckError = String(err?.message || err || '检查更新失败');
      checkCooldownUntil = Date.now() + UPDATES.FAIL_COOLDOWN;
      renderUpdateBanner();
      renderUpdateSettings();
      startCooldownUi();
    }
  }

  async function changeUpdateChannel(value) {
    const previous = segmentValue('set-update-channel') || 'stable';
    if (!invoke || !value || value === previous) return;
    selectSegment('set-update-channel', value);
    try {
      await invoke('update_settings', { values: { updateChannel: value } });
      if (settings?.updates) settings.updates.channel = value;
      toast(value === 'beta' ? '已切换到预览通道' : '已切换到稳定通道');
      renderUpdateSettings(); // R2-16：早退路径也要刷新 beta 风险提示。
      // DE-103：通道切换立即重检，但 60s 内不重复触发（避免连点打满上游）。
      if (Date.now() - manualCheckAt < 60_000) { toast('刚检查过，稍后再试'); return; }
      await checkUpdateManually(false);
    } catch (err) {
      selectSegment('set-update-channel', previous);
      toast(String(err), true);
    }
  }

  async function restoreLastUpdate() {
    if (!invoke) return;
    try {
      const payload = await invoke('get_last_update');
      if (payload) applyUpdateCheck(payload, { manual: false });
    } catch { /* 忽略 */ }
  }

  async function restorePendingPair() {
    if (!invoke) return;
    try {
      const payload = await invoke('get_pending_pair');
      if (payload) { pendingPair = payload; renderPairPrefill(); }
    } catch { /* 忽略 */ }
  }

  // ── 首启自检（DE-110..DE-114） ──────────────────────────────────
  // WebView2 仅 Windows 必需；Rust 的 self_check 在非 Windows 返回 webview2=false，不能当成失败。
  function isWindows() {
    try { return /Windows/i.test(String(navigator.userAgent || navigator.platform || '')); } catch { return true; }
  }

  // DESK-08：macOS 走系统登录项/安装器，不提供「重启后自动安装」开关。
  function isMac() {
    try {
      const platform = navigator.userAgentData?.platform || navigator.platform || '';
      return /mac/i.test(String(platform)) || /Macintosh/i.test(String(navigator.userAgent || ''));
    } catch { return false; }
  }

  function selfCheckCritical(sc) {
    if (!sc) return [];
    const fails = [];
    if (!sc.piInstalled) fails.push('pi');
    else if (sc.piVersionOk === false) fails.push('pi-version');
    if (sc.webview2 === false && isWindows()) fails.push('webview2');
    if (sc.dataWritable === false) fails.push('data');
    return fails;
  }

  function selfCheckBlocking() {
    const fails = selfCheckCritical(selfCheck);
    const done = (() => { try { return localStorage.getItem(UPDATES.SELF_CHECK_KEY) === '1'; } catch { return false; } })();
    return fails.length > 0 || !done || !selfCheck;
  }

  function diagCard(item) {
    const tone = item.state === 'checking' ? 'amber' : item.ok ? 'green' : 'rose';
    const label = item.state === 'checking' ? '检测中' : item.ok ? '通过' : '失败';
    const actionMap = {
      pi: 'pick-pi',
      'pi-version': 'pick-pi',
      webview2: 'install-webview2',
      data: 'open-data',
    };
    const actionText = { 'pick-pi': '选择 pi', 'install-webview2': '安装 WebView2', 'open-data': '打开目录' };
    const action = item.ok ? '' : actionMap[item.id] || '';
    return `<div class="diag">
      <span class="diag-icon" aria-hidden="true">${icon(item.icon || 'terminal', 14)}</span>
      <div class="diag-main"><b>${esc(item.title)}</b><span class="mono">${esc(item.detail || '')}</span></div>
      <span class="pill ${tone}">${label}</span>
      ${action ? `<button type="button" class="btn ghost sm" data-selfcheck-act="${action}">${actionText[action]}</button>` : ''}
    </div>`;
  }

  function renderSelfCheckOverlay(force = false) {
    const overlay = $('#selfcheck-overlay');
    if (!overlay) return;
    const checking = !selfCheck && !selfCheckFailed;
    const fails = selfCheckCritical(selfCheck);
    const done = (() => { try { return localStorage.getItem(UPDATES.SELF_CHECK_KEY) === '1'; } catch { return false; } })();
    // DESK-16：检测请求失败同样必须阻断，且提供「重新检测」与「退出」，不能停在检测中。
    const shouldBlock = force || checking || !!selfCheckFailed || fails.length > 0 || !done;
    if (!shouldBlock) { if (!overlay.hidden) closeModal(overlay); return; }

    const cards = [];
    const piTooOld = !!(selfCheck && selfCheck.piInstalled && selfCheck.piVersionOk === false);
    const piDetail = checking ? '正在检测…'
      : selfCheckFailed ? '检测失败'
        : selfCheck
          ? selfCheck.piInstalled
            ? `${selfCheck.piPath || 'pi'}${selfCheck.piVersion ? ` · ${selfCheck.piVersion}` : ''}`
            : (selfCheck.piError || '未找到 pi 可执行文件')
          : '正在检测…';
    cards.push({
      id: piTooOld ? 'pi-version' : 'pi',
      icon: 'terminal',
      title: 'pi 可执行文件',
      detail: piTooOld ? 'pi 版本过低（需要 ≥1.5.2）' : piDetail,
      ok: !!(selfCheck && selfCheck.piInstalled && !piTooOld),
      state: checking ? 'checking' : 'done',
    });
    const webview2Missing = !!(selfCheck && selfCheck.webview2 === false && isWindows());
    cards.push({
      id: 'webview2',
      icon: 'grid',
      title: 'WebView2',
      detail: checking ? '正在检测…' : selfCheckFailed ? '检测失败' : (webview2Missing ? '未安装 WebView2 运行时' : (selfCheck?.webview2 === false ? '当前系统无需 WebView2' : '运行时已加载')),
      ok: !!(selfCheck && !webview2Missing),
      state: checking ? 'checking' : 'done',
    });
    cards.push({
      id: 'data',
      icon: 'folder',
      title: '数据目录',
      detail: checking ? '正在检测…' : selfCheckFailed ? '检测失败' : (selfCheck?.dataWritable === false ? '数据目录不可写' : (selfCheck?.dataDir || '可写')),
      ok: !!(selfCheck && selfCheck.dataWritable !== false),
      state: checking ? 'checking' : 'done',
    });

    const list = $('#selfcheck-overlay .diag-list');
    if (list) list.innerHTML = cards.map(diagCard).join('');
    setText('#selfcheck-sub', selfCheckFailed
      ? `检测失败：${selfCheckFailed.slice(0, 120)}`
      : 'pi carry 需要 pi 与 WebView2 才能运行。');
    const recheck = $('#btn-recheck');
    if (recheck) recheck.disabled = checking;
    const failCount = fails.length;
    if (!overlay.hidden) {
      // 已是打开状态：只刷新内容与焦点，不重复 openModal。
      return;
    }
    openModal(overlay, { esc: 'ignore', backdrop: false, initial: failCount ? '[data-selfcheck-act]' : '#btn-recheck' });
  }

  async function loadSelfCheck(forceShow = false) {
    if (!invoke) return;
    // 首次判断需要尽快给出阻断遮罩：先打开（检测中），再填充结果。
    if (!selfCheck && !forceShow && !localStorage.getItem(UPDATES.SELF_CHECK_KEY)) renderSelfCheckOverlay(true);
    selfCheckFailed = '';
    try {
      selfCheck = await invoke('self_check');
    } catch (err) {
      // DESK-16：无法执行检测时如实展示失败态（可重新检测/退出），不能停在「检测中」。
      console.error(err);
      selfCheck = null;
      selfCheckFailed = String(err?.message || err || '未知错误');
      renderSelfCheckOverlay(true);
      return;
    }
    renderSecret();
    renderUpdateSettings();
    const fails = selfCheckCritical(selfCheck);
    if (fails.length) {
      renderSelfCheckOverlay(true);
      return;
    }
    try { localStorage.setItem(UPDATES.SELF_CHECK_KEY, '1'); } catch { /* 忽略 */ }
    const overlay = $('#selfcheck-overlay');
    if (overlay && !overlay.hidden) closeModal(overlay);
    // 通过后恢复更新横幅/顶栏 pill（此前因自检阻断而隐藏），并启动检查节奏。
    renderUpdateBanner();
    renderUpdatePill();
    scheduleAutoCheck();
    if (!selfCheck.tray) toast('系统托盘不可用，关闭窗口将直接退出', true);
  }

  async function pickPiPath() {
    const list = $('#selfcheck-overlay .diag-list');
    if (!list || $('#selfcheck-pi-input')) return;
    const wrap = document.createElement('div');
    wrap.className = 'diag';
    wrap.id = 'selfcheck-pi-input';
    wrap.innerHTML = '<div class="diag-main"><b>pi 可执行文件路径</b><input class="input mono" id="selfcheck-pi-path-input" placeholder="C:\\Users\\me\\bin\\pi.exe" /></div>'
      + '<button type="button" class="btn primary sm" id="selfcheck-pi-save">保存</button>'
      + '<button type="button" class="btn ghost sm" id="selfcheck-pi-cancel">取消</button>';
    list.appendChild(wrap);
    const input = $('#selfcheck-pi-path-input');
    if (input) input.focus();
    on($('#selfcheck-pi-cancel'), 'click', () => wrap.remove());
    on($('#selfcheck-pi-save'), 'click', async () => {
      const value = String(input?.value || '').trim();
      if (!value) return;
      try {
        await invoke('update_settings', { values: { piPath: value } });
        wrap.remove();
        toast('已保存 pi 路径，重新检测中…');
        selfCheck = null;
        await loadSelfCheck();
      } catch (err) { toast(String(err), true); }
    });
  }

  async function handleSelfCheckAction(action) {
    if (action === 'pick-pi') { pickPiPath(); return; }
    if (action === 'install-webview2') {
      invoke?.('open_external', { url: 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' })
        .catch((err) => toast(String(err), true));
      return;
    }
    if (action === 'open-data') {
      invoke?.('open_data_dir').catch((err) => toast(String(err), true));
    }
  }

  async function recheckSelfCheck() {
    const btn = $('#btn-recheck');
    if (btn) { btn.disabled = true; btn.textContent = '检测中…'; }
    selfCheck = null;
    renderSelfCheckOverlay(true);
    try {
      await loadSelfCheck(true);
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '重新检测'; }
    }
  }

  async function requestQuit() {
    if (updatePhase === 'installing') { toast('安装中，请勿关闭应用'); return; }
    const busy = Number(status?.runningSessions) > 0;
    const ok = await askConfirm(
      '退出 pi carry？',
      busy ? '退出后手机将无法连接；正在运行的会话会中断。' : '退出后手机将无法连接。',
      { ok: '退出', danger: true },
    );
    if (!ok) return;
    invoke?.('quit_app').catch((err) => toast(String(err), true));
  }

  // ── 事件绑定 ─────────────────────────────────────────────────────
  function bind() {
    $$('[data-icon]').forEach((el) => { el.innerHTML = icon(el.dataset.icon, el.dataset.size || 16); });
    $$('[data-page]').forEach((b) => on(b, 'click', () => go(b.dataset.page)));
    // data-go 可能是运行时生成的空态按钮（设备/会话空态），统一用文档级委托。
    on(document, 'click', (e) => {
      const el = e.target.closest('[data-go]');
      if (el && !el.closest('.overlay')) go(el.dataset.go);
    });
    on($('#copy-endpoint'), 'click', () => copyText(status.endpointId || '', 'Endpoint ID'));
    on($('#copy-uri'), 'click', () => copyText(pairing?.uri || '', '配对链接'));
    on($('#btn-pair'), 'click', startPairing);
    on($('#btn-cancel-pair'), 'click', cancelPairing);
    on($('#btn-confirm-pair'), 'click', confirmPairing);
    on($('#btn-restart'), 'click', restartService);
    on($('#btn-data'), 'click', () => invoke?.('open_data_dir').catch((e) => toast(String(e), true)));
    on($('#btn-open-data'), 'click', () => invoke?.('open_data_dir').catch((e) => toast(String(e), true)));
    on($('#btn-refresh-sessions'), 'click', refresh);
    on($('#btn-refresh-logs'), 'click', loadLogs);
    on($('#btn-save-settings'), 'click', saveSettings);
    on($('#btn-reload-settings'), 'click', loadSettings);
    on($('#btn-exit-app'), 'click', requestQuit);
    on($('#btn-recheck'), 'click', recheckSelfCheck);
    on($('#btn-check-update'), 'click', () => checkUpdateManually());
    on($('#update-pill'), 'click', () => {
      if (updateError?.kind === 'signature') showSignatureFailure();
      else openUpdateOverlay();
    });
    on($('#secret-pill'), 'click', () => {
      go('settings');
      $('#security-card')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
    $$('#set-theme button').forEach((b) => on(b, 'click', () => applyTheme(b.dataset.value, true)));
    on(window.matchMedia('(prefers-color-scheme: dark)'), 'change', () => { if (themeMode === 'auto') applyTheme('auto', false); });
    $$('#set-relay button').forEach((b) => on(b, 'click', () => {
      if (segmentValue('set-relay') === b.dataset.value) return;
      selectSegment('set-relay', b.dataset.value);
      updateRelayHint();
      instantSave('relayMode', b.dataset.value);
    }));
    $$('#set-capability button').forEach((b) => on(b, 'click', () => {
      if (segmentValue('set-capability') === b.dataset.value) return;
      selectSegment('set-capability', b.dataset.value);
      instantSave('defaultCapability', b.dataset.value);
    }));
    bindInstantSettings();
    $$('#set-update-channel button').forEach((b) => on(b, 'click', () => changeUpdateChannel(b.dataset.value)));
    $$('#log-level button').forEach((b) => on(b, 'click', () => { selectSegment('log-level', b.dataset.value); renderLogLines(logLines); }));
    on($('#set-close-tray'), 'click', () => persistSwitch('#set-close-tray', 'closeToTray'));
    on($('#set-autostart'), 'click', () => persistSwitch('#set-autostart', 'autoStart'));
    on($('#set-start-minimized'), 'click', () => persistSwitch('#set-start-minimized', 'startMinimized'));
    on($('#set-check-update'), 'click', () => persistSwitch('#set-check-update', 'checkOnStartup'));
    on($('#log-autoscroll'), 'click', () => {
      const el = $('#log-autoscroll');
      el.classList.toggle('on');
      el.setAttribute('aria-checked', el.classList.contains('on') ? 'true' : 'false');
    });
    on($('#log-search'), 'input', () => renderLogLines(logLines));
    on($('#log-copy'), 'click', async () => {
      const rows = $$('.log-line', $('#log-view') || document);
      const text = rows.map((row) => row.textContent.trim()).join('\n');
      await copyText(text, '', `已复制 ${rows.length} 行`);
    });

    // 事件委托：动态渲染的按钮只在容器上注册一次（避免反复 innerHTML 后监听器堆积）。
    on($('#update-banner'), 'click', (e) => {
      const btn = e.target.closest('[data-update-act]');
      if (btn) handleUpdateAction(btn.dataset.updateAct);
    });
    on($('#page-settings'), 'click', (e) => {
      const btn = e.target.closest('[data-update-act]');
      if (btn) handleUpdateAction(btn.dataset.updateAct);
    });
    on($('#secret-banner'), 'click', (e) => {
      const btn = e.target.closest('[data-secret-act]');
      if (!btn) return;
      if (btn.dataset.secretAct === 'settings') go('settings');
      if (btn.dataset.secretAct === 'dismiss') {
        try { sessionStorage.setItem(UPDATES.SECRET_DISMISS_KEY, '1'); } catch { /* 忽略 */ }
        renderSecurityCard();
      }
    });
    on($('#pair-prefill'), 'click', (e) => {
      const btn = e.target.closest('[data-pair-act]');
      if (btn?.dataset.pairAct === 'dismiss') dismissPairPrefill();
    });
    on($('#selfcheck-overlay'), 'click', (e) => {
      const btn = e.target.closest('[data-selfcheck-act]');
      if (btn) handleSelfCheckAction(btn.dataset.selfcheckAct);
    });
    on($('#sigfail-overlay'), 'click', (e) => {
      if (e.target.closest('[data-retry-update]')) { closeModal($('#sigfail-overlay')); checkUpdateManually(); }
      if (e.target.closest('[data-close="sigfail"]')) closeModal($('#sigfail-overlay'));
      if (e.target.closest('[data-open-logs]')) { closeModal($('#sigfail-overlay')); go('logs'); invoke?.('open_logs_dir').catch(() => {}); }
    });
    $$('[data-open-logs]').forEach((btn) => on(btn, 'click', (e) => {
      e.preventDefault();
      invoke?.('open_logs_dir').catch((err) => toast(String(err), true));
    }));
    $$('[data-copy]').forEach((btn) => on(btn, 'click', () => {
      if (btn.dataset.copy === 'endpoint') copyText(status.endpointId || '', 'Endpoint ID');
      if (btn.dataset.copy === 'version') copyText(`pi carry ${status.version || ''} · pi ${selfCheck?.piVersion || '1.5.2'}`, '版本信息');
    }));
    $$('[data-clean-cache]').forEach((btn) => {
      const row = btn.closest('.setting-row');
      if (row) row.hidden = true; // 后端无缓存清理命令，不显示假按钮。
    });

    // 更新详情对话框
    const updateOverlay = $('#update-overlay');
    on($('#update-cancel'), 'click', closeUpdateOverlay);
    on($('#update-close'), 'click', closeUpdateOverlay);
    on($('#update-download'), 'click', openUpdatePage);
    on($('#update-install'), 'click', () => {
      if (updatePhase === 'installed') { restartForUpdate(); return; }
      if (updateState?.portable || status?.portable) { openUpdatePage(); return; }
      startInstall();
    });
    on($('#update-cancel-download'), 'click', cancelInstall);
    // DESK-08 / DE-90：重启安装勾选影响主按钮文案。
    on($('#update-restart'), 'change', () => renderUpdateDialog());
    // DESK-19：role=switch 自定义控件补 Enter/Space 键盘激活（原生 button 已天然支持）。
    $$('[role="switch"]').forEach((el) => on(el, 'keydown', (e) => {
      if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); el.click(); }
    }));
    on($('#update-skip'), 'click', () => {
      if (!updateState?.version) return;
      setSkippedVersion(String(updateState.version));
      toast(`已跳过 ${versionText(updateState.version)}`);
      closeModal(updateOverlay);
    });
    on($('#btn-unskip-version'), 'click', () => {
      setSkippedVersion('');
      toast('已取消跳过');
    });
    on(updateOverlay, 'click', (e) => { if (e.target === updateOverlay) closeUpdateOverlay(); });

    // 托盘事件（Phase A FE 契约）
    if (listen) {
      listen('navigate', (event) => go(event.payload)).catch(() => {});
      listen('bridge-status', () => { refresh(); loadSelfCheck(); }).catch(() => {});
      listen('update-check', (event) => {
        const manual = Date.now() - manualCheckAt < 3000;
        // DESK-17：后端事件只是结果通知；自动结果不得重置用户「稍后」与失败计数。
        applyUpdateCheck(event.payload, { manual, fromEvent: true });
      }).catch(() => {});
      listen('update-progress', (event) => applyUpdateProgress(event.payload)).catch(() => {});
      listen('update-error', (event) => applyUpdateError(event.payload)).catch(() => {});
      listen('pair-uri', (event) => { pendingPair = event.payload || null; renderPairPrefill(); }).catch(() => {});
      listen('tray-exit-requested', () => requestQuit()).catch(() => {});
      listen('settings-changed', (event) => {
        const payload = event.payload || {};
        if (payload.source === 'tray') {
          if (settings?.service) settings.service.autostart = !!payload.autostart;
          setSwitch('#set-autostart', !!payload.autostart);
          toast(payload.autostart ? '已开启开机自启' : '已关闭开机自启');
        }
      }).catch(() => {});
      listen('tray-toast', (event) => {
        const payload = event.payload || {};
        toast(payload.message || '托盘操作失败', !!payload.error);
      }).catch(() => {});
    }
  }

  async function restartService() {
    const btn = $('#btn-restart');
    if (btn?.disabled) return;
    const ok = await askConfirm('重启桥接？', '重启桥接会断开当前设备连接，手机会自动重连。', { ok: '重启', danger: false });
    if (!ok) return;
    if (btn) btn.disabled = true;
    try {
      toast('正在重启…');
      await invoke('restart_service');
      toast('桥接已重启');
      await refresh();
    } catch (err) { toast(String(err), true); }
    finally { if (btn) btn.disabled = false; }
  }

  // ── 启动流程 ─────────────────────────────────────────────────────
  function boot() {
    // DE-68：设计无全局保存按钮；首帧就隐藏，避免静态截图与生产出现按钮。
    hideSettingsActions();
    bind();
    applyTheme(localStorage.getItem('pi_carry_theme') || 'auto', false);
    if (!invoke) {
      setText('#hero-title', '桌面运行时不可用');
      setText('#hero-sub', '请通过 pi carry 桌面应用启动。');
      return;
    }
    // 静态骨架里这些行没有后端能力：直接隐藏，避免假开关（DE-65/Q-01、Q-02）。
    hideRow('#set-pause-bridge');
    hideRow('#set-direct-priority');
    renderPairPrefill();
    refresh();
    pollTimer = setInterval(refresh, 5000);
    restoreLastUpdate();
    restorePendingPair();
    loadSelfCheck();
    loadSettings();
    window.addEventListener('beforeunload', () => {
      clearInterval(pollTimer);
      clearInterval(pairTimer);
      clearInterval(lockTimer);
      clearTimeout(updateTimer);
      clearInterval(cooldownTimer);
    });
  }

  // DE-68：脚本在 body 末尾执行时按钮已解析——立即隐藏整行，不等 DOMContentLoaded，首屏不出现保存按钮。
  hideSettingsActions();

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
