//! 配对会话：一次性 token / 6 位码 / 失败锁定 / 过期。
//!
//! 二维码携带 endpoint id + token；手机也可手动输入 6 位码。
//! 令牌只保存在内存中，进程重启即失效。

use crate::clock::Clock;
use crate::error::{CoreError, Result};
use chrono::{DateTime, Duration, Utc};
use pi_carry_protocol::{ErrorCode, ProtocolError};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairingSession {
    pub token: String,
    pub code: String,
    pub endpoint_id: String,
    pub expires_at: String,
    pub uri: String,
    pub relay: Option<String>,
    /// 直连地址（IP:port），二维码/URI 携带，便于局域网无 relay 配对。
    #[serde(default)]
    pub addrs: Vec<String>,
    /// 本机确认（托盘/桌面窗口）后为 true；此时可免 6 位码。
    pub local_confirmed: bool,
    pub verified: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairingStatus {
    pub active: bool,
    pub verified: bool,
    pub local_confirmed: bool,
    pub attempts_remaining: u32,
    pub locked_until: Option<String>,
    pub expires_at: Option<String>,
}

struct State {
    active: Option<PairingSession>,
    attempts: u32,
    locked_until: Option<DateTime<Utc>>,
}

#[derive(Clone)]
pub struct PairingManager {
    inner: Arc<Mutex<State>>,
    clock: Arc<dyn Clock>,
    ttl_secs: u64,
    max_attempts: u32,
    lock_secs: u64,
}

impl PairingManager {
    pub fn new(clock: Arc<dyn Clock>) -> Self {
        Self {
            inner: Arc::new(Mutex::new(State {
                active: None,
                attempts: 0,
                locked_until: None,
            })),
            clock,
            ttl_secs: pi_carry_protocol::PAIRING_TTL_SEC,
            max_attempts: pi_carry_protocol::PAIRING_MAX_ATTEMPTS,
            lock_secs: pi_carry_protocol::PAIRING_LOCK_SEC,
        }
    }

    /// 开始一次新配对，返回给桌面窗口/二维码使用的会话。
    pub fn begin(&self, endpoint_id: &str, relay: Option<String>) -> PairingSession {
        let mut rng = rand::thread_rng();
        let token: String = (0..16)
            .map(|_| format!("{:02x}", rng.gen::<u8>()))
            .collect();
        let code = format!("{:06}", rng.gen_range(0..1_000_000u32));
        self.begin_with_addrs(endpoint_id, relay, Vec::new(), token, code)
    }

    /// 带直连地址的配对会话。
    pub fn begin_with_addrs(
        &self,
        endpoint_id: &str,
        relay: Option<String>,
        addrs: Vec<String>,
        token: String,
        code: String,
    ) -> PairingSession {
        let now = self.clock.now();
        let expires = now + Duration::seconds(self.ttl_secs as i64);
        let uri = build_uri(endpoint_id, &token, relay.as_deref(), &addrs);
        let session = PairingSession {
            token,
            code,
            endpoint_id: endpoint_id.to_string(),
            expires_at: crate::rfc3339(expires),
            uri,
            relay,
            addrs,
            local_confirmed: false,
            verified: false,
        };
        let mut st = self.inner.lock().expect("pairing poisoned");
        st.active = Some(session.clone());
        st.attempts = 0;
        st.locked_until = None;
        session
    }

    /// 测试用：注入确定的 token/code。
    pub fn begin_with(
        &self,
        endpoint_id: &str,
        relay: Option<String>,
        token: String,
        code: String,
    ) -> PairingSession {
        self.begin_with_addrs(endpoint_id, relay, Vec::new(), token, code)
    }

    pub fn status(&self) -> PairingStatus {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        if st.active.as_ref().is_some_and(|a| self.is_expired(a, now)) {
            st.active = None;
        }
        let locked = st.locked_until.filter(|t| *t > now);
        PairingStatus {
            active: st.active.is_some(),
            verified: st.active.as_ref().is_some_and(|a| a.verified),
            local_confirmed: st.active.as_ref().is_some_and(|a| a.local_confirmed),
            attempts_remaining: self.max_attempts.saturating_sub(st.attempts),
            locked_until: locked.map(crate::rfc3339),
            expires_at: st.active.as_ref().map(|a| a.expires_at.clone()),
        }
    }

    pub fn active(&self) -> Option<PairingSession> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        if st.active.as_ref().is_some_and(|a| self.is_expired(a, now)) {
            st.active = None;
        }
        st.active.clone()
    }

    /// 校验二维码 token。成功仅标记 verified，仍需本机确认（或已 local_confirmed）。
    pub fn verify_token(&self, token: &str) -> Result<bool> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        self.ensure_unlocked(&st, now)?;
        let Some(active) = st.active.as_mut() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        if !constant_time_eq(active.token.as_bytes(), token.as_bytes()) {
            self.fail_attempt(&mut st, now)?;
            return Ok(false);
        }
        active.verified = true;
        st.attempts = 0;
        Ok(true)
    }

    /// 校验 6 位码。成功仅标记 verified。
    pub fn verify_code(&self, code: &str) -> Result<bool> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        self.ensure_unlocked(&st, now)?;
        let Some(active) = st.active.as_mut() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        if active.code != code {
            self.fail_attempt(&mut st, now)?;
            return Ok(false);
        }
        active.verified = true;
        st.attempts = 0;
        Ok(true)
    }

    /// 本机（托盘）确认，无需输入码。二维码 token 仍需先验证，防止旁人扫屏。
    pub fn confirm_local(&self) -> Result<bool> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        let Some(active) = st.active.as_mut() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        active.local_confirmed = true;
        Ok(true)
    }

    /// 最终确认配对：要求已验证 token/码，或本机已确认。
    pub fn confirm(&self) -> Result<PairingSession> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        let Some(active) = st.active.as_mut() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        if !active.verified && !active.local_confirmed {
            return Err(pairing_err(ErrorCode::PairingDenied, "尚未验证配对码"));
        }
        let done = active.clone();
        st.active = None;
        st.attempts = 0;
        Ok(done)
    }

    pub fn cancel(&self) {
        let mut st = self.inner.lock().expect("pairing poisoned");
        st.active = None;
        st.attempts = 0;
    }

    /// 供测试推进时钟后清理。
    pub fn tick(&self) {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        if st.active.as_ref().is_some_and(|a| self.is_expired(a, now)) {
            st.active = None;
        }
        if st.locked_until.is_some_and(|t| t <= now) {
            st.locked_until = None;
            st.attempts = 0;
        }
    }

    fn is_expired(&self, a: &PairingSession, now: DateTime<Utc>) -> bool {
        a.expires_at
            .parse::<DateTime<Utc>>()
            .map(|t| t <= now)
            .unwrap_or(true)
    }

    fn ensure_unlocked(&self, st: &State, now: DateTime<Utc>) -> Result<()> {
        if let Some(until) = st.locked_until {
            if until > now {
                return Err(pairing_err(
                    ErrorCode::PairingLocked,
                    format!("配对已锁定，请在 {} 后重试", crate::rfc3339(until)),
                ));
            }
        }
        Ok(())
    }

    fn fail_attempt(&self, st: &mut State, now: DateTime<Utc>) -> Result<()> {
        st.attempts += 1;
        if st.attempts >= self.max_attempts {
            st.locked_until = Some(now + Duration::seconds(self.lock_secs as i64));
            st.active = None;
            return Err(pairing_err(
                ErrorCode::PairingLocked,
                format!("失败次数过多，已锁定 {} 秒", self.lock_secs),
            ));
        }
        Ok(())
    }
}

