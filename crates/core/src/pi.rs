//! pi RPC 子进程客户端。
//!
//! pi 的 stdout 是严格 JSONL；这里不用 `lines()` 之外的分帧技巧，
//! 也不把 stderr 混进协议流。请求按 id 关联，事件通过 broadcast 分发。

use crate::error::{CoreError, Result};
use pi_carry_protocol::MAX_JSONL_LINE;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::{broadcast, oneshot};

/// pi stderr 单行读取上限；超过部分丢弃，避免异常输出撑爆内存。
const STDERR_LINE_LIMIT: usize = 8 * 1024;

#[derive(Debug, Clone)]
pub struct PiEvent {
    pub kind: String,
    pub value: Value,
}

#[derive(Debug, Clone)]
pub struct PiExit {
    pub code: Option<i32>,
    pub reason: String,
}

struct Pending {
    tx: oneshot::Sender<Result<Value>>,
}

struct Inner {
    stdin: tokio::sync::Mutex<ChildStdin>,
    child: std::sync::Mutex<Option<Child>>,
    pending: Mutex<HashMap<String, Pending>>,
    next_id: AtomicU64,
    events: broadcast::Sender<PiEvent>,
    exit: broadcast::Sender<PiExit>,
    /// 最后一次退出状态。既是 `is_closed` 的判据，也用于重放订阅窗口之前的退出。
    closed: Mutex<Option<PiExit>>,
}

impl Drop for Inner {
    fn drop(&mut self) {
        if let Some(child) = self.child.lock().expect("child poisoned").as_mut() {
            let _ = child.start_kill();
        }
    }
}

#[derive(Clone)]
pub struct PiClient {
    inner: Arc<Inner>,
    pid: u32,
}

impl PiClient {
    /// 启动 pi 并连接会话文件。`guard` 为 None 时不加载审批扩展。
    ///
    /// `trust_project = false` 时不传 `--approve`：pi 会对「含 `.pi/extensions`、
    /// `.agents/skills` 等项目级资源」的目录走自身信任策略（RPC 下 hasUI=false → 默认拒绝），
    /// 从而不会加载工作区内的任意扩展代码。只有在工作目录被显式列入允许清单时才放行。
    pub async fn spawn(
        pi_path: &Path,
        session_path: &Path,
        guard: Option<&Path>,
        extra_args: &[String],
        trust_project: bool,
    ) -> Result<Self> {
        // 审批扩展缺失时绝不能静默启动一个无审批的 pi：先 fail-closed 校验可读性。
        if let Some(guard) = guard {
            std::fs::File::open(guard).map_err(|e| {
                CoreError::Pi(format!(
                    "审批扩展 {} 不可读，拒绝启动: {e}",
                    guard.display()
                ))
            })?;
        }
        // 四条启动路径（node 启动器 / .cmd shim / 直接可执行 / 非 Windows）必须
        // 产出完全相同的参数集，否则会出现「某个平台静默跳过 --approve 或跳过 -e guard」。
        let args = session_args(session_path, guard, extra_args, trust_project);
        #[cfg(windows)]
        let mut cmd = if let Some((node, launcher)) = pi_node_launcher(pi_path) {
            // pi 自带的启动器：直接交给 node，绕开 cmd.exe 对内嵌引号和 stdin 的处理。
            let mut cmd = Command::new(node);
            cmd.arg(launcher);
            cmd.args(&args);
            cmd
        } else if is_cmd_shim(pi_path) {
            let mut cmd = Command::new("cmd.exe");
            cmd.arg("/C");
            cmd.raw_arg(build_cmd_line(pi_path, &args));
            cmd
        } else {
            let mut cmd = Command::new(pi_path);
            cmd.args(&args);
            cmd
        };
        #[cfg(not(windows))]
        let mut cmd = {
            let mut cmd = Command::new(pi_path);
            cmd.args(&args);
            cmd
        };
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            // 不弹控制台窗口；Node 的 stdin/stdout 仍可用。
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = cmd.spawn().map_err(|e| {
            CoreError::PiNotInstalled(format!("无法启动 pi（{}）：{e}", pi_path.display()))
        })?;
        let pid = child.id().unwrap_or(0);
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| CoreError::Pi("pi 子进程没有 stdin".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| CoreError::Pi("pi 子进程没有 stdout".into()))?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| CoreError::Pi("pi 子进程没有 stderr".into()))?;
        let (events, _) = broadcast::channel(4096);
        let (exit, _) = broadcast::channel(8);
        let inner = Arc::new(Inner {
            stdin: tokio::sync::Mutex::new(stdin),
            child: std::sync::Mutex::new(Some(child)),
            pending: Mutex::new(HashMap::new()),
            next_id: AtomicU64::new(1),
            events: events.clone(),
            exit: exit.clone(),
            closed: Mutex::new(None),
        });

