//! pi carry 受控端：常驻托盘，内嵌桥接核心，只负责本机管理与配对展示。
//!
//! 桌面端刻意不做聊天、审批和会话渲染：这些能力只属于手机。桌面只展示
//! 连接状态、已配对设备、会话索引、配对二维码和诊断日志。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::menu::{CheckMenuItem, IsMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WindowEvent};
use tracing_subscriber::fmt::time::ChronoLocal;

mod deeplink;
mod selfcheck;
mod update;

/// desktop.log 超过这个大小就在下次写入前轮转，避免常驻托盘时无限增长。
const LOG_ROTATE_BYTES: u64 = 10 * 1024 * 1024;
/// 轮转归档保留天数：再久的日志对用户排障没有价值，只会占满磁盘。
const LOG_RETENTION_DAYS: u64 = 7;
/// get_logs 只读日志尾部这么多字节：日志可能很大，整文件读入会按文件大小分配内存。
const LOG_TAIL_BYTES: u64 = 256 * 1024;

/// 第二个实例发给首个实例的信号内容（Unix socket 实现）。
#[cfg(unix)]
const SHOW_MESSAGE: &str = "show";

/// 首个实例保留的监听套接字。故意不 drop：单实例互斥必须覆盖整个进程生命周期。
#[cfg(unix)]
static SOCKET_LISTENER: std::sync::OnceLock<std::os::unix::net::UnixListener> =
    std::sync::OnceLock::new();

struct AppState {
    bridge: Mutex<Option<Arc<Bridge>>>,
    last_error: Mutex<Option<String>>,
    close_to_tray: AtomicBool,
    /// restart_service 的并发守卫：两个并发重启会各自起一个 Bridge，后写入者覆盖前者造成泄漏。
    restarting: AtomicBool,
    /// 桥接生命周期锁：启动与重启都走它，否则「开机启动仍在 launch_bridge」时
    /// 点一下重启就会同时存在两个 Bridge（同 endpoint_secret、同 SQLite），
    /// 被覆盖的那个既不断连也不释放子进程（D-1）。
    lifecycle: tokio::sync::Mutex<()>,
    /// 设置读写锁：`update_settings` 是「读—改—写」，两次保存并发会互相覆盖。
    settings: Mutex<()>,
    /// 最近一次检查到的安装器更新：`install_update` 只装这里的东西，
    /// 避免前端传入任意下载地址。
    pending_update: Mutex<Option<tauri_plugin_updater::Update>>,
    /// 取消更新下载：句柄唤醒 select，标志位覆盖「正在退避等待」的窗口期。
    update_cancel: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    update_cancelled: AtomicBool,
    /// 深链预填数据：页面加载完成前先存着，前端用 `get_pending_pair` 取走。
    pending_pair: Mutex<Option<Value>>,
    /// 最近一次检查结果：启动检查可能早于页面加载，窗口打开后仍能看到横幅。
    last_update: Mutex<Option<Value>>,
    /// 托盘是否创建成功：失败时自检卡提示，且开机自启不再追加 `--minimized`
    /// （否则窗口永远出不来）。
    tray_available: AtomicBool,
    /// 更新安装阶段（DESK-18）：系统安装器运行期间关闭窗口不能直接退出进程，
    /// 否则会打断安装并留下半成品。
    installing: AtomicBool,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            bridge: Mutex::new(None),
            last_error: Mutex::new(None),
            close_to_tray: AtomicBool::new(true),
            restarting: AtomicBool::new(false),
            lifecycle: tokio::sync::Mutex::new(()),
            settings: Mutex::new(()),
            pending_update: Mutex::new(None),
            update_cancel: Mutex::new(None),
            update_cancelled: AtomicBool::new(false),
            pending_pair: Mutex::new(None),
            last_update: Mutex::new(None),
            tray_available: AtomicBool::new(false),
            installing: AtomicBool::new(false),
        }
    }
}

/// 安装新 Bridge。槽位里如果已经有（并发启动的）旧 Bridge，必须关掉它：
/// 直接覆盖会让旧实例的 endpoint、pi 子进程、后台任务泄漏到进程退出。
async fn install_bridge(state: &AppState, bridge: Arc<Bridge>) {
    let displaced = {
        let mut slot = lock(&state.bridge);
        slot.replace(bridge)
    };
    if let Some(old) = displaced {
        tracing::warn!("发现并发的旧 Bridge，正在关闭以免端点泄漏");
        old.shutdown().await;
    }
}

impl AppState {
    fn take_bridge(&self) -> Option<Arc<Bridge>> {
        lock(&self.bridge).take()
    }

    fn bridge(&self) -> Result<Arc<Bridge>, String> {
        lock(&self.bridge)
            .clone()
            .ok_or_else(|| "桥接服务尚未启动".to_string())
    }

    /// 优先用桥接持有的路径；桥接未启动时退回本机发现，保证设置页仍可读改配置。
    fn paths(&self) -> Paths {
        match lock(&self.bridge).as_ref() {
            Some(bridge) => bridge.paths.clone(),
            None => Paths::discover(),
        }
    }

    fn set_error(&self, message: Option<String>) {
        *lock(&self.last_error) = message;
    }

    fn error(&self) -> Option<String> {
        lock(&self.last_error).clone()
    }

    pub(crate) fn pending_update(&self) -> Option<tauri_plugin_updater::Update> {
        lock(&self.pending_update).clone()
    }

    pub(crate) fn set_pending_update(&self, value: Option<tauri_plugin_updater::Update>) {
        *lock(&self.pending_update) = value;
    }

    /// 开始一次安装：清掉上次的取消标志并返回本次的取消信号。
    pub(crate) fn begin_update_cancel(&self) -> tokio::sync::oneshot::Receiver<()> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.update_cancelled.store(false, Ordering::Relaxed);
        *lock(&self.update_cancel) = Some(tx);
        rx
    }

    pub(crate) fn cancel_update(&self) {
        self.update_cancelled.store(true, Ordering::Relaxed);
        if let Some(tx) = lock(&self.update_cancel).take() {
            let _ = tx.send(());
        }
    }

    pub(crate) fn end_update_cancel(&self) {
        lock(&self.update_cancel).take();
    }

    pub(crate) fn is_update_cancelled(&self) -> bool {
        self.update_cancelled.load(Ordering::Relaxed)
    }

    pub(crate) fn set_pending_pair(&self, value: Option<Value>) {
        *lock(&self.pending_pair) = value;
    }

    fn take_pending_pair(&self) -> Option<Value> {
        lock(&self.pending_pair).take()
    }

    pub(crate) fn set_last_update(&self, value: Value) {
        *lock(&self.last_update) = Some(value);
    }

    fn last_update(&self) -> Value {
        lock(&self.last_update).clone().unwrap_or(Value::Null)
    }

    pub(crate) fn tray_available(&self) -> bool {
        self.tray_available.load(Ordering::Relaxed)
    }

    fn set_tray_available(&self, value: bool) {
        self.tray_available.store(value, Ordering::Relaxed);
    }

    pub(crate) fn is_installing(&self) -> bool {
        self.installing.load(Ordering::Relaxed)
    }

    pub(crate) fn set_installing(&self, value: bool) {
        self.installing.store(value, Ordering::Relaxed);
    }

    /// 在设置锁内读—改—写配置：更新检查记录时间/失败版本时，
    /// 不能绕过 `update_settings` 的串行化，否则会把它刚保存的字段覆盖回旧值。
    pub(crate) fn update_config<F>(&self, paths: &Paths, change: F) -> Result<(), String>
    where
        F: FnOnce(&mut Config),
    {
        let _guard = lock(&self.settings);
        let mut config = Config::load(paths).map_err(|e| format!("读取配置失败：{e}"))?;
        change(&mut config);
        config.save(paths).map_err(|e| format!("保存配置失败：{e}"))
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn now_rfc3339() -> String {
    pi_carry_core::rfc3339(chrono::Utc::now())
}

/// 从 node_status() 的返回值里算概览计数。
///
/// 直接复用 node_status 已经算好的 devices/sessions，不再各调一次 list_devices/sessions.list()：
/// 两者都要全量扫盘，而 5 秒轮询会把它变成持续的 CPU/磁盘开销。
fn status_counts(node: &Value) -> Value {
    let empty = Vec::new();
    let devices = node
        .get("devices")
        .and_then(Value::as_array)
        .unwrap_or(&empty);
    let sessions = node
        .get("sessions")
        .and_then(Value::as_array)
        .unwrap_or(&empty);
    let active_devices = || {
        devices
            // DeviceRecord 没有 rename_all，键名是 snake_case（revoked_at）。
            .iter()
            .filter(|d| d.get("revoked_at").map(Value::is_null).unwrap_or(true))
    };
    json!({
        "deviceCount": active_devices().count(),
        "onlineDevices": active_devices()
            .filter(|d| d.get("online").and_then(Value::as_bool).unwrap_or(false))
            .count(),
        "sessionCount": sessions.len(),
        "runningSessions": sessions
            .iter()
            .filter(|s| s.get("status").and_then(Value::as_str) == Some("streaming"))
            .count(),
        // SessionSummary 带 rename_all=camelCase，键名是 pendingApprovals。
        "pendingApprovals": sessions
            .iter()
            .map(|s| {
                s.get("pendingApprovals")
                    .and_then(Value::as_u64)
                    .unwrap_or(0)
            })
            .sum::<u64>(),
    })
}

/// 把 `node_status()` 里的 devices（snake_case DeviceRecord）转成前端用的 camelCase 形式。
///
/// 前端每 5 秒轮询一次状态；如果概览不带上设备/会话明细，前端就得再调
/// `list_devices` + `list_sessions` 各做一次全量扫盘（D-3）。
/// `node_status()` 本身已经把两者都算好了，这里只做键名映射。
fn device_items_from_node(devices: &[Value]) -> Value {
    let items: Vec<Value> = devices
        .iter()
        .map(|d| {
            let get = |k: &str| d.get(k).cloned().unwrap_or(Value::Null);
            // 连接方式由 node_status 合并进 devices；离线设备没有，保持 null，
            // 前端据此不渲染徽章（而不是渲染一个「未知」）。
            let connection = d
                .get("connection")
                .filter(|c| c.is_object())
                .cloned()
                .unwrap_or(Value::Null);
            json!({
                "id": get("id"),
                "name": get("name"),
                "platform": get("platform"),
                "deviceClass": get("device_class"),
                "capability": get("capability"),
                "pairedAt": get("paired_at"),
                "lastSeen": get("last_seen"),
                "revokedAt": get("revoked_at"),
                "online": get("online"),
                "connection": connection,
            })
        })
        .collect();
    Value::Array(items)
}

fn status_value(state: &AppState) -> Value {
    let Some(bridge) = lock(&state.bridge).clone() else {
        return json!({
            "running": false,
            "lastError": state.error(),
            "configPath": Paths::discover().config().display().to_string(),
            "dataDir": Paths::discover().home.display().to_string(),
            "version": pi_carry_core::VERSION,
            // 桥接未起时还不知道后端，前端按 null 处理（不显示「明文」误报）。
            "secretBackend": Value::Null,
            "portable": update::is_portable(),
            "devices": [],
            "sessions": [],
        });
    };
    let node = bridge.node_status();
    let counts = status_counts(&node);
    let pairing = bridge.pairing.status();
    let devices = node
        .get("devices")
        .and_then(Value::as_array)
        .map(|d| device_items_from_node(d))
        .unwrap_or_else(|| Value::Array(Vec::new()));
    let sessions = node
        .get("sessions")
        .cloned()
        .unwrap_or_else(|| Value::Array(Vec::new()));
    json!({
        "running": true,
        "nodeName": node.get("nodeName").cloned().unwrap_or(Value::Null),
        "endpointId": node.get("endpointId").cloned().unwrap_or(Value::Null),
        "relayMode": node.get("relayMode").cloned().unwrap_or(Value::Null),
        "directAddresses": node.get("directAddresses").cloned().unwrap_or(Value::Null),
        "piInstalled": node.get("piInstalled").cloned().unwrap_or(Value::Bool(false)),
        "deviceCount": counts["deviceCount"].clone(),
        "onlineDevices": counts["onlineDevices"].clone(),
        "sessionCount": counts["sessionCount"].clone(),
        "runningSessions": counts["runningSessions"].clone(),
        "pendingApprovals": counts["pendingApprovals"].clone(),
        "pairing": pairing,
        "configPath": bridge.paths.config().display().to_string(),
        "dataDir": bridge.paths.home.display().to_string(),
        "version": pi_carry_core::VERSION,
        "secretBackend": bridge.secret_backend.as_str(),
        "portable": update::is_portable(),
        "lastError": state.error(),
        // 概览自带明细：前端不需要再发起两次全量扫描（D-3）。
        "devices": devices,
        "sessions": sessions,
    })
}

fn devices_value(state: &AppState) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let rows = bridge.store.list_devices().map_err(|e| e.to_string())?;
    // 走同一个映射函数，避免「概览」与「设备页」两条路径的字段漂移。
    let raw = serde_json::to_value(&rows).map_err(|e| e.to_string())?;
    let empty = Vec::new();
    Ok(device_items_from_node(
        raw.as_array().unwrap_or(&empty).as_slice(),
    ))
}

