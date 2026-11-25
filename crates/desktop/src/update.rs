//! 自动更新：检查、签名校验、安装与失败降级。
//!
//! 安装器版交给 tauri-plugin-updater：它按 `latest.json` 找到当前 target，
//! 下载后先用内置公钥验签再交给系统安装器。Windows 便携版与安装器共用同
//! 一份 release 二进制（带着 Nsis 标记），但目录里没有卸载器，必须单独读
//! 清单的 `portable` 段：只提示下载，绝不替换正在运行的 exe。

use crate::{now_rfc3339, AppState};
use pi_carry_core::config::Config;
use pi_carry_core::updatecheck::{self, PORTABLE_TARGET_WINDOWS};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_updater::UpdaterExt;

/// 手动下载页：稳定的 latest 永远指向最新正式版，beta 走固定 tag。
const STABLE_PAGE: &str = "https://github.com/y-cyfor/Pi-Carry/releases/latest";
const BETA_PAGE: &str = "https://github.com/y-cyfor/Pi-Carry/releases/tag/beta";

pub(crate) fn release_page(channel: &str) -> &'static str {
    match channel {
        "beta" => BETA_PAGE,
        _ => STABLE_PAGE,
    }
}

/// DE-89：签名来源。桌面更新包只经 GitHub Releases 分发；
/// 其它地址（内网镜像、代理）不能冒充官方来源，照实标成外部地址。
fn update_source(url: &str) -> &'static str {
    if url.contains("github.com") || url.contains("githubusercontent.com") {
        "GitHub Releases"
    } else {
        "外部地址"
    }
}

/// Windows 便携版：解压即用，程序目录里没有 NSIS 卸载器。
///
/// 不读 `portable.txt` 之类的标记文件：用户可能只把 exe 拖出来，
/// 而「有没有卸载器」由安装方式决定，改不了也伪造不了。
pub(crate) fn is_portable() -> bool {
    if !cfg!(windows) {
        return false;
    }
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| !dir.join("uninstall.exe").exists()))
        .unwrap_or(false)
}

/// 检查更新。`manual=false` 用于启动后 30s 的静默检查：
/// 失败只写日志，不弹错误；`manual=true` 把错误原样返回给设置页。
#[tauri::command]
pub(crate) async fn check_update(
    app: AppHandle,
    state: State<'_, AppState>,
    manual: bool,
) -> Result<Value, String> {
    let paths = state.paths();
    let config = Config::load(&paths).map_err(|e| format!("读取更新配置失败：{e}"))?;
    let channel = config.updates.channel.clone();
    let last_failed = config.updates.last_failed_version.clone();
    let current = app.package_info().version.to_string();
    let portable = is_portable();
    let checked_at = now_rfc3339();

    let payload = if portable {
        check_portable(&current, &channel, &last_failed, &checked_at).await?
    } else {
        check_installer(&app, &state, &current, &channel, &last_failed, &checked_at).await?
    };

    // 记录时间点失败不影响本次检查结果：日志里能看到，设置页下次会重试。
    if let Err(err) = state.update_config(&paths, |config| {
        config.updates.last_checked_at = checked_at.clone();
    }) {
        tracing::warn!(error = %err, "记录更新检查时间失败");
    }
    let available = payload
        .get("available")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    let suppressed = payload
        .get("suppressed")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    tracing::info!(manual, channel = %channel, portable, available, suppressed, "更新检查完成");
    state.set_last_update(payload.clone());
    let _ = app.emit("update-check", payload.clone());
    // DE-130：托盘菜单「检查更新（有更新）」与状态行要跟着本次结果变。
    crate::refresh_tray(&app);
    Ok(payload)
}

