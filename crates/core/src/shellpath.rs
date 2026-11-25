//! GUI 启动时的 PATH 补全。
//!
//! 从 Finder / Dock / 桌面条目启动的进程不会读取 `~/.zshrc`、`~/.bash_profile`，
//! Windows 的 `HKCU\Environment` 也可能与当前进程的环境块不一致。用户用 nvm/fnm/volta
//! 装 Node、再用 npm 装 `pi` 时，直接扫进程 `PATH` 必然找不到，表现为 `PI_NOT_INSTALLED`，
//! 而同一台机器的终端里 `pi --version` 完全正常。
//!
//! 这里只做一件事：向登录环境要一份 `PATH`，与进程 `PATH` 合并（进程优先、去重、绝不删除），
//! 然后让 `resolve_pi` 使用合并后的结果。发现失败不致命，退回原 `PATH`。

use crate::paths::Paths;
use std::collections::HashSet;
use std::ffi::{OsStr, OsString};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

/// 登录 shell 可能加载重量级 profile，必须设上限：首帧配对不能被它拖住。
const DISCOVERY_TIMEOUT: Duration = Duration::from_millis(1200);
/// 轮询子进程退出的间隔。
const POLL_INTERVAL: Duration = Duration::from_millis(10);

static DISCOVERED: OnceLock<Option<OsString>> = OnceLock::new();
static EFFECTIVE: OnceLock<OsString> = OnceLock::new();
static INSTALLED: OnceLock<()> = OnceLock::new();
static ATTEMPTS: OnceLock<Vec<String>> = OnceLock::new();

/// 登录环境里的 `PATH`；拿不到返回 `None`。
pub fn discover_shell_path() -> Option<OsString> {
    discover_with(|attempt| {
        ATTEMPTS.get_or_init(|| attempt.to_vec());
    })
}

/// 与 `discover_shell_path` 相同，但把「尝试过什么」交给调用方保存，便于单测与诊断。
fn discover_with(mut record: impl FnMut(&[String])) -> Option<OsString> {
    let mut attempts = Vec::new();
    let result = discover_inner(&mut attempts);
    record(&attempts);
    result
}

#[cfg(windows)]
fn discover_inner(attempts: &mut Vec<String>) -> Option<OsString> {
    // 用户级优先：nvm/fnm/volta 把目录写在这里。
    let user = read_registry_path("HKCU\\Environment", attempts);
    let machine = read_registry_path(
        "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
        attempts,
    );
    let mut parts: Vec<OsString> = Vec::new();
    if let Some(v) = user {
        parts.push(v);
    }
    if let Some(v) = machine {
        parts.push(v);
    }
    if parts.is_empty() {
        return None;
    }
    let joined = std::env::join_paths(parts.iter().flat_map(|p| std::env::split_paths(p)))
        .unwrap_or_default();
    Some(joined)
}

#[cfg(windows)]
fn read_registry_path(key: &str, attempts: &mut Vec<String>) -> Option<OsString> {
    attempts.push(format!("reg query {key} /v Path"));
    let output = run_capture("reg", &["query", key, "/v", "Path"], DISCOVERY_TIMEOUT)?;
    let raw = parse_reg_path(&output)?;
    let expanded = expand_percent_vars(&raw);
    if expanded.trim().is_empty() {
        None
    } else {
        Some(OsString::from(expanded))
    }
}

/// 解析 `reg query` 的输出：`    Path    REG_EXPAND_SZ    C:\a;D:\b`。
///
/// 值本身可能包含空格，所以不能简单按空白切分后取固定下标。
fn parse_reg_path(output: &str) -> Option<String> {
    for line in output.lines() {
        let line = line.trim_start();
        let mut rest = match strip_prefix_ignore_case(line, "Path") {
            Some(rest) if rest.starts_with(char::is_whitespace) => rest.trim_start(),
            _ => continue,
        };
        let kind = match rest.split_whitespace().next() {
            Some(k) if k.to_ascii_uppercase().starts_with("REG_") => k,
            _ => continue,
        };
        rest = rest[kind.len()..].trim_start();
        if rest.is_empty() {
            return None;
        }
        return Some(rest.to_string());
    }
    None
}

#[cfg(windows)]
fn strip_prefix_ignore_case<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    match s.get(..prefix.len()) {
        Some(head) if head.eq_ignore_ascii_case(prefix) => s.get(prefix.len()..),
        _ => None,
    }
}

/// 展开 `%USERPROFILE%` 这类引用。未知变量保留原文，避免把路径改成空串。
#[cfg(windows)]
fn expand_percent_vars(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut rest = value;
    while let Some(start) = rest.find('%') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        match after.find('%') {
            Some(end) => {
                let name = &after[..end];
                match std::env::var(name) {
                    Ok(v) if !name.is_empty() => out.push_str(&v),
                    _ => {
                        out.push('%');
                        out.push_str(name);
                        out.push('%');
                    }
                }
                rest = &after[end + 1..];
            }
            None => {
                out.push_str(rest);
                return out;
            }
        }
    }
    out.push_str(rest);
    out
}

