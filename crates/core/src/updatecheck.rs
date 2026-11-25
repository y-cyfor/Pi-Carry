//! 更新检查的纯逻辑与清单读取。
//!
//! 安装器版走 `tauri-plugin-updater`（它自己解析清单、验签、安装）；这里只负责
//! 便携版需要的轻量检查：读 `latest.json` 的 `portable` 段，比较版本，给出下载页。
//! 便携版**绝不**尝试替换正在运行的 exe。

use std::collections::BTreeMap;
use std::time::Duration;

use semver::Version;
use serde::Deserialize;

use crate::error::{CoreError, Result};

/// 正式渠道清单（GitHub Releases 的 latest 资产）。
pub const STABLE_ENDPOINT: &str =
    "https://github.com/y-cyfor/Pi-Carry/releases/latest/download/latest.json";
/// Beta 渠道清单：固定 tag `beta` 下的资产。
pub const BETA_ENDPOINT: &str =
    "https://github.com/y-cyfor/Pi-Carry/releases/download/beta/latest.json";
/// 便携包在 `portable` 段里的键。
pub const PORTABLE_TARGET_WINDOWS: &str = "windows-x86_64";
/// 网络超时：清单只有几 KB，15s 足够覆盖慢网络。
pub const FETCH_TIMEOUT_SECS: u64 = 15;

pub fn endpoint_for(channel: &str) -> &'static str {
    match channel {
        "beta" => BETA_ENDPOINT,
        _ => STABLE_ENDPOINT,
    }
}

/// `latest.json` 中单个平台条目（与 tauri-plugin-updater 的字段一致）。
#[derive(Debug, Clone, Deserialize)]
pub struct PlatformEntry {
    pub signature: String,
    pub url: String,
}

/// 本项目自定的便携包条目；插件会忽略这一段。
#[derive(Debug, Clone, Deserialize)]
pub struct PortableEntry {
    pub version: String,
    pub url: String,
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Manifest {
    pub version: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub pub_date: Option<String>,
    #[serde(default)]
    pub platforms: BTreeMap<String, PlatformEntry>,
    #[serde(default)]
    pub portable: BTreeMap<String, PortableEntry>,
}

impl Manifest {
    /// 取便携包条目；没有对应键返回 `None`（视为无更新，不报错）。
    pub fn portable_entry(&self, target: &str) -> Option<&PortableEntry> {
        self.portable.get(target)
    }
}

/// 便携版的更新信息。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortableUpdate {
    pub version: String,
    pub url: String,
    pub sha256: Option<String>,
    pub notes: Option<String>,
}

/// `offered` 是否比 `current` 新。
///
/// 正式渠道忽略预发布版本（`0.4.0-beta.1` 不会推给 stable 用户）；
/// beta 渠道允许预发布。版本号非法时返回错误，让调用方记日志而不是静默当作「无更新」。
pub fn is_newer(current: &str, offered: &str, channel: &str) -> Result<bool> {
    let current = parse_version(current, "当前版本")?;
    let offered = parse_version(offered, "清单版本")?;
    if channel != "beta" && !offered.pre.is_empty() {
        return Ok(false);
    }
    Ok(offered > current)
}

/// 同一个版本只提示一次：签名失败后把版本写进 `lastFailedVersion`。
pub fn should_suppress(offered: &str, last_failed: &str) -> bool {
    let last_failed = last_failed.trim();
    if last_failed.is_empty() {
        return false;
    }
    match (Version::parse(offered.trim()), Version::parse(last_failed)) {
        (Ok(a), Ok(b)) => a == b,
        // 版本号解析失败时退回字符串比较：宁可多抑制一次，也不反复弹同一个坏包。
        _ => offered.trim() == last_failed,
    }
}

