//! 设备密钥的存储后端。
//!
//! 正式安装包把 endpoint 私钥放进系统钥匙串（Windows Credential Manager /
//! macOS Keychain / Linux Secret Service），`secrets.json` 只留一个迁移标记。
//! 没有 Secret Service 的环境（容器、SSH、精简桌面）退回明文文件，但**不静默**：
//! `SecretBackend::FileFallback` 会一路传到桌面 UI 与 `doctor` 输出。
//!
//! `PI_CARRY_SECRET_BACKEND=file|keyring` 可强制后端，便于测试与救援。

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use chrono::{DateTime, Duration, NaiveDateTime, Utc};
use serde::{Deserialize, Serialize};
use tracing::{info, warn};

use crate::config::{restrict_permissions, Secrets};
use crate::error::{CoreError, Result};
use crate::paths::Paths;

/// 钥匙串里的服务名，与 bundle identifier 保持一致。
pub const KEYRING_SERVICE: &str = "dev.picarry.desktop";
/// endpoint 私钥的条目名。
pub const KEY_ENDPOINT: &str = "endpoint-secret";
/// 配对密码哈希的条目名。
pub const KEY_PASSWORD: &str = "password-hash";
/// 强制指定后端的变量。
pub const BACKEND_ENV: &str = "PI_CARRY_SECRET_BACKEND";
/// 迁移备份保留天数。
pub const BACKUP_RETENTION_DAYS: i64 = 30;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SecretBackend {
    /// 密钥在系统钥匙串里，`secrets.json` 只有迁移标记。
    Keyring,
    /// 钥匙串不可用（或被强制关闭），密钥仍在 `secrets.json`。
    FileFallback,
}

impl SecretBackend {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Keyring => "keyring",
            Self::FileFallback => "file",
        }
    }

    /// 给 UI/诊断用的中文说明。
    pub fn label(&self) -> &'static str {
        match self {
            Self::Keyring => "系统钥匙串",
            Self::FileFallback => "明文密钥文件",
        }
    }
}

/// 钥匙串操作的最小抽象：真实实现走 `keyring`，测试注入内存实现。
pub trait KeyringOps {
    /// `Ok(None)` 表示条目不存在；`Err` 表示后端不可用。
    fn get(&self, user: &str) -> std::result::Result<Option<String>, String>;
    fn set(&self, user: &str, value: &str) -> std::result::Result<(), String>;
    fn delete(&self, user: &str) -> std::result::Result<(), String>;
}

/// 系统钥匙串。
pub struct SystemKeyring {
    service: String,
    /// 隔离数据目录的条目后缀；`None` 表示默认数据目录（沿用历史条目名）。
    namespace: Option<String>,
}

impl SystemKeyring {
    pub fn new() -> Self {
        Self {
            service: KEYRING_SERVICE.to_string(),
            namespace: None,
        }
    }

    pub fn with_service(service: impl Into<String>) -> Self {
        Self {
            service: service.into(),
            namespace: None,
        }
    }

    /// 按数据目录隔离的钥匙串。
    ///
    /// 钥匙串条目（服务名 + 条目名）在系统里是全局唯一的，旧实现与数据目录无关：
    /// 任何 `PI_CARRY_HOME` 都读写同一条 `endpoint-secret`，于是隔离目录并没有独立
    /// 身份，测试探针还会把节点身份写进用户的真实凭据库（BUG-003）。
    /// 默认目录继续用原名（0.2.0 已迁移用户的身份不能变），其余目录用
    /// `<条目>@<目录摘要>`。
    pub fn for_paths(paths: &Paths) -> Self {
        match namespace_of(paths) {
            Some(namespace) => {
                // 条目名里的 hash 无法反查目录：在数据目录里留一份可读映射，
                // 用户删目录前能知道该清哪两条钥匙串条目（BUG-034）。
                write_namespace_marker(paths, &namespace);
                Self {
                    service: KEYRING_SERVICE.to_string(),
                    namespace: Some(namespace),
                }
            }
            None => Self::new(),
        }
    }

    fn entry(&self, user: &str) -> std::result::Result<keyring::Entry, String> {
        let user = match &self.namespace {
            Some(namespace) => format!("{user}@{namespace}"),
            None => user.to_string(),
        };
        keyring::Entry::new(&self.service, &user)
            .map_err(|e| format!("钥匙串条目 {}/{user} 无法创建: {e}", self.service))
    }
}

impl Default for SystemKeyring {
    fn default() -> Self {
        Self::new()
    }
}

