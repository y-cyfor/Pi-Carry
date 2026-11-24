//! `fs.roots` / `fs.list` 的端到端门禁（需求 0.1.4-2）。
//!
//! 移动端新建会话前要先选工作目录。这两个 op 会读写本机文件系统，
//! 所以必须同时守住三件事：能力位（无 control 拒绝）、根目录边界
//! （越界必须报错而不是静默返回空列表）、以及 hasSessions 标记
//! （决定 UI 是否显示「有历史会话」小圆点）。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_core::server::hello_frame;
use pi_carry_protocol::frame::{read_frame, write_frame};
use pi_carry_protocol::model::{ClientFrame, ServerFrame};
use pi_carry_protocol::{HelloPayload, MAX_CONTROL_FRAME, PROTOCOL_VERSION};
use serde_json::{json, Value};
use std::sync::Arc;

mod common;

use common::fake_pi;

struct Harness {
    bridge: Arc<Bridge>,
    endpoint: iroh::Endpoint,
    conn: iroh::endpoint::Connection,
    send: iroh::endpoint::SendStream,
    recv: iroh::endpoint::RecvStream,
    /// 服务端主动推送的事件（session_id 为 None，如 connection.updated），
    /// 它们会插在响应之间，必须收集起来而不是当成「响应不符合预期」报错。
    events: Vec<Value>,
}

impl Harness {
    async fn call(&mut self, op: &str, payload: Value) -> Result<Value, String> {
        let frame = ClientFrame::Request {
            id: format!("req-{op}"),
            op: op.into(),
            payload,
        };
        // write_frame 自己加长度前缀，直接传结构体；传 Vec<u8> 会被当成字节数组序列化。
        write_frame(&mut self.send, &frame, MAX_CONTROL_FRAME)
            .await
            .unwrap();
        loop {
            let resp = read_frame(&mut self.recv, MAX_CONTROL_FRAME).await.unwrap();
            let resp: ServerFrame = serde_json::from_slice(&resp.expect("必须收到响应")).unwrap();
            match resp {
                ServerFrame::Response {
                    ok, payload, error, ..
                } => {
                    return if ok {
                        Ok(payload.unwrap_or(Value::Null))
                    } else {
                        Err(error
                            .map(|e| format!("{}: {}", e.code, e.message))
                            .unwrap_or_else(|| "unknown".into()))
                    };
                }
                ServerFrame::Event { event, .. } => self.events.push(event),
                other => panic!("期望 response，实际 {other:?}"),
            }
        }
    }
}

async fn boot(home: &std::path::Path, allowed: Vec<String>) -> Harness {
    boot_with_cap(home, allowed, "full").await
}

async fn boot_with_cap(home: &std::path::Path, allowed: Vec<String>, capability: &str) -> Harness {
    let mut config = Config::default();
    config.node.name = "fs-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = home.join("sessions").display().to_string();
    config.sessions.default_capability = capability.into();
    config.sessions.allowed_roots = allowed;
    let paths = Paths::at(home.join("home"));
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths,
        clock: Some(Arc::new(FixedClock::new(Utc::now()))),
    })
    .await
    .unwrap();
    let pairing = bridge.begin_pairing(None);
    let hello = HelloPayload {
        protocol: PROTOCOL_VERSION.into(),
        client: pi_carry_protocol::ClientInfo {
            name: "测试手机".into(),
            platform: "android".into(),
            device_class: "phone".into(),
            app_version: "0.1.4".into(),
            locale: "zh-CN".into(),
        },
        token: Some(pairing.token.clone()),
        code: None,
        password: None,
        resume_session_id: None,
    };
    let bytes = hello_frame("hello", &hello).unwrap();
    let endpoint = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .relay_mode(iroh::RelayMode::Disabled)
        .bind()
        .await
        .unwrap();
    let addr = bridge
        .server
        .endpoint()
        .addr()
        .addrs
        .iter()
        .find_map(|a| match a {
            iroh::TransportAddr::Ip(ip) => Some(*ip),
            _ => None,
        })
        .expect("disabled relay 必须有直连地址");
    let server_addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(addr);
    let conn = endpoint
        .connect(server_addr, pi_carry_protocol::ALPN)
        .await
        .unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    use tokio::io::AsyncWriteExt;
    send.write_all(&bytes).await.unwrap();
    send.flush().await.unwrap();
    let resp = read_frame(&mut recv, MAX_CONTROL_FRAME).await.unwrap();
    let resp: ServerFrame = serde_json::from_slice(&resp.expect("hello 必须有响应")).unwrap();
    match resp {
        ServerFrame::Response { ok: true, .. } => {}
        other => panic!("hello 失败: {other:?}"),
    }
    Harness {
        bridge,
        endpoint,
        conn,
        send,
        recv,
        events: Vec::new(),
    }
}

async fn shutdown(h: Harness) {
    h.conn.close(0u32.into(), b"bye");
    h.endpoint.close().await;
    h.bridge.shutdown().await;
}