fn sessions_value(state: &AppState) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let rows = bridge.sessions.list().map_err(|e| e.to_string())?;
    serde_json::to_value(rows).map_err(|e| e.to_string())
}

#[tauri::command]
fn get_status(state: State<'_, AppState>) -> Value {
    status_value(&state)
}

#[tauri::command]
fn get_pending_pair(state: State<'_, AppState>) -> Value {
    state.take_pending_pair().unwrap_or(Value::Null)
}

#[tauri::command]
fn get_last_update(state: State<'_, AppState>) -> Value {
    state.last_update()
}

#[tauri::command]
fn list_devices(state: State<'_, AppState>) -> Result<Value, String> {
    devices_value(&state)
}

#[tauri::command]
fn list_sessions(state: State<'_, AppState>) -> Result<Value, String> {
    sessions_value(&state)
}

#[tauri::command]
async fn start_pairing(state: State<'_, AppState>, relay: Option<String>) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let session = bridge.begin_pairing(relay);
    let svg = qr_svg(&session.uri).map_err(|e| format!("生成二维码失败: {e}"))?;
    Ok(json!({
        "token": session.token,
        "code": session.code,
        "endpointId": session.endpoint_id,
        "expiresAt": session.expires_at,
        "uri": session.uri,
        "relay": session.relay,
        "addrs": session.addrs,
        "localConfirmed": session.local_confirmed,
        "verified": session.verified,
        "qrSvg": svg,
    }))
}

#[tauri::command]
fn confirm_pairing(state: State<'_, AppState>) -> Result<bool, String> {
    let bridge = state.bridge()?;
    bridge.pairing.confirm_local().map_err(|e| e.to_string())
}

#[tauri::command]
fn cancel_pairing(state: State<'_, AppState>) -> Result<(), String> {
    let bridge = state.bridge()?;
    bridge.pairing.cancel();
    Ok(())
}

#[tauri::command]
fn pairing_status(state: State<'_, AppState>) -> Result<Value, String> {
    let bridge = state.bridge()?;
    Ok(json!({
        "status": bridge.pairing.status(),
        "active": bridge.pairing.active(),
    }))
}

#[tauri::command]
fn revoke_device(state: State<'_, AppState>, id: String) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let at = now_rfc3339();
    let changed = bridge
        .store
        .revoke_device(&id, &at)
        .map_err(|e| e.to_string())?;
    if !changed {
        return Err("设备不存在或已经被吊销".into());
    }
    let _ = bridge
        .store
        .audit(&at, "desktop", "device.revoke", &json!({ "deviceId": id }));
    Ok(json!({ "revoked": changed, "devices": devices_value(&state)? }))
}