/// 便携版是否需要提示更新。清单缺 `portable` 段、或没有本平台条目时按「无更新」处理。
pub fn portable_update(
    manifest: &Manifest,
    current: &str,
    channel: &str,
    last_failed: &str,
    target: &str,
) -> Result<Option<PortableUpdate>> {
    let Some(entry) = manifest.portable_entry(target) else {
        return Ok(None);
    };
    if should_suppress(&entry.version, last_failed) {
        return Ok(None);
    }
    if !is_newer(current, &entry.version, channel)? {
        return Ok(None);
    }
    Ok(Some(PortableUpdate {
        version: entry.version.clone(),
        url: entry.url.clone(),
        sha256: entry.sha256.clone(),
        notes: entry.notes.clone().or_else(|| manifest.notes.clone()),
    }))
}

/// 拉取并解析清单。错误信息保留 URL 与状态码，便于用户反馈。
pub async fn fetch_manifest(url: &str) -> Result<Manifest> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(FETCH_TIMEOUT_SECS))
        .user_agent(format!("pi-carry/{}", crate::VERSION))
        .build()
        .map_err(|e| CoreError::Internal(format!("创建更新检查客户端失败：{e}")))?;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|e| CoreError::Internal(format!("检查更新失败（{url}）：{e}")))?;
    let status = response.status();
    if !status.is_success() {
        return Err(CoreError::Internal(format!(
            "检查更新失败（{url}）：HTTP {status}"
        )));
    }
    response
        .json::<Manifest>()
        .await
        .map_err(|e| CoreError::Internal(format!("更新清单解析失败（{url}）：{e}")))
}

