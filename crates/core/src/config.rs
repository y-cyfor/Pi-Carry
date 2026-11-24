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
    /// 强制本机确认：开启后必须先在电脑托盘点「允许」才能完成配对。
    /// 默认关（同局域网下二维码只在电脑屏幕上显示）；在共享桌面/投屏/录屏环境建议开启。
    pub require_local_confirm: bool,
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
            require_local_confirm: false,
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
    /// 允许创建会话的工作区根目录；为空时使用默认根（主目录 + 桥接进程的启动目录）。
    /// 显式配置后只认这里的路径。任一情况下都不是「全盘放行」。
    pub allowed_roots: Vec<String>,
    /// 显式允许加载**项目级可执行资源**（`.pi/extensions`、`.agents/skills`）的根目录。
    /// 空（默认）表示一律传 `--no-approve`：手机端不会因为某个仓库带了 `.pi/extensions`
    /// 就自动执行那里的代码。与 `allowedRoots` 分离：前者只需“能读文件”，后者是“能执行代码”。
    pub trusted_roots: Vec<String>,
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
            trusted_roots: Vec::new(),
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
        // max_pending = 0 会让所有审批在插入前就被拒绝，等于关闭审批能力却看不出来。
        if self.approvals.max_pending == 0 || self.approvals.max_pending > 1024 {
            return Err(CoreError::Config(
                "approvals.maxPending 必须在 1..=1024".into(),
            ));
        }
        // journal 每会话上限过小会让 seq 归零，客户端水位会把新事件当成旧事件丢掉。
        if self.sessions.journal_max_events < 100 {
            return Err(CoreError::Config(
                "sessions.journalMaxEvents 不能小于 100".into(),
            ));
        }
        if self.sessions.journal_retention_days < 1 {
            return Err(CoreError::Config(
                "sessions.journalRetentionDays 不能小于 1".into(),
            ));
        }
        if self.sessions.scan_limit == 0 || self.sessions.scan_limit > 5000 {
            return Err(CoreError::Config(
                "sessions.scanLimit 必须在 1..=5000".into(),
            ));
        }
        if !matches!(
            self.sessions.default_capability.as_str(),
            "read" | "control" | "full"
        ) {
            return Err(CoreError::Config(
                "sessions.defaultCapability 必须是 read/control/full".into(),
            ));
        }
        // allowedRoots 为空表示「默认根」（家目录 + 当前目录）；一旦显式配置，
        // 每项必须是绝对路径，否则相对路径会随进程 cwd 漂移、形同虚设。
        for root in &self.sessions.allowed_roots {
            if root.trim().is_empty() || !Path::new(root).is_absolute() {
                return Err(CoreError::Config(format!(
                    "sessions.allowedRoots 每项必须是绝对路径，实际 {root}"
                )));
            }
        }
        // trustedRoots 直接决定「是否给 pi 加 --approve」（等于信任项目里的可执行扩展），
        // 相对路径会随进程 cwd 漂移，于是同一个配置在不同启动目录下给出不同的信任结果，
        // 比 allowed_roots 更不能容忍，必须同样要求绝对路径。
        for root in &self.sessions.trusted_roots {
            if root.trim().is_empty() || !Path::new(root).is_absolute() {
                return Err(CoreError::Config(format!(
                    "sessions.trustedRoots 每项必须是绝对路径，实际 {root}"
                )));
            }
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
    // tmp 名带 pid + 时间戳：并发写入（多进程/多线程）不会互相覆盖同一个临时文件。
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let tmp = path.with_extension(format!(
        "{}.tmp-{}-{stamp}",
        path.extension().and_then(|s| s.to_str()).unwrap_or(""),
        std::process::id()
    ));
    // 先写临时文件并 fsync：否则 rename 成功而数据仍在页缓存时断电，目标文件会变成 0 字节。
    // 密码哈希、设备列表都走这个函数，丢一次就要重新配对。
    {
        use std::io::Write;
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    // 不先 remove 目标：那会留下一个「文件不存在」的窗口，进程这时崩溃就丢配置。
    // Windows 的 std::fs::rename 不能覆盖已存在文件，失败时再退回删除重试。
    if let Err(first) = std::fs::rename(&tmp, path) {
        if path.exists() && std::fs::remove_file(path).is_ok() {
            std::fs::rename(&tmp, path).map_err(|e| {
                let _ = std::fs::remove_file(&tmp);
                CoreError::from(e)
            })?;
        } else {
            let _ = std::fs::remove_file(&tmp);
            return Err(CoreError::from(first));
        }
    }
    Ok(())
}

fn restrict_permissions(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    #[cfg(windows)]
    {
        // secrets.json 里是设备密钥，Windows 默认继承父目录 ACL，其他本机用户可读。
        // 用 icacls 关掉继承并只给当前用户：失败不致命（例如受限环境无 icacls）。
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let user = std::env::var("USERNAME").unwrap_or_default();
        if !user.is_empty() {
            let mut grant = std::process::Command::new("icacls");
            grant
                .arg(path)
                .arg("/inheritance:r")
                .arg("/grant:r")
                .arg(format!("{user}:F"))
                .creation_flags(CREATE_NO_WINDOW);
            let _ = grant.output();
        }
    }
    #[cfg(not(any(unix, windows)))]
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

    /// B-4：`trustedRoots` 直接决定是否给 pi 加 `--approve`（= 信任项目里的可执行扩展），
    /// 相对路径会随进程 cwd 漂移，必须在加载时就拒绝。
    #[test]
    fn validate_rejects_relative_trusted_root() {
        let mut cfg = Config::default();
        cfg.sessions.trusted_roots = vec!["repo".into()];
        let err = cfg.validate().unwrap_err();
        assert!(
            err.to_string().contains("sessions.trustedRoots"),
            "错误文案必须指明是哪个字段: {err}"
        );

        // 空项同样拒绝：它会被当成「空路径」静默失效。
        let mut cfg = Config::default();
        cfg.sessions.trusted_roots = vec!["   ".into()];
        assert!(cfg.validate().is_err());

        // 绝对路径通过。
        let mut cfg = Config::default();
        cfg.sessions.trusted_roots = vec![std::env::temp_dir().display().to_string()];
        cfg.validate().expect("绝对路径的 trustedRoots 必须通过");
    }

    #[test]
    fn validate_rejects_degenerate_limits() {
        // 这些值共同的效果是让功能“静默关闭”或让客户端水位失效，必须在加载时就拒绝。
        let mut cfg = Config::default();
        cfg.approvals.max_pending = 0;
        assert!(cfg.validate().is_err());

        let mut cfg = Config::default();
        cfg.sessions.journal_max_events = 10;
        assert!(cfg.validate().is_err());

        let mut cfg = Config::default();
        cfg.sessions.scan_limit = 0;
        assert!(cfg.validate().is_err());

        let mut cfg = Config::default();
        cfg.sessions.journal_retention_days = 0;
        assert!(cfg.validate().is_err());

        // 默认值必须自身合法，否则用户首次启动就会失败。
        assert!(Config::default().validate().is_ok());
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
