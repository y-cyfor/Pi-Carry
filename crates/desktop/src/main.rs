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
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WindowEvent};

struct AppState {
    bridge: Mutex<Option<Arc<Bridge>>>,
    last_error: Mutex<Option<String>>,
    close_to_tray: AtomicBool,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            bridge: Mutex::new(None),
            last_error: Mutex::new(None),
            close_to_tray: AtomicBool::new(true),
        }
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

    fn set_error(&self, message: Option<String>) {
        *lock(&self.last_error) = message;
    }

    fn error(&self) -> Option<String> {
        lock(&self.last_error).clone()
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn now_rfc3339() -> String {
    pi_carry_core::rfc3339(chrono::Utc::now())
}

fn status_value(state: &AppState) -> Value {
    let Some(bridge) = lock(&state.bridge).clone() else {
        return json!({
            "running": false,
            "lastError": state.error(),
            "configPath": Paths::discover().config().display().to_string(),
            "dataDir": Paths::discover().home.display().to_string(),
            "version": pi_carry_core::VERSION,
        });
    };
    let node = bridge.node_status();
    let devices = bridge.store.list_devices().unwrap_or_default();
    let sessions = bridge.sessions.list().unwrap_or_default();
    let pairing = bridge.pairing.status();
    json!({
        "running": true,
        "nodeName": node.get("nodeName").cloned().unwrap_or(Value::Null),
        "endpointId": node.get("endpointId").cloned().unwrap_or(Value::Null),
        "relayMode": node.get("relayMode").cloned().unwrap_or(Value::Null),
        "directAddresses": node.get("directAddresses").cloned().unwrap_or(Value::Null),
        "piInstalled": node.get("piInstalled").cloned().unwrap_or(Value::Bool(false)),
        "deviceCount": devices.iter().filter(|d| d.revoked_at.is_none()).count(),
        "onlineDevices": devices.iter().filter(|d| d.revoked_at.is_none() && d.online).count(),
        "sessionCount": sessions.len(),
        "runningSessions": sessions.iter().filter(|s| s.status == "streaming").count(),
        "pendingApprovals": sessions.iter().map(|s| s.pending_approvals).sum::<u32>(),
        "pairing": pairing,
        "configPath": bridge.paths.config().display().to_string(),
        "dataDir": bridge.paths.home.display().to_string(),
        "version": pi_carry_core::VERSION,
        "lastError": state.error(),
    })
}

fn devices_value(state: &AppState) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let rows = bridge.store.list_devices().map_err(|e| e.to_string())?;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|d| {
            json!({
                "id": d.id,
                "name": d.name,
                "platform": d.platform,
                "deviceClass": d.device_class,
                "capability": d.capability,
                "pairedAt": d.paired_at,
                "lastSeen": d.last_seen,
                "revokedAt": d.revoked_at,
                "online": d.online,
            })
        })
        .collect();
    Ok(Value::Array(items))
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
    let bridge = state.bridge()?;
    // 从磁盘读取，避免使用桥接启动时的配置快照：否则刚保存的主题/节点设置会被旧值覆盖。
    let config = Config::load(&bridge.paths).map_err(|e| e.to_string())?;
    let mut value = serde_json::to_value(config).map_err(|e| e.to_string())?;
    if let Value::Object(map) = &mut value {
        map.insert(
            "closeToTray".into(),
            Value::Bool(state.close_to_tray.load(Ordering::Relaxed)),
        );
        map.insert(
            "dataDir".into(),
            Value::String(bridge.paths.home.display().to_string()),
        );
        map.insert(
            "configPath".into(),
            Value::String(bridge.paths.config().display().to_string()),
        );
    }
    Ok(value)
}