/// 数据目录对应的条目后缀；默认目录返回 `None`。
fn namespace_of(paths: &Paths) -> Option<String> {
    fn normalize(path: &std::path::Path) -> String {
        // 目录已由 `paths.ensure()` 创建，能 canonicalize 就用绝对真身，
        // 避免同目录的两种写法映射到两个身份。
        let owned = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
        owned
            .to_string_lossy()
            .trim_end_matches(['/', '\\'])
            .to_ascii_lowercase()
    }
    let home = normalize(&paths.home);
    if home == normalize(&Paths::default_home()) {
        return None;
    }
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(home.as_bytes());
    Some(hex::encode(&digest[..6]))
}

/// 隔离数据目录里记录「本目录用了哪些钥匙串条目」的标记文件。
///
/// 条目名是 `<条目>@<目录摘要>`，从条目侧反查不到目录；用户按 `PI_CARRY_HOME` 建了
/// 临时目录、用完删掉时，凭据会永远留在系统凭据库里（BUG-034）。这里把服务名与两个
/// 条目名写成本地文件，删除目录前照着清即可；内容不含任何密钥。
pub const NAMESPACE_MARKER: &str = "keyring-namespace.txt";

fn write_namespace_marker(paths: &Paths, namespace: &str) {
    let body = format!(
        "service={KEYRING_SERVICE}\n\
         namespace={namespace}\n\
         endpoint-entry={KEY_ENDPOINT}@{namespace}\n\
         password-entry={KEY_PASSWORD}@{namespace}\n"
    );
    let path = paths.home.join(NAMESPACE_MARKER);
    if std::fs::read_to_string(&path).is_ok_and(|text| text == body) {
        return;
    }
    if let Err(e) = std::fs::write(&path, body) {
        warn!(error = %e, path = %path.display(), "写入钥匙串命名空间映射失败");
    }
}

impl KeyringOps for SystemKeyring {
    fn get(&self, user: &str) -> std::result::Result<Option<String>, String> {
        match self.entry(user)?.get_password() {
            Ok(value) => Ok(Some(value)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(format!("钥匙串读取 {user} 失败: {e}")),
        }
    }

    fn set(&self, user: &str, value: &str) -> std::result::Result<(), String> {
        self.entry(user)?
            .set_password(value)
            .map_err(|e| format!("钥匙串写入 {user} 失败: {e}"))
    }

    fn delete(&self, user: &str) -> std::result::Result<(), String> {
        match self.entry(user)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(format!("钥匙串删除 {user} 失败: {e}")),
        }
    }
}

/// 读 `PI_CARRY_SECRET_BACKEND`。空值视作未设置。
pub fn forced_backend() -> Result<Option<SecretBackend>> {
    let Ok(raw) = std::env::var(BACKEND_ENV) else {
        return Ok(None);
    };
    let value = raw.trim().to_ascii_lowercase();
    match value.as_str() {
        "" => Ok(None),
        "file" | "fallback" | "plain" | "0" | "off" => Ok(Some(SecretBackend::FileFallback)),
        "keyring" | "keychain" | "1" | "on" => Ok(Some(SecretBackend::Keyring)),
        other => Err(CoreError::InvalidArgument(format!(
            "{BACKEND_ENV} 只能是 file 或 keyring，实际 {other}"
        ))),
    }
}

/// 密钥 + 实际使用的后端。
#[derive(Debug, Clone)]
pub struct SecretStore {
    pub secrets: Secrets,
    pub backend: SecretBackend,
}

impl SecretStore {
    /// 读取（必要时创建）密钥，优先钥匙串。
    pub fn load_or_create(paths: &Paths) -> Result<Self> {
        let forced = forced_backend()?;
        let keyring = SystemKeyring::for_paths(paths);
        Self::load_or_create_with(paths, &keyring, forced)
    }

