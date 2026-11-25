//! 首启自检：把「pi 未找到 / 托盘不可用 / 密钥明文存储」这类环境问题
//! 变成界面上一张可执行的卡片，而不是让用户面对一个永远连不上的节点。

use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use serde_json::{json, Value};
use tauri::State;

/// DE-114：pi 版本下限。低于它时部分能力不可用，自检卡要给出「版本过低」。
const PI_MIN_VERSION: &str = "1.5.2";

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
    // DE-114：自检卡要能区分「没装 pi」和「pi 太旧」，所以带上探测到的版本号。
    // detect_version 最坏阻塞 5 秒（外部脚本卡死），因此只在自检时探测一次，
    // 不放进每 5 秒的前端轮询路径。
    let (pi_installed, pi_path, pi_error, pi_version) =
        match pi_carry_core::pi::resolve_pi(explicit) {
            Ok(path) => {
                let version = pi_carry_core::pi::detect_version(&path);
                (
                    true,
                    Value::String(path.display().to_string()),
                    Value::Null,
                    (!version.is_empty()).then_some(version),
                )
            }
            Err(err) => (false, Value::Null, Value::String(err.to_string()), None),
        };
    let pi_version_ok = pi_version
        .as_deref()
        .map(|version| version_at_least(version, PI_MIN_VERSION));

    let secret_backend = state
        .bridge()
        .ok()
        .map(|bridge| bridge.secret_backend.as_str());

    json!({
        "piInstalled": pi_installed,
        "piPath": pi_path,
        "piError": pi_error,
        // DE-112/DE-114：诊断卡要能显示「pi 版本过低（需要 ≥1.5.2）」。
        "piVersion": pi_version,
        "piMinVersion": PI_MIN_VERSION,
        "piVersionOk": pi_version_ok,
        "tray": state.tray_available(),
        "secretBackend": secret_backend,
        "portable": crate::update::is_portable(),
        // 能跑起来就说明 WebView2 已安装；保留字段让前端自检卡逻辑完整。
        "webview2": cfg!(windows),
        "autostart": config.service.autostart,
        "startMinimized": config.service.start_minimized,
        "configError": config_error,
        // DE-112：数据目录可写是桥接启动的前提，自检卡要给出具体结论。
        "dataWritable": probe_data_dir(&paths.home),
        "dataDir": paths.home.display().to_string(),
        "logDir": paths.logs().display().to_string(),
        "version": env!("CARGO_PKG_VERSION"),
    })
}

/// DE-112：数据目录可写探针。只做一次临时文件往返，失败时由自检卡给出路径；
/// 探针文件一定删掉，不在用户数据目录留下垃圾。
fn probe_data_dir(dir: &std::path::Path) -> bool {
    if std::fs::create_dir_all(dir).is_err() {
        return false;
    }
    let probe = dir.join(format!(".selfcheck-{}", std::process::id()));
    if std::fs::write(&probe, b"pi-carry").is_err() {
        return false;
    }
    let _ = std::fs::remove_file(&probe);
    true
}

/// 按数字段比较版本号：只用于「≥ 1.5.2」这种简单判断，
/// 遇到无法解析的值一律按「不满足」处理（自检会提示用户升级）。
fn version_at_least(version: &str, minimum: &str) -> bool {
    fn parse(value: &str) -> Option<(u64, u64, u64)> {
        let mut parts = value.trim().trim_start_matches('v').split('.');
        let major = parts.next()?.parse().ok()?;
        let minor = parts.next().unwrap_or("0").parse().ok()?;
        let patch = parts.next().unwrap_or("0").parse().ok()?;
        Some((major, minor, patch))
    }
    match (parse(version), parse(minimum)) {
        (Some(actual), Some(required)) => actual >= required,
        _ => false,
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_at_least_compares_numeric_segments() {
        assert!(version_at_least("1.5.2", PI_MIN_VERSION));
        assert!(version_at_least("1.10.0", "1.9.9"));
        assert!(version_at_least("2.0.0", PI_MIN_VERSION));
        assert!(!version_at_least("1.5.1", PI_MIN_VERSION));
        assert!(!version_at_least("1.4.99", PI_MIN_VERSION));
        // 探测失败时 detect_version 返回空串，必须按「不满足」处理而不是 panic。
        assert!(!version_at_least("", PI_MIN_VERSION));
        assert!(!version_at_least("dev", PI_MIN_VERSION));
    }
}