#[tauri::command]
fn set_window_theme(window: tauri::Window, theme: String) -> Result<(), String> {
    let theme = match theme.as_str() {
        "dark" => Some(tauri::Theme::Dark),
        "light" => Some(tauri::Theme::Light),
        "auto" => None,
        other => return Err(format!("未知主题: {other}")),
    };
    window.set_theme(theme).map_err(|e| e.to_string())
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Result<Value, String> {
    // 不依赖桥接存活：relay 等配置写坏导致桥接起不来时，用户仍要能在设置页改回去。
    let bridge = lock(&state.bridge).clone();
    let paths = state.paths();
    // 从磁盘读取，避免使用桥接启动时的配置快照：否则刚保存的主题/节点设置会被旧值覆盖。
    let config = Config::load(&paths).map_err(|e| e.to_string())?;
    let mut value = serde_json::to_value(config).map_err(|e| e.to_string())?;
    if let Value::Object(map) = &mut value {
        map.insert(
            "closeToTray".into(),
            Value::Bool(state.close_to_tray.load(Ordering::Relaxed)),
        );
        map.insert(
            "dataDir".into(),
            Value::String(paths.home.display().to_string()),
        );
        map.insert(
            "configPath".into(),
            Value::String(paths.config().display().to_string()),
        );
        map.insert(
            "bridgeError".into(),
            // 仅当真的启动失败（有 lastError）才报错：桥接还在启动中不算错误，避免设置页误报红。
            match (&bridge, state.error()) {
                (Some(_), _) | (None, None) => Value::Null,
                (None, Some(err)) => Value::String(err),
            },
        );
    }
    Ok(value)
}

/// relay URL 形态校验：桥接启动时才 parse，非法值先落盘会让桥接再也起不来。
fn validate_relay_urls(urls: &[String]) -> Result<(), String> {
    for url in urls {
        if !(url.starts_with("http://") || url.starts_with("https://")) {
            return Err(format!(
                "relay URL 必须以 http:// 或 https:// 开头，非法值：{url}"
            ));
        }
    }
    Ok(())
}

/// 把设置页提交的 payload 叠加到 `previous` 上。
///
/// 纯函数，不碰磁盘也不碰 `AppState`，因此可以直接单测：只覆盖 payload 里
/// 真实出现的键，其余字段保留磁盘上的旧值——设置页只是「部分提交」。
fn apply_settings(mut config: Config, values: &Value) -> Result<Config, String> {
    if let Some(v) = values.get("nodeName").and_then(Value::as_str) {
        config.node.name = v.trim().to_string();
    }
    if let Some(v) = values.get("piPath").and_then(Value::as_str) {
        config.node.pi_path = v.trim().to_string();
    }
    if let Some(v) = values.get("relayMode").and_then(Value::as_str) {
        config.transport.relay_mode = v.to_string();
    }
    if let Some(arr) = values.get("relayUrls").and_then(Value::as_array) {
        let urls: Vec<String> = arr
            .iter()
            .filter_map(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
        validate_relay_urls(&urls)?;
        config.transport.relay_urls = urls;
    }
    if let Some(v) = values.get("enableMdns").and_then(Value::as_bool) {
        config.transport.enable_mdns = v;
    }
    if let Some(v) = values.get("autoStart").and_then(Value::as_bool) {
        config.service.autostart = v;
    }
    if let Some(v) = values.get("closeToTray").and_then(Value::as_bool) {
        config.service.close_to_tray = v;
    }
    if let Some(v) = values.get("startMinimized").and_then(Value::as_bool) {
        config.service.start_minimized = v;
    }
    if let Some(v) = values.get("logLevel").and_then(Value::as_str) {
        if !matches!(v, "error" | "warn" | "info" | "debug" | "trace") {
            return Err(format!(
                "logLevel 必须是 error/warn/info/debug/trace，实际 {v}"
            ));
        }
        config.service.log_level = v.to_string();
    }
    if let Some(v) = values.get("checkOnStartup").and_then(Value::as_bool) {
        config.updates.check_on_startup = v;
    }
    if let Some(v) = values.get("updateChannel").and_then(Value::as_str) {
        if !matches!(v, "stable" | "beta") {
            return Err(format!("updateChannel 必须是 stable/beta，实际 {v}"));
        }
        config.updates.channel = v.to_string();
    }
    if let Some(v) = values.get("defaultCapability").and_then(Value::as_str) {
        config.sessions.default_capability = v.to_string();
    }
    if let Some(v) = values.get("theme").and_then(Value::as_str) {
        if !matches!(v, "auto" | "dark" | "light") {
            return Err(format!("theme 必须是 auto/dark/light，实际 {v}"));
        }
        config.ui.theme = v.to_string();
    }
    if let Some(arr) = values.get("allowedRoots").and_then(Value::as_array) {
        // 每行一个绝对路径；空行忽略。校验交给 Config::validate（要求绝对路径），
        // 不在这里自己拼第二套规则，否则两处会漂移。
        config.sessions.allowed_roots = arr
            .iter()
            .filter_map(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
    }
    if let Some(v) = values.get("approvalTimeoutSecs").and_then(Value::as_u64) {
        config.approvals.timeout_secs = v;
    }
    Ok(config)
}

/// 配置的「读—改—写」三步。调用方必须持有 `AppState` 的 `settings` 守卫：
/// 同一秒内的两次保存（用户快速拖两个滑块、WebView 重放事件）如果各自从
/// 同一份旧快照出发，后写入者会把前者的字段覆盖回旧值，静默丢掉一次修改。
///
/// 返回 `(旧配置, 新配置)`：旧值用于开机自启失败时回滚。
fn apply_and_save_settings(
    _settings: &std::sync::MutexGuard<'_, ()>,
    paths: &Paths,
    values: &Value,
) -> Result<(Config, Config), String> {
    let previous = Config::load(paths).map_err(|e| e.to_string())?;
    let config = apply_settings(previous.clone(), values)?;
    // 先落盘再应用开机自启：反序时 save 失败（校验拒绝）会留下注册表已改、配置未改的不一致。
    config
        .save(paths)
        .map_err(|e| format!("保存配置失败: {e}"))?;
    Ok((previous, config))
}

#[tauri::command]
fn update_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    values: Value,
) -> Result<Value, String> {
    // 整段「读—改—写」串行（理由见 apply_and_save_settings）。只锁本命令，
    // 不与 restart_service 的 lifecycle 锁嵌套，也不在这里 await。
    let settings = lock(&state.settings);
    // 不依赖桥接存活：relay 等配置写坏导致桥接起不来时，用户仍要能在设置页改回去。
    let bridge = lock(&state.bridge).clone();
    let paths = state.paths();
    // 传引用：守卫活到本函数结尾，开机自启失败的回滚落盘也在锁内。
    let (previous, config) = apply_and_save_settings(&settings, &paths, &values)?;
    // 落盘成功后才同步内存里的托盘开关：payload 被校验拒绝时不留半套状态。
    if let Some(v) = values.get("closeToTray").and_then(Value::as_bool) {
        state.close_to_tray.store(v, Ordering::Relaxed);
    }
    // 开机自启的注册项要跟着两个开关走：显式切换自启，或自启开着时改
    // 「启动时最小化」。否则用户改了最小化，注册表/plist 里仍是旧参数。
    let autostart_toggled = values.get("autoStart").is_some();
    let minimized_toggled = values.get("startMinimized").is_some();
    if autostart_toggled || (minimized_toggled && config.service.autostart) {
        // 托盘不可用时绝不追加 --minimized：那会让开机启动后窗口永远不出现。
        let minimized = config.service.start_minimized && state.tray_available();
        if let Err(err) = set_autostart(config.service.autostart, minimized) {
            // 注册表没改成，把配置回滚到旧值，避免两边对不上。
            // 只回滚 service 里的这两个开关：同一 payload 里的其他字段已经成功落盘，
            // 内存里的 close_to_tray 也已同步为新值，不再动它。
            let mut rolled_back = config.clone();
            rolled_back.service.autostart = previous.service.autostart;
            rolled_back.service.start_minimized = previous.service.start_minimized;
            let _ = rolled_back.save(&paths);
            return Err(err);
        }
    }
    if let Some(bridge) = &bridge {
        let _ = bridge
            .store
            .audit(&now_rfc3339(), "desktop", "settings.update", &values);
    }
    // 设置页可能改了开机自启：托盘勾选状态必须跟着走（DE-132）。
    refresh_tray(&app);
    Ok(json!({
        "saved": true,
        "restartRequired": values.get("relayMode").is_some()
            || values.get("relayUrls").is_some()
            || values.get("enableMdns").is_some()
            || values.get("nodeName").is_some()
            || values.get("piPath").is_some()
            || values.get("defaultCapability").is_some()
            // SessionManager 持有 Config 克隆，允许根目录只在启动时读取，
            // 不重启的话给手机展示的仍是旧根目录。
            || values.get("allowedRoots").is_some()
            || values.get("approvalTimeoutSecs").is_some()
            // 日志级别只在 init_logging 时读取；托盘/开机自启参数在下次登录才生效。
            || values.get("logLevel").is_some()
            || values.get("startMinimized").is_some(),
    }))
}

/// 托盘「退出…」确认后由前端调用（DE-133）。
///
/// 托盘不再直接退出：先发 `tray-exit-requested` 让前端弹统一确认框，
/// 用户确认后才走到这里。真正的桥接关闭与进程收尾仍由
/// `RunEvent::ExitRequested` 统一处理，和窗口退出完全同一条路径。
#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

#[tauri::command]
async fn restart_service(app: AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    // 两个并发重启会各自起一个同 endpoint_secret 的 Bridge，后写入者覆盖前者并泄漏。
    if state.restarting.swap(true, Ordering::SeqCst) {
        return Err("正在重启桥接服务，请稍候".into());
    }
    // Drop 复位：中途 panic 或 future 被丢弃时守卫也会松开，否则重启按钮会永久失效。
    struct RestartGuard<'a>(&'a AtomicBool);
    impl Drop for RestartGuard<'_> {
        fn drop(&mut self) {
            self.0.store(false, Ordering::SeqCst);
        }
    }
    let _guard = RestartGuard(&state.restarting);
    restart_bridge_now(&app, &state).await
}

async fn restart_bridge_now(app: &AppHandle, state: &AppState) -> Result<Value, String> {
    // 串行化整个「关旧的→起新的」过程：与开机启动的 launch_bridge 也不能重叠。
    let _lifecycle = state.lifecycle.lock().await;
    if let Some(old) = state.take_bridge() {
        old.shutdown().await;
    }
    state.set_error(None);
    match launch_bridge().await {
        Ok(bridge) => {
            install_bridge(state, bridge).await;
            let value = status_value(state);
            let _ = app.emit("bridge-status", value.clone());
            // 桥接从失败恢复到在线时，托盘状态行要立刻变（DE-131）。
            refresh_tray(app);
            Ok(value)
        }
        Err(err) => {
            state.set_error(Some(err.clone()));
            Err(err)
        }
    }
}

/// 只读日志尾部：整文件读入会按文件大小分配内存，而调用方只要最后几百行。
fn read_log_tail(path: &std::path::Path, max_bytes: u64) -> std::io::Result<String> {
    use std::io::{Read, Seek, SeekFrom};

    let mut file = std::fs::File::open(path)?;
    let len = file.metadata()?.len();
    let truncated = len > max_bytes;
    if truncated {
        file.seek(SeekFrom::Start(len - max_bytes))?;
    }
    let mut buf = Vec::new();
    file.read_to_end(&mut buf)?;
    let mut text = String::from_utf8_lossy(&buf).into_owned();
    if truncated {
        // 起点落在行中间：丢掉这半行，保证返回的每一行都是完整行。
        match text.find('\n') {
            Some(idx) => {
                text.drain(..=idx);
            }
            None => text.clear(),
        }
    }
    Ok(text)
}

#[tauri::command]
fn get_logs(lines: Option<usize>) -> Value {
    let paths = Paths::discover();
    let log = paths.logs().join("desktop.log");
    let limit = lines.unwrap_or(240).min(2000);
    match read_log_tail(&log, LOG_TAIL_BYTES) {
        Ok(text) => {
            let mut rows: Vec<&str> = text.lines().collect();
            if rows.len() > limit {
                rows = rows.split_off(rows.len() - limit);
            }
            json!({ "path": log.display().to_string(), "lines": rows })
        }
        Err(_) => json!({
            "path": log.display().to_string(),
            "lines": ["（尚未写入日志。启动服务后，诊断信息会出现在这里。）"],
        }),
    }
}

#[tauri::command]
fn open_data_dir() -> Result<(), String> {
    let paths = Paths::discover();
    paths
        .ensure()
        .map_err(|e| format!("创建数据目录失败: {e}"))?;
    open_path(&paths.home)
}

