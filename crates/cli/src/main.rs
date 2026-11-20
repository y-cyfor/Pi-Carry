//! pi-carry 命令行：启动桥、诊断、配对、设备管理与冒烟测试。

use clap::{Parser, Subcommand};
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::SystemClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_core::store::Store;
use pi_carry_protocol::PromptPayload;
use serde_json::json;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

#[derive(Parser)]
#[command(
    name = "pi-carry",
    version,
    about = "把本机 pi 安全地接到手机上的受控端"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    /// 启动桥（默认命令），打印配对二维码信息。
    Serve {
        /// 只输出一行 JSON，便于脚本消费。
        #[arg(long)]
        json: bool,
    },
    /// 显示本机节点、设备与会话概况（不联网）。
    Status,
    /// 生成一次性配对码并等待手机连接。
    Pair,
    /// 查看已配对设备。
    Devices,
    /// 吊销设备。
    Revoke { id: String },
    /// 环境自检。
    Doctor,
    /// 用 fake-pi 跑一遍启动/会话/流式冒烟。
    Smoke,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .with_target(false)
        .init();
    match cli.command.unwrap_or(Command::Serve { json: false }) {
        Command::Serve { json } => serve(json).await,
        Command::Pair => serve(false).await,
        Command::Status => status(),
        Command::Devices => devices(),
        Command::Revoke { id } => revoke(&id),
        Command::Doctor => doctor(),
        Command::Smoke => smoke().await,
    }
}

async fn serve(json: bool) -> anyhow::Result<()> {
    let options = BridgeOptions::load()?;
    let bridge = Bridge::start(options).await?;
    if json {
        let sessions = bridge.sessions.list()?.len();
        let line = json!({
            "endpointId": bridge.server.endpoint_id().to_string(),
            "nodeName": bridge.config.node.name.clone(),
            "relayMode": bridge.config.transport.relay_mode,
            "sessions": sessions,
        });
        println!("{}", serde_json::to_string(&line)?);
        std::io::stdout().flush()?;
    }
    bridge.clone().run_until_signal().await?;
    Ok(())
}

fn status() -> anyhow::Result<()> {
    let paths = Paths::discover();
    let config = Config::load(&paths)?;
    let store = Store::open(&paths.db())?;
    let devices = store.list_devices()?;
    let sessions: Vec<_> = store
        .list_sessions(50)?
        .into_iter()
        .map(|(s, _)| s)
        .collect();
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "home": paths.home,
            "nodeName": config.node.name,
            "piPath": config.node.pi_path,
            "piInstalled": pi_carry_core::pi::resolve_pi(Some(&config.node.pi_path)).is_ok(),
            "relayMode": config.transport.relay_mode,
            "sessionsDir": config.sessions.dir,
            "devices": devices,
            "sessions": sessions,
        }))?
    );
    Ok(())
}

fn devices() -> anyhow::Result<()> {
    let paths = Paths::discover();
    let store = Store::open(&paths.db())?;
    let devices = store.list_devices()?;
    if devices.is_empty() {
        println!("尚无已配对设备。运行 `pi-carry serve` 生成配对码。");
        return Ok(());
    }
    for d in devices {
        println!(
            "{}  {}  {}  capability={}  online={}  revoked={}",
            d.id,
            d.name,
            d.platform,
            d.capability,
            d.online,
            d.revoked_at.as_deref().unwrap_or("-")
        );
    }
    Ok(())
}

fn revoke(id: &str) -> anyhow::Result<()> {
    let paths = Paths::discover();
    let store = Store::open(&paths.db())?;
    let at = pi_carry_core::rfc3339(chrono::Utc::now());
    if store.revoke_device(id, &at)? {
        println!("已吊销 {id}");
        Ok(())
    } else {
        anyhow::bail!("设备 {id} 不存在或已被吊销")
    }
}