#[cfg(unix)]
fn discover_inner(attempts: &mut Vec<String>) -> Option<OsString> {
    // 先试用户自己的 shell（保留 nvm/fnm 的 hook），再试系统 shell 兜底。
    let mut shells: Vec<String> = Vec::new();
    if let Ok(shell) = std::env::var("SHELL") {
        if !shell.trim().is_empty() {
            shells.push(shell);
        }
    }
    for fallback in ["/bin/zsh", "/bin/bash", "/bin/sh"] {
        if !shells.iter().any(|s| s == fallback) {
            shells.push(fallback.to_string());
        }
    }
    for shell in shells {
        if !Path::new(&shell).is_file() {
            attempts.push(format!("{shell} -lc（不存在）"));
            continue;
        }
        attempts.push(format!("{shell} -lc 'printf %s \"$PATH\"'"));
        if let Some(out) = run_capture(&shell, &["-lc", "printf %s \"$PATH\""], DISCOVERY_TIMEOUT) {
            let trimmed = out.trim();
            if !trimmed.is_empty() {
                return Some(OsString::from(trimmed));
            }
        }
    }
    None
}

#[cfg(not(any(unix, windows)))]
fn discover_inner(_attempts: &mut Vec<String>) -> Option<OsString> {
    None
}

/// 带超时地执行命令并取回 stdout。
///
/// 不用 `output()`：它会一直等到子进程退出，profile 里挂一个交互式 `read` 就能把首帧卡死。
/// stdout 用独立线程读取，避免管道写满后子进程阻塞、而我们又在等它退出。
fn run_capture(program: &str, args: &[&str], timeout: Duration) -> Option<String> {
    let mut child = std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let mut stdout = child.stdout.take()?;
    let reader = std::thread::spawn(move || {
        let mut text = String::new();
        let _ = Read::read_to_string(&mut stdout, &mut text);
        text
    });
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                break;
            }
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(POLL_INTERVAL);
            }
            Err(_) => {
                let _ = child.kill();
                return None;
            }
        }
    }
    reader.join().ok()
}

/// 合并两份 `PATH`：进程里的项优先、缺失项按顺序追加、去重、不删任何原有项。
pub fn merge_path(discovered: Option<&OsStr>) -> OsString {
    let current = std::env::var_os("PATH").unwrap_or_default();
    merge_path_with(&current, discovered)
}

/// `merge_path` 的纯函数版本，便于单测注入。
pub fn merge_path_with(current: &OsStr, discovered: Option<&OsStr>) -> OsString {
    let mut seen: HashSet<String> = HashSet::new();
    let mut merged: Vec<PathBuf> = Vec::new();
    let sources = std::iter::once(current.to_os_string()).chain(discovered.map(OsString::from));
    for source in sources {
        for dir in std::env::split_paths(&source) {
            if dir.as_os_str().is_empty() {
                continue;
            }
            let key = dedup_key(&dir);
            if seen.insert(key) {
                merged.push(dir);
            }
        }
    }
    std::env::join_paths(&merged).unwrap_or_else(|_| current.to_os_string())
}

fn dedup_key(dir: &Path) -> String {
    let text = dir.to_string_lossy();
    let text = text.trim_end_matches(['/', '\\']);
    if cfg!(windows) {
        text.to_ascii_lowercase()
    } else {
        text.to_string()
    }
}

/// 进程实际使用的 `PATH`（缓存一次，避免每次 `resolve_pi` 都去跑登录 shell）。
pub fn effective_path() -> OsString {
    EFFECTIVE
        .get_or_init(|| {
            let discovered = DISCOVERED.get_or_init(discover_shell_path).clone();
            merge_path(discovered.as_deref())
        })
        .clone()
}

/// 把合并后的 `PATH` 写回进程环境。重复调用无副作用。
pub fn install() -> OsString {
    let path = effective_path();
    INSTALLED.get_or_init(|| {
        std::env::set_var("PATH", &path);
    });
    path
}