#[tokio::test]
async fn fs_roots_reports_allowed_roots_and_recent() {
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().join("work");
    std::fs::create_dir_all(work.join("alpha")).unwrap();
    std::fs::create_dir_all(work.join("beta")).unwrap();
    std::fs::write(work.join("notes.txt"), "文件不应出现在目录列表".as_bytes()).unwrap();
    std::fs::create_dir_all(work.join(".hidden")).unwrap();
    std::fs::create_dir_all(work.join("项目")).unwrap();

    let mut h = boot(tmp.path(), vec![work.display().to_string()]).await;
    let roots = h.call("fs.roots", json!({})).await.unwrap();
    let list = roots["roots"].as_array().expect("roots 必须是数组");
    assert_eq!(list.len(), 1, "只配置了一个 allowedRoots");
    assert_eq!(list[0]["path"], work.display().to_string());
    assert!(roots["recent"].is_array(), "recent 必须是数组（可为空）");

    let payload = h
        .call("fs.list", json!({"path": work.display().to_string()}))
        .await
        .unwrap();
    let names: Vec<&str> = payload["entries"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["name"].as_str().unwrap())
        .collect();
    assert_eq!(
        names,
        vec!["alpha", "beta", "项目"],
        "只列目录、跳过隐藏目录、按名称排序"
    );
    assert!(payload["entries"][0]["path"]
        .as_str()
        .unwrap()
        .ends_with("alpha"));
    assert_eq!(payload["truncated"], false);
    // parent 键必须存在（上层据此渲染「上一级」），但允许根目录的上一级在根外，
    // 必须为 null，否则 UI 给出一个点进去必被拒的按钮。
    assert!(payload.get("parent").is_some(), "必须带 parent 供上层返回");
    assert!(
        payload["parent"].is_null(),
        "允许根目录的 parent 必须为 null"
    );

    shutdown(h).await;
}

#[tokio::test]
async fn fs_list_marks_directories_with_sessions() {
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().join("work");
    std::fs::create_dir_all(work.join("with-session")).unwrap();
    std::fs::create_dir_all(work.join("empty")).unwrap();

    // 直接写一条会话索引，模拟该目录下已有历史会话。
    let mut h = boot(tmp.path(), vec![work.display().to_string()]).await;
    let created = h
        .call(
            "session.create",
            json!({"cwd": work.join("with-session").display().to_string()}),
        )
        .await
        .unwrap();
    assert!(created.get("session").is_some(), "创建会话应返回 session");

    let payload = h
        .call("fs.list", json!({"path": work.display().to_string()}))
        .await
        .unwrap();
    let entries = payload["entries"].as_array().unwrap();
    let find = |name: &str| {
        entries
            .iter()
            .find(|e| e["name"] == name)
            .unwrap_or_else(|| panic!("缺少条目 {name}"))
    };
    assert_eq!(
        find("with-session")["hasSessions"],
        true,
        "有历史会话的目录必须标记 hasSessions"
    );
    assert_eq!(find("empty")["hasSessions"], false);

    shutdown(h).await;
}

#[tokio::test]
async fn fs_list_rejects_path_outside_roots() {
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().join("work");
    std::fs::create_dir_all(&work).unwrap();
    let outside = tmp.path().join("outside");
    std::fs::create_dir_all(&outside).unwrap();

    let mut h = boot(tmp.path(), vec![work.display().to_string()]).await;
    let err = h
        .call("fs.list", json!({"path": outside.display().to_string()}))
        .await
        .expect_err("根目录之外必须报错");
    assert!(
        err.to_lowercase().contains("permission"),
        "越界应为 permission 类错误，实际: {err}"
    );

    shutdown(h).await;
}

#[tokio::test]
async fn fs_browse_requires_control_capability() {
    // 目录名会泄露本机文件系统结构：只读设备（read）必须被拒绝，
    // 而且拒绝要发生在回 root 之前，不能先返回数据再由客户端丢弃。
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().join("work");
    std::fs::create_dir_all(&work).unwrap();

    let mut h = boot_with_cap(tmp.path(), vec![work.display().to_string()], "read").await;
    let err = h
        .call("fs.roots", json!({}))
        .await
        .expect_err("read 设备不能调 fs.roots");
    assert!(
        err.to_lowercase().contains("permission"),
        "read 设备应为 permission 类错误，实际: {err}"
    );
    let err = h
        .call("fs.list", json!({"path": work.display().to_string()}))
        .await
        .expect_err("read 设备不能调 fs.list");
    assert!(
        err.to_lowercase().contains("permission"),
        "read 设备应为 permission 类错误，实际: {err}"
    );

    shutdown(h).await;
}

#[tokio::test]
async fn fs_list_rejects_missing_directory() {
    let tmp = tempfile::tempdir().unwrap();
    let work = tmp.path().join("work");
    std::fs::create_dir_all(&work).unwrap();

    let mut h = boot(tmp.path(), vec![work.display().to_string()]).await;
    let missing = work.join("nope");
    let err = h
        .call("fs.list", json!({"path": missing.display().to_string()}))
        .await
        .expect_err("不存在的目录必须报错");
    assert!(
        err.contains("invalid") || err.contains("不可访问") || err.contains("不是目录"),
        "不存在的路径应报 invalid 类错误，实际: {err}"
    );

    shutdown(h).await;
}