        let reader_inner = inner.clone();
        tokio::spawn(async move {
            read_stdout(stdout, reader_inner).await;
        });
        tokio::spawn(async move {
            let mut reader = BufReader::new(stderr);
            loop {
                match read_line_bounded(&mut reader, STDERR_LINE_LIMIT).await {
                    Ok(Some((bytes, truncated))) => {
                        let line = String::from_utf8_lossy(&bytes);
                        let line = truncate(line.trim_end(), 2000);
                        // stderr 也会被工具输出污染（例如 pi 的 bash 工具把命令输出转写）：
                        // 默认降到 TRACE，开启时才看得到，避免会话内容进持久化日志。
                        if truncated {
                            tracing::trace!(target: "pi.stderr", "{line}（已截断）");
                        } else {
                            tracing::trace!(target: "pi.stderr", "{line}");
                        }
                    }
                    _ => break,
                }
            }
        });
        Ok(Self { inner, pid })
    }

    pub fn pid(&self) -> u32 {
        self.pid
    }

    pub fn subscribe(&self) -> broadcast::Receiver<PiEvent> {
        self.inner.events.subscribe()
    }

    pub fn subscribe_exit(&self) -> broadcast::Receiver<PiExit> {
        self.inner.exit.subscribe()
    }

    pub fn is_closed(&self) -> bool {
        self.inner.closed.lock().expect("closed poisoned").is_some()
    }

    /// 最后一次退出状态。订阅者早于退出事件创建时用它补齐。
    pub fn exit_status(&self) -> Option<PiExit> {
        self.inner.closed.lock().expect("closed poisoned").clone()
    }

    pub async fn request(&self, op: &str, payload: Value) -> Result<Value> {
        self.request_with_timeout(op, payload, Duration::from_secs(120))
            .await
    }

    pub async fn request_with_timeout(
        &self,
        op: &str,
        payload: Value,
        timeout: Duration,
    ) -> Result<Value> {
        if let Some(exit) = self.inner.closed.lock().expect("closed poisoned").clone() {
            return Err(CoreError::Pi(format!("pi 已退出：{}", exit.reason)));
        }
        let id = format!("req-{}", self.inner.next_id.fetch_add(1, Ordering::SeqCst));
        let (tx, rx) = oneshot::channel();
        self.inner
            .pending
            .lock()
            .expect("pending poisoned")
            .insert(id.clone(), Pending { tx });
        let mut msg = match payload {
            Value::Object(mut m) => {
                m.insert("id".into(), Value::String(id.clone()));
                m.insert("type".into(), Value::String(op.into()));
                Value::Object(m)
            }
            Value::Null => json!({"id": id, "type": op}),
            other => {
                self.inner
                    .pending
                    .lock()
                    .expect("pending poisoned")
                    .remove(&id);
                return Err(CoreError::InvalidArgument(format!(
                    "{op} 的 payload 必须是对象或 null，实际 {other}"
                )));
            }
        };
        if msg.get("type").is_none() {
            msg["type"] = Value::String(op.into());
        }
        let line = match serde_json::to_vec(&msg) {
            Ok(line) => line,
            Err(e) => {
                self.forget_pending(&id);
                return Err(e.into());
            }
        };
        let written = {
            let mut stdin = self.inner.stdin.lock().await;
            match stdin.write_all(&line).await {
                Ok(()) => match stdin.write_all(b"\n").await {
                    Ok(()) => stdin.flush().await,
                    Err(e) => Err(e),
                },
                Err(e) => Err(e),
            }
        };
        if let Err(e) = written {
            // 写失败时 pi 不会回响应，必须自己撤销 pending，否则泄漏等待者。
            self.forget_pending(&id);
            return Err(e.into());
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(e),
            Ok(Err(_)) => Err(CoreError::Pi(format!("{op} 的响应通道已关闭"))),
            Err(_) => {
                self.inner
                    .pending
                    .lock()
                    .expect("pending poisoned")
                    .remove(&id);
                Err(CoreError::Pi(format!(
                    "{op} 超时（{}s）",
                    timeout.as_secs()
                )))
            }
        }
    }

    /// 为外部生成的请求 id 登记一次性响应等待（非阻塞消费）。
    pub fn track_response(&self, id: &str) -> Option<oneshot::Receiver<Result<Value>>> {
        if self.is_closed() {
            return None;
        }
        let (tx, rx) = oneshot::channel();
        self.inner
            .pending
            .lock()
            .expect("pending poisoned")
            .insert(id.to_string(), Pending { tx });
        Some(rx)
    }

    /// 放弃一次已登记的等待（例如写 stdin 失败后）。
    pub fn forget_pending(&self, id: &str) {
        self.inner
            .pending
            .lock()
            .expect("pending poisoned")
            .remove(id);
    }

    /// 写一条不等待响应的协议消息（扩展 UI 响应、通知）。
    pub async fn send_raw(&self, value: &Value) -> Result<()> {
        let line = serde_json::to_vec(value)?;
        let mut stdin = self.inner.stdin.lock().await;
        stdin.write_all(&line).await?;
        stdin.write_all(b"\n").await?;
        stdin.flush().await?;
        Ok(())
    }

    pub async fn extension_ui_response(&self, id: &str, body: Value) -> Result<()> {
        let mut v = body;
        v["type"] = Value::String("extension_ui_response".into());
        v["id"] = Value::String(id.into());
        self.send_raw(&v).await
    }

    /// 优雅终止：先 SIGTERM/Close，再等待；最后强杀。
    pub async fn shutdown(&self) {
        let child = self.inner.child.lock().expect("child poisoned").take();
        if let Some(mut child) = child {
            let _ = child.start_kill();
            let _ = tokio::time::timeout(Duration::from_secs(3), child.wait()).await;
        }
        self.mark_closed("shutdown");
    }

    fn mark_closed(&self, reason: &str) {
        let exit = PiExit {
            code: None,
            reason: reason.to_string(),
        };
        close_with(&self.inner, exit);
    }
}