fn doctor() -> anyhow::Result<()> {
    let paths = Paths::discover();
    let config = Config::load(&paths)?;
    let pi = pi_carry_core::pi::resolve_pi(Some(&config.node.pi_path));
    let sessions = config.session_dir();
    let checks = json!([
        {"name": "数据目录", "ok": paths.ensure().is_ok(), "detail": paths.home},
        {"name": "pi 可执行文件", "ok": pi.is_ok(), "detail": pi.map(|p| p.display().to_string()).unwrap_or_else(|e| e.to_string())},
        {"name": "会话目录", "ok": sessions.is_dir(), "detail": sessions},
        {"name": "配置解析", "ok": config.validate().is_ok(), "detail": config.node.name},
        {"name": "relay 模式", "ok": matches!(config.transport.relay_mode.as_str(), "n0"|"disabled"|"custom"), "detail": config.transport.relay_mode},
    ]);
    println!("{}", serde_json::to_string_pretty(&checks)?);
    let ok = checks
        .as_array()
        .map(|a| a.iter().all(|c| c["ok"] == true))
        .unwrap_or(false);
    if ok {
        Ok(())
    } else {
        anyhow::bail!("自检失败")
    }
}

async fn smoke() -> anyhow::Result<()> {
    let root = std::env::temp_dir().join(format!("pi-carry-smoke-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(root.join("sessions"))?;
    let fake = fake_pi_path()?;
    let session_id = "smoke-session";
    let session_file = root
        .join("sessions")
        .join("--smoke--")
        .join(format!("{session_id}.jsonl"));
    std::fs::create_dir_all(session_file.parent().unwrap())?;
    std::fs::write(
        &session_file,
        format!(
            "{}\n",
            serde_json::to_string(&json!({
                "type": "session", "version": 3, "id": session_id,
                "timestamp": pi_carry_core::rfc3339(chrono::Utc::now()),
                "cwd": root.display().to_string()
            }))?
        ),
    )?;

    let mut config = Config::default();
    config.node.name = "smoke-node".into();
    config.node.pi_path = fake.display().to_string();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = root.join("sessions").display().to_string();
    let paths = Paths::at(root.join("home"));
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths,
        clock: Some(Arc::new(SystemClock)),
    })
    .await?;

    let sessions = bridge.sessions.list()?;
    if sessions.is_empty() {
        anyhow::bail!("冒烟失败：未扫描到会话");
    }
    bridge.sessions.open(session_id).await?;
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: session_id.into(),
                message: "冒烟测试".into(),
                mode: "auto".into(),
                client_id: "smoke".into(),
                images: vec![],
            },
            "smoke-device",
        )
        .await?;
    let mut final_message = None;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while tokio::time::Instant::now() < deadline {
        match tokio::time::timeout_at(deadline, events.recv()).await {
            Ok(Ok(ev)) if ev.event["type"] == "message.final" => {
                final_message = Some(ev.event);
                break;
            }
            Ok(Ok(_)) => {}
            Ok(Err(e)) => anyhow::bail!("冒烟事件流失败: {e}"),
            Err(_) => break,
        }
    }
    bridge.smoke_ping().await?;
    bridge.shutdown().await;
    let Some(final_message) = final_message else {
        anyhow::bail!("冒烟失败：未收到 message.final");
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&json!({
            "ok": true,
            "sessions": sessions.len(),
            "messageFinal": final_message,
            "home": root,
        }))?
    );
    Ok(())
}

fn fake_pi_path() -> anyhow::Result<PathBuf> {
    if let Ok(p) = std::env::var("PI_CARRY_FAKE_PI") {
        return Ok(PathBuf::from(p));
    }
    let exe = std::env::current_exe()?;
    let dir = exe.parent().unwrap_or_else(|| std::path::Path::new("."));
    // 常规构建在 target/{debug,release}；测试辅助构建在 target/test-fake-pi/debug。
    let mut dirs = vec![dir.to_path_buf()];
    if let Some(target) = dir.parent() {
        dirs.push(target.join("test-fake-pi").join("debug"));
    }
    for d in dirs {
        for name in ["fake-pi.exe", "fake-pi"] {
            let p = d.join(name);
            if p.exists() {
                return Ok(p);
            }
        }
    }
    anyhow::bail!(
        "找不到 fake-pi，可通过 PI_CARRY_FAKE_PI 指定，或先 cargo build -p pi-carry-fake-pi"
    )
}
