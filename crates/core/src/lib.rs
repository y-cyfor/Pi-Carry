//! 受控端核心：配置、会话、审批、配对与 iroh 传输。

// `CoreError` 携带可操作的中文错误上下文（路径、命令输出），体积较大；
// 全量装箱会改动所有调用方签名，收益不抵风险，故在 crate 级别豁免。
#![allow(clippy::result_large_err)]
// 这些读取循环用 `loop` + `?` 提前返回错误，改成 `while let` 会吞掉 I/O 错误。
#![allow(clippy::while_let_loop)]

pub mod approval;
pub mod blob;
pub mod bridge;
pub mod clock;
pub mod config;
pub mod conninfo;
pub mod dirlock;
pub mod error;
pub mod fsbrowse;
pub mod guard;
pub mod pair_uri;
pub mod pairing;
pub mod paths;
pub mod pi;
pub mod secretstore;
pub mod server;
pub mod sessions;
pub mod shellpath;
pub mod store;
pub mod updatecheck;

pub use approval::{classify, ApprovalManager, Category, Decision, Risk};
pub use bridge::{Bridge, BridgeOptions};
pub use clock::{rfc3339, Clock, FixedClock, IdGen, SeqIdGen, SystemClock, UlidGen};
pub use config::{Config, Secrets};
pub use conninfo::{classify as classify_conn, ConnInfo, ConnType};
pub use dirlock::DirLock;
pub use error::{CoreError, Result};
pub use pair_uri::PairUri;
pub use pairing::{PairingManager, PairingSession, PairingStatus};
pub use paths::Paths;
pub use pi::{resolve_pi, PiClient, PiEvent, PiExit};
pub use secretstore::{SecretBackend, SecretStore};
pub use server::Server;
pub use sessions::{LiveSession, SessionEvent, SessionManager};
pub use store::{DeviceRecord, PendingApproval, Store};
pub use updatecheck::{is_newer, portable_update, Manifest, PortableUpdate};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