pub(crate) fn open_path(path: &std::path::Path) -> Result<(), String> {
    #[cfg(windows)]
    {
        std::process::Command::new("explorer.exe")
            .arg(path)
            .spawn()
            .map_err(|e| format!("打开 {} 失败: {e}", path.display()))?;
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(path)
            .spawn()
            .map_err(|e| format!("打开 {} 失败: {e}", path.display()))?;
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        std::process::Command::new("xdg-open")
            .arg(path)
            .spawn()
            .map_err(|e| format!("打开 {} 失败: {e}", path.display()))?;
    }
    Ok(())
}

/// 开机自启命令行：路径必须带引号（含空格时 reg/launchd 才会当成一个参数），
/// `--minimized` 只在托盘可用时追加，否则开机后窗口永远不出现。
#[cfg(windows)]
fn autostart_command(exe: &std::path::Path, minimized: bool) -> String {
    let mut command = format!("\"{}\"", exe.display());
    if minimized {
        command.push_str(" --minimized");
    }
    command
}

fn set_autostart(enable: bool, minimized: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        let exe = std::env::current_exe().map_err(|e| format!("无法获取程序路径: {e}"))?;
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        if enable {
            // Run 键的值必须是带引号的完整路径，否则路径含空格时开机不会启动。
            let command = autostart_command(&exe, minimized);
            let status = std::process::Command::new("reg")
                .args([
                    "add",
                    key,
                    "/v",
                    "pi-carry",
                    "/t",
                    "REG_SZ",
                    "/d",
                    command.as_str(),
                    "/f",
                ])
                .status()
                .map_err(|e| format!("写入开机自启失败: {e}"))?;
            if !status.success() {
                return Err(format!("reg add 退出码 {}", status.code().unwrap_or(-1)));
            }
        } else {
            let status = std::process::Command::new("reg")
                .args(["delete", key, "/v", "pi-carry", "/f"])
                .status()
                .map_err(|e| format!("关闭开机自启失败: {e}"))?;
            if !status.success() {
                // 值本来就不存在时 reg delete 也返回非零：再查一次，确定没删掉才报错。
                let still_registered = std::process::Command::new("reg")
                    .args(["query", key, "/v", "pi-carry"])
                    .status()
                    .map(|s| s.success())
                    .unwrap_or(true);
                if still_registered {
                    return Err(format!(
                        "关闭开机自启失败：reg delete 退出码 {}",
                        status.code().unwrap_or(-1)
                    ));
                }
            }
        }
    }
    #[cfg(target_os = "macos")]
    {
        // LaunchAgent：登录时由 launchd 拉起，与 Windows 的 Run 键语义对齐。
        let exe = std::env::current_exe().map_err(|e| format!("无法获取程序路径: {e}"))?;
        let home = std::env::var("HOME").map_err(|_| "无法定位 HOME 目录".to_string())?;
        let dir = std::path::Path::new(&home).join("Library/LaunchAgents");
        let plist = dir.join("dev.picarry.desktop.plist");
        if enable {
            std::fs::create_dir_all(&dir)
                .map_err(|e| format!("创建 LaunchAgents 目录失败: {e}"))?;
            let mut arguments = format!("<string>{}</string>", xml_escape(&exe.to_string_lossy()));
            if minimized {
                arguments.push_str("<string>--minimized</string>");
            }
            let body = format!(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
                 <!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
                 <plist version=\"1.0\"><dict>\n\
                 \u{20}<key>Label</key><string>dev.picarry.desktop</string>\n\
                 \u{20}<key>ProgramArguments</key><array>{arguments}</array>\n\
                 \u{20}<key>RunAtLoad</key><true/>\n\
                 \u{20}<key>ProcessType</key><string>Interactive</string>\n\
                 </dict></plist>\n"
            );
            crate::config_atomic_write(&plist, body.as_bytes())?;
            // load -w 让本次登录也立即生效；已加载时返回非零，不算失败。
            let _ = std::process::Command::new("launchctl")
                .args(["load", "-w", &plist.to_string_lossy()])
                .status();
        } else {
            let _ = std::process::Command::new("launchctl")
                .args(["unload", "-w", &plist.to_string_lossy()])
                .status();
            if plist.exists() {
                std::fs::remove_file(&plist).map_err(|e| format!("删除开机自启配置失败: {e}"))?;
            }
        }
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        // XDG autostart：桌面环境在登录后按 .desktop 文件拉起。
        let exe = std::env::current_exe().map_err(|e| format!("无法获取程序路径: {e}"))?;
        let home = std::env::var("HOME").map_err(|_| "无法定位 HOME 目录".to_string())?;
        let dir = std::path::Path::new(&home).join(".config/autostart");
        let entry = dir.join("pi-carry.desktop");
        if enable {
            std::fs::create_dir_all(&dir).map_err(|e| format!("创建 autostart 目录失败: {e}"))?;
            // Exec 需要引号包裹路径，且路径内的引号/反斜杠要按 Desktop Entry 规范转义。
            let exec = format!(
                "\"{}\"{}",
                exe.to_string_lossy()
                    .replace('\\', "\\\\")
                    .replace('"', "\\\""),
                if minimized { " --minimized" } else { "" }
            );
            let body = format!(
                "[Desktop Entry]\nType=Application\nName=pi carry\nComment=pi 的远程桥接\nExec={exec}\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"
            );
            crate::config_atomic_write(&entry, body.as_bytes())?;
        } else if entry.exists() {
            std::fs::remove_file(&entry).map_err(|e| format!("删除开机自启配置失败: {e}"))?;
        }
    }
    Ok(())
}

/// XML 文本转义（plist 字符串值用）。
#[cfg(target_os = "macos")]
fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

/// 原子写配置文件的薄包装：桌面端与核心共用同一套「先写临时文件再 rename」语义。
#[cfg(unix)]
fn config_atomic_write(path: &std::path::Path, bytes: &[u8]) -> Result<(), String> {
    pi_carry_core::config::atomic_write(path, bytes)
        .map_err(|e| format!("写入 {} 失败: {e}", path.display()))
}

fn qr_svg(data: &str) -> Result<String, String> {
    use qrcode::render::svg;
    let code = qrcode::QrCode::new(data.as_bytes()).map_err(|e| e.to_string())?;
    Ok(code
        .render::<svg::Color>()
        .min_dimensions(248, 248)
        .quiet_zone(true)
        .dark_color(svg::Color("#05050A"))
        .light_color(svg::Color("#FFFFFF"))
        .build())
}

async fn launch_bridge() -> Result<Arc<Bridge>, String> {
    let options = BridgeOptions::load().map_err(|e| format!("读取配置失败: {e}"))?;
    Bridge::start(options)
        .await
        .map_err(|e| format!("桥接服务启动失败: {e}"))
}

fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 托盘菜单的状态文案（DE-131/DE-134）。
struct TrayText {
    status: String,
    update: String,
    tooltip: String,
}

/// 菜单状态行、更新项与 tooltip 必须来自同一份运行时状态，否则会出现
/// 菜单写「节点在线」而 tooltip 写「节点离线」的自相矛盾。
fn tray_text(state: &AppState, version: &str) -> TrayText {
    let online = lock(&state.bridge).is_some();
    let update_available = state
        .last_update()
        .get("available")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let (status, tip) = if !online {
        ("节点离线".to_string(), "节点离线")
    } else if update_available {
        ("节点在线 · 更新可用".to_string(), "更新可用")
    } else {
        (format!("节点在线 · {version}"), "节点在线")
    };
    TrayText {
        status,
        // DE-130：有更新时直接把提示写进菜单项，用户不用点开才知道。
        update: if update_available {
            "检查更新（有更新）".to_string()
        } else {
            "检查更新".to_string()
        },
        // DE-134：tooltip 「pi carry 0.3.2 · 节点在线」，版本号不写死。
        tooltip: format!("pi carry {version} · {tip}"),
    }
}

/// 托盘勾选状态以磁盘配置为准：托盘可能早于桥接启动，内存里没有权威状态。
fn tray_autostart_checked(state: &AppState) -> bool {
    Config::load(&state.paths())
        .map(|config| config.service.autostart)
        .unwrap_or(false)
}

/// 托盘「开机自启」勾选（DE-132）：先落盘再改注册项，失败回滚配置。
fn toggle_tray_autostart(app: &AppHandle) -> Result<bool, String> {
    let state = app.state::<AppState>();
    let paths = state.paths();
    // 与设置页共用同一把「读—改—写」锁，避免两边同时保存互相覆盖。
    let _settings = lock(&state.settings);
    let previous = Config::load(&paths).map_err(|e| format!("读取配置失败：{e}"))?;
    let enabled = !previous.service.autostart;
    let mut config = previous.clone();
    config.service.autostart = enabled;
    config
        .save(&paths)
        .map_err(|e| format!("保存配置失败：{e}"))?;
    let minimized = config.service.start_minimized && state.tray_available();
    if let Err(err) = set_autostart(enabled, minimized) {
        // 注册表/登录项没改成：配置退回旧值，勾选状态与系统保持一致。
        let mut rolled_back = previous;
        rolled_back.service.autostart = !enabled;
        let _ = rolled_back.save(&paths);
        return Err(err);
    }
    Ok(enabled)
}

