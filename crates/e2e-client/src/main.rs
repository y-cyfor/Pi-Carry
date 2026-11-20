//! 真实网络探针：用配对链接连接一个 pi-carry 节点并执行只读请求。
//!
//! 该工具用于桌面/移动端之外的黑盒验证，不参与产品运行。

use anyhow::{anyhow, Context, Result};
use clap::{Parser, Subcommand};
use pi_carry_protocol::{
    read_frame, write_frame, ClientFrame, ClientInfo, HelloPayload, ServerFrame, ALPN,
    MAX_CONTROL_FRAME, MAX_EVENT_FRAME, PROTOCOL_VERSION,
};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "pi-carry-e2e", about = "pi carry 网络黑盒探针")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// 用 pi-carry://pair 链接连接节点并读取状态
    Probe {
        #[arg(long)]
        uri: String,
        /// 可选：配对后额外发送一条 prompt
        #[arg(long)]
        prompt: Option<String>,
    },
}

fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(&input[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        if bytes[i] == b'+' {
            out.push(b' ');
        } else {
            out.push(bytes[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn parse_uri(uri: &str) -> Result<(String, String, Vec<SocketAddr>)> {
    let query = uri
        .split_once('?')
        .map(|(_, q)| q)
        .ok_or_else(|| anyhow!("配对链接缺少查询参数"))?;
    let mut endpoint = None;
    let mut token = None;
    let mut addrs = Vec::new();
    for pair in query.split('&') {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        let v = percent_decode(v);
        match k {
            "endpoint" => endpoint = Some(v),
            "token" => token = Some(v),
            "addr" => {
                if let Ok(addr) = v.parse::<SocketAddr>() {
                    addrs.push(addr);
                }
            }
            _ => {}
        }
    }
    Ok((
        endpoint.ok_or_else(|| anyhow!("缺少 endpoint"))?,
        token.ok_or_else(|| anyhow!("缺少 token"))?,
        addrs,
    ))
}

struct Conn {
    conn: iroh::endpoint::Connection,
    send: iroh::endpoint::SendStream,
    recv: iroh::endpoint::RecvStream,
    next: u64,
    events: Vec<Value>,
}

impl Conn {
    async fn next_event(&mut self, wait: Duration) -> Result<Option<Value>> {
        if !self.events.is_empty() {
            return Ok(Some(self.events.remove(0)));
        }
        let raw = tokio::time::timeout(wait, read_frame(&mut self.recv, MAX_EVENT_FRAME))
            .await
            .context("等待事件超时")??
            .ok_or_else(|| anyhow!("服务端关闭了控制流"))?;
        let frame: ServerFrame = serde_json::from_slice(&raw).context("事件不是合法帧")?;
        Ok(match frame {
            ServerFrame::Event { event, .. } => Some(event),
            _ => None,
        })
    }

    async fn request(&mut self, op: &str, payload: Value) -> Result<Value> {
        self.next += 1;
        let id = format!("p{}", self.next);
        write_frame(
            &mut self.send,
            &ClientFrame::Request {
                id: id.clone(),
                op: op.into(),
                payload,
            },
            MAX_CONTROL_FRAME,
        )
        .await
        .context("发送请求失败")?;
        loop {
            let raw = tokio::time::timeout(
                Duration::from_secs(15),
                read_frame(&mut self.recv, 4 * 1024 * 1024),
            )
            .await
            .context("等待响应超时")??
            .ok_or_else(|| anyhow!("服务端关闭了控制流"))?;
            let frame: ServerFrame = serde_json::from_slice(&raw).context("响应不是合法帧")?;
            if let ServerFrame::Event { event, .. } = frame {
                self.events.push(event);
                continue;
            }
            if let ServerFrame::Response {
                id: rid,
                ok,
                payload,
                error,
            } = frame
            {
                if rid.as_deref() != Some(id.as_str()) {
                    continue;
                }
                if !ok {
                    let err = serde_json::to_value(error).unwrap_or(Value::Null);
                    return Err(anyhow!("{op} 失败: {err}"));
                }
                return Ok(payload.unwrap_or(Value::Null));
            }
        }
    }
}

async fn probe(uri: &str, prompt: Option<String>) -> Result<()> {
    let (endpoint_id, token, addrs) = parse_uri(uri)?;
    let id = endpoint_id
        .parse::<iroh::EndpointId>()
        .context("Endpoint ID 非法")?;
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .context("创建 iroh endpoint 失败")?;

    let mut conn = None;
    let mut last = None;
    let targets = if addrs.is_empty() {
        vec![iroh::EndpointAddr::new(id)]
    } else {
        addrs
            .iter()
            .map(|a| iroh::EndpointAddr::new(id).with_ip_addr(*a))
            .collect()
    };
    for target in targets {
        match tokio::time::timeout(Duration::from_secs(8), client.connect(target, ALPN)).await {
            Ok(Ok(c)) => {
                conn = Some(c);
                break;
            }
            Ok(Err(e)) => last = Some(e.to_string()),
            Err(_) => last = Some("连接超时".into()),
        }
    }
    let conn = conn.ok_or_else(|| anyhow!("所有地址都连接失败: {last:?}"))?;
    let (mut send, mut recv) = conn.open_bi().await.context("打开控制流失败")?;
    let hello = ClientFrame::Request {
        id: "hello".into(),
        op: "hello".into(),
        payload: serde_json::to_value(HelloPayload {
            protocol: PROTOCOL_VERSION.into(),
            client: ClientInfo {
                name: "e2e-probe".into(),
                platform: "rust".into(),
                device_class: "phone".into(),
                app_version: "0.1.0".into(),
                locale: "zh-CN".into(),
            },
            token: Some(token),
            code: None,
            password: None,
            resume_session_id: None,
        })?,
    };
    write_frame(&mut send, &hello, MAX_CONTROL_FRAME)
        .await
        .context("发送 hello 失败")?;
    let raw = read_frame(&mut recv, MAX_CONTROL_FRAME)
        .await?
        .ok_or_else(|| anyhow!("服务端在 hello 阶段关闭连接"))?;
    let hello_resp: ServerFrame = serde_json::from_slice(&raw)?;
    let ServerFrame::Response {
        ok, payload, error, ..
    } = hello_resp
    else {
        return Err(anyhow!("期望 hello response"));
    };
    if !ok {
        return Err(anyhow!("hello 失败: {error:?}"));
    }
    let mut c = Conn {
        conn,
        send,
        recv,
        next: 0,
        events: Vec::new(),
    };
    let status = c.request("node.status", Value::Null).await?;
    let sessions = c.request("session.list", json!({})).await?;
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "ok": true,
            "device": payload.unwrap_or(Value::Null).get("device").cloned(),
            "node": status.get("node").cloned(),
            "sessions": sessions.get("sessions").cloned(),
        }))?
    );

    if let Some(text) = prompt {
        let created = c.request("session.create", json!({"cwd": ""})).await?;
        let sid = created["session"]["id"]
            .as_str()
            .ok_or_else(|| anyhow!("create 未返回 session.id"))?
            .to_string();
        c.request(
            "prompt.send",
            json!({
                "sessionId": sid,
                "message": text,
                "mode": "auto",
                "clientId": format!("probe-{}", std::process::id()),
                "images": []
            }),
        )
        .await?;
        println!("{}", json!({"promptSent": true}));
        // 等待最终消息，确保真实 pi 不只是“接受”了请求，而是真的完成了一轮。
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(120);
        let mut final_text = None;
        let mut last_state = None;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return Err(anyhow!("等待 message.final 超时"));
            }
            let Some(event) = c.next_event(remaining).await? else {
                continue;
            };
            let kind = event["type"].as_str().unwrap_or("");
            if kind == "run.state" {
                last_state = event.get("state").cloned();
                let status = event
                    .get("state")
                    .and_then(|s| {
                        s.as_str()
                            .or_else(|| s.get("status").and_then(|v| v.as_str()))
                    })
                    .unwrap_or("");
                if matches!(status, "idle" | "error") && final_text.is_some() {
                    break;
                }
                if status == "error" {
                    return Err(anyhow!("运行失败: {event}"));
                }
            }
            if kind == "approval.requested" {
                let id = event
                    .get("approval")
                    .and_then(|a| a.get("id"))
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();
                if !id.is_empty() {
                    println!("{}", json!({"approvalRequested": id}));
                    c.request(
                        "approval.resolve",
                        json!({"approvalId": id, "decision": "allow", "remember": false}),
                    )
                    .await?;
                    println!("{}", json!({"approvalResolved": true}));
                }
            }
            if kind == "message.final" {
                let content = event.get("message").and_then(|m| m.get("content"));
                final_text = match content {
                    Some(Value::String(text)) => Some(text.clone()),
                    Some(Value::Array(parts)) => Some(
                        parts
                            .iter()
                            .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                            .collect::<String>(),
                    ),
                    _ => None,
                };
            }
            if kind == "error" {
                return Err(anyhow!("服务端错误事件: {event}"));
            }
        }
        println!(
            "{}",
            json!({"promptSent": true, "final": final_text, "lastState": last_state})
        );
    }
    c.conn.close(0u32.into(), b"probe done");
    client.close().await;
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn")),
        )
        .init();
    let cli = Cli::parse();
    match cli.command {
        Command::Probe { uri, prompt } => probe(&uri, prompt).await,
    }
}