/// 按行读取，但单行最多缓存 `limit` 字节；更长的行整行丢弃并标记 truncated。
/// `read_until` 会把整行读进内存，遇到恶意/异常超长行会 OOM，因此手工分块扫描。
async fn read_line_bounded<R: AsyncBufRead + Unpin>(
    reader: &mut R,
    limit: usize,
) -> std::io::Result<Option<(Vec<u8>, bool)>> {
    let mut out: Vec<u8> = Vec::new();
    let mut truncated = false;
    let mut read_any = false;
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if !read_any {
                return Ok(None);
            }
            break;
        }
        read_any = true;
        match available.iter().position(|b| *b == b'\n') {
            Some(end) => {
                if !truncated {
                    if out.len() + end > limit {
                        truncated = true;
                        out.clear();
                    } else {
                        out.extend_from_slice(&available[..end]);
                    }
                }
                reader.consume(end + 1);
                break;
            }
            None => {
                let len = available.len();
                if !truncated {
                    if out.len() + len > limit {
                        truncated = true;
                        out.clear();
                    } else {
                        out.extend_from_slice(available);
                    }
                }
                reader.consume(len);
            }
        }
    }
    Ok(Some((out, truncated)))
}

async fn read_stdout(stdout: tokio::process::ChildStdout, inner: Arc<Inner>) {
    let mut reader = BufReader::new(stdout);
    loop {
        match read_line_bounded(&mut reader, MAX_JSONL_LINE).await {
            Ok(Some((bytes, truncated))) => {
                if truncated {
                    tracing::warn!(limit = MAX_JSONL_LINE, "pi stdout 单行超过协议上限，已丢弃");
                    continue;
                }
                let line = String::from_utf8_lossy(&bytes);
                let line = line.trim();
                if line.is_empty() {
                    continue;
                }
                let value: Value = match serde_json::from_str(line) {
                    Ok(v) => v,
                    Err(e) => {
                        // 不记录行内容：pi 偶尔会把助手文本写到 stdout，
                        // 落进 desktop.log 就等于把会话内容明文持久化（D-4）。
                        tracing::warn!(
                            error = %e,
                            bytes = line.len(),
                            "pi stdout 非 JSON"
                        );
                        continue;
                    }
                };
                if value.get("type").and_then(Value::as_str) == Some("response") {
                    if let Some(id) = value.get("id").and_then(Value::as_str) {
                        let pending = inner.pending.lock().expect("pending poisoned").remove(id);
                        if let Some(p) = pending {
                            let res = if value.get("success").and_then(Value::as_bool) == Some(true)
                            {
                                Ok(value.get("data").cloned().unwrap_or(Value::Null))
                            } else {
                                Err(response_error(&value))
                            };
                            let _ = p.tx.send(res);
                            continue;
                        }
                    }
                }
                let kind = value
                    .get("type")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown")
                    .to_string();
                let _ = inner.events.send(PiEvent { kind, value });
            }
            Ok(None) => {
                close_inner(&inner, "stdout EOF");
                return;
            }
            Err(e) => {
                close_inner(&inner, &format!("stdout 读取失败: {e}"));
                return;
            }
        }
    }
}