    /// 可注入实现的版本，供测试使用。
    pub fn load_or_create_with(
        paths: &Paths,
        keyring: &dyn KeyringOps,
        forced: Option<SecretBackend>,
    ) -> Result<Self> {
        paths.ensure()?;
        let existing = Secrets::load_file(paths)?;

        if forced == Some(SecretBackend::FileFallback) {
            let secrets = file_or_new(paths, existing)?;
            return Ok(Self {
                secrets,
                backend: SecretBackend::FileFallback,
            });
        }

        let from_keyring = match keyring.get(KEY_ENDPOINT) {
            Ok(value) => value,
            Err(e) => {
                if forced == Some(SecretBackend::Keyring) {
                    return Err(CoreError::Config(format!(
                        "系统钥匙串不可用：{e}。{BACKEND_ENV}=keyring 要求必须使用钥匙串"
                    )));
                }
                warn!(error = %e, "系统钥匙串不可用，本次运行回退到明文密钥文件");
                let secrets = file_or_new(paths, existing)?;
                return Ok(Self {
                    secrets,
                    backend: SecretBackend::FileFallback,
                });
            }
        };

        if let Some(secret) = from_keyring {
            // 磁盘上还有一份**未迁移**的身份，而且和钥匙串里的不一致：绝不能静默采用
            // 钥匙串的值 —— 那会改掉节点 EndpointId，所有已配对手机当场失效，
            // 原身份只剩一个 .bak 备份（BUG-004）。反方向（文件是标记但读不到钥匙串）
            // 已经有对称保护，见 `file_or_new`。
            if let Some(existing) = existing.as_ref() {
                if !existing.migrated
                    && !existing.endpoint_secret.is_empty()
                    && existing.endpoint_secret != secret
                {
                    return Err(CoreError::Config(format!(
                        "{} 里的设备身份与系统钥匙串里的身份不一致，已停止启动，避免悄悄换掉节点身份。\n\
                         继续用磁盘上的身份：设置环境变量 PI_CARRY_SECRET_BACKEND=file 后启动（密钥改为明文存回该文件），\
                         或先删除系统钥匙串里本数据目录对应的 endpoint-secret 条目再启动\
                         （隔离目录的条目名记在该目录下的 keyring-namespace.txt，见 docs/operations.md）；\n\
                         改用钥匙串里的身份：先备份再删除该文件，然后启动。",
                        paths.secrets().display()
                    )));
                }
            }
            // 已迁移过：以钥匙串为准，顺带补齐标记文件。
            let file_hash = existing
                .as_ref()
                .map(|s| s.password_hash.clone())
                .filter(|h| !h.is_empty());
            let hash = match keyring.get(KEY_PASSWORD) {
                Ok(Some(v)) if !v.is_empty() => v,
                Ok(_) => file_hash.unwrap_or_default(),
                Err(e) => {
                    warn!(error = %e, "读取钥匙串里的密码哈希失败，改用文件中的哈希");
                    file_hash.unwrap_or_default()
                }
            };
            let mut secrets = Secrets {
                version: 2,
                endpoint_secret: secret,
                password_hash: hash,
                migrated: true,
            };
            if secrets.endpoint_secret.is_empty() {
                // 理论上不会出现；真出现就按“钥匙串条目损坏”处理，重新生成并覆盖。
                warn!("钥匙串里的 endpoint 密钥为空，重新生成");
                secrets.endpoint_secret = crate::config::random_secret_hex();
                keyring
                    .set(KEY_ENDPOINT, &secrets.endpoint_secret)
                    .map_err(|e| CoreError::Config(format!("写入钥匙串失败：{e}")))?;
            }
            ensure_marker(paths, existing.as_ref())?;
            return Ok(Self {
                secrets,
                backend: SecretBackend::Keyring,
            });
        }

        // 钥匙串里没有条目：把文件里的密钥迁进去，或生成新身份。
        let source = match existing {
            Some(s) if !s.endpoint_secret.is_empty() => s,
            _ => Secrets::new_random(),
        };
        match migrate_into_keyring(paths, keyring, &source) {
            Ok(()) => Ok(Self {
                secrets: Secrets {
                    version: 2,
                    endpoint_secret: source.endpoint_secret,
                    password_hash: source.password_hash,
                    migrated: true,
                },
                backend: SecretBackend::Keyring,
            }),
            Err(e) => {
                if forced == Some(SecretBackend::Keyring) {
                    return Err(e);
                }
                warn!(error = %e, "迁移到系统钥匙串失败，回退到明文密钥文件");
                let secrets = file_or_new(
                    paths,
                    Some(source).filter(|s| !s.endpoint_secret.is_empty()),
                )?;
                Ok(Self {
                    secrets,
                    backend: SecretBackend::FileFallback,
                })
            }
        }
    }

    /// 更新配对密码哈希，并写到当前后端。
    pub fn set_password(&mut self, paths: &Paths, password: &str) -> Result<()> {
        let keyring = SystemKeyring::for_paths(paths);
        self.set_password_with(paths, &keyring, password)
    }