/// 安装器版：由插件负责 target 解析、下载与验签。
async fn check_installer(
    app: &AppHandle,
    state: &AppState,
    current: &str,
    channel: &str,
    last_failed: &str,
    checked_at: &str,
) -> Result<Value, String> {
    let endpoint = tauri::Url::parse(updatecheck::endpoint_for(channel))
        .map_err(|e| format!("更新地址无效：{e}"))?;
    let channel_for_compare = channel.to_string();
    let updater = app
        .updater_builder()
        .endpoints(vec![endpoint])
        .map_err(|e| format!("更新地址无效：{e}"))?
        .version_comparator(move |current, release| {
            // 比较器不能返回 Result：服务端版本号写坏时按「不是更新」处理并留日志，
            // 不让一次坏清单把检查流程变成错误弹窗。
            match updatecheck::is_newer(
                &current.to_string(),
                &release.version.to_string(),
                &channel_for_compare,
            ) {
                Ok(newer) => newer,
                Err(err) => {
                    tracing::warn!(
                        error = %err,
                        version = %release.version,
                        "更新清单版本号无效，忽略该版本"
                    );
                    false
                }
            }
        })
        .build()
        .map_err(|e| format!("初始化更新检查失败：{e}"))?;

    let Some(update) = updater
        .check()
        .await
        .map_err(|e| format!("检查更新失败：{e}"))?
    else {
        state.set_pending_update(None);
        return Ok(json!({
            "available": false,
            "portable": false,
            "suppressed": false,
            "currentVersion": current,
            "channel": channel,
            "checkedAt": checked_at,
        }));
    };

    // 自动检查时，装失败过的同一版本不再打扰；手动检查仍展示。
    // 自动/手动的展示差异交给前端：自动检查按 suppressed 隐藏横幅，
    // 手动检查即使被抑制也展示版本与下载页。
    let suppressed = updatecheck::should_suppress(&update.version, last_failed);
    let source = update_source(update.download_url.as_str());
    let payload = json!({
        "available": true,
        "portable": false,
        "suppressed": suppressed,
        "currentVersion": current,
        "version": update.version,
        "notes": update.body,
        "date": update.date.map(|d| d.to_string()),
        "target": update.target,
        "url": update.download_url.as_str(),
        "pageUrl": release_page(channel),
        "channel": channel,
        "checkedAt": checked_at,
        // DE-89：检查阶段只声明「发布签名」，绝不提前声称已校验 ——
        // 验签发生在下载完成之后（插件用内置公钥校验，见 install_update）。
        "signatureAlgorithm": "ed25519",
        "signature": update.signature,
        "signatureVerified": false,
        "source": source,
        // latest.json 不带文件大小：未知就明确给 null，不编造数值。
        "sizeBytes": Value::Null,
    });
    // 始终保留待安装槽：自动检查时前端按 suppressed 隐藏横幅，
    // 用户手动检查后仍可重试安装（例如上次只是取消授权）。
    state.set_pending_update(Some(update));
    Ok(payload)
}

/// 便携版：只读清单 `portable` 段，判断有没有新版本。
async fn check_portable(
    current: &str,
    channel: &str,
    last_failed: &str,
    checked_at: &str,
) -> Result<Value, String> {
    let manifest = updatecheck::fetch_manifest(updatecheck::endpoint_for(channel))
        .await
        .map_err(|e| format!("检查更新失败：{e}"))?;
    // 抑制逻辑由本函数自行判断：便携版没有「安装」入口，手动检查
    // 仍应看到版本号与下载页，只是自动检查时不弹横幅。
    let Some(update) =
        updatecheck::portable_update(&manifest, current, channel, "", PORTABLE_TARGET_WINDOWS)
            .map_err(|e| format!("更新清单无效：{e}"))?
    else {
        return Ok(json!({
            "available": false,
            "portable": true,
            "suppressed": false,
            "currentVersion": current,
            "channel": channel,
            "checkedAt": checked_at,
        }));
    };
    let source = update_source(&update.url);
    Ok(json!({
        "available": true,
        "portable": true,
        "suppressed": updatecheck::should_suppress(&update.version, last_failed),
        "currentVersion": current,
        "version": update.version,
        "notes": update.notes,
        "sha256": update.sha256,
        "url": update.url,
        "pageUrl": release_page(channel),
        "channel": channel,
        "checkedAt": checked_at,
        // DE-89：便携版没有 ed25519 签名，只有发布清单里的 SHA-256；
        // 与安装器一致，检查阶段绝不声称「已校验」。
        "signatureAlgorithm": Value::Null,
        "signature": Value::Null,
        "signatureVerified": false,
        "sizeBytes": Value::Null,
        "source": source,
    }))
}

