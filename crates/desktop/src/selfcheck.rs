//! 首启自检：把「pi 未找到 / 托盘不可用 / 密钥明文存储」这类环境问题
//! 变成界面上一张可执行的卡片，而不是让用户面对一个永远连不上的节点。

use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use serde_json::{json, Value};
use tauri::State;

#[tauri::command]
pub(crate) fn self_check(state: State<'_, crate::AppState>) -> Value {
    let paths = state.paths();
    let (config, config_error) = match Config::load(&paths) {
        Ok(config) => (config, Value::Null),
        Err(err) => (
            Config::default(),
            Value::String(format!("读取配置失败：{err}")),
        ),
    };

    let explicit = config.node.pi_path.trim();
    let explicit = (!explicit.is_empty()).then_some(explicit);
    let (pi_installed, pi_path, pi_error) = match pi_carry_core::pi::resolve_pi(explicit) {
        Ok(path) => (true, Value::String(path.display().to_string()), Value::Null),
        Err(err) => (false, Value::Null, Value::String(err.to_string())),
    };

    let secret_backend = state
        .bridge()
        .ok()
        .map(|bridge| bridge.secret_backend.as_str());

    json!({
        "piInstalled": pi_installed,
        "piPath": pi_path,
        "piError": pi_error,
        "tray": state.tray_available(),
        "secretBackend": secret_backend,
        "portable": crate::update::is_portable(),
        // 能跑起来就说明 WebView2 已安装；保留字段让前端自检卡逻辑完整。
        "webview2": cfg!(windows),
        "autostart": config.service.autostart,
        "startMinimized": config.service.start_minimized,
        "configError": config_error,
        "dataDir": paths.home.display().to_string(),
        "logDir": paths.logs().display().to_string(),
        "version": env!("CARGO_PKG_VERSION"),
    })
}

/// 托盘「打开日志目录」与设置页按钮共用。
#[tauri::command]
pub(crate) fn open_logs_dir() -> Result<(), String> {
    let paths = Paths::discover();
    paths
        .ensure()
        .map_err(|e| format!("创建日志目录失败：{e}"))?;
    crate::open_path(&paths.logs())
}
