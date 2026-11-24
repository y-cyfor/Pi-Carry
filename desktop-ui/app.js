(() => {
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
    phone2: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>'
  };

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
  const icon = (name, size = 16) => `<svg class="ico" viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${icons[name] || ''}</svg>`;

  const api = window.__TAURI__;
  const invoke = api?.core?.invoke;
  const listen = api?.event?.listen;

  let status = { running: false };
  let devices = [];
  let sessions = [];
  let settings = null;
  let pairing = null;
  let pairingTimer = null;
  let pollTimer = null;
  let page = 'overview';
  let themeMode = 'auto';
  // 记录两种错误来源，避免 5 秒轮询（get_status）把 loadSettings 写进横幅的错误当场冲掉。
  let bannerStatusError = null;
  let bannerSettingsError = null;
  let settingsBannerMessage = null;
  const settingsBannerDefault = '修改 relay、节点名或 pi 路径后需要重启桥接服务才能生效。审批超时与权限默认值可即时保存。';
  const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');

  const titles = {
    overview: ['概览', '本机桥接与连接状态'],
    pairing: ['配对手机', '扫描二维码，把手机接到这台电脑'],
    devices: ['设备', '已授权设备与权限边界'],
    sessions: ['会话', '本机会话索引，只读'],
    settings: ['设置', '传输、权限与托盘行为'],
    logs: ['诊断日志', '本机运行记录，不上传'],
  };

  /**
   * 应用内确认框，替代 window.confirm()。
   * Tauri 在 macOS/Linux 的 WebView 上可能静默忽略同步 confirm()
   * （返回值直接是 false 或根本不弹），会让“吊销设备”这类操作点了没反应。
   */
  function askConfirm(title, body) {
    return new Promise((resolve) => {
      const overlay = $('#confirm-overlay');
      $('#confirm-title').textContent = title;
      $('#confirm-body').textContent = body;
      overlay.classList.add('show');
      const cleanup = (result) => {
        overlay.classList.remove('show');
        $('#confirm-ok').removeEventListener('click', onOk);
        $('#confirm-cancel').removeEventListener('click', onCancel);
        overlay.removeEventListener('click', onBackdrop);
        document.removeEventListener('keydown', onKey);
        resolve(result);
      };
      const onOk = () => cleanup(true);
      const onCancel = () => cleanup(false);
      const onBackdrop = (e) => { if (e.target === overlay) cleanup(false); };
      const onKey = (e) => { if (e.key === 'Escape') cleanup(false); };
      $('#confirm-ok').addEventListener('click', onOk);
      $('#confirm-cancel').addEventListener('click', onCancel);
      overlay.addEventListener('click', onBackdrop);
      document.addEventListener('keydown', onKey);
    });
  }

  function toast(message, isError = false) {
    const el = $('#toast');
    el.textContent = message;
    el.classList.toggle('err', isError);
    el.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove('show'), 3200);
  }

  function go(next) {
    if (!titles[next]) return;
    page = next;
    $$('.page').forEach((el) => el.classList.toggle('active', el.id === `page-${next}`));
    $$('.nav button').forEach((el) => el.classList.toggle('active', el.dataset.page === next));
    const [title, sub] = titles[next];
    $('#page-title').textContent = title;
    $('#page-sub').textContent = sub;
    if (next === 'settings') loadSettings();
    if (next === 'logs') loadLogs();
    if (next === 'devices') renderDevices();
    if (next === 'sessions') renderSessions();
  }

  function relTime(value) {
    if (!value) return '—';
    const t = Date.parse(value);
    if (!Number.isFinite(t)) return value;
    const diff = Math.max(0, Date.now() - t);
    if (diff < 60_000) return '刚刚';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
    return `${Math.floor(diff / 86_400_000)} 天前`;
  }

  function statusTone(statusName) {
    return statusName === 'streaming' ? 'green' : statusName === 'waiting_approval' ? 'amber' : statusName === 'error' ? 'rose' : '';
  }

  function statusLabel(statusName) {
    return { streaming: '运行中', waiting_approval: '等待审批', waiting_input: '等待输入', error: '出错', idle: '空闲' }[statusName] || statusName || '空闲';
  }

  function renderSettingsBanner() {
    const next = bannerStatusError || bannerSettingsError || null;
    if (next === settingsBannerMessage) return;
    settingsBannerMessage = next;
    const el = $('#settings-banner');
    if (!next) {
      el.classList.remove('rose');
      el.innerHTML = `${icon('info', 17)}<div>${settingsBannerDefault}</div>`;
      return;
    }
    el.classList.add('rose');
    el.innerHTML = `${icon('info', 17)}<div>${esc(next)}</div>`;
  }

  function renderStatus() {
    const running = !!status.running;
    $('#side-dot').className = `dot ${running ? 'on' : status.lastError ? 'err' : ''}`;
    $('#side-state').textContent = running ? '桥接运行中' : status.lastError ? '启动失败' : '已停止';
    $('#side-endpoint').textContent = status.endpointId ? status.endpointId.slice(0, 18) + '…' : '—';
    $('#top-relay').textContent = `relay · ${status.relayMode || '—'}`;
    $('#top-relay').className = `pill ${status.relayMode === 'disabled' ? 'amber' : 'violet'}`;
    $('#top-pi').textContent = status.piInstalled ? 'pi · 已安装' : 'pi · 未找到';
    $('#top-pi').className = `pill ${status.piInstalled ? 'green' : 'rose'}`;
    $('#hero-title').textContent = running ? (status.nodeName || 'pi carry 节点') : status.lastError ? '桥接启动失败' : '正在启动桥接服务';
    $('#hero-sub').textContent = running
      ? 'pi 正在本机运行；手机通过加密 QUIC 连接，不经过云端账号。'
      : status.lastError || '正在读取本机配置并初始化 iroh endpoint…';
    $('#hero-endpoint').textContent = status.endpointId || '—';
    $('#hero-state').textContent = running ? '● 运行中' : status.lastError ? '● 失败' : '● 启动中';
    $('#hero-state').className = `pill ${running ? 'green' : status.lastError ? 'rose' : 'amber'}`;
    $('#hero-version').textContent = status.version ? `core ${status.version}` : '';
    $('#m-devices').innerHTML = `${status.deviceCount || 0}<small>台</small>`;
    $('#m-online').innerHTML = `${status.onlineDevices || 0}<small>台</small>`;
    $('#m-sessions').innerHTML = `${status.sessionCount || 0}<small>个</small>`;
    $('#m-running').textContent = `${status.runningSessions || 0} 个运行中`;
    $('#m-approvals').innerHTML = `${status.pendingApprovals || 0}<small>待处理</small>`;
    $('#nav-devices').textContent = status.deviceCount || 0;
    $('#nav-sessions').textContent = status.sessionCount || 0;
    $('#relay-pill').textContent = status.relayMode === 'disabled' ? '仅局域网' : status.relayMode === 'custom' ? '自托管 relay' : 'n0 relay';
    const addrs = status.directAddresses || [];
    $('#addresses').innerHTML = addrs.length
      ? addrs.map((a) => `<span class="address">${esc(a)}</span>`).join('')
      : '<span class="address">relay 就绪后显示直连地址</span>';
    bannerStatusError = status.lastError || null;
    renderSettingsBanner();
    $('#pair-endpoint').textContent = status.endpointId || '—';
  }

  // 连接方式徽章：relay 琥珀、直连绿/青。离线设备没有 connection，不渲染。
  function connBadge(c) {
    if (!c || !c.type) return '';
    const tone = c.type === 'relay' ? 'amber' : c.type === 'p2p' ? 'violet' : 'green';
    const label = { relay: 'relay 转发', lan: '局域网', v4: 'v4 直连', v6: 'v6 直连', p2p: 'p2p 直连' }[c.type] || c.type;
    return `<span class="pill ${tone}" title="${esc(c.remoteAddr || '')}">${icon('link', 12)}${label}</span>`;
  }

  function renderDevices() {
    const list = devices.filter((d) => !d.revokedAt);
    $('#devices-caption').textContent = `${list.length} 台`;
    $('#nav-devices').textContent = list.length;
    const html = list.length ? list.map((d) => `
      <div class="device">
        <div class="avatar ${d.deviceClass === 'desktop' ? '' : 'cyan'}">${d.deviceClass === 'desktop' ? '▣' : '▯'}</div>
        <div style="min-width:0;flex:1">
          <div class="name">${esc(d.name)} ${d.online ? '<span class="pill green" style="margin-left:6px">在线</span>' : ''}${d.online ? connBadge(d.connection) : ''}</div>
          <div class="id">${esc(d.id.slice(0, 22))}… · ${esc(d.platform)}</div>
        </div>
        <div class="right">
          <span class="pill ${d.capability === 'full' ? 'violet' : ''}">${esc(d.capability)}</span>
          <span class="time">${esc(relTime(d.lastSeen))}</span>
          <button class="btn danger" data-revoke="${esc(d.id)}">${icon('trash', 14)}吊销</button>
        </div>
      </div>`).join('') : '<div class="empty"><b>还没有配对设备</b>打开配对页，用手机扫描二维码。</div>';
    $('#devices-list').innerHTML = html;
    $('#recent-devices').innerHTML = list.length ? list.slice(0, 3).map((d) => `
      <div class="row">
        <div class="avatar ${d.deviceClass === 'desktop' ? '' : 'cyan'}">${d.deviceClass === 'desktop' ? '▣' : '▯'}</div>
        <div class="main-col"><div class="title">${esc(d.name)} ${d.online ? '<span class="dot on" style="display:inline-block;margin-left:5px"></span>' : ''}</div><div class="meta">${esc(d.platform)} · ${esc(d.capability)}</div></div>
        <div class="time">${esc(relTime(d.lastSeen))}</div>
      </div>`).join('') : '<div class="empty"><b>还没有配对设备</b>打开配对页，用手机扫描二维码。</div>';
    $$('[data-revoke]').forEach((btn) => btn.addEventListener('click', () => revoke(btn.dataset.revoke)));
  }

  function renderSessions() {
    const list = sessions || [];
    $('#nav-sessions').textContent = list.length;
    $('#sessions-list').innerHTML = list.length ? list.map((s) => `
      <div class="session-card">
        <div class="top"><span class="dot ${s.status === 'streaming' ? 'on' : s.status === 'waiting_approval' ? 'warn' : ''}"></span><span class="title">${esc(s.name || s.preview || '未命名会话')}</span><span class="pill ${statusTone(s.status)}" style="margin-left:auto">${esc(statusLabel(s.status))}</span></div>
        <div class="cwd">${esc(s.cwd || '—')}</div>
        <div class="foot"><span>${s.messageCount || 0} 条消息</span><span>·</span><span>${esc(relTime(s.updatedAt))}</span>${s.pendingApprovals ? `<span class="pill rose" style="margin-left:auto">${s.pendingApprovals} 待审批</span>` : ''}</div>
      </div>`).join('') : '<div class="empty" style="grid-column:1/-1"><b>暂无会话</b>从手机端发起对话后，这里会出现会话索引。</div>';
    $('#recent-sessions').innerHTML = list.length ? list.slice(0, 4).map((s) => `
      <div class="row">
        <div class="avatar">▤</div>
        <div class="main-col"><div class="title">${esc(s.name || s.preview || '未命名会话')}</div><div class="meta">${esc(s.cwd || '')}</div></div>
        <span class="pill ${statusTone(s.status)}">${esc(statusLabel(s.status))}</span>
        <div class="time">${esc(relTime(s.updatedAt))}</div>
      </div>`).join('') : '<div class="empty"><b>暂无会话</b>手机端发起对话后，这里会出现索引。</div>';
  }

  async function refresh() {
    if (!invoke) {
      $('#hero-title').textContent = '桌面运行时不可用';
      $('#hero-sub').textContent = '请通过 pi-carry-desktop 启动，而不是直接用浏览器打开。';
      return;
    }
    try {
      // get_status 已经带回 devices/sessions 明细：再调 list_devices + list_sessions
      // 等于每 5 秒多做两次全量扫盘（会话要读文件并逐行解析）（D-3）。
      const s = await invoke('get_status');
      status = s || { running: false };
      renderStatus();
      if (status.running) {
        devices = status.devices || [];
        sessions = status.sessions || [];
        renderDevices();
        renderSessions();
      }
    } catch (err) {
      $('#side-state').textContent = '读取状态失败';
      console.error(err);
    }
  }

  async function startPairing() {
    // 后端 begin 会覆盖活动会话：请求中重复点击会让旧二维码在无提示的情况下失效。
    const btn = $('#btn-pair');
    if (btn.disabled) return;
    btn.disabled = true;
    try {
      const p = await invoke('start_pairing', { relay: null });
      pairing = p;
      $('#qr-frame').innerHTML = p.qrSvg || '<div class="qr-placeholder">二维码生成失败</div>';
      const digits = String(p.code || '').padStart(6, '0').split('');
      $('#code-row').innerHTML = digits.map((d) => `<div class="code-digit">${esc(d)}</div>`).join('');
      $('#btn-cancel-pair').disabled = false;
      $('#copy-uri').disabled = false;
      $('#pair-status-text').textContent = '等待手机扫描或输入 6 位码…';
      $('#pair-dot').className = 'dot warn';
      updatePairingExpiry();
      clearInterval(pairingTimer);
      pairingTimer = setInterval(() => { updatePairingExpiry(); pollPairing(); }, 1000);
      toast('配对会话已生成，10 分钟内有效');
    } catch (err) {
      toast(String(err), true);
    } finally {
      btn.disabled = false;
    }
  }

  function updatePairingExpiry() {
    if (!pairing?.expiresAt) return;
    const left = Math.max(0, Math.floor((Date.parse(pairing.expiresAt) - Date.now()) / 1000));
    const mm = String(Math.floor(left / 60)).padStart(2, '0');
    const ss = String(left % 60).padStart(2, '0');
    $('#pair-expiry').textContent = left > 0 ? `有效期 ${mm}:${ss} · 过期后自动作废` : '已过期，请重新生成';
    if (left <= 0) {
      $('#pair-dot').className = 'dot err';
      $('#pair-status-text').textContent = '配对已过期';
      clearInterval(pairingTimer);
      $('#btn-cancel-pair').disabled = true;
      $('#copy-uri').disabled = true;
      // 过期后必须把二维码与配对码清掉：留一张扫不动的死码只会让用户
      // 反复扫、反复失败，而界面上看不到任何“已失效”的痕迹。
      $('#qr-frame').innerHTML = '<div class="qr-placeholder">配对码已过期，请重新生成</div>';
      $('#code-row').innerHTML = '';
      pairing = null;
    }
  }

  async function pollPairing() {
    try {
      const res = await invoke('pairing_status');
      const st = res?.status;
      if (!st?.active) {
        $('#pair-dot').className = 'dot on';
        $('#pair-status-text').textContent = st?.verified ? '配对完成，设备已接入' : '配对会话已结束';
        $('#btn-cancel-pair').disabled = true;
        $('#copy-uri').disabled = true;
        clearInterval(pairingTimer);
        refresh();
        return;
      }
      if (st.verified) {
        $('#pair-dot').className = 'dot on';
        $('#pair-status-text').textContent = '已校验配对码，等待设备接入…';
      } else {
        $('#pair-status-text').textContent = `等待手机扫码 · 剩余 ${st.attemptsRemaining ?? 5} 次尝试`;
      }
    } catch { /* 轮询失败不打扰用户 */ }
  }

  async function cancelPairing() {
    try {
      await invoke('cancel_pairing');
      pairing = null;
      clearInterval(pairingTimer);
      $('#qr-frame').innerHTML = '<div class="qr-placeholder"><span>配对已取消</span></div>';
      $('#code-row').innerHTML = Array.from({ length: 6 }, () => '<div class="code-digit">·</div>').join('');
      $('#pair-expiry').textContent = '等待生成配对会话';
      $('#pair-status-text').textContent = '没有进行中的配对';
      $('#pair-dot').className = 'dot';
      $('#btn-cancel-pair').disabled = true;
      $('#copy-uri').disabled = true;
      toast('已取消配对');
    } catch (err) { toast(String(err), true); }
  }

  async function revoke(id) {
    if (!await askConfirm('吊销设备', '吊销后这台设备会立即断开，且无法再次连接。确定继续？')) return;
    try {
      await invoke('revoke_device', { id });
      toast('设备已吊销');
      refresh();
    } catch (err) { toast(String(err), true); }
  }

  async function loadSettings() {
    try {
      settings = await invoke('get_settings');
      $('#set-node-name').value = settings.node?.name || '';
      $('#set-pi-path').value = settings.node?.piPath || '';
      $('#set-approval-timeout').value = settings.approvals?.timeoutSecs ?? 120;
      $('#set-allowed-roots').value = (settings.sessions?.allowedRoots || []).join('\n');
      $('#set-relay-urls').value = (settings.transport?.relayUrls || []).join('\n');
      $('#set-close-tray').classList.toggle('on', !!settings.closeToTray);
      $('#set-autostart').classList.toggle('on', !!settings.service?.autostart);
      $('#data-dir').textContent = settings.dataDir || '—';
      selectSegment('set-relay', settings.transport?.relayMode || 'n0');
      selectSegment('set-capability', settings.sessions?.defaultCapability || 'full');
      applyTheme(settings.ui?.theme || 'auto', false);
      updateRelayHint();
      bannerSettingsError = settings.bridgeError || null;
      renderSettingsBanner();
    } catch (err) { toast(String(err), true); }
  }

  function applyTheme(mode, persist) {
    themeMode = ['auto', 'dark', 'light'].includes(mode) ? mode : 'auto';
    const dark = themeMode === 'dark' || (themeMode === 'auto' && systemTheme.matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    localStorage.setItem('pi_carry_theme', themeMode);
    selectSegment('set-theme', themeMode);
    if (invoke) {
      invoke('set_window_theme', { theme: themeMode }).catch(() => {});
      if (persist) {
        invoke('update_settings', { values: { theme: themeMode } }).catch((err) => toast(String(err), true));
      }
    }
  }

  function selectSegment(id, value) {
    const root = document.getElementById(id);
    if (!root) return;
    root.dataset.value = value;
    $$('button', root).forEach((b) => b.classList.toggle('active', b.dataset.value === value));
  }

  function segmentValue(id) {
    return document.getElementById(id)?.dataset.value || '';
  }

  function updateRelayHint() {
    const mode = segmentValue('set-relay');
    $('#relay-urls-field').style.display = mode === 'custom' ? '' : 'none';
    $('#relay-hint').textContent = mode === 'disabled'
      ? '禁用 relay 后只能在同一局域网直连；跨网络将无法连接。'
      : mode === 'custom'
        ? '使用你信任的 relay 服务；直连仍然优先。'
        : 'n0 提供开箱即用的穿透，但中继节点会看到连接元数据。';
  }

  async function saveSettings() {
    const urls = $('#set-relay-urls').value.split('\n').map((s) => s.trim()).filter(Boolean);
    // 与服务端 core 的 10..=3600 保持一致：先在前端 clamp，
    // 否则填 0 会得到一个仅前端“看起来保存成功”的非法配置。
    const rawTimeout = Number($('#set-approval-timeout').value);
    const approvalTimeoutSecs = Math.min(3600, Math.max(10, Number.isFinite(rawTimeout) ? Math.round(rawTimeout) : 120));
    if (approvalTimeoutSecs !== rawTimeout) {
      $('#set-approval-timeout').value = String(approvalTimeoutSecs);
    }
    const payload = {
      nodeName: $('#set-node-name').value.trim(),
      piPath: $('#set-pi-path').value.trim() || 'pi',
      approvalTimeoutSecs,
      allowedRoots: $('#set-allowed-roots').value.split('\n').map((s) => s.trim()).filter(Boolean),
      relayMode: segmentValue('set-relay'),
      relayUrls: urls,
      closeToTray: $('#set-close-tray').classList.contains('on'),
      autoStart: $('#set-autostart').classList.contains('on'),
      defaultCapability: segmentValue('set-capability'),
      theme: themeMode,
    };
    try {
      const res = await invoke('update_settings', { values: payload });
      // 保存成功后清掉上一次失败的横幅，否则旧错误会一直挂在设置页上。
      bannerSettingsError = null;
      renderSettingsBanner();
      toast(res.restartRequired ? '设置已保存；重启桥接后生效' : '设置已保存');
      refresh();
    } catch (err) {
      bannerSettingsError = String(err);
      renderSettingsBanner();
      toast(String(err), true);
    }
  }

  // 开关类设置点击即落盘，避免切到其他页面后 loadSettings() 又读回旧值。
  async function persistSwitch(id, key) {
    const el = $('#' + id);
    const next = !el.classList.contains('on');
    el.classList.toggle('on', next);
    try {
      await invoke('update_settings', { values: { [key]: next } });
      toast(next ? '已开启' : '已关闭');
    } catch (err) {
      el.classList.toggle('on', !next);
      toast(String(err), true);
    }
  }

  async function restartService() {
    // 后端有生命周期锁，但连点会让请求排队并逐个执行：第二次重启完全是多余的
    // 停机（还会把刚连上的手机再踢一次）。前端也据住按钮（D-1）。
    const btn = $('#btn-restart');
    if (btn.disabled) return;
    if (!await askConfirm('重启桥接', '重启桥接会断开当前设备连接，手机会自动重连。继续？')) return;
    btn.disabled = true;
    try {
      toast('正在重启…');
      await invoke('restart_service');
      toast('桥接已重启');
      await refresh();
    } catch (err) { toast(String(err), true); }
    finally { btn.disabled = false; }
  }

  async function loadLogs() {
    try {
      const res = await invoke('get_logs', { lines: 300 });
      $('#log-path').textContent = res.path || 'desktop.log';
      const lines = (res.lines || []).join('\n');
      $('#log-view').textContent = lines || '（暂无日志）';
    } catch (err) { $('#log-view').textContent = String(err); }
  }

  async function copyText(text, label) {
    try {
      await navigator.clipboard.writeText(text);
      toast(`${label}已复制`);
    } catch { toast('复制失败', true); }
  }

  function bind() {
    $$('[data-icon]').forEach((el) => { el.innerHTML = icon(el.dataset.icon, el.dataset.size || 16); });
    $$('.nav button').forEach((b) => b.addEventListener('click', () => go(b.dataset.page)));
    $$('[data-go]').forEach((b) => b.addEventListener('click', () => go(b.dataset.go)));
    $('#copy-endpoint').addEventListener('click', () => copyText(status.endpointId || '', 'Endpoint ID'));
    $('#copy-uri').addEventListener('click', () => pairing?.uri && copyText(pairing.uri, '配对链接'));
    $('#btn-pair').addEventListener('click', startPairing);
    $('#btn-cancel-pair').addEventListener('click', cancelPairing);
    $('#btn-restart').addEventListener('click', restartService);
    $('#btn-data').addEventListener('click', () => invoke('open_data_dir').catch((e) => toast(String(e), true)));
    $('#btn-open-data').addEventListener('click', () => invoke('open_data_dir').catch((e) => toast(String(e), true)));
    $('#btn-refresh-sessions').addEventListener('click', refresh);
    $('#btn-refresh-logs').addEventListener('click', loadLogs);
    $('#btn-save-settings').addEventListener('click', saveSettings);
    $('#btn-reload-settings').addEventListener('click', loadSettings);
    $$('#set-relay button').forEach((b) => b.addEventListener('click', () => { selectSegment('set-relay', b.dataset.value); updateRelayHint(); }));
    $$('#set-capability button').forEach((b) => b.addEventListener('click', () => selectSegment('set-capability', b.dataset.value)));
    $$('#set-theme button').forEach((b) => b.addEventListener('click', () => applyTheme(b.dataset.value, true)));
    systemTheme.addEventListener('change', () => { if (themeMode === 'auto') applyTheme('auto', false); });
    $('#set-close-tray').addEventListener('click', () => persistSwitch('set-close-tray', 'closeToTray'));
    $('#set-autostart').addEventListener('click', () => persistSwitch('set-autostart', 'autoStart'));
    if (listen) {
      listen('navigate', (event) => go(event.payload)).catch(() => {});
      listen('bridge-status', () => refresh()).catch(() => {});
    }
  }

  bind();
  applyTheme(localStorage.getItem('pi_carry_theme') || 'auto', false);
  invoke?.('get_settings').then((s) => applyTheme(s.ui?.theme || 'auto', false)).catch(() => {});
  refresh();
  pollTimer = setInterval(refresh, 5000);
  window.addEventListener('beforeunload', () => clearInterval(pollTimer));
})();