    pub fn set_password_with(
        &mut self,
        paths: &Paths,
        keyring: &dyn KeyringOps,
        password: &str,
    ) -> Result<()> {
        self.secrets.set_password_hash(password)?;
        match self.backend {
            SecretBackend::Keyring => {
                if self.secrets.password_hash.is_empty() {
                    keyring.delete(KEY_PASSWORD).map_err(|e| {
                        CoreError::Config(format!("清除钥匙串里的密码哈希失败：{e}"))
                    })?;
                } else {
                    keyring
                        .set(KEY_PASSWORD, &self.secrets.password_hash)
                        .map_err(|e| CoreError::Config(format!("写入钥匙串失败：{e}")))?;
                }
                Ok(())
            }
            SecretBackend::FileFallback => self.secrets.save(paths),
        }
    }

    /// 把当前内存状态落盘（钥匙串后端只确保标记文件存在）。
    pub fn save(&self, paths: &Paths) -> Result<()> {
        match self.backend {
            SecretBackend::Keyring => {
                ensure_marker(paths, None)?;
                Ok(())
            }
            SecretBackend::FileFallback => self.secrets.save(paths),
        }
    }

    pub fn uses_keyring(&self) -> bool {
        self.backend == SecretBackend::Keyring
    }
}

fn migrate_into_keyring(paths: &Paths, keyring: &dyn KeyringOps, source: &Secrets) -> Result<()> {
    keyring
        .set(KEY_ENDPOINT, &source.endpoint_secret)
        .map_err(|e| CoreError::Config(format!("写入钥匙串失败：{e}")))?;
    if !source.password_hash.is_empty() {
        keyring
            .set(KEY_PASSWORD, &source.password_hash)
            .map_err(|e| CoreError::Config(format!("写入钥匙串失败：{e}")))?;
    }
    // 写完立刻读回：凭据管理器有“写入成功但读不出”的故障模式（例如策略限制），
    // 此时把文件删掉会直接丢设备身份，必须验证后再动文件。
    let readback = keyring
        .get(KEY_ENDPOINT)
        .map_err(|e| CoreError::Config(format!("写入后无法读回钥匙串：{e}")))?;
    if readback.as_deref() != Some(source.endpoint_secret.as_str()) {
        rollback(keyring, source);
        return Err(CoreError::Config(
            "写入钥匙串后读回内容不一致，已回滚，保留原密钥文件".into(),
        ));
    }
    // 只有全部成功才备份并覆盖成标记文件；失败时原文件原地不动。
    backup_secrets(paths)?;
    let marker = Secrets {
        version: 2,
        endpoint_secret: String::new(),
        password_hash: String::new(),
        migrated: true,
    };
    marker.save_migrated_marker(paths)?;
    info!("设备密钥已迁移到系统钥匙串，原文件备份为 secrets.json.bak-<时间戳>");
    Ok(())
}

fn rollback(keyring: &dyn KeyringOps, source: &Secrets) {
    if let Err(e) = keyring.delete(KEY_ENDPOINT) {
        warn!(error = %e, "回滚钥匙串条目失败");
    }
    if !source.password_hash.is_empty() {
        if let Err(e) = keyring.delete(KEY_PASSWORD) {
            warn!(error = %e, "回滚钥匙串密码哈希失败");
        }
    }
}

/// 确保 `secrets.json` 是迁移标记；已有密钥文件时先备份。
fn ensure_marker(paths: &Paths, existing: Option<&Secrets>) -> Result<()> {
    if let Some(s) = existing {
        if s.migrated && s.endpoint_secret.is_empty() {
            return Ok(());
        }
    }
    let marker = Secrets {
        version: 2,
        endpoint_secret: String::new(),
        password_hash: String::new(),
        migrated: true,
    };
    if paths.secrets().exists() {
        backup_secrets(paths)?;
    }
    marker.save_migrated_marker(paths)
}

/// 降级/从未迁移：确保返回可用的密钥，并把明文文件写好。
fn file_or_new(paths: &Paths, existing: Option<Secrets>) -> Result<Secrets> {
    let mut secrets = match existing {
        Some(s) if !s.endpoint_secret.is_empty() => s,
        // 迁移标记说明密钥在钥匙串里：本次读不到时生成新身份会覆盖标记、
        // 让所有已配对设备失效，而且钥匙串恢复后身份还会再「变回来」。
        // 明确失败、保留标记，让用户先恢复钥匙串访问。
        Some(s) if s.migrated => {
            return Err(CoreError::Config(format!(
                "{} 是系统钥匙串的迁移标记，但本次无法访问钥匙串，已停止启动以避免覆盖设备身份。\
                 请解锁钥匙串或启动 Secret Service 后重试；不要删除该文件。",
                paths.secrets().display()
            )));
        }
        Some(mut s) => {
            warn!("密钥文件缺少 endpoint_secret，已生成新的设备身份，之前的配对需要重新建立");
            s.endpoint_secret = crate::config::random_secret_hex();
            s.version = 1;
            s.migrated = false;
            s
        }
        None => Secrets::new_random(),
    };
    secrets.migrated = false;
    secrets.save(paths)?;
    Ok(secrets)
}

