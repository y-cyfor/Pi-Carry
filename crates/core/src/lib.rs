//! 受控端核心：配置、会话、审批、配对与 iroh 传输。

pub mod approval;
pub mod blob;
pub mod bridge;
pub mod clock;
pub mod config;
pub mod conninfo;
pub mod error;
pub mod fsbrowse;
pub mod guard;
pub mod pairing;
pub mod paths;
pub mod pi;
pub mod server;
pub mod sessions;
pub mod store;

pub use approval::{classify, ApprovalManager, Category, Decision, Risk};
pub use bridge::{Bridge, BridgeOptions};
pub use clock::{rfc3339, Clock, FixedClock, IdGen, SeqIdGen, SystemClock, UlidGen};
pub use config::{Config, Secrets};
pub use conninfo::{classify as classify_conn, ConnInfo, ConnType};
pub use error::{CoreError, Result};
pub use pairing::{PairingManager, PairingSession, PairingStatus};
pub use paths::Paths;
pub use pi::{resolve_pi, PiClient, PiEvent, PiExit};
pub use server::Server;
pub use sessions::{LiveSession, SessionEvent, SessionManager};
pub use store::{DeviceRecord, PendingApproval, Store};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