#[tauri::command]
fn update_settings(state: State<'_, AppState>, values: Value) -> Result<Value, String> {
    let bridge = state.bridge()?;
    let mut config = Config::load(&bridge.paths).map_err(|e| e.to_string())?;
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
        config.transport.relay_urls = arr
            .iter()
            .filter_map(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
    }
    if let Some(v) = values.get("enableMdns").and_then(Value::as_bool) {
        config.transport.enable_mdns = v;
    }
    if let Some(v) = values.get("autoStart").and_then(Value::as_bool) {
        config.service.autostart = v;
        set_autostart(v)?;
    }
    if let Some(v) = values.get("closeToTray").and_then(Value::as_bool) {
        config.service.close_to_tray = v;
        state.close_to_tray.store(v, Ordering::Relaxed);
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
    if let Some(v) = values.get("approvalTimeoutSecs").and_then(Value::as_u64) {
        config.approvals.timeout_secs = v;
    }
    config
        .save(&bridge.paths)
        .map_err(|e| format!("保存配置失败: {e}"))?;
    let _ = bridge
        .store
        .audit(&now_rfc3339(), "desktop", "settings.update", &values);
    Ok(json!({
        "saved": true,
        "restartRequired": values.get("relayMode").is_some()
            || values.get("relayUrls").is_some()
            || values.get("enableMdns").is_some()
            || values.get("nodeName").is_some()
            || values.get("piPath").is_some()
            || values.get("defaultCapability").is_some()
            || values.get("approvalTimeoutSecs").is_some(),
    }))
}

#[tauri::command]
async fn restart_service(app: AppHandle, state: State<'_, AppState>) -> Result<Value, String> {
    if let Some(old) = state.take_bridge() {
        old.shutdown().await;
    }
    state.set_error(None);
    match launch_bridge().await {
        Ok(bridge) => {
            *lock(&state.bridge) = Some(bridge);
            let value = status_value(&state);
            let _ = app.emit("bridge-status", value.clone());
            Ok(value)
        }
        Err(err) => {
            state.set_error(Some(err.clone()));
            Err(err)
        }
    }
}

#[tauri::command]
fn get_logs(lines: Option<usize>) -> Value {
    let paths = Paths::discover();
    let log = paths.logs().join("desktop.log");
    let limit = lines.unwrap_or(240).min(2000);
    match std::fs::read_to_string(&log) {
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

#[tauri::command]
fn open_config_dir() -> Result<(), String> {
    let paths = Paths::discover();
    paths
        .ensure()
        .map_err(|e| format!("创建数据目录失败: {e}"))?;
    open_path(&paths.home)
}

fn open_path(path: &std::path::Path) -> Result<(), String> {
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

fn set_autostart(enable: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        let exe = std::env::current_exe().map_err(|e| format!("无法获取程序路径: {e}"))?;
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        if enable {
            let status = std::process::Command::new("reg")
                .args([
                    "add",
                    key,
                    "/v",
                    "pi-carry",
                    "/t",
                    "REG_SZ",
                    "/d",
                    &exe.display().to_string(),
                    "/f",
                ])
                .status()
                .map_err(|e| format!("写入开机自启失败: {e}"))?;
            if !status.success() {
                return Err(format!("reg add 退出码 {}", status.code().unwrap_or(-1)));
            }
        } else {
            let _ = std::process::Command::new("reg")
                .args(["delete", key, "/v", "pi-carry", "/f"])
                .status();
        }
    }
    #[cfg(not(windows))]
    {
        let _ = enable;
    }
    Ok(())
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

fn init_logging() {
    let paths = Paths::discover();
    let _ = paths.ensure();
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(paths.logs().join("desktop.log"));
    let level = std::env::var("PI_CARRY_LOG").unwrap_or_else(|_| "info".into());
    let filter = tracing_subscriber::EnvFilter::try_new(level)
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    let builder = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_ansi(false);
    if let Ok(file) = file {
        let writer = Mutex::new(file);
        let _ = builder.with_writer(writer).try_init();
    } else {
        let _ = builder.try_init();
    }
}

fn run_smoke() -> Result<(), String> {
    tauri::async_runtime::block_on(async {
        let bridge = Bridge::start(BridgeOptions::load().map_err(|e| e.to_string())?)
            .await
            .map_err(|e| format!("桥接启动失败: {e}"))?;
        let node = bridge.node_status();
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({
                "ok": true,
                "endpointId": node.get("endpointId"),
                "relayMode": node.get("relayMode"),
                "sessions": bridge.sessions.list().map(|s| s.len()).unwrap_or(0),
                "piInstalled": node.get("piInstalled"),
            }))
            .unwrap()
        );
        bridge.shutdown().await;
        Ok::<(), String>(())
    })
}

/// 单实例互斥：第二个进程启动时直接退出，避免两个托盘同时持有同一个端点。
#[cfg(windows)]
fn acquire_single_instance() -> bool {
    use std::ptr;
    use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;

    let name: Vec<u16> = "Local\\pi-carry-desktop\0".encode_utf16().collect();
    unsafe {
        let handle = CreateMutexW(ptr::null(), 1, name.as_ptr());
        if handle.is_null() {
            return true;
        }
        if GetLastError() == ERROR_ALREADY_EXISTS {
            CloseHandle(handle);
            return false;
        }
        // 故意不关闭：句柄随进程退出由系统释放，保证互斥覆盖整个生命周期。
        let _ = handle;
        true
    }
}

#[cfg(not(windows))]
fn acquire_single_instance() -> bool {
    true
}

fn main() {
    // --smoke 是测试探针，不是第二个桌面实例：不打开窗口、不抢单实例互斥。
    // 必须先于互斥判定，否则桌面端已运行时会被 exit(0) 提前放行，smoke 假绿。
    let smoke = std::env::args().any(|a| a == "--smoke");
    if !smoke && !acquire_single_instance() {
        eprintln!("pi carry 已在运行");
        std::process::exit(0);
    }

    init_logging();
    if smoke {
        if let Err(e) = run_smoke() {
            eprintln!("{e}");
            std::process::exit(1);
        }
        return;
    }
    let state = AppState::default();
    let close_to_tray = Config::load(&Paths::discover())
        .map(|c| c.service.close_to_tray)
        .unwrap_or(true);
    state.close_to_tray.store(close_to_tray, Ordering::Relaxed);

    let app = tauri::Builder::default()
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
            restart_service,
            get_logs,
            open_data_dir,
            open_config_dir,
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let state = app.state::<AppState>();

            // 使用固定的逻辑尺寸：Tauri 在部分 Windows 缩放环境下会错误上报 scale_factor，
            // 按显示器反推尺寸反而会让窗口超出屏幕。1160×680 在 100%–200% 缩放下都能完整显示。
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_min_size(Some(tauri::LogicalSize::new(880.0, 560.0)));
                let _ = window.set_size(tauri::LogicalSize::new(1160.0, 680.0));
                let _ = window.center();
            }

            let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/32x32.png"))?;
            let show = MenuItem::with_id(app, "show", "打开 pi carry", true, None::<&str>)?;
            let pair = MenuItem::with_id(app, "pair", "开始配对", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &pair, &quit])?;
            TrayIconBuilder::with_id("main")
                .icon(icon)
                .tooltip("pi carry · 本机桥接")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_main(app),
                    "pair" => {
                        show_main(app);
                        let _ = app.emit("navigate", "pairing");
                    }
                    "quit" => app.exit(0),
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
                .build(app)?;

            let _ = app.emit("bridge-status", status_value(&state));
            tauri::async_runtime::spawn(async move {
                let state = handle.state::<AppState>();
                match launch_bridge().await {
                    Ok(bridge) => {
                        *lock(&state.bridge) = Some(bridge);
                        state.set_error(None);
                    }
                    Err(err) => {
                        tracing::error!(error = %err, "bridge 启动失败");
                        state.set_error(Some(err));
                    }
                }
                let _ = handle.emit("bridge-status", status_value(&state));
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<AppState>();
                if window.label() == "main" && state.close_to_tray.load(Ordering::Relaxed) {
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