/// 下载并安装待安装的更新。
///
/// Windows 安装器由插件启动后自行退出本进程；其余平台装完后返回，
/// 由前端提示「重启以完成更新」并调用 `restart_app`。
#[tauri::command]
pub(crate) async fn install_update(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    if is_portable() {
        return Err("便携版不会自动替换程序，请下载新包后手动解压".into());
    }
    let Some(update) = state.pending_update() else {
        return Err("没有待安装的更新，请先检查更新".into());
    };
    let version = update.version.clone();
    // R2-10：只有真正进入安装（插件回调 on_download_finish，验签已通过）才置 installing；
    // 下载/校验阶段允许关窗，不能误拦成「安装中」。
    // 网络类失败按 5s/15s/60s 退避重试（DE-96、plan §7.2）；签名、授权、安装失败不重试。
    const BACKOFF_SECS: [u64; 3] = [5, 15, 60];
    let mut attempt = 0usize;
    let result = loop {
        attempt += 1;
        // 退避等待期间用户也可能点取消，此时下载还没开始。
        if state.is_update_cancelled() {
            let _ = app.emit(
                "update-progress",
                json!({ "phase": "cancelled", "version": version.clone() }),
            );
            state.set_installing(false);
            return Ok(json!({
                "installed": false,
                "cancelled": true,
                "version": version,
            }));
        }
        let cancel = state.begin_update_cancel();
        let progress = app.clone();
        let finishing = app.clone();
        let current_attempt = attempt;
        let install = update.download_and_install(
            move |downloaded, total| {
                let _ = progress.emit(
                    "update-progress",
                    json!({
                        "phase": "downloading",
                        "downloaded": downloaded,
                        "total": total,
                        "attempt": current_attempt,
                    }),
                );
            },
            move || {
                // 插件语义：下载完成且验签通过，接下来交给系统安装器。
                // R2-10：到这一步才是真正的安装阶段，此后的关窗请求由主进程拦截（DESK-18）。
                finishing.state::<AppState>().set_installing(true);
                let _ = finishing.emit("update-progress", json!({ "phase": "installing" }));
            },
        );
        tokio::pin!(install);
        // 取消时直接丢弃下载 future：reqwest 会中止请求，不会留下半截安装。
        let result = tokio::select! {
            result = &mut install => result,
            _ = cancel => {
                state.end_update_cancel();
                tracing::info!(version = %version, "用户取消更新下载");
                let _ = app.emit(
                    "update-progress",
                    json!({ "phase": "cancelled", "version": version.clone() }),
                );
                state.set_installing(false);
                return Ok(json!({
                    "installed": false,
                    "cancelled": true,
                    "version": version,
                }));
            }
        };
        state.end_update_cancel();
        match result {
            Err(err) if attempt <= BACKOFF_SECS.len() && is_retryable(&err) => {
                let delay = BACKOFF_SECS[attempt - 1];
                tracing::warn!(attempt, delay, error = %err, "更新下载失败，准备重试");
                let _ = app.emit(
                    "update-progress",
                    json!({ "phase": "retrying", "attempt": attempt, "delaySeconds": delay }),
                );
                // 退避等待也要能被取消，否则用户点了取消还得等满 15 秒。
                if state.is_update_cancelled() {
                    let _ = app.emit(
                        "update-progress",
                        json!({ "phase": "cancelled", "version": version.clone() }),
                    );
                    state.set_installing(false);
                    return Ok(json!({
                        "installed": false,
                        "cancelled": true,
                        "version": version,
                    }));
                }
                let wait = state.begin_update_cancel();
                tokio::select! {
                    _ = tokio::time::sleep(std::time::Duration::from_secs(delay)) => {}
                    _ = wait => {
                        state.end_update_cancel();
                        tracing::info!(version = %version, "用户在重试等待中取消更新");
                        let _ = app.emit(
                            "update-progress",
                            json!({ "phase": "cancelled", "version": version.clone() }),
                        );
                        state.set_installing(false);
                        return Ok(json!({
                            "installed": false,
                            "cancelled": true,
                            "version": version,
                        }));
                    }
                }
            }
            other => break other,
        }
    };

    let outcome = match result {
        Ok(()) => {
            tracing::info!(version = %version, "更新已安装，等待重启");
            state.set_pending_update(None);
            // DE-100：装成功说明这个版本已被接受，清掉「验签失败」抑制标记，
            // 否则重发的同版本修复包会被当成坏包静默跳过。
            if let Err(save) = state.update_config(&state.paths(), |config| {
                config.updates.last_failed_version.clear();
            }) {
                tracing::warn!(error = %save, "清理失败版本标记失败");
            }
            let _ = app.emit(
                "update-progress",
                json!({ "phase": "installed", "version": version }),
            );
            Ok(json!({ "installed": true, "version": version, "restartRequired": true }))
        }
        Err(err) => {
            let (kind, message) = classify_error(&err);
            // 只有「包被换过/签名不对」才抑制该版本；权限取消、磁盘满等问题
            // 修好后还能重试，不能因此让同一版本永远不再提示。
            if kind == "signature" {
                if let Err(save) = state.update_config(&state.paths(), |config| {
                    config.updates.last_failed_version = version.clone();
                }) {
                    tracing::warn!(error = %save, "记录失败版本失败");
                }
            }
            tracing::error!(kind, version = %version, error = %err, "更新安装失败");
            let _ = app.emit(
                "update-error",
                json!({
                    "kind": kind,
                    "message": message,
                    "version": version,
                    // DE-99：验签失败阻断对话框需要「期望签名」和下载源，前端据此展示 kv 行。
                    "expectedSignature": update.signature,
                    "downloadUrl": update.download_url.as_str(),
                }),
            );
            Err(message)
        }
    };
    state.set_installing(false);
    outcome
}