fn close_inner(inner: &Arc<Inner>, reason: &str) {
    let code = inner
        .child
        .lock()
        .expect("child poisoned")
        .as_mut()
        .and_then(|c| c.try_wait().ok().flatten())
        .and_then(|s| s.code());
    close_with(
        inner,
        PiExit {
            code,
            reason: reason.to_string(),
        },
    );
}

fn close_with(inner: &Arc<Inner>, exit: PiExit) {
    let mut closed = inner.closed.lock().expect("closed poisoned");
    if closed.is_some() {
        return;
    }
    *closed = Some(exit.clone());
    drop(closed);
    let mut pending = inner.pending.lock().expect("pending poisoned");
    for (_, p) in pending.drain() {
        let _ =
            p.tx.send(Err(CoreError::Pi(format!("pi 已退出：{}", exit.reason))));
    }
    let _ = inner.exit.send(exit);
}

fn response_error(value: &Value) -> CoreError {
    let err = value.get("error").cloned().unwrap_or(Value::Null);
    let msg = err
        .as_str()
        .map(ToOwned::to_owned)
        .or_else(|| {
            err.get("message")
                .and_then(Value::as_str)
                .map(ToOwned::to_owned)
        })
        .unwrap_or_else(|| err.to_string());
    CoreError::Pi(format!(
        "pi 拒绝 {}: {msg}",
        value
            .get("command")
            .and_then(Value::as_str)
            .unwrap_or("命令")
    ))
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect::<String>() + "…"
    }
}

/// Windows 的 npm shim 是 .cmd，必须经 cmd.exe 执行。
#[cfg(windows)]
fn pi_node_launcher(path: &Path) -> Option<(PathBuf, PathBuf)> {
    if !is_cmd_shim(path) {
        return None;
    }
    let launcher = path.with_file_name("pi-launcher.js");
    if !launcher.is_file() {
        return None;
    }
    let node = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .map(|p| p.join("pi-node").join("current").join("node.exe"))
        .filter(|p| p.is_file())
        .unwrap_or_else(|| PathBuf::from("node"));
    Some((node, launcher))
}