/// 按当前状态生成托盘菜单。
///
/// 只渲染真实存在的开关：暂停桥接还没有后端能力（Q-01），
/// 开机自启在 macOS/Linux 交给系统登录项（DE-135），都不放占位项。
fn build_tray_menu(
    app: &AppHandle,
    state: &AppState,
    text: &TrayText,
) -> tauri::Result<Menu<tauri::Wry>> {
    // DE-130：第一行是禁用状态行，用户扫一眼就知道节点在不在。
    let status = MenuItem::with_id(app, "status", &text.status, false, None::<&str>)?;
    let show = MenuItem::with_id(app, "show", "打开 pi carry", true, None::<&str>)?;
    // 设计画板没画「配对…」，但生产版本有且必要：手机首次连接靠它出码。
    let pair = MenuItem::with_id(app, "pair", "配对…", true, None::<&str>)?;
    let check = MenuItem::with_id(app, "update", &text.update, true, None::<&str>)?;
    let logs = MenuItem::with_id(app, "logs", "打开日志", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出…", true, None::<&str>)?;
    let sep_top = PredefinedMenuItem::separator(app)?;
    let sep_mid = PredefinedMenuItem::separator(app)?;
    let sep_quit = PredefinedMenuItem::separator(app)?;
    let autostart = cfg!(target_os = "windows")
        .then(|| {
            CheckMenuItem::with_id(
                app,
                "autostart",
                "开机自启",
                true,
                tray_autostart_checked(state),
                None::<&str>,
            )
        })
        .transpose()?;
    let mut items: Vec<&dyn IsMenuItem<tauri::Wry>> =
        vec![&status, &sep_top, &show, &pair, &check, &logs];
    if let Some(item) = &autostart {
        items.push(&sep_mid);
        items.push(item);
    }
    items.push(&sep_quit);
    items.push(&quit);
    Menu::with_items(app, &items)
}

/// 重建托盘菜单与 tooltip；托盘不可用（Linux 无 StatusNotifier 宿主）时静默返回。
///
/// 菜单项很少，整体重建比逐项 set_text/set_checked 更不容易漏改；
/// 只在状态变化（桥接启停、更新检查、设置保存）时调用，不会打断用户操作。
pub(crate) fn refresh_tray(app: &AppHandle) {
    let Some(tray) = app.tray_by_id("main") else {
        return;
    };
    let state = app.state::<AppState>();
    let version = app.package_info().version.to_string();
    let text = tray_text(&state, &version);
    match build_tray_menu(app, &state, &text) {
        Ok(menu) => {
            if let Err(err) = tray.set_menu(Some(menu)) {
                tracing::warn!(error = %err, "更新托盘菜单失败");
            }
        }
        Err(err) => {
            tracing::warn!(error = %err, "重建托盘菜单失败");
            return;
        }
    }
    if let Err(err) = tray.set_tooltip(Some(text.tooltip.as_str())) {
        tracing::warn!(error = %err, "更新托盘提示失败");
    }
}

/// 轮转归档文件名：`desktop-20261125-011500-123.log`。
/// 同一毫秒内多次轮转时追加序号，避免 Windows 上 rename 因目标已存在而失败
/// （Unix 会静默覆盖，反而丢掉一份日志）。
fn log_archive_path(log: &std::path::Path) -> std::path::PathBuf {
    let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S-%3f");
    let mut candidate = log.with_file_name(format!("desktop-{stamp}.log"));
    let mut seq = 1;
    while candidate.exists() && seq < 1000 {
        candidate = log.with_file_name(format!("desktop-{stamp}-{seq}.log"));
        seq += 1;
    }
    candidate
}

/// 清理超过保留期的轮转归档。只认 `desktop-*.log`：
/// 不碰当前正在写的 `desktop.log`，也不碰用户放在同目录的其它文件。
fn cleanup_old_logs(log: &std::path::Path, now: std::time::SystemTime) {
    let Some(dir) = log.parent() else {
        return;
    };
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let cutoff = now
        .checked_sub(std::time::Duration::from_secs(
            LOG_RETENTION_DAYS * 24 * 60 * 60,
        ))
        .unwrap_or(std::time::UNIX_EPOCH);
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !(name.starts_with("desktop-") && name.ends_with(".log")) {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        // 拿不到修改时间时保守保留：删错日志比多占几 MB 更糟。
        if meta.modified().is_ok_and(|modified| modified < cutoff) {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

/// 写入前轮转：常驻托盘时 desktop.log 只增不减，超限就归档后新建。
///
/// 这里刻意不写 tracing 日志：它由下面的写入器在持锁状态下调用，再进 tracing 会重入死锁。
fn rotate_log_if_needed(log: &std::path::Path) -> std::io::Result<()> {
    let Ok(meta) = std::fs::metadata(log) else {
        return Ok(());
    };
    if meta.len() <= LOG_ROTATE_BYTES {
        return Ok(());
    }
    std::fs::rename(log, log_archive_path(log))?;
    cleanup_old_logs(log, std::time::SystemTime::now());
    Ok(())
}

/// 每次写入前检查大小/日期并轮转。
///
/// 不长期持有文件句柄：Windows 上仍被打开的文件无法 rename，常驻进程会一直占着句柄，
/// 只在启动时轮转等于让 desktop.log 无限增长。
struct RotatingLog {
    path: std::path::PathBuf,
    /// 当前文件属于哪一天：跨天时即使没到 10MB 也归档，保证「按天 + 10MB」。
    day: chrono::NaiveDate,
}

impl RotatingLog {
    fn new(path: std::path::PathBuf) -> Self {
        Self {
            path,
            day: chrono::Local::now().date_naive(),
        }
    }

    fn rotate_checked(&mut self) -> std::io::Result<()> {
        let today = chrono::Local::now().date_naive();
        if self.day != today {
            if self.path.exists() {
                std::fs::rename(&self.path, log_archive_path(&self.path))?;
                cleanup_old_logs(&self.path, std::time::SystemTime::now());
            }
            self.day = today;
            return Ok(());
        }
        rotate_log_if_needed(&self.path)
    }
}

impl std::io::Write for RotatingLog {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.rotate_checked()?;
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        file.write_all(buf)?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// 日志级别：`PI_CARRY_LOG`（临时排障）优先于配置，配置优先于 info。
fn log_level(paths: &Paths) -> String {
    if let Ok(value) = std::env::var("PI_CARRY_LOG") {
        if !value.trim().is_empty() {
            return value;
        }
    }
    Config::load(paths)
        .ok()
        .map(|config| config.service.log_level)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "info".into())
}

fn init_logging() {
    let paths = Paths::discover();
    let _ = paths.ensure();
    let log = paths.logs().join("desktop.log");
    let _ = rotate_log_if_needed(&log);
    cleanup_old_logs(&log, std::time::SystemTime::now());
    // 先建出文件：首次启动时日志目录可能是空的，get_logs 需要文件存在才不报空。
    let prepared = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log)
        .is_ok();
    let level = log_level(&paths);
    let filter = tracing_subscriber::EnvFilter::try_new(&level).unwrap_or_else(|err| {
        // 订阅器还没装好，只能先写 stderr；级别写坏不应阻止程序启动。
        eprintln!("日志级别 {level:?} 无效，回退到 info：{err}");
        tracing_subscriber::EnvFilter::new("info")
    });
    let builder = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_timer(ChronoLocal::rfc_3339())
        .with_ansi(false);
    if prepared {
        // Mutex 保证单个事件的多段写入不与其他线程交错。
        let writer = Mutex::new(RotatingLog::new(log));
        let _ = builder.with_writer(writer).try_init();
    } else {
        let _ = builder.try_init();
    }
}

/// --smoke 的数据目录：没指定 PI_CARRY_HOME 时自动建一个隔离临时目录。
///
/// 探针会起一个完整 Bridge（同一 endpoint_secret、同一 SQLite），如果和
/// 正在跑的桌面实例共用数据目录，就会产生两个端点抢同一身份的状态（D-15）。
fn isolated_smoke_home() -> std::io::Result<std::path::PathBuf> {
    let dir = std::env::temp_dir().join(format!(
        "pi-carry-smoke-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0)
    ));
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

fn run_smoke(pair_uri: Option<String>) -> Result<(), String> {
    // 深链探针：只验证「能解析、会预填」，不开始也不确认配对（plan §4.4）。
    let pair_uri_ok = pair_uri
        .as_deref()
        .map(|uri| pi_carry_core::pair_uri::parse(uri).is_ok());
    tauri::async_runtime::block_on(async {
        let bridge = Bridge::start(BridgeOptions::load().map_err(|e| e.to_string())?)
            .await
            .map_err(|e| format!("桥接启动失败: {e}"))?;
        let node = bridge.node_status();
        // 深链可用性的真实检验：把「本机刚刚生成的配对链接」喂回解析器。
        // 手写 URI 只能证明解析器对理想输入有效，证明不了产品自己的产物能被自己接受（BUG-001）。
        let round_trip = pi_carry_core::pair_uri::parse(&bridge.begin_pairing(None).uri).is_ok();
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({
                "ok": true,
                "endpointId": node.get("endpointId"),
                "relayMode": node.get("relayMode"),
                "sessions": bridge.sessions.list().map(|s| s.len()).unwrap_or(0),
                "piInstalled": node.get("piInstalled"),
                "pairUriOk": pair_uri_ok,
                "pairUriRoundTrip": round_trip,
            }))
            .unwrap()
        );
        bridge.shutdown().await;
        Ok::<(), String>(())
    })
}

/// 单实例互斥：第二个进程启动时把「显示主窗口」信号交给已运行实例，然后退出。
///
/// 直接 `exit(0)` 会让用户双击图标“什么都没发生”（D-8）：
/// 托盘隐藏、窗口最小化时尤其容易被当成程序没启动。
///
/// 返回 `Ok(true)` = 本进程是主实例；`Ok(false)` = 已有实例在跑；
/// `Err` = 拿不到互斥锁（无法保证单实例）。后者必须让调用方失败退出：
/// 放行会让两个桌面进程并发写同一份 SQLite 与同一个 endpoint_secret。
#[cfg(windows)]
fn acquire_single_instance() -> Result<bool, String> {
    use std::ptr;
    use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;

    let name: Vec<u16> = "Local\\pi-carry-desktop\0".encode_utf16().collect();
    unsafe {
        let handle = CreateMutexW(ptr::null(), 1, name.as_ptr());
        if handle.is_null() {
            let code = GetLastError();
            return Err(format!(
                "无法获取单实例锁（CreateMutexW 失败，错误码 {code}）：\n\
                 请确认没有另一个 pi-carry 桌面进程在运行，并检查当前用户是否有权限。\n\
                 为避免两个进程同时写坏数据目录，本次启动已中止。"
            ));
        }
        if GetLastError() == ERROR_ALREADY_EXISTS {
            CloseHandle(handle);
            return Ok(false);
        }
        // 故意不关闭：句柄随进程退出由系统释放，保证互斥覆盖整个生命周期。
        let _ = handle;
        Ok(true)
    }
}

/// 让已运行实例把窗口提到前台（Windows：具名事件）。
#[cfg(windows)]
fn notify_running_instance() {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenEventW, SetEvent, EVENT_MODIFY_STATE};
    let name: Vec<u16> = "Local\\pi-carry-desktop-show\0".encode_utf16().collect();
    unsafe {
        let ev = OpenEventW(EVENT_MODIFY_STATE, 0, name.as_ptr());
        if !ev.is_null() {
            SetEvent(ev);
            CloseHandle(ev);
        }
    }
}

/// 监听「显示主窗口」信号（Windows）。
#[cfg(windows)]
fn spawn_show_listener(app: tauri::AppHandle) {
    use std::ptr;
    use windows_sys::Win32::System::Threading::{
        CreateEventW, ResetEvent, WaitForSingleObject, INFINITE,
    };
    let name: Vec<u16> = "Local\\pi-carry-desktop-show\0".encode_utf16().collect();
    std::thread::spawn(move || unsafe {
        // 手动重置事件：第二个实例只在 SetEvent 的一瞬间置位，消费后必须清回。
        let ev = CreateEventW(ptr::null(), 1, 0, name.as_ptr());
        if ev.is_null() {
            return;
        }
        loop {
            WaitForSingleObject(ev, INFINITE);
            ResetEvent(ev);
            // 第二个实例可能同时留下了深链：先消费再显示，避免窗口先闪一下空白页。
            deeplink::consume_pending(&app);
            show_main(&app);
        }
    });
}

#[cfg(unix)]
fn acquire_single_instance() -> Result<bool, String> {
    use std::io::Write;
    use std::os::unix::net::{UnixListener, UnixStream};
    let path = single_instance_socket_path();
    if path.exists() {
        // 能连上 = 已有活着的实例，把「显示窗口」发过去再退出；
        // 连不上 = 上次崩溃留下的死 socket，清掉重建。
        if let Ok(mut stream) = UnixStream::connect(&path) {
            let _ = stream.write_all(SHOW_MESSAGE.as_bytes());
            return Ok(false);
        }
        let _ = std::fs::remove_file(&path);
    }
    match UnixListener::bind(&path) {
        // 故意泄漏监听套接字：互斥必须覆盖整个进程生命周期，drop 会立刻释放。
        Ok(listener) => {
            SOCKET_LISTENER.set(listener).ok();
            Ok(true)
        }
        // 绑不上（目录无权限/路径被占）就等于无法保证单实例：
        // 放行会让两个桌面进程并发写同一份 SQLite 与同一个 endpoint_secret。
        Err(e) => Err(format!(
            "无法创建单实例锁 {}: {e}\n为避免两个进程同时写坏数据目录，本次启动已中止。",
            path.display()
        )),
    }
}

/// 第一个实例收到的信号走这里；单实例监听线程只接一个客户端。
#[cfg(unix)]
fn notify_running_instance() {
    // 第二个实例的动作在 acquire_single_instance 里完成（连上即写信号）。
}

/// 监听「显示主窗口」信号（Unix：已绑定的 socket 接受连接）。
#[cfg(unix)]
fn spawn_show_listener(app: tauri::AppHandle) {
    let Some(listener) = SOCKET_LISTENER.get() else {
        return;
    };
    std::thread::spawn(move || {
        use std::io::Read;
        // 单实例监听不该失败；accept 出错即退出线程，由下一次启动重建。
        while let Ok((mut stream, _)) = listener.accept() {
            let mut buf = [0u8; 16];
            let n = stream.read(&mut buf).unwrap_or(0);
            if &buf[..n] == SHOW_MESSAGE.as_bytes() {
                deeplink::consume_pending(&app);
                show_main(&app);
            }
        }
    });
}

/// 单实例锁的位置：优先用运行时目录，回落到 /tmp 并按用户区分。
#[cfg(unix)]
fn single_instance_socket_path() -> std::path::PathBuf {
    if let Ok(dir) = std::env::var("XDG_RUNTIME_DIR") {
        if !dir.is_empty() {
            return std::path::PathBuf::from(dir).join("pi-carry-desktop.sock");
        }
    }
    let user = std::env::var("USER")
        .or_else(|_| std::env::var("LOGNAME"))
        .unwrap_or_else(|_| "default".into());
    let safe: String = user
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    std::env::temp_dir().join(format!("pi-carry-desktop-{safe}.sock"))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    // --smoke 是测试探针，不是第二个桌面实例：不打开窗口、不抢单实例互斥。
    // 必须先于互斥判定，否则桌面端已运行时会被 exit(0) 提前放行，smoke 假绿。
    let smoke = args.iter().any(|a| a == "--smoke");
    // 探针自建的隔离目录要在退出时删掉：里面含 endpoint_secret 与 SQLite，
    // 留在 %TEMP% 等于把节点身份副本散落在磁盘上（NEW-16）。
    let mut smoke_owned_home: Option<std::path::PathBuf> = None;
    // 会话目录同样要隔离：否则 `--smoke` 会把真实 `~/.pi/agent/sessions` 扫进内存（只读也
    // 不该碰用户的会话列表），机器上会话多时还会拖慢探针（BUG-033）。
    let mut smoke_owned_sessions: Option<std::path::PathBuf> = None;
    if smoke {
        // 探针不能与正在运行的桌面实例共享数据目录（两个 Bridge 会抢同一个
        // endpoint_secret 与 SQLite）。没显式指定就自动隔离到临时目录。
        if std::env::var_os("PI_CARRY_HOME").is_none() {
            match isolated_smoke_home() {
                Ok(dir) => {
                    std::env::set_var("PI_CARRY_HOME", &dir);
                    smoke_owned_home = Some(dir);
                }
                Err(e) => {
                    eprintln!("无法创建冒烟测试目录: {e}");
                    std::process::exit(1);
                }
            }
        }
        // 临时目录删得掉，系统钥匙串里写进去的身份删不掉：探针默认走明文后端，
        // 绝不读写用户真实的凭据库（BUG-003）。显式设置 PI_CARRY_SECRET_BACKEND 时尊重用户选择。
        if std::env::var_os(pi_carry_core::secretstore::BACKEND_ENV).is_none() {
            std::env::set_var(pi_carry_core::secretstore::BACKEND_ENV, "file");
        }
        if std::env::var_os("PI_CARRY_SESSIONS").is_none() {
            let dir = std::env::temp_dir()
                .join(format!("pi-carry-smoke-sessions-{}", std::process::id()));
            match std::fs::create_dir_all(&dir) {
                Ok(()) => {
                    std::env::set_var("PI_CARRY_SESSIONS", &dir);
                    smoke_owned_sessions = Some(dir);
                }
                Err(e) => {
                    eprintln!("无法创建冒烟会话目录: {e}");
                    std::process::exit(1);
                }
            }
        }
    } else {
        let pair_uri = deeplink::pair_uri_from_args(&args);
        match acquire_single_instance() {
            Ok(true) => {}
            Ok(false) => {
                // 第二个实例拿到的深链先落到运行目录，再发「显示窗口」信号：
                // 具名事件/socket 只传信号，不携带任意长度 payload。
                if let Some(uri) = pair_uri.as_deref() {
                    if let Err(err) = deeplink::forward_uri(uri) {
                        eprintln!("{err}");
                    }
                }
                // 已有实例在跑：把窗口提上前台再退出，而不是无声退出（D-8）。
                notify_running_instance();
                std::process::exit(0);
            }
            Err(e) => {
                eprintln!("{e}");
                std::process::exit(1);
            }
        }
    }

    init_logging();
    if smoke {
        let result = run_smoke(deeplink::pair_uri_from_args(&args));
        if let Some(dir) = smoke_owned_home {
            // SQLite 的 WAL/SHM 文件在连接关闭后才能删干净；Bridge::shutdown 已跑完。
            if let Err(e) = std::fs::remove_dir_all(&dir) {
                eprintln!("清理冒烟目录 {} 失败: {e}", dir.display());
            }
        }
        if let Some(dir) = smoke_owned_sessions {
            if let Err(e) = std::fs::remove_dir_all(&dir) {
                eprintln!("清理冒烟会话目录 {} 失败: {e}", dir.display());
            }
        }
        if let Err(e) = result {
            eprintln!("{e}");
            std::process::exit(1);
        }
        return;
    }
    let state = AppState::default();
    let startup_config = Config::load(&Paths::discover()).unwrap_or_default();
    state
        .close_to_tray
        .store(startup_config.service.close_to_tray, Ordering::Relaxed);
    let setup_args = args.clone();

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            get_status,
            list_devices,
            list_sessions,
            start_pairing,
            confirm_pairing,
            cancel_pairing,
            pairing_status,
            revoke_device,
            get_settings,
            update_settings,
            set_window_theme,
            quit_app,
            restart_service,
            get_logs,
            open_data_dir,
            get_pending_pair,
            get_last_update,
            update::check_update,
            update::install_update,
            update::cancel_update,
            update::restart_app,
            update::open_external,
            selfcheck::self_check,
            selfcheck::open_logs_dir,
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            let state = app.state::<AppState>();

            // 窗口尺寸以 tauri.conf.json 为准（默认 1040×720、最小 880×600，DE-01）：
            // 这里再 set_size/set_min_size 会把设计尺寸覆盖掉，只保留居中。
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.center();
            }

            // 第二个实例不会自己开窗口，而是通知首个实例把窗口提上来。
            spawn_show_listener(app.handle().clone());
            let tray_icon = if cfg!(target_os = "macos") {
                // macOS 要求模板图：纯黑 + alpha，系统自动适配深浅色菜单栏。
                tauri::image::Image::from_bytes(include_bytes!("../icons/tray-template.png"))?
            } else {
                tauri::image::Image::from_bytes(include_bytes!("../icons/32x32.png"))?
            };
            // DE-134：左键打开窗口、右键出菜单；菜单与 tooltip 由 refresh_tray 按状态生成。
            let tray = TrayIconBuilder::with_id("main")
                .icon(tray_icon)
                .icon_as_template(cfg!(target_os = "macos"))
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_main(app),
                    "pair" => {
                        show_main(app);
                        let _ = app.emit("navigate", "pairing");
                    }
                    "update" => {
                        show_main(app);
                        // 失败原因显示在设置页「更新」卡：先切页，避免用户停在概览页看不到任何反馈。
                        let _ = app.emit("navigate", "settings");
                        let app = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let state = app.state::<AppState>();
                            if let Err(err) = update::check_update(app.clone(), state, true).await {
                                tracing::warn!(error = %err, "手动检查更新失败");
                                let _ = app.emit(
                                    "update-error",
                                    json!({ "kind": "check", "message": err }),
                                );
                            }
                        });
                    }
                    "logs" => {
                        if let Err(err) = selfcheck::open_logs_dir() {
                            tracing::warn!(error = %err, "打开日志目录失败");
                        }
                    }
                    "autostart" => {
                        match toggle_tray_autostart(app) {
                            Ok(enabled) => {
                                // 托盘改的是同一份配置：让已打开的设置页跟着刷新（DE-132）。
                                let _ = app.emit(
                                    "settings-changed",
                                    json!({ "source": "tray", "autostart": enabled }),
                                );
                            }
                            Err(err) => {
                                // 托盘没有气泡，失败提示交给前端统一 toast。
                                show_main(app);
                                let _ = app
                                    .emit("tray-toast", json!({ "message": err, "error": true }));
                            }
                        }
                        // 勾选状态与注册项都以刚保存的配置为准，所以无条件重建。
                        refresh_tray(app);
                    }
                    // DE-133：托盘退出不直接退出，交给前端统一确认框；
                    // 用户确认后 invoke quit_app（见 Phase B FE 契约）。
                    "quit" => {
                        show_main(app);
                        let _ = app.emit("tray-exit-requested", Value::Null);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main(tray.app_handle());
                    }
                })
                .build(app);
            match tray {
                Ok(_) => {
                    state.set_tray_available(true);
                    // 菜单与 tooltip 依赖运行时状态（桥接是否在线、是否有更新、自启勾选），
                    // 托盘建好后统一生成一次。
                    refresh_tray(app.handle());
                }
                // 托盘创建失败（例如 Linux 没有 StatusNotifier 宿主）不应阻止启动：
                // 窗口仍可用，自检卡会提示；开机自启也不再追加 --minimized。
                Err(err) => {
                    tracing::warn!(error = %err, "托盘创建失败，继续以无托盘模式运行");
                    state.set_tray_available(false);
                }
            }

            // 开机自启带 --minimized：有托盘时只驻留托盘，不弹窗。
            if setup_args.iter().any(|arg| arg == "--minimized") && state.tray_available() {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
            }

            deeplink::init(&handle);
            deeplink::handle_cli(&handle, &setup_args);
            // 第二个实例可能在本进程 setup 完成前就写好文件并退出。
            deeplink::consume_pending(&handle);

            let _ = app.emit("bridge-status", status_value(&state));
            let bridge_handle = handle.clone();
            tauri::async_runtime::spawn(async move {
                let state = bridge_handle.state::<AppState>();
                // 与 restart_service 共用生命周期锁：启动期间用户点重启会排队，
                // 而不是各自起一个 Bridge（D-1）。
                let _lifecycle = state.lifecycle.lock().await;
                match launch_bridge().await {
                    Ok(bridge) => {
                        // 一行结构化摘要：不含任何密钥/token，排障先看这行。
                        let pi_path = {
                            let explicit = bridge.config.node.pi_path.trim();
                            let explicit = (!explicit.is_empty()).then_some(explicit);
                            pi_carry_core::pi::resolve_pi(explicit)
                                .map(|path| path.display().to_string())
                                .unwrap_or_else(|err| format!("未找到（{err}）"))
                        };
                        tracing::info!(
                            version = %bridge_handle.package_info().version,
                            platform = %std::env::consts::OS,
                            data_dir = %bridge.paths.home.display(),
                            pi_path = %pi_path,
                            relay_mode = %bridge.config.transport.relay_mode,
                            backend = bridge.secret_backend.as_str(),
                            "启动摘要"
                        );
                        install_bridge(&state, bridge).await;
                        state.set_error(None);
                    }
                    Err(err) => {
                        tracing::error!(error = %err, "bridge 启动失败");
                        state.set_error(Some(err));
                    }
                }
                let _ = bridge_handle.emit("bridge-status", status_value(&state));
                refresh_tray(&bridge_handle);
            });

            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<AppState>();
                // DESK-18：安装阶段关闭窗口会打断系统安装器，这里拦下并提示前端。
                if window.label() == "main" && state.is_installing() {
                    api.prevent_close();
                    let _ = window.app_handle().emit(
                        "tray-toast",
                        json!({ "message": "安装中，请勿关闭应用", "error": false }),
                    );
                    return;
                }
                // 托盘不可用时不能隐藏：否则窗口关不掉、托盘又找不到，用户只能杀进程。
                if window.label() == "main"
                    && state.close_to_tray.load(Ordering::Relaxed)
                    && state.tray_available()
                {
                    api.prevent_close();
                    let _ = window.hide();
                } else {
                    window.app_handle().exit(0);
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("创建 pi carry 窗口失败");

    static EXITING: AtomicBool = AtomicBool::new(false);
    app.run(|handle, event| {
        if let RunEvent::ExitRequested { api, .. } = event {
            if EXITING.swap(true, Ordering::SeqCst) {
                return;
            }
            api.prevent_exit();
            let state = handle.state::<AppState>();
            let bridge = state.take_bridge();
            let handle = handle.clone();
            tauri::async_runtime::spawn(async move {
                if let Some(bridge) = bridge {
                    bridge.shutdown().await;
                }
                handle.exit(0);
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "pi-carry-desktop-test-{}-{}",
            std::process::id(),
            name
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("创建临时目录");
        dir
    }

    #[test]
    fn config_service_autostart_serializes_as_autostart() {
        // serde 的 camelCase 不改写单词 "autostart"：前端回显读 service.autostart 才是对的
        // （M19 报告为缺陷，实测不成立）。
        let value = serde_json::to_value(Config::default()).expect("序列化配置");
        let service = value.get("service").expect("service 段");
        assert!(service.get("autostart").is_some());
        assert!(service.get("autoStart").is_none());
    }

    #[test]
    fn config_with_unparseable_relay_url_still_loads() {
        // 这是 D-2 的前提：桥接因 relay URL 非法起不来（CoreError::Config），
        // 但 Config::load 本身能成功，所以设置页可以在无桥接时读回并修正。
        let dir = temp_path("broken-relay-config");
        let paths = Paths::at(&dir);
        let mut config = Config::default();
        config.transport.relay_mode = "custom".into();
        config.transport.relay_urls = vec!["relay.example.com".into()];
        config.save(&paths).expect("写坏配置应能落盘");

        let loaded = Config::load(&paths).expect("坏 relay URL 不应阻止读取配置");
        assert_eq!(loaded.transport.relay_urls, vec!["relay.example.com"]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn status_counts_reuses_node_status_devices_and_sessions() {
        let node = json!({
            "devices": [
                {"id": "d1", "revoked_at": null, "online": true},
                {"id": "d2", "revoked_at": null, "online": false},
                {"id": "d3", "revoked_at": "2026-01-01T00:00:00Z", "online": true},
            ],
            "sessions": [
                {"id": "s1", "status": "streaming", "pendingApprovals": 2},
                {"id": "s2", "status": "idle", "pendingApprovals": 1},
            ],
        });

        let counts = status_counts(&node);
        assert_eq!(counts["deviceCount"], 2, "已吊销设备不计入");
        assert_eq!(counts["onlineDevices"], 1, "已吊销设备不算在线");
        assert_eq!(counts["sessionCount"], 2);
        assert_eq!(counts["runningSessions"], 1);
        assert_eq!(counts["pendingApprovals"], 3);
    }

    #[test]
    fn status_counts_tolerates_missing_arrays() {
        let counts = status_counts(&json!({ "running": true }));
        assert_eq!(counts["deviceCount"], 0);
        assert_eq!(counts["sessionCount"], 0);
        assert_eq!(counts["pendingApprovals"], 0);
    }

    #[test]
    fn validate_relay_urls_accepts_http_and_https() {
        let urls = vec![
            "https://relay.example.com".to_string(),
            "http://127.0.0.1:8080".to_string(),
        ];
        assert!(validate_relay_urls(&urls).is_ok());
        assert!(validate_relay_urls(&[]).is_ok());
    }

    #[test]
    fn validate_relay_urls_rejects_missing_scheme() {
        let err = validate_relay_urls(&["relay.example.com".to_string()]).unwrap_err();
        assert!(err.contains("http:// 或 https://"), "实际错误: {err}");
        assert!(err.contains("relay.example.com"));
    }

    #[test]
    fn apply_settings_keeps_fields_absent_from_payload() {
        // 设置页是部分提交：payload 里没有的键必须保留磁盘上的旧值。
        let mut previous = Config::default();
        previous.node.name = "旧节点".into();
        previous.ui.theme = "dark".into();

        let applied =
            apply_settings(previous, &json!({ "nodeName": "  新节点  " })).expect("应能应用");
        assert_eq!(applied.node.name, "新节点", "前后空白应被裁掉");
        assert_eq!(applied.ui.theme, "dark", "未提交的字段必须保留");
    }

    #[test]
    fn apply_settings_rejects_invalid_theme_and_relay_url() {
        let err = apply_settings(Config::default(), &json!({ "theme": "neon" })).unwrap_err();
        assert!(err.contains("auto/dark/light"), "实际错误: {err}");

        let err = apply_settings(
            Config::default(),
            &json!({ "relayUrls": ["relay.example.com"] }),
        )
        .unwrap_err();
        assert!(err.contains("http:// 或 https://"), "实际错误: {err}");
    }

    #[test]
    fn concurrent_settings_saves_keep_every_field() {
        // E-1：两次保存并发时，若各自从同一份旧快照出发，后写入者会把前者的字段
        // 覆盖回旧值。八个线程各改一个互不相同的字段，全部经 settings 锁串行落盘，
        // 最终八个字段都必须还在。
        let dir = temp_path("concurrent-settings");
        let paths = Paths::at(&dir);
        Config::default().save(&paths).expect("写入初始配置");
        let settings = Mutex::new(());
        // 每项的取值都必须与 Config::default() 不同，否则丢更新也看不出来。
        let payloads = [
            json!({ "nodeName": "并发节点" }),
            json!({ "piPath": "pi-concurrent" }),
            json!({ "relayMode": "disabled" }),
            json!({ "enableMdns": true }),
            json!({ "defaultCapability": "read" }),
            json!({ "theme": "light" }),
            json!({ "approvalTimeoutSecs": 42 }),
            json!({ "closeToTray": false }),
        ];

        std::thread::scope(|scope| {
            for payload in &payloads {
                scope.spawn(|| {
                    let guard = lock(&settings);
                    apply_and_save_settings(&guard, &paths, payload).expect("保存设置");
                });
            }
        });

        let saved = Config::load(&paths).expect("读回配置");
        assert_eq!(saved.node.name, "并发节点");
        assert_eq!(saved.node.pi_path, "pi-concurrent");
        assert_eq!(saved.transport.relay_mode, "disabled");
        assert!(saved.transport.enable_mdns);
        assert_eq!(saved.sessions.default_capability, "read");
        assert_eq!(saved.ui.theme, "light");
        assert_eq!(saved.approvals.timeout_secs, 42);
        assert!(!saved.service.close_to_tray);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn validate_relay_urls_rejects_other_schemes() {
        for bad in ["ftp://relay.example.com", "//relay.example.com", "https:/x"] {
            assert!(
                validate_relay_urls(&[bad.to_string()]).is_err(),
                "应拒绝 {bad}"
            );
        }
    }

    #[test]
    fn read_log_tail_keeps_whole_lines_from_tail() {
        let dir = temp_path("read-log-tail");
        let log = dir.join("desktop.log");
        std::fs::write(&log, "第一行\n第二行\n第三行\n").expect("写入日志");

        let tail = read_log_tail(&log, 12).expect("读取日志尾部");
        // 起点落在行中间时丢掉半行，剩余行序保持原顺序。
        assert!(tail.ends_with('\n'), "尾部应保留换行: {tail:?}");
        assert!(!tail.contains("第一行"), "应截掉最早的行: {tail:?}");
        let rows: Vec<&str> = tail.lines().collect();
        let mut sorted = rows.clone();
        sorted.sort();
        assert_eq!(rows, sorted, "行序应保持原顺序");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn read_log_tail_reads_small_file_whole() {
        let dir = temp_path("read-log-small");
        let log = dir.join("desktop.log");
        std::fs::write(&log, "a\nb\n").expect("写入日志");

        assert_eq!(read_log_tail(&log, LOG_TAIL_BYTES).unwrap(), "a\nb\n");
        assert!(read_log_tail(&dir.join("missing.log"), LOG_TAIL_BYTES).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn archived_logs(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
        std::fs::read_dir(dir)
            .expect("读取日志目录")
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with("desktop-") && name.ends_with(".log"))
            })
            .collect()
    }

    #[test]
    fn rotate_log_if_needed_moves_oversized_log() {
        let dir = temp_path("rotate-log");
        let log = dir.join("desktop.log");
        std::fs::write(&log, vec![b'x'; (LOG_ROTATE_BYTES + 1) as usize]).expect("写入日志");

        rotate_log_if_needed(&log).expect("轮转日志");
        assert!(!log.exists(), "超限日志应被移走");
        let archived = archived_logs(&dir);
        assert_eq!(archived.len(), 1, "应生成一个时间戳归档: {archived:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn rotate_log_if_needed_keeps_small_log() {
        let dir = temp_path("rotate-log-small");
        let log = dir.join("desktop.log");
        std::fs::write(&log, "小日志\n").expect("写入日志");

        rotate_log_if_needed(&log).expect("小日志不轮转");
        assert!(log.exists());
        assert!(archived_logs(&dir).is_empty());
        // 文件不存在时不应报错（首次启动尚无日志）。
        rotate_log_if_needed(&dir.join("missing.log")).expect("缺失文件不报错");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn rotating_log_writes_and_rotates_on_size() {
        use std::io::Write as _;

        let dir = temp_path("rotating-log");
        let log = dir.join("desktop.log");
        std::fs::write(&log, vec![b'x'; (LOG_ROTATE_BYTES + 1) as usize]).expect("写入日志");

        let mut writer = RotatingLog::new(log.clone());
        writer.write_all(b"after-rotate\n").expect("写入新日志");
        assert_eq!(std::fs::read_to_string(&log).unwrap(), "after-rotate\n");
        assert_eq!(archived_logs(&dir).len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn cleanup_old_logs_only_removes_expired_archives() {
        let dir = temp_path("log-cleanup");
        let log = dir.join("desktop.log");
        let old = dir.join("desktop-20200101-000000-000.log");
        let other = dir.join("notes.txt");
        std::fs::write(&log, "current\n").expect("写当前日志");
        std::fs::write(&old, "old\n").expect("写旧归档");
        std::fs::write(&other, "keep\n").expect("写其它文件");

        // 用一个「未来」的时间点，等价于归档已超过保留期。
        let future = std::time::SystemTime::now()
            + std::time::Duration::from_secs((LOG_RETENTION_DAYS + 1) * 24 * 60 * 60);
        cleanup_old_logs(&log, future);
        assert!(!old.exists(), "过期归档应被删除");
        assert!(log.exists(), "当前日志不能被删");
        assert!(other.exists(), "非归档文件不能被删");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn update_cancel_flag_resets_on_next_run() {
        let state = AppState::default();
        let _rx = state.begin_update_cancel();
        state.cancel_update();
        assert!(state.is_update_cancelled());
        state.end_update_cancel();
        let _rx = state.begin_update_cancel();
        assert!(
            !state.is_update_cancelled(),
            "新一次安装必须清掉上次的取消标志"
        );
    }

    #[test]
    #[cfg(windows)]
    fn autostart_command_quotes_path_and_appends_minimized() {
        let exe = std::path::PathBuf::from(r"C:\Program Files\pi carry\pi-carry-desktop.exe");
        assert_eq!(
            autostart_command(&exe, false),
            format!("\"{}\"", exe.display())
        );
        assert!(autostart_command(&exe, true).ends_with(" --minimized"));
    }

    #[test]
    fn apply_settings_accepts_update_and_service_fields() {
        let applied = apply_settings(
            Config::default(),
            &json!({
                "startMinimized": false,
                "logLevel": "debug",
                "checkOnStartup": false,
                "updateChannel": "beta",
            }),
        )
        .expect("应能应用");
        assert!(!applied.service.start_minimized);
        assert_eq!(applied.service.log_level, "debug");
        assert!(!applied.updates.check_on_startup);
        assert_eq!(applied.updates.channel, "beta");
    }

    #[test]
    fn apply_settings_rejects_invalid_log_level_and_channel() {
        let err = apply_settings(Config::default(), &json!({ "logLevel": "verbose" })).unwrap_err();
        assert!(
            err.contains("error/warn/info/debug/trace"),
            "实际错误: {err}"
        );

        let err =
            apply_settings(Config::default(), &json!({ "updateChannel": "nightly" })).unwrap_err();
        assert!(err.contains("stable/beta"), "实际错误: {err}");
    }
}