/// 预热并落一份诊断文件：`paths.logs()/pi-resolve.log`。
///
/// 只在 `Bridge::start` 调用一次。写文件失败不影响启动（日志目录不可写时
/// 配置/密钥同样写不进去，真正的问题会在别处暴露）。
pub fn prewarm(paths: &Paths) {
    let path = install();
    let discovered = DISCOVERED.get_or_init(discover_shell_path);
    let attempts = ATTEMPTS.get().cloned().unwrap_or_default();
    let line = format!(
        "{} discovered={} process={} attempts={}\n",
        crate::rfc3339(chrono::Utc::now()),
        discovered
            .as_ref()
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|| "<none>".into()),
        path.to_string_lossy(),
        if attempts.is_empty() {
            "<none>".to_string()
        } else {
            attempts.join("; ")
        }
    );
    if let Err(e) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(paths.logs().join("pi-resolve.log"))
        .and_then(|mut f| std::io::Write::write_all(&mut f, line.as_bytes()))
    {
        tracing::warn!(error = %e, "写入 pi-resolve.log 失败");
    }
    if discovered.is_none() {
        tracing::warn!(
            attempts = %attempts.join("; "),
            "无法从登录环境发现 PATH，继续使用进程 PATH"
        );
    }
}

/// 已尝试过的发现方式（诊断用）。
pub fn attempts() -> Vec<String> {
    DISCOVERED.get_or_init(discover_shell_path);
    ATTEMPTS.get().cloned().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn merge_keeps_process_order_and_appends_missing() {
        let current = std::env::join_paths(["/a", "/b"]).unwrap();
        let discovered = std::env::join_paths(["/b", "/c", "/a"]).unwrap();
        let merged = merge_path_with(&current, Some(discovered.as_os_str()));
        let dirs: Vec<String> = std::env::split_paths(&merged)
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        assert_eq!(dirs, vec!["/a", "/b", "/c"]);
    }

    #[test]
    fn merge_never_drops_process_path_when_discovered_is_empty() {
        let current = std::env::join_paths(["/only"]).unwrap();
        assert_eq!(
            merge_path_with(&current, None).to_string_lossy(),
            current.to_string_lossy()
        );
        let empty = OsString::new();
        let merged = merge_path_with(&current, Some(empty.as_os_str()));
        let dirs: Vec<String> = std::env::split_paths(&merged)
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        assert_eq!(dirs, vec!["/only"]);
    }

    #[test]
    fn merge_deduplicates_trailing_separators() {
        let current = std::env::join_paths(["/a/", "/b"]).unwrap();
        let discovered = std::env::join_paths(["/a", "/c/"]).unwrap();
        let merged = merge_path_with(&current, Some(discovered.as_os_str()));
        let dirs: Vec<String> = std::env::split_paths(&merged)
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        assert_eq!(dirs, vec!["/a/", "/b", "/c/"]);
    }

    #[test]
    fn discovered_path_finds_pi_missing_from_process_path() {
        let tmp = TempDir::new().unwrap();
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let pi = bin.join(if cfg!(windows) { "pi.cmd" } else { "pi" });
        std::fs::write(&pi, "#!/bin/sh\nexit 0\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&pi, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let current = OsString::from("/definitely-not-a-bin-dir");
        let discovered = std::env::join_paths([&bin]).unwrap();
        let merged = merge_path_with(&current, Some(discovered.as_os_str()));
        let found =
            std::env::split_paths(&merged).any(|dir| dir.join(pi.file_name().unwrap()).is_file());
        assert!(found, "合并后的 PATH 应包含假 pi 所在目录: {:?}", merged);
    }

    #[cfg(unix)]
    #[test]
    fn slow_shell_hits_timeout_without_panicking() {
        let started = Instant::now();
        assert!(run_capture("/bin/sh", &["-c", "sleep 5"], Duration::from_millis(80)).is_none());
        assert!(started.elapsed() < Duration::from_secs(2), "超时没有生效");
    }

    #[cfg(unix)]
    #[test]
    fn failing_shell_returns_none() {
        assert!(run_capture("/bin/sh", &["-c", "exit 3"], DISCOVERY_TIMEOUT).is_none());
    }

    #[cfg(windows)]
    #[test]
    fn parse_reg_path_takes_value_with_spaces() {
        let output = "HKEY_CURRENT_USER\\Environment\r\n    Path    REG_EXPAND_SZ    C:\\Program Files\\nodejs;D:\\bin\r\n";
        assert_eq!(
            parse_reg_path(output).as_deref(),
            Some("C:\\Program Files\\nodejs;D:\\bin")
        );
    }

    #[cfg(windows)]
    #[test]
    fn parse_reg_path_ignores_unrelated_values() {
        assert!(parse_reg_path("    TEMP    REG_SZ    C:\\Temp\r\n").is_none());
        assert!(parse_reg_path("garbage").is_none());
    }

    #[cfg(windows)]
    #[test]
    fn expand_percent_vars_keeps_unknown_names() {
        std::env::set_var("PI_CARRY_TEST_VAR", "X");
        assert_eq!(
            expand_percent_vars("a;%PI_CARRY_TEST_VAR%;%NOPE%"),
            "a;X;%NOPE%"
        );
        std::env::remove_var("PI_CARRY_TEST_VAR");
    }
}