#[cfg(windows)]
fn is_cmd_shim(path: &Path) -> bool {
    matches!(
        path.extension()
            .and_then(|e| e.to_str())
            .map(|e| e.to_ascii_lowercase())
            .as_deref(),
        Some("cmd") | Some("bat")
    )
}

/// 构造启动 pi 的参数集（所有平台共用，保证四处一致）。
fn session_args(
    session_path: &Path,
    guard: Option<&Path>,
    extra_args: &[String],
    trust_project: bool,
) -> Vec<String> {
    let mut args: Vec<String> = vec![
        "--mode".into(),
        "rpc".into(),
        "--session".into(),
        session_path.display().to_string(),
    ];
    // 只有显式允许的工作区才信任项目级资源；否则交给 pi 的默认策略（RPC 下无 UI → 拒绝），
    // 避免主目录下任意仓库的 `.pi/extensions` 代码在无人审批时自动执行。
    // 注意：一旦传了 `--approve` 或 `--no-approve`，pi 会跳过自己的询问（见 project-trust.js 的 trustOverride）。
    args.push(if trust_project {
        "--approve".into()
    } else {
        "--no-approve".into()
    });
    if let Some(guard) = guard {
        // `--no-extensions` 只关掉自动发现的扩展，`-e` 显式加载的 guard 仍生效。
        args.push("--no-extensions".into());
        args.push("-e".into());
        args.push(guard.display().to_string());
    }
    args.extend(extra_args.iter().cloned());
    args
}

/// 构造 cmd.exe /C 之后的原始命令行。
/// 不能用普通 arg：Rust 会把内嵌引号转义成反斜杠引号，cmd.exe 不认。
#[cfg(windows)]
fn build_cmd_line(path: &Path, args: &[String]) -> String {
    let mut line = format!("\"{}\"", path.display());
    for arg in args {
        line.push(' ');
        line.push_str(&quote_cmd_arg(arg));
    }
    line
}

#[cfg(windows)]
fn quote_cmd_arg(arg: &str) -> String {
    format!("\"{}\"", arg.replace('"', "\"\""))
}

/// 查找 pi：显式配置 > PI_CARRY_PI > PATH。
pub fn resolve_pi(explicit: Option<&str>) -> Result<PathBuf> {
    let candidates: Vec<PathBuf> = explicit
        .map(PathBuf::from)
        .into_iter()
        .chain(std::env::var("PI_CARRY_PI").ok().map(PathBuf::from))
        .collect();
    for c in candidates {
        if c.is_file() {
            return Ok(prefer_windows_shim(c));
        }
    }
    // Windows 上优先 .cmd/.exe：无扩展名的 npm shim 是 shell 脚本，CreateProcess 无法直接执行。
    let exts: &[&str] = if cfg!(windows) {
        &[".cmd", ".exe", ".bat", ""]
    } else {
        &[""]
    };
    let path = std::env::var_os("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path) {
        for ext in exts {
            let c = dir.join(format!("pi{ext}"));
            if c.is_file() {
                return Ok(c);
            }
        }
    }
    Err(CoreError::PiNotInstalled(
        "未找到 pi，请安装 @earendil-works/pi-coding-agent 或在 config.toml 里设置 node.piPath"
            .into(),
    ))
}

#[cfg(windows)]
fn prefer_windows_shim(path: PathBuf) -> PathBuf {
    if path.extension().is_some() {
        return path;
    }
    for ext in ["cmd", "exe", "bat"] {
        let candidate = path.with_extension(ext);
        if candidate.is_file() {
            return candidate;
        }
    }
    path
}

#[cfg(not(windows))]
fn prefer_windows_shim(path: PathBuf) -> PathBuf {
    path
}

#[cfg(test)]
mod tests {
    use super::*;