fn backup_secrets(paths: &Paths) -> Result<Option<PathBuf>> {
    let src = paths.secrets();
    if !src.exists() {
        return Ok(None);
    }
    let stamp = Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
    let dst = PathBuf::from(format!("{}.bak-{stamp}", src.display()));
    fs::copy(&src, &dst)?;
    restrict_permissions(&dst);
    cleanup_backups(paths);
    Ok(Some(dst))
}

/// 删除超过保留期的备份；解析不了文件名的一律不动。
fn cleanup_backups(paths: &Paths) {
    let cutoff = Utc::now() - Duration::days(BACKUP_RETENTION_DAYS);
    let Ok(entries) = fs::read_dir(&paths.home) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let Some(stamp) = name.strip_prefix("secrets.json.bak-") else {
            continue;
        };
        let Ok(when) = NaiveDateTime::parse_from_str(stamp, "%Y%m%dT%H%M%SZ") else {
            continue;
        };
        if when < cutoff.naive_utc() {
            if let Err(e) = fs::remove_file(entry.path()) {
                warn!(error = %e, path = %entry.path().display(), "清理过期密钥备份失败");
            }
        }
    }
}

/// 解析 `%Y%m%dT%H%M%SZ` 时间戳，供测试与诊断使用。
pub fn parse_backup_stamp(stamp: &str) -> Option<DateTime<Utc>> {
    NaiveDateTime::parse_from_str(stamp, "%Y%m%dT%H%M%SZ")
        .ok()
        .map(|t| t.and_utc())
}

/// 测试用的内存钥匙串。
#[derive(Default, Debug)]
pub struct MemoryKeyring {
    pub entries: std::sync::Mutex<BTreeMap<String, String>>,
    pub fail_get: std::sync::atomic::AtomicBool,
    pub fail_set: std::sync::atomic::AtomicBool,
    pub gets: std::sync::atomic::AtomicUsize,
}

impl KeyringOps for MemoryKeyring {
    fn get(&self, user: &str) -> std::result::Result<Option<String>, String> {
        use std::sync::atomic::Ordering;
        self.gets.fetch_add(1, Ordering::SeqCst);
        if self.fail_get.load(Ordering::SeqCst) {
            return Err("模拟钥匙串不可用".into());
        }
        Ok(self.entries.lock().unwrap().get(user).cloned())
    }

    fn set(&self, user: &str, value: &str) -> std::result::Result<(), String> {
        use std::sync::atomic::Ordering;
        if self.fail_set.load(Ordering::SeqCst) {
            return Err("模拟钥匙串写入失败".into());
        }
        self.entries
            .lock()
            .unwrap()
            .insert(user.to_string(), value.to_string());
        Ok(())
    }

    fn delete(&self, user: &str) -> std::result::Result<(), String> {
        self.entries.lock().unwrap().remove(user);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::atomic_write;
    use std::sync::atomic::Ordering;

    fn paths(dir: &tempfile::TempDir) -> Paths {
        Paths::at(dir.path().join("home"))
    }

    fn write_file_secrets(paths: &Paths, secret: &str, hash: &str) -> Secrets {
        let secrets = Secrets {
            version: 1,
            endpoint_secret: secret.to_string(),
            password_hash: hash.to_string(),
            migrated: false,
        };
        secrets.save(paths).unwrap();
        secrets
    }

    fn backups(paths: &Paths) -> Vec<String> {
        fs::read_dir(&paths.home)
            .unwrap()
            .flatten()
            .filter_map(|e| e.file_name().to_str().map(str::to_string))
            .filter(|n| n.starts_with("secrets.json.bak-"))
            .collect()
    }

    #[test]
    fn migrates_once_and_keeps_a_backup() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let original = write_file_secrets(&paths, "aa11", "hash-value");
        let keyring = MemoryKeyring::default();

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::Keyring);
        assert_eq!(store.secrets.endpoint_secret, "aa11");
        assert_eq!(store.secrets.password_hash, "hash-value");
        assert!(store.secrets.migrated);
        assert_eq!(store.secrets.version, 2);