fn parse_version(raw: &str, what: &str) -> Result<Version> {
    Version::parse(raw.trim())
        .map_err(|e| CoreError::InvalidArgument(format!("{what}不是合法 semver（{raw}）：{e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    const MANIFEST: &str = r#"{
        "version": "0.3.1",
        "notes": "修复若干问题",
        "pub_date": "2026-12-01T10:00:00Z",
        "platforms": {
            "windows-x86_64-nsis": { "signature": "sig", "url": "https://example.com/a.exe" },
            "linux-x86_64-deb": { "signature": "sig2", "url": "https://example.com/a.deb" }
        },
        "portable": {
            "windows-x86_64": {
                "version": "0.3.1",
                "url": "https://example.com/a.zip",
                "sha256": "abc",
                "notes": "便携版说明"
            }
        }
    }"#;

    fn manifest() -> Manifest {
        serde_json::from_str(MANIFEST).unwrap()
    }

    #[test]
    fn newer_versions_are_offered() {
        assert!(is_newer("0.2.0", "0.3.0", "stable").unwrap());
        assert!(is_newer("0.2.0", "0.2.1", "stable").unwrap());
        assert!(!is_newer("0.3.0", "0.3.0", "stable").unwrap());
        assert!(!is_newer("0.3.1", "0.3.0", "stable").unwrap());
        assert!(!is_newer("1.0.0", "0.9.9", "stable").unwrap());
    }

    #[test]
    fn stable_channel_ignores_prereleases_but_beta_accepts() {
        assert!(!is_newer("0.3.0", "0.4.0-beta.1", "stable").unwrap());
        assert!(is_newer("0.3.0", "0.4.0-beta.1", "beta").unwrap());
        // 预发布之间按 semver 排序。
        assert!(is_newer("0.4.0-beta.1", "0.4.0-beta.2", "beta").unwrap());
        assert!(!is_newer("0.4.0-beta.2", "0.4.0-beta.1", "beta").unwrap());
        // 正式版永远比同版本预发布新。
        assert!(is_newer("0.4.0-beta.2", "0.4.0", "beta").unwrap());
    }

    #[test]
    fn invalid_versions_are_errors() {
        assert!(is_newer("0.3", "0.4.0", "stable").is_err());
        assert!(is_newer("0.3.0", "latest", "stable").is_err());
        let err = is_newer("0.3.0", "latest", "stable")
            .unwrap_err()
            .to_string();
        assert!(err.contains("semver"), "{err}");
    }

    #[test]
    fn last_failed_version_suppresses_same_version_only() {
        assert!(should_suppress("0.3.1", "0.3.1"));
        assert!(!should_suppress("v0.3.1", "0.3.1"), "不要接受 v 前缀");
        assert!(!should_suppress("0.3.1", "0.3.0"));
        assert!(!should_suppress("0.3.1", ""));
        assert!(!should_suppress("0.3.1", "   "));
        assert!(should_suppress("0.3.1", " 0.3.1 "));
        // 解析不了的版本号退回字符串比较。
        assert!(should_suppress("weird", "weird"));
    }

    #[test]
    fn parses_manifest_and_picks_portable_entry() {
        let m = manifest();
        assert_eq!(m.version, "0.3.1");
        assert_eq!(m.platforms.len(), 2);
        let entry = m.portable_entry(PORTABLE_TARGET_WINDOWS).unwrap();
        assert_eq!(entry.url, "https://example.com/a.zip");
        assert_eq!(entry.sha256.as_deref(), Some("abc"));
        assert!(m.portable_entry("macos-universal").is_none());
    }

    #[test]
    fn portable_update_respects_channel_suppression_and_target() {
        let m = manifest();
        let update = portable_update(&m, "0.3.0", "stable", "", PORTABLE_TARGET_WINDOWS)
            .unwrap()
            .unwrap();
        assert_eq!(update.version, "0.3.1");
        assert_eq!(update.sha256.as_deref(), Some("abc"));
        // 条目自带 notes 时优先用它。
        assert_eq!(update.notes.as_deref(), Some("便携版说明"));

        assert!(
            portable_update(&m, "0.3.1", "stable", "", PORTABLE_TARGET_WINDOWS)
                .unwrap()
                .is_none()
        );
        assert!(
            portable_update(&m, "0.3.0", "stable", "0.3.1", PORTABLE_TARGET_WINDOWS)
                .unwrap()
                .is_none()
        );
        assert!(
            portable_update(&m, "0.3.0", "stable", "", "macos-universal")
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn portable_section_is_optional() {
        let m: Manifest = serde_json::from_str(
            r#"{"version":"1.0.0","platforms":{"windows-x86_64-nsis":{"signature":"s","url":"u"}}}"#,
        )
        .unwrap();
        assert!(
            portable_update(&m, "0.2.0", "stable", "", PORTABLE_TARGET_WINDOWS)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn portable_notes_fall_back_to_manifest_notes() {
        let m: Manifest = serde_json::from_str(
            r#"{
                "version": "1.0.0",
                "notes": "总说明",
                "portable": {"windows-x86_64": {"version": "1.0.0", "url": "u"}}
            }"#,
        )
        .unwrap();
        let update = portable_update(&m, "0.2.0", "stable", "", PORTABLE_TARGET_WINDOWS)
            .unwrap()
            .unwrap();
        assert_eq!(update.notes.as_deref(), Some("总说明"));
        assert!(update.sha256.is_none());
    }

    #[test]
    fn portable_prerelease_is_hidden_on_stable_channel() {
        let m: Manifest = serde_json::from_str(
            r#"{"version":"1.0.0","portable":{"windows-x86_64":{"version":"1.0.0-rc.1","url":"u"}}}"#,
        )
        .unwrap();
        assert!(
            portable_update(&m, "0.2.0", "stable", "", PORTABLE_TARGET_WINDOWS)
                .unwrap()
                .is_none()
        );
        assert!(
            portable_update(&m, "0.2.0", "beta", "", PORTABLE_TARGET_WINDOWS)
                .unwrap()
                .is_some()
        );
    }

    #[test]
    fn endpoints_are_stable_and_beta() {
        assert!(STABLE_ENDPOINT.ends_with("/releases/latest/download/latest.json"));
        assert!(BETA_ENDPOINT.ends_with("/releases/download/beta/latest.json"));
        assert_eq!(endpoint_for("stable"), STABLE_ENDPOINT);
        assert_eq!(endpoint_for("beta"), BETA_ENDPOINT);
        assert_eq!(endpoint_for("anything-else"), STABLE_ENDPOINT);
    }
}