    /// P-3 回归：参数集必须显式声明信任决策。
    ///
    /// pi 的 `project_trust.js` 在拿到 `trustOverride` 时完全跳过自己的询问，
    /// 所以“不传任何 flag”并不安全（会被 settings 的 defaultProjectTrust 静默接管），
    /// 必须明确传 `--no-approve` 才真的不信任项目资源。
    #[test]
    fn session_args_always_declare_trust_decision() {
        let session = Path::new("C:/tmp/s.jsonl");
        let guard = Path::new("C:/tmp/guard.ts");
        let untrusted = session_args(session, Some(guard), &[], false);
        assert!(untrusted.contains(&"--no-approve".to_string()));
        assert!(!untrusted.contains(&"--approve".to_string()));

        let trusted = session_args(session, Some(guard), &[], true);
        assert!(trusted.contains(&"--approve".to_string()));
        assert!(!trusted.contains(&"--no-approve".to_string()));
    }

    /// 审批扩展必须总是显式加载：`--no-extensions` 关掉自动发现后，
    /// 只有 `-e guard` 能把守卫装回去。两个 flag 缺一不可。
    #[test]
    fn session_args_always_load_guard_explicitly() {
        let args = session_args(
            Path::new("s.jsonl"),
            Some(Path::new("guard.ts")),
            &[],
            false,
        );
        assert!(args.contains(&"--no-extensions".to_string()));
        // 结构固定为 [... "--no-extensions", "-e", <guard 路径>]
        let n = args.len();
        assert_eq!(args[n - 3], "--no-extensions");
        assert_eq!(args[n - 2], "-e");
        assert_eq!(args.last().map(String::as_str), Some("guard.ts"));
    }

    /// 用户自定义参数必须排在内部参数之后（`--` 之后才是文件参数），
    /// 且不能被内部参数覆盖（以后只看 `session_args` 一处即可）。
    #[test]
    fn session_args_append_user_args_last() {
        let extra = vec!["--model".to_string(), "x".to_string()];
        let args = session_args(Path::new("s.jsonl"), None, &extra, false);
        assert_eq!(&args[args.len() - 2..], &extra[..]);
    }

    #[tokio::test]
    async fn read_line_bounded_drops_overlong_line_and_keeps_next() {
        let data = b"short\n0123456789abcdefghij\nafter\n".to_vec();
        let mut reader = BufReader::new(data.as_slice());
        let (first, t1) = read_line_bounded(&mut reader, 16).await.unwrap().unwrap();
        assert_eq!(String::from_utf8_lossy(&first), "short");
        assert!(!t1);
        let (second, t2) = read_line_bounded(&mut reader, 16).await.unwrap().unwrap();
        assert!(t2, "超过上限的行必须标记 truncated");
        assert!(second.is_empty(), "超长行内容必须丢弃，不能缓存前缀");
        let (third, t3) = read_line_bounded(&mut reader, 16).await.unwrap().unwrap();
        assert_eq!(String::from_utf8_lossy(&third), "after");
        assert!(!t3, "被丢弃的行不能影响后续分帧");
        assert!(read_line_bounded(&mut reader, 16).await.unwrap().is_none());
    }

    #[test]
    fn prefers_windows_cmd_over_extensionless_shim() {
        let dir = tempfile::tempdir().unwrap();
        let bare = dir.path().join("pi");
        std::fs::write(
            &bare,
            b"#!/bin/sh
",
        )
        .unwrap();
        std::fs::write(
            dir.path().join("pi.cmd"),
            b"@echo off
",
        )
        .unwrap();
        let picked = prefer_windows_shim(bare);
        if cfg!(windows) {
            assert_eq!(picked.extension().and_then(|e| e.to_str()), Some("cmd"));
        } else {
            assert_eq!(picked.extension(), None);
        }
    }

    #[cfg(windows)]
    #[test]
    fn builds_raw_cmd_line_without_rust_escaped_quotes() {
        let line = build_cmd_line(
            Path::new("C:/Program Files/pi.cmd"),
            &["--mode".into(), "rpc".into(), "C:/a b/s.jsonl".into()],
        );
        assert!(line.starts_with('"'));
        assert!(line.contains("\"C:/Program Files/pi.cmd\""));
        assert!(line.contains("\"C:/a b/s.jsonl\""));
        assert!(!line.contains("\\\""));
    }
}

