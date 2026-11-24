//! P-3 回归：默认不信任项目级可执行资源。
//!
//! 手机端可以指定任意被允许的工作目录。如果 bridge 无条件给 pi 传 `--approve`，
//! 那么该目录下 `.pi/extensions` 里的任意代码都会在电脑上自动执行，
//! 而审批守卫（guard.ts）只拦截**工具调用**，拦不住扩展自身在加载时跑的代码。
//!
//! 这里走真实子进程验证信任决策确实传到了 pi 命令行：fake-pi 把收到的
//! `--approve` / `--no-approve` 回显成 `get_commands` 里的一个命令名。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use std::path::PathBuf;
use std::sync::Arc;

mod common;

use common::fake_pi;

/// 返回 bridge、临时目录和被测工作区（位于临时目录内，避免碰真实仓库）。
async fn bridge_with(
    config_mut: impl FnOnce(&mut Config, &PathBuf),
) -> (Arc<Bridge>, tempfile::TempDir, PathBuf) {
    let tmp = tempfile::tempdir().unwrap();
    let workspace = tmp.path().join("repo");
    std::fs::create_dir_all(&workspace).unwrap();
    let mut config = Config::default();
    config.node.name = "trust-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = tmp.path().join("sessions").display().to_string();
    config.sessions.allowed_roots = vec![tmp.path().display().to_string()];
    config_mut(&mut config, &workspace);
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths: Paths::at(tmp.path().join("home")),
        clock: Some(Arc::new(FixedClock::new(Utc::now()))),
    })
    .await
    .unwrap();
    (bridge, tmp, workspace)
}

/// 信任决策通过 `get_commands` 里的自检命令名暴露（见 fake-pi 的注释）。
async fn trust_marker(bridge: &Arc<Bridge>, session_id: &str) -> String {
    let v = bridge.sessions.commands(session_id).await.unwrap();
    let names: Vec<String> = v["commands"]
        .as_array()
        .expect("commands 必须是数组")
        .iter()
        .filter_map(|c| c["name"].as_str().map(ToOwned::to_owned))
        .collect();
    for marker in [
        "project-trusted",
        "project-untrusted",
        "project-trust-unspecified",
    ] {
        if names.iter().any(|n| n == marker) {
            return marker.to_string();
        }
    }
    panic!("fake-pi 未回显信任决策，命令列表: {names:?}");
}

#[tokio::test]
async fn default_workspace_is_not_trusted() {
    let (bridge, _tmp, workspace) = bridge_with(|_, _| {}).await;
    let s = bridge
        .sessions
        .create(&workspace.display().to_string())
        .await
        .unwrap();
    assert_eq!(
        trust_marker(&bridge, &s.id).await,
        "project-untrusted",
        "默认配置必须显式传 --no-approve：手机能指定 cwd 时，项目内扩展代码不得自动执行"
    );
}

#[tokio::test]
async fn explicitly_trusted_root_is_trusted() {
    let (bridge, _tmp, workspace) = bridge_with(|c, workspace| {
        c.sessions.trusted_roots = vec![workspace.display().to_string()];
    })
    .await;
    let s = bridge
        .sessions
        .create(&workspace.display().to_string())
        .await
        .unwrap();
    assert_eq!(
        trust_marker(&bridge, &s.id).await,
        "project-trusted",
        "显式信任的根目录应传 --approve，否则用户无法使用自己项目里的扩展"
    );
}
