//! `pi-carry://pair` 深链：注册协议、接收系统唤醒、把配对信息预填到配对页。
//!
//! 深链只做「预填」：不自动开始、也不确认配对。链接可能来自任何聊天窗口，
//! 因此只用于核对手机信息；实际配对仍需本机生成二维码、由手机扫码并确认。

use pi_carry_core::paths::Paths;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_deep_link::DeepLinkExt;

/// 第二个实例把 URI 留在这里，首个实例收到唤醒信号后读取。
///
/// Windows 具名事件与 Unix socket 都只传「显示窗口」这一个信号，不携带
/// 任意长度的 payload；用文件交接既不改协议，也不会被 16 字节缓冲截断。
const PENDING_FILE: &str = "pending-pair-uri";

/// 从启动参数里取配对链接。
///
/// 支持 `--pair-uri <uri>`、`--pair-uri=<uri>`，以及系统直接传入的裸 URL：
/// Windows 注册表与 Linux desktop 文件都把链接作为唯一参数交给可执行文件，
/// 不会替我们加 `--pair-uri` 前缀。
pub(crate) fn pair_uri_from_args(args: &[String]) -> Option<String> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == "--pair-uri" {
            return iter.next().filter(|v| !v.is_empty()).cloned();
        }
        if let Some(value) = arg.strip_prefix("--pair-uri=") {
            return (!value.is_empty()).then(|| value.to_string());
        }
        if arg.to_ascii_lowercase().starts_with("pi-carry://") {
            return Some(arg.clone());
        }
    }
    None
}

/// 处理一条深链：解析 → 存为待预填 → 显示窗口 → 通知前端。
pub(crate) fn handle(app: &AppHandle, uri: &str) {
    let payload = payload_for(uri);
    let valid = payload.get("valid") == Some(&Value::Bool(true));
    if !valid {
        // 不记录完整 URI：里面带 token，等于把入网凭证写进日志。
        tracing::warn!(
            reason = payload
                .get("error")
                .and_then(|value| value.as_str())
                .unwrap_or("未知"),
            "拒绝外部配对链接"
        );
    }
    if let Some(state) = app.try_state::<crate::AppState>() {
        state.set_pending_pair(Some(payload.clone()));
    }
    crate::show_main(app);
    let _ = app.emit("pair-uri", payload);
    if valid {
        let _ = app.emit("navigate", "pairing");
    }
}

fn payload_for(uri: &str) -> Value {
    match pi_carry_core::pair_uri::parse(uri) {
        Ok(pair) => {
            // 生成侧会把所有直连地址写进二维码：`addr` 保留首条以兼容既有前端，
            // 新增 `addrs` 带全部（首条往往只是 127.0.0.1，不可用于跨机连接）。
            let first_addr = pair.addrs.first().cloned();
            json!({
                "valid": true,
                "uri": uri,
                "endpointId": pair.endpoint,
                "token": pair.token,
                "name": pair.name,
                "relay": pair.relay,
                "addr": first_addr,
                "addrs": pair.addrs,
            })
        }
        Err(err) => json!({
            "valid": false,
            "uri": uri,
            "error": err.to_string(),
        }),
    }
}

/// 注册协议并接收「应用已在运行时」的深链事件。
pub(crate) fn init(app: &AppHandle) {
    // macOS 的 scheme 由 Info.plist 声明，运行期注册会返回 UnsupportedPlatform；
    // Windows/Linux 必须显式注册，否则浏览器点链接不会拉起本程序。
    #[cfg(any(windows, target_os = "linux"))]
    if let Err(err) = app.deep_link().register_all() {
        tracing::warn!(error = %err, "注册 pi-carry:// 协议失败");
    }

    let listener = app.clone();
    app.deep_link().on_open_url(move |event| {
        for url in event.urls() {
            handle(&listener, url.as_str());
        }
    });

    // 冷启动：插件在 setup 阶段把命令行里的 URL 收进了 get_current。
    match app.deep_link().get_current() {
        Ok(Some(urls)) => {
            for url in urls {
                handle(app, url.as_str());
            }
        }
        Ok(None) => {}
        Err(err) => tracing::warn!(error = %err, "读取启动深链失败"),
    }
}

/// 处理 `--pair-uri` 注入（冒烟测试与手动排查用）。
pub(crate) fn handle_cli(app: &AppHandle, args: &[String]) {
    if let Some(uri) = pair_uri_from_args(args) {
        handle(app, &uri);
    }
}

/// 第二个实例：把 URI 写入运行目录，随后再发「显示窗口」信号。
pub(crate) fn forward_uri(uri: &str) -> Result<(), String> {
    let paths = Paths::discover();
    paths
        .ensure()
        .map_err(|e| format!("创建运行目录失败：{e}"))?;
    let target = paths.run().join(PENDING_FILE);
    let temp = paths.run().join(format!("{PENDING_FILE}.tmp"));
    std::fs::write(&temp, uri).map_err(|e| format!("暂存配对链接失败：{e}"))?;
    // 链接里含一次性配对 token：只允许当前用户读取。
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&temp, std::fs::Permissions::from_mode(0o600));
    }
    std::fs::rename(&temp, &target).map_err(|e| format!("暂存配对链接失败：{e}"))
}

/// 首个实例：消费第二条实例留下的 URI（启动时也调用一次，覆盖竞态）。
pub(crate) fn consume_pending(app: &AppHandle) {
    let pending = Paths::discover().run().join(PENDING_FILE);
    match std::fs::read_to_string(&pending) {
        Ok(uri) => {
            let _ = std::fs::remove_file(&pending);
            let uri = uri.trim();
            if !uri.is_empty() {
                handle(app, uri);
            }
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {}
        Err(err) => tracing::warn!(
            error = %err,
            path = %pending.display(),
            "读取待处理配对链接失败"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(items: &[&str]) -> Vec<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn pair_uri_flag_accepts_both_forms() {
        assert_eq!(
            pair_uri_from_args(&args(&["--minimized", "--pair-uri", "pi-carry://pair?x=1"])),
            Some("pi-carry://pair?x=1".to_string())
        );
        assert_eq!(
            pair_uri_from_args(&args(&["--pair-uri=pi-carry://pair?x=2"])),
            Some("pi-carry://pair?x=2".to_string())
        );
    }

    #[test]
    fn pair_uri_accepts_bare_url_from_system_handler() {
        // Windows 注册表/ Linux .desktop 直接以 URL 作为参数启动。
        assert_eq!(
            pair_uri_from_args(&args(&["pi-carry://pair?v=1&endpoint=a"])),
            Some("pi-carry://pair?v=1&endpoint=a".to_string())
        );
        assert_eq!(
            pair_uri_from_args(&args(&["PI-CARRY://pair?x=1"])),
            Some("PI-CARRY://pair?x=1".to_string())
        );
    }

    #[test]
    fn pair_uri_flag_ignores_missing_or_empty_values() {
        assert_eq!(pair_uri_from_args(&args(&["--pair-uri"])), None);
        assert_eq!(pair_uri_from_args(&args(&["--pair-uri="])), None);
        assert_eq!(pair_uri_from_args(&args(&["--smoke"])), None);
    }

    #[test]
    fn payload_marks_invalid_uri_without_panicking() {
        let payload = payload_for("https://example.com");
        assert_eq!(payload["valid"], false);
        assert!(!payload["error"].as_str().unwrap_or_default().is_empty());

        let payload = payload_for("pi-carry://pair?bad=1");
        assert_eq!(payload["valid"], false);
    }
}