/// 读取 `pi --version`，失败返回空串。
///
/// 为什么不用 RPC：版本在 `hello` 时就要报给客户端（客户端要判断
/// `get_state`/`prompt` 是否可用、是否低于 1.5.0），此时还没建会话。
/// 这个调用最坏情况阻塞 5 秒，所以只在启动时做一次并把结果缓存在 `Server` 里。
pub fn detect_version(pi_path: &Path) -> String {
    // 超时保护：pi 是外部脚本，卡住不能拖死 bridge 启动。
    let (tx, rx) = std::sync::mpsc::channel();
    let path = pi_path.to_path_buf();
    std::thread::spawn(move || {
        let (program, mut cmd) = version_command(&path);
        let out = cmd
            .arg("--version")
            .stdin(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .output();
        let _ = tx.send(out.map(|o| (program, o)));
    });
    let Ok(Ok((program, out))) = rx.recv_timeout(std::time::Duration::from_secs(5)) else {
        tracing::warn!(pi = %pi_path.display(), "探测 pi 版本超时");
        return String::new();
    };
    if !out.status.success() {
        tracing::warn!(pi = %pi_path.display(), program = %program, "pi --version 退出码非 0");
        return String::new();
    }
    let raw = String::from_utf8_lossy(&out.stdout);
    // 只取第一个形如 `1.5.2` 的 token：某些版本会在前面打印警告。
    raw.split_whitespace()
        .find(|t| t.chars().next().is_some_and(|c| c.is_ascii_digit()) && t.contains('.'))
        .map(|t| {
            t.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '.' && c != '-')
                .to_string()
        })
        .unwrap_or_default()
}

/// `pi --version` 的启动方式必须与真正 spawn 会话时一致（同一套 shim 处理），
/// 否则会出现「版本探测成功但会话启不起来」的伪阳性。
fn version_command(path: &Path) -> (String, std::process::Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        if let Some((node, launcher)) = pi_node_launcher(path) {
            let mut cmd = std::process::Command::new(&node);
            cmd.arg(launcher);
            return (node.display().to_string(), cmd);
        }
        if is_cmd_shim(path) {
            let mut cmd = std::process::Command::new("cmd.exe");
            cmd.arg("/C").raw_arg(build_cmd_line(path, &[]));
            return ("cmd.exe".into(), cmd);
        }
        // 不弹控制台窗口
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut cmd = std::process::Command::new(path);
        cmd.creation_flags(CREATE_NO_WINDOW);
        return (path.display().to_string(), cmd);
    }
    #[cfg(not(windows))]
    {
        let mut cmd = std::process::Command::new(path);
        cmd.env("NO_COLOR", "1");
        (path.display().to_string(), cmd)
    }
}

#[cfg(test)]
mod version_tests {
    use super::*;

    #[test]
    fn detect_version_parses_real_output() {
        // 用 shell/node 脚本模拟 `pi --version` 的输出形态，避免依赖本机 pi。
        let dir = tempfile::tempdir().unwrap();
        #[cfg(windows)]
        let (script, content) = ("pi.cmd", "@echo off\r\nrem 提示行\r\necho 1.5.2\r\n");
        #[cfg(not(windows))]
        let (script, content) = ("pi", "#!/bin/sh\necho 'warn: x'\necho 1.5.2\n");
        let path = dir.path().join(script);
        std::fs::write(&path, content).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        assert_eq!(detect_version(&path), "1.5.2");
    }

    #[test]
    fn detect_version_returns_empty_for_missing_binary() {
        let dir = tempfile::tempdir().unwrap();
        // 不存在的路径：必须返回空串而不是 panic 或阻塞。
        assert_eq!(detect_version(&dir.path().join("nope")), "");
    }
}