#[tauri::command]
pub(crate) fn cancel_update(state: State<'_, AppState>) {
    state.cancel_update();
}

/// 只重试网络层失败：签名错误重试也没用，安装/授权失败重试会重复弹窗。
fn is_retryable(err: &tauri_plugin_updater::Error) -> bool {
    matches!(
        err,
        tauri_plugin_updater::Error::Reqwest(_) | tauri_plugin_updater::Error::Network(_)
    )
}

/// 把插件错误翻译成前端要展示的类别与中文说明。
fn classify_error(err: &tauri_plugin_updater::Error) -> (&'static str, String) {
    use tauri_plugin_updater::Error;
    match err {
        Error::Minisign(_)
        | Error::Base64(_)
        | Error::SignatureUtf8(_)
        | Error::SignedVersionMismatch { .. }
        | Error::MissingSignedVersion => (
            "signature",
            format!("更新包签名校验失败，已阻止安装：{err}"),
        ),
        Error::AuthenticationFailed => (
            "auth",
            "系统授权被取消，更新未安装。Linux 可手动执行 sudo dnf upgrade 或 sudo apt install --reinstall。"
                .to_string(),
        ),
        Error::DebInstallFailed | Error::PackageInstallFailed => (
            "install",
            format!("更新包安装失败，请手动下载：{err}"),
        ),
        _ => ("install", format!("更新安装失败：{err}")),
    }
}

#[tauri::command]
pub(crate) fn restart_app(app: AppHandle) {
    app.restart()
}

/// 用系统默认浏览器打开外部链接（只允许 http/https）。
#[tauri::command]
pub(crate) fn open_external(url: String) -> Result<(), String> {
    let url = validate_external_url(&url)?;
    #[cfg(windows)]
    let mut command = {
        let mut command = std::process::Command::new("rundll32");
        command.args(["url.dll,FileProtocolHandler", url]);
        command
    };
    #[cfg(target_os = "macos")]
    let mut command = {
        let mut command = std::process::Command::new("open");
        command.arg(url);
        command
    };
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut command = {
        let mut command = std::process::Command::new("xdg-open");
        command.arg(url);
        command
    };
    command.spawn().map_err(|e| format!("打开链接失败：{e}"))?;
    Ok(())
}

/// 只接受 http(s)，并拒绝空白/控制字符：参数直接传给系统打开器，
/// 不经过 shell，但仍然要挡住 `file://` 之类本机路径。
fn validate_external_url(url: &str) -> Result<&str, String> {
    let url = url.trim();
    if url.is_empty() {
        return Err("链接为空".into());
    }
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err(format!("只允许打开 http(s) 链接：{url}"));
    }
    if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(format!("链接包含空白或控制字符：{url}"));
    }
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn external_url_accepts_https_and_http() {
        assert_eq!(
            validate_external_url(" https://example.com/a?b=1 ").unwrap(),
            "https://example.com/a?b=1"
        );
        assert!(validate_external_url("http://127.0.0.1:8080/x").is_ok());
    }

    #[test]
    fn external_url_rejects_other_schemes() {
        assert!(validate_external_url("file:///etc/passwd").is_err());
        assert!(validate_external_url("javascript:alert(1)").is_err());
        assert!(validate_external_url("pi-carry://pair?x=1").is_err());
    }

    #[test]
    fn external_url_rejects_empty_and_control_chars() {
        assert!(validate_external_url("   ").is_err());
        assert!(validate_external_url("https://example.com/a b").is_err());
        // 首尾空白会被 trim 掉，但夹在中间的控制字符必须拒绝。
        assert!(validate_external_url("https://example.com/a\nb").is_err());
    }

    #[test]
    fn release_page_follows_channel() {
        assert_eq!(release_page("stable"), STABLE_PAGE);
        assert_eq!(release_page("beta"), BETA_PAGE);
        assert_eq!(release_page("unknown"), STABLE_PAGE);
    }
}
