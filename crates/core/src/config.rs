//! 配置与机密文件。写入使用临时文件 + rename，保留未知字段。

use crate::error::{CoreError, Result};
use crate::paths::Paths;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    pub node: NodeConfig,
    pub transport: TransportConfig,
    pub approvals: ApprovalsConfig,
    pub sessions: SessionsConfig,
    pub service: ServiceConfig,
    pub ui: UiConfig,
    /// 未知字段原样保留，便于旧版本不丢新配置。
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct NodeConfig {
    pub name: String,
    /// pi 可执行文件；测试可指向 fake-pi。
    pub pi_path: String,
    pub pi_args: Vec<String>,
    /// 新会话默认 cwd；空表示当前目录。
    pub working_dir: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            name: default_hostname(),
            pi_path: std::env::var("PI_CARRY_PI").unwrap_or_else(|_| "pi".into()),
            pi_args: Vec::new(),
            working_dir: String::new(),
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct TransportConfig {
    /// n0 / default（n0 别名） / disabled / custom
    pub relay_mode: String,
    pub relay_urls: Vec<String>,
    /// None 表示自动绑定所有网卡；测试用 127.0.0.1:0。
    pub bind_addr: Option<String>,
    pub enable_mdns: bool,
    pub max_devices: usize,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for TransportConfig {
    fn default() -> Self {
        Self {
            relay_mode: "n0".into(),
            relay_urls: Vec::new(),
            bind_addr: None,
            enable_mdns: false,
            max_devices: pi_carry_protocol::MAX_DEVICES,
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct ApprovalsConfig {
    pub timeout_secs: u64,
    pub auto_allow_low_risk: bool,
    pub allow_remember: bool,
    pub max_pending: usize,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for ApprovalsConfig {
    fn default() -> Self {
        Self {
            timeout_secs: pi_carry_protocol::APPROVAL_TIMEOUT_SEC,
            auto_allow_low_risk: false,
            allow_remember: true,
            max_pending: 32,
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct SessionsConfig {
    pub dir: String,
    pub scan_limit: usize,
    pub journal_retention_days: i64,
    pub journal_max_events: u32,
    /// 默认给新设备的能力：read / control / full。
    pub default_capability: String,
    /// 允许创建会话的工作区根目录；为空时仅允许当前用户主目录。
    pub allowed_roots: Vec<String>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for SessionsConfig {
    fn default() -> Self {
        Self {
            dir: crate::paths::default_sessions_dir()
                .to_string_lossy()
                .into_owned(),
            scan_limit: 200,
            journal_retention_days: 30,
            journal_max_events: 10_000,
            default_capability: "full".into(),
            allowed_roots: Vec::new(),
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct ServiceConfig {
    pub autostart: bool,
    pub close_to_tray: bool,
    pub log_level: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for ServiceConfig {
    fn default() -> Self {
        Self {
            autostart: false,
            close_to_tray: true,
            log_level: "info".into(),
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct UiConfig {
    pub theme: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}

impl Default for UiConfig {
    fn default() -> Self {
        Self {
            theme: "auto".into(),
            extra: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Secrets {
    #[serde(default = "one")]
    pub version: u32,
    /// iroh SecretKey 的 32 字节 hex。
    pub endpoint_secret: String,
    /// Argon2 PHC 字符串；为空表示未设置密码。
    #[serde(default)]
    pub password_hash: String,
}

fn one() -> u32 {
    1
}

impl Config {
    pub fn load(paths: &Paths) -> Result<Self> {
        let path = paths.config();
        if !path.exists() {
            return Ok(Self::default());
        }
        let text = std::fs::read_to_string(&path)?;
        let cfg: Self = toml::from_str(&text)
            .map_err(|e| CoreError::Config(format!("解析 {} 失败: {e}", path.display())))?;
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn validate(&self) -> Result<()> {
        if self.node.pi_path.trim().is_empty() {
            return Err(CoreError::Config("node.piPath 不能为空".into()));
        }
        // 与 Server 的接受集合保持一致：`default` 视为 `n0` 的别名。
        match self.transport.relay_mode.as_str() {
            "n0" | "default" | "disabled" | "custom" => {}
            other => {
                return Err(CoreError::Config(format!(
                    "transport.relayMode 必须是 n0/default/disabled/custom，实际 {other}"
                )))
            }
        }
        if self.transport.relay_mode == "custom" && self.transport.relay_urls.is_empty() {
            return Err(CoreError::Config(
                "transport.relayMode=custom 时 relayUrls 不能为空".into(),
            ));
        }
        if self.transport.max_devices == 0 || self.transport.max_devices > 64 {
            return Err(CoreError::Config("maxDevices 必须在 1..=64".into()));
        }
        if !matches!(self.ui.theme.as_str(), "auto" | "dark" | "light") {
            return Err(CoreError::Config(format!(
                "ui.theme 必须是 auto/dark/light，实际 {}",
                self.ui.theme
            )));
        }
        if self.approvals.timeout_secs < 10 || self.approvals.timeout_secs > 3600 {
            return Err(CoreError::Config(
                "approvals.timeoutSecs 必须在 10..=3600".into(),
            ));
        }
        if !matches!(
            self.sessions.default_capability.as_str(),
            "read" | "control" | "full"
        ) {
            return Err(CoreError::Config(
                "sessions.defaultCapability 必须是 read/control".into(),
            ));
        }
        Ok(())
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        self.validate()?;
        let text = toml::to_string_pretty(self)
            .map_err(|e| CoreError::Config(format!("序列化配置失败: {e}")))?;
        atomic_write(&paths.config(), text.as_bytes())
    }

    pub fn session_dir(&self) -> PathBuf {
        if self.sessions.dir.trim().is_empty() {
            crate::paths::default_sessions_dir()
        } else {
            PathBuf::from(&self.sessions.dir)
        }
    }
}

impl Secrets {
    pub fn load_or_create(paths: &Paths) -> Result<Self> {
        let path = paths.secrets();
        if path.exists() {
            let text = std::fs::read_to_string(&path)?;
            let mut secrets: Self = serde_json::from_str(&text)?;
            if secrets.endpoint_secret.trim().is_empty() {
                secrets.endpoint_secret = random_secret_hex();
                secrets.save(paths)?;
            }
            return Ok(secrets);
        }
        let secrets = Self {
            version: 1,
            endpoint_secret: random_secret_hex(),
            password_hash: String::new(),
        };
        secrets.save(paths)?;
        Ok(secrets)
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        let text = serde_json::to_vec_pretty(self)?;
        atomic_write(&paths.secrets(), &text)?;
        restrict_permissions(&paths.secrets());
        Ok(())
    }

    /// 32 字节 iroh 私钥。
    pub fn endpoint_key(&self) -> Result<Vec<u8>> {
        let bytes = hex::decode(self.endpoint_secret.trim())
            .map_err(|e| CoreError::Config(format!("endpointSecret 不是合法 hex: {e}")))?;
        if bytes.len() != 32 {
            return Err(CoreError::Config(format!(
                "endpointSecret 必须是 32 字节，实际 {}",
                bytes.len()
            )));
        }
        Ok(bytes)
    }

    /// 设置访问密码（Argon2id PHC）；传空字符串表示清除。
    pub fn set_password(&mut self, paths: &Paths, password: &str) -> Result<()> {
        if password.is_empty() {
            self.password_hash.clear();
        } else {
            use argon2::password_hash::{rand_core::OsRng, PasswordHasher, SaltString};
            let salt = SaltString::generate(&mut OsRng);
            self.password_hash = argon2::Argon2::default()
                .hash_password(password.as_bytes(), &salt)
                .map_err(|e| CoreError::Config(format!("生成密码哈希失败: {e}")))?
                .to_string();
        }
        self.save(paths)
    }
}

pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension(format!(
        "{}.tmp-{}",
        path.extension().and_then(|s| s.to_str()).unwrap_or(""),
        std::process::id()
    ));
    std::fs::write(&tmp, bytes)?;
    // Windows 上 rename 覆盖已存在文件会失败，先移除目标。
    if path.exists() {
        std::fs::remove_file(path)?;
    }
    std::fs::rename(&tmp, path)?;
    Ok(())
}

fn restrict_permissions(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    let _ = path;
}

fn random_secret_hex() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

pub fn default_hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "pi-carry".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn default_roundtrip_preserves_unknown_fields() {
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        paths.ensure().unwrap();
        let mut cfg = Config::default();
        cfg.node.name = "Studio PC".into();
        cfg.extra
            .insert("futureFeature".into(), serde_json::json!(true));
        cfg.save(&paths).unwrap();
        let loaded = Config::load(&paths).unwrap();
        assert_eq!(loaded.node.name, "Studio PC");
        assert_eq!(loaded.extra["futureFeature"], true);
    }

    #[test]
    fn invalid_relay_mode_rejected() {
        let mut cfg = Config::default();
        cfg.transport.relay_mode = "sneaky".into();
        assert!(cfg.validate().is_err());
        cfg.transport.relay_mode = "custom".into();
        assert!(cfg.validate().is_err());
        cfg.transport.relay_urls = vec!["https://relay.example".into()];
        assert!(cfg.validate().is_ok());
    }

    #[test]
    fn default_relay_mode_alias_is_accepted() {
        // 与 Server 一致：`default` 是 `n0` 的别名，不应被配置校验拒绝。
        let mut cfg = Config::default();
        cfg.transport.relay_mode = "default".into();
        assert!(cfg.validate().is_ok());
    }

    #[test]
    fn ui_theme_defaults_to_auto_and_is_validated() {
        let mut cfg = Config::default();
        assert_eq!(cfg.ui.theme, "auto");
        cfg.ui.theme = "light".into();
        assert!(cfg.validate().is_ok());
        cfg.ui.theme = "sepia".into();
        assert!(cfg.validate().is_err());
    }

    #[test]
    fn secrets_are_created_once() {
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        paths.ensure().unwrap();
        let a = Secrets::load_or_create(&paths).unwrap();
        let b = Secrets::load_or_create(&paths).unwrap();
        assert_eq!(a.endpoint_secret, b.endpoint_secret);
        assert_eq!(a.endpoint_secret.len(), 64);
    }

    #[test]
    fn atomic_write_replaces_content() {
        let dir = TempDir::new().unwrap();
        let p = dir.path().join("a/b.txt");
        atomic_write(&p, b"one").unwrap();
        atomic_write(&p, b"two").unwrap();
        assert_eq!(std::fs::read_to_string(p).unwrap(), "two");
    }
}