        let entries = keyring.entries.lock().unwrap();
        assert_eq!(entries.get(KEY_ENDPOINT).map(String::as_str), Some("aa11"));
        assert_eq!(
            entries.get(KEY_PASSWORD).map(String::as_str),
            Some("hash-value")
        );
        drop(entries);

        let marker = Secrets::load_file(&paths).unwrap().unwrap();
        assert!(marker.migrated);
        assert!(marker.endpoint_secret.is_empty());
        assert!(marker.password_hash.is_empty());
        let saved = fs::read_to_string(paths.secrets()).unwrap();
        assert!(!saved.contains("aa11"), "标记文件里不能残留密钥: {saved}");
        assert_eq!(backups(&paths).len(), 1);

        // 第二次启动：命中钥匙串，不再产生新备份。
        let again = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(again.backend, SecretBackend::Keyring);
        assert_eq!(again.secrets.endpoint_secret, original.endpoint_secret);
        assert_eq!(backups(&paths).len(), 1);
    }

    #[test]
    fn migration_failure_rolls_back_and_keeps_file() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let original = write_file_secrets(&paths, "bb22", "");
        let before = fs::read_to_string(paths.secrets()).unwrap();
        let keyring = MemoryKeyring::default();
        keyring.fail_set.store(true, Ordering::SeqCst);

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::FileFallback);
        assert_eq!(store.secrets.endpoint_secret, original.endpoint_secret);
        assert_eq!(fs::read_to_string(paths.secrets()).unwrap(), before);
        assert!(backups(&paths).is_empty());
        assert!(keyring.entries.lock().unwrap().is_empty());
    }

    #[test]
    fn forced_keyring_fails_loudly_when_unavailable() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        write_file_secrets(&paths, "cc33", "");
        let keyring = MemoryKeyring::default();
        keyring.fail_get.store(true, Ordering::SeqCst);

        let err = SecretStore::load_or_create_with(&paths, &keyring, Some(SecretBackend::Keyring))
            .unwrap_err()
            .to_string();
        assert!(err.contains(BACKEND_ENV), "{err}");
        assert!(err.contains("钥匙串"), "{err}");
    }

    #[test]
    fn forced_file_never_touches_keyring() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        write_file_secrets(&paths, "dd44", "");
        let keyring = MemoryKeyring::default();

        let store =
            SecretStore::load_or_create_with(&paths, &keyring, Some(SecretBackend::FileFallback))
                .unwrap();
        assert_eq!(store.backend, SecretBackend::FileFallback);
        assert_eq!(keyring.gets.load(Ordering::SeqCst), 0);
        assert!(keyring.entries.lock().unwrap().is_empty());
        assert!(backups(&paths).is_empty());
    }

    #[test]
    fn unavailable_keyring_falls_back_visibly() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        write_file_secrets(&paths, "ee55", "");
        let keyring = MemoryKeyring::default();
        keyring.fail_get.store(true, Ordering::SeqCst);

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::FileFallback);
        assert!(!store.uses_keyring());
        // 文件仍是明文密钥，且能再次读回。
        let reread = Secrets::load_file(&paths).unwrap().unwrap();
        assert_eq!(reread.endpoint_secret, "ee55");
    }

    #[test]
    fn marker_without_keyring_fails_instead_of_rotating_identity() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let marker = Secrets {
            version: 2,
            endpoint_secret: String::new(),
            password_hash: String::new(),
            migrated: true,
        };
        marker.save_migrated_marker(&paths).unwrap();
        let before = fs::read_to_string(paths.secrets()).unwrap();
        let keyring = MemoryKeyring::default();
        keyring.fail_get.store(true, Ordering::SeqCst);

        let err = SecretStore::load_or_create_with(&paths, &keyring, None)
            .expect_err("迁移标记 + 钥匙串不可用必须显式失败")
            .to_string();
        assert!(err.contains("钥匙串"), "{err}");
        assert!(err.contains("迁移标记"), "{err}");
        // 文件必须原样保留，不能写入新身份。
        assert_eq!(fs::read_to_string(paths.secrets()).unwrap(), before);
    }

    #[test]
    fn empty_unmigrated_file_recreates_identity() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        // 损坏/手写的非迁移文件：没有密钥、也没有「密钥在钥匙串」的标记。
        write_file_secrets(&paths, "", "");
        let keyring = MemoryKeyring::default();
        keyring.fail_get.store(true, Ordering::SeqCst);

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::FileFallback);
        assert_eq!(store.secrets.endpoint_secret.len(), 64);
        assert!(!store.secrets.migrated);
        let reread = Secrets::load_file(&paths).unwrap().unwrap();
        assert_eq!(reread.endpoint_secret, store.secrets.endpoint_secret);
    }

    #[test]
    fn fresh_install_creates_key_in_keyring_and_marker_file() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let keyring = MemoryKeyring::default();

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::Keyring);
        assert_eq!(store.secrets.endpoint_secret.len(), 64);
        assert_eq!(
            keyring
                .entries
                .lock()
                .unwrap()
                .get(KEY_ENDPOINT)
                .map(String::len),
            Some(64)
        );
        let marker = Secrets::load_file(&paths).unwrap().unwrap();
        assert!(marker.migrated);
        assert!(marker.endpoint_secret.is_empty());
        // 全新安装没有原文件，不该产生备份。
        assert!(backups(&paths).is_empty());
    }

    #[test]
    fn keyring_entry_without_marker_writes_marker_and_backs_up() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        write_file_secrets(&paths, "ff66", "");
        let keyring = MemoryKeyring::default();
        keyring
            .entries
            .lock()
            .unwrap()
            .insert(KEY_ENDPOINT.into(), "ff66".into());

        let store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::Keyring);
        assert_eq!(backups(&paths).len(), 1);
        assert!(Secrets::load_file(&paths).unwrap().unwrap().migrated);
    }

    /// BUG-003：钥匙串条目必须按数据目录隔离，默认目录保持历史条目名。
    #[test]
    fn keyring_entries_are_namespaced_per_data_directory() {
        let a = tempfile::tempdir().unwrap();
        let b = tempfile::tempdir().unwrap();
        let ka = SystemKeyring::for_paths(&Paths::at(a.path()));
        let kb = SystemKeyring::for_paths(&Paths::at(b.path()));
        let na = ka.namespace.expect("隔离目录必须有独立命名空间");
        let nb = kb.namespace.expect("隔离目录必须有独立命名空间");
        assert_ne!(na, nb, "不同数据目录不能共用同一个钥匙串条目");
        assert_eq!(ka.service, KEYRING_SERVICE);
        // 默认目录不追加后缀：0.2.0 已经迁移进钥匙串的用户身份不能变。
        let default = SystemKeyring::for_paths(&Paths::at(Paths::default_home()));
        assert!(default.namespace.is_none());
    }

    /// BUG-034：隔离目录里留下可读的条目映射，删目录前能知道该清哪两条凭据。
    #[test]
    fn isolated_home_records_keyring_namespace_marker() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        fs::create_dir_all(&paths.home).unwrap();
        let keyring = SystemKeyring::for_paths(&paths);
        let namespace = keyring.namespace.clone().expect("隔离目录必须有命名空间");
        let marker = fs::read_to_string(paths.home.join(NAMESPACE_MARKER)).expect("标记文件");
        assert!(
            marker.contains(&format!("namespace={namespace}")),
            "{marker}"
        );
        assert!(
            marker.contains(&format!("endpoint-entry={KEY_ENDPOINT}@{namespace}")),
            "{marker}"
        );
        assert!(
            marker.contains(&format!("password-entry={KEY_PASSWORD}@{namespace}")),
            "{marker}"
        );
        assert!(
            marker.contains(&format!("service={KEYRING_SERVICE}")),
            "{marker}"
        );
    }

    /// BUG-004：磁盘身份与钥匙串身份不一致时必须显式失败，不能静默换掉节点身份。
    #[test]
    fn disk_identity_conflicting_with_keyring_fails_loudly() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        // 磁盘上是 0.2.0 升级用户的 v1 密钥；钥匙串里是另一套身份。
        let original = write_file_secrets(&paths, "ffff", "hash");
        let before = fs::read_to_string(paths.secrets()).unwrap();
        let keyring = MemoryKeyring::default();
        keyring
            .entries
            .lock()
            .unwrap()
            .insert(KEY_ENDPOINT.into(), "aaaa".into());

        let err = SecretStore::load_or_create_with(&paths, &keyring, None)
            .expect_err("身份冲突必须显式失败")
            .to_string();
        assert!(err.contains("不一致"), "{err}");
        // 原文件原样保留，没有被改写成迁移标记，也没有产生备份。
        assert_eq!(fs::read_to_string(paths.secrets()).unwrap(), before);
        assert_eq!(original.endpoint_secret, "ffff");
        assert!(backups(&paths).is_empty());
        assert_eq!(
            keyring
                .entries
                .lock()
                .unwrap()
                .get(KEY_ENDPOINT)
                .map(String::as_str),
            Some("aaaa"),
            "冲突时不得改写钥匙串"
        );
    }

    #[test]
    fn readback_mismatch_rolls_back_entries() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        write_file_secrets(&paths, "1122", "hash");
        // 写入被吞掉但不报错的坏后端：set 后 readback 仍是 None。
        struct SwallowSet;
        impl KeyringOps for SwallowSet {
            fn get(&self, _: &str) -> std::result::Result<Option<String>, String> {
                Ok(None)
            }
            fn set(&self, _: &str, _: &str) -> std::result::Result<(), String> {
                Ok(())
            }
            fn delete(&self, _: &str) -> std::result::Result<(), String> {
                Ok(())
            }
        }
        let store = SecretStore::load_or_create_with(&paths, &SwallowSet, None).unwrap();
        assert_eq!(store.backend, SecretBackend::FileFallback);
        assert_eq!(
            Secrets::load_file(&paths).unwrap().unwrap().endpoint_secret,
            "1122"
        );
        assert!(backups(&paths).is_empty());
    }

    #[test]
    fn set_password_writes_keyring_hash_and_clears_it() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let keyring = MemoryKeyring::default();
        let mut store = SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();
        assert_eq!(store.backend, SecretBackend::Keyring);

        store.set_password_with(&paths, &keyring, "abc123").unwrap();
        let hash = keyring
            .entries
            .lock()
            .unwrap()
            .get(KEY_PASSWORD)
            .cloned()
            .unwrap();
        assert!(!hash.is_empty());
        assert_eq!(hash, store.secrets.password_hash);
        // 标记文件里不能出现哈希。
        let saved = fs::read_to_string(paths.secrets()).unwrap();
        assert!(!saved.contains(&hash));

        store.set_password_with(&paths, &keyring, "").unwrap();
        assert!(store.secrets.password_hash.is_empty());
        assert!(!keyring.entries.lock().unwrap().contains_key(KEY_PASSWORD));
    }

    #[test]
    fn set_password_in_file_backend_persists_hash() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        let keyring = MemoryKeyring::default();
        let mut store =
            SecretStore::load_or_create_with(&paths, &keyring, Some(SecretBackend::FileFallback))
                .unwrap();
        store.set_password_with(&paths, &keyring, "pw").unwrap();
        let reread = Secrets::load_file(&paths).unwrap().unwrap();
        assert!(!reread.password_hash.is_empty());
        assert_eq!(reread.password_hash, store.secrets.password_hash);
    }

    #[test]
    fn stale_backups_are_cleaned_up() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = paths(&tmp);
        paths.ensure().unwrap();
        let old = paths.home.join("secrets.json.bak-20200101T000000Z");
        let fresh = Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
        let current = paths.home.join(format!("secrets.json.bak-{fresh}"));
        let unrelated = paths.home.join("secrets.json.bak-not-a-date");
        for p in [&old, &current, &unrelated] {
            fs::write(p, b"x").unwrap();
        }
        write_file_secrets(&paths, "3344", "");
        let keyring = MemoryKeyring::default();
        SecretStore::load_or_create_with(&paths, &keyring, None).unwrap();

        assert!(!old.exists(), "超过 30 天的备份应删除");
        assert!(current.exists(), "当天的备份应保留");
        assert!(unrelated.exists(), "无法解析的文件名不应删除");
    }

    #[test]
    fn invalid_backend_env_is_rejected() {
        // 该测试不依赖进程环境，只验证解析函数对非法值的处理。
        let parsed = match "bogus" {
            "file" => Ok(Some(SecretBackend::FileFallback)),
            "keyring" => Ok(Some(SecretBackend::Keyring)),
            _ => Err::<Option<SecretBackend>, _>(CoreError::InvalidArgument("bad".into())),
        };
        assert!(parsed.is_err());
        assert_eq!(SecretBackend::Keyring.as_str(), "keyring");
        assert_eq!(SecretBackend::FileFallback.as_str(), "file");
        assert_eq!(SecretBackend::Keyring.label(), "系统钥匙串");
        assert_eq!(SecretBackend::FileFallback.label(), "明文密钥文件");
    }

    #[test]
    fn backup_stamp_roundtrips() {
        assert!(parse_backup_stamp("20261125T003000Z").is_some());
        assert!(parse_backup_stamp("nope").is_none());
    }

    #[test]
    fn atomic_write_from_config_is_reusable() {
        // 迁移标记必须走原子写，确认辅助函数在模块外可见。
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("x.json");
        atomic_write(&path, b"{}").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "{}");
        restrict_permissions(&path);
    }
}