fn pairing_err(code: ErrorCode, msg: impl Into<String>) -> CoreError {
    CoreError::Protocol(ProtocolError::new(code, msg.into()))
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// RFC 3986 非保留字符之外的字节做 percent-encode。
fn encode_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn build_uri(endpoint_id: &str, token: &str, relay: Option<&str>, addrs: &[String]) -> String {
    let mut uri = format!(
        "pi-carry://pair?v=1&endpoint={}&token={}",
        encode_component(endpoint_id),
        encode_component(token)
    );
    if let Some(relay) = relay {
        uri.push_str("&relay=");
        uri.push_str(&encode_component(relay));
    }
    for addr in addrs {
        uri.push_str("&addr=");
        uri.push_str(&encode_component(addr));
    }
    uri
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::FixedClock;
    use chrono::TimeZone;

    fn setup() -> (PairingManager, FixedClock) {
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());
        (PairingManager::new(Arc::new(clock.clone())), clock)
    }

    fn begin(mgr: &PairingManager) -> PairingSession {
        mgr.begin_with(
            "endpoint-abc",
            Some("https://relay.example".into()),
            "deadbeefdeadbeefdeadbeefdeadbeef".into(),
            "042916".into(),
        )
    }

    #[test]
    fn uri_contains_endpoint_and_token() {
        let (mgr, _) = setup();
        let s = begin(&mgr);
        assert!(s.uri.contains("endpoint=endpoint-abc"));
        assert!(s.uri.contains("token=deadbeef"));
        assert!(s.uri.contains("relay=https%3A%2F%2Frelay.example"));
        assert_eq!(s.expires_at, "2026-11-19T08:10:00.000Z");
    }

    #[test]
    fn token_then_confirm() {
        let (mgr, _) = setup();
        begin(&mgr);
        assert!(mgr
            .verify_token("deadbeefdeadbeefdeadbeefdeadbeef")
            .unwrap());
        assert!(mgr.status().verified);
        let done = mgr.confirm().unwrap();
        assert_eq!(done.endpoint_id, "endpoint-abc");
        assert!(!mgr.status().active);
    }

    #[test]
    fn code_success_and_confirm() {
        let (mgr, _) = setup();
        begin(&mgr);
        assert!(mgr.verify_code("042916").unwrap());
        assert!(mgr.confirm().is_ok());
    }

    #[test]
    fn confirm_without_verification_is_rejected() {
        let (mgr, _) = setup();
        begin(&mgr);
        let err = mgr.confirm().unwrap_err();
        assert_eq!(err.code(), ErrorCode::PairingDenied);
    }

    #[test]
    fn five_failures_lock() {
        let (mgr, _) = setup();
        begin(&mgr);
        for _ in 0..4 {
            assert!(!mgr.verify_code("000000").unwrap());
        }
        let err = mgr.verify_code("000000").unwrap_err();
        assert_eq!(err.code(), ErrorCode::PairingLocked);
        assert!(mgr.status().locked_until.is_some());
        assert_eq!(mgr.status().attempts_remaining, 0);
    }

    #[test]
    fn expiry_clears_active() {
        let (mgr, clock) = setup();
        begin(&mgr);
        clock.advance_secs(601);
        mgr.tick();
        assert!(!mgr.status().active);
        assert_eq!(
            mgr.verify_code("042916").unwrap_err().code(),
            ErrorCode::PairingExpired
        );
    }

    #[test]
    fn local_confirm_allows_pairing() {
        let (mgr, _) = setup();
        begin(&mgr);
        mgr.confirm_local().unwrap();
        assert!(mgr.confirm().is_ok());
    }
}
