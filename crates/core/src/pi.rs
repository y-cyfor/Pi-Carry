//! pi RPC 子进程客户端。
//!
//! pi 的 stdout 是严格 JSONL；这里不用 `lines()` 之外的分帧技巧，
//! 也不把 stderr 混进协议流。请求按 id 关联，事件通过 broadcast 分发。

use crate::error::{CoreError, Result};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::{broadcast, oneshot};

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
    closed: Mutex<Option<String>>,
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
    pub async fn spawn(
        pi_path: &Path,
        session_path: &Path,
        guard: Option<&Path>,
        extra_args: &[String],
    ) -> Result<Self> {
        #[cfg(windows)]
        let mut cmd = if let Some((node, launcher)) = pi_node_launcher(pi_path) {
            // pi 自带的启动器：直接交给 node，绕开 cmd.exe 对内嵌引号和 stdin 的处理。
            let mut cmd = Command::new(node);
            cmd.arg(launcher);
            cmd.arg("--mode").arg("rpc");
            cmd.arg("--session").arg(session_path);
            cmd.arg("--approve");
            if let Some(guard) = guard {
                cmd.arg("--no-extensions");
                cmd.arg("-e").arg(guard);
            }
            cmd.args(extra_args);
            cmd
        } else if is_cmd_shim(pi_path) {
            let mut args: Vec<String> = vec![
                "--mode".into(),
                "rpc".into(),
                "--session".into(),
                session_path.display().to_string(),
                "--approve".into(),
            ];
            if let Some(guard) = guard {
                args.push("--no-extensions".into());
                args.push("-e".into());
                args.push(guard.display().to_string());
            }
            args.extend(extra_args.iter().cloned());
            let mut cmd = Command::new("cmd.exe");
            cmd.arg("/C");
            cmd.raw_arg(build_cmd_line(pi_path, &args));
            cmd
        } else {
            let mut cmd = Command::new(pi_path);
            cmd.arg("--mode").arg("rpc");
            cmd.arg("--session").arg(session_path);
            cmd.arg("--approve");
            if let Some(guard) = guard {
                cmd.arg("--no-extensions");
                cmd.arg("-e").arg(guard);
            }
            cmd.args(extra_args);
            cmd
        };
        #[cfg(not(windows))]
        let mut cmd = {
            let mut cmd = Command::new(pi_path);
            cmd.arg("--mode").arg("rpc");
            cmd.arg("--session").arg(session_path);
            cmd.arg("--approve");
            if let Some(guard) = guard {
                cmd.arg("--no-extensions");
                cmd.arg("-e").arg(guard);
            }
            cmd.args(extra_args);
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
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                tracing::debug!(target: "pi.stderr", "{}", line);
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
        if let Some(reason) = self.inner.closed.lock().expect("closed poisoned").clone() {
            return Err(CoreError::Pi(format!("pi 已退出：{reason}")));
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
        let line = serde_json::to_vec(&msg)?;
        {
            let mut stdin = self.inner.stdin.lock().await;
            stdin.write_all(&line).await?;
            stdin.write_all(b"\n").await?;
            stdin.flush().await?;
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
        let mut closed = self.inner.closed.lock().expect("closed poisoned");
        if closed.is_some() {
            return;
        }
        *closed = Some(reason.to_string());
        drop(closed);
        let mut pending = self.inner.pending.lock().expect("pending poisoned");
        for (_, p) in pending.drain() {
            let _ =
                p.tx.send(Err(CoreError::Pi(format!("pi 已退出：{reason}"))));
        }
        let _ = self.inner.exit.send(PiExit {
            code: None,
            reason: reason.to_string(),
        });
    }
}

async fn read_stdout(stdout: tokio::process::ChildStdout, inner: Arc<Inner>) {
    let mut lines = BufReader::new(stdout).lines();
    loop {
        match lines.next_line().await {
            Ok(Some(line)) => {
                let line = line.trim();
                if line.is_empty() {
                    continue;
                }
                let value: Value = match serde_json::from_str(line) {
                    Ok(v) => v,
                    Err(e) => {
                        tracing::warn!(error = %e, line = %truncate(line, 300), "pi stdout 非 JSON");
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
    let mut closed = inner.closed.lock().expect("closed poisoned");
    if closed.is_some() {
        return;
    }
    *closed = Some(reason.to_string());
    drop(closed);
    let mut pending = inner.pending.lock().expect("pending poisoned");
    for (_, p) in pending.drain() {
        let _ =
            p.tx.send(Err(CoreError::Pi(format!("pi 已退出：{reason}"))));
    }
    let code = inner
        .child
        .lock()
        .expect("child poisoned")
        .as_mut()
        .and_then(|c| c.try_wait().ok().flatten())
        .and_then(|s| s.code());
    let _ = inner.exit.send(PiExit {
        code,
        reason: reason.to_string(),
    });
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
