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
    /// 失败计数、锁定期、验证进度按「凭据类型」分开：
    /// 二维码 token 与 6 位码是两个独立入口，共用一个计数会互相拖累（P-4）。
    token: Attempts,
    code: Attempts,
}

/// 单一凭据类型的验证进度。
#[derive(Default, Debug, Clone)]
struct Attempts {
    verified: bool,
    failures: u32,
    locked_until: Option<DateTime<Utc>>,
}

/// 凭据种类。两种凭据各自计数，互不影响。
#[derive(Clone, Copy)]
enum AttemptKind {
    Token,
    Code,
}

#[derive(Clone)]
pub struct PairingManager {
    inner: Arc<Mutex<State>>,
    clock: Arc<dyn Clock>,
    ttl_secs: u64,
    max_attempts: u32,
    lock_secs: u64,
    /// 强制本机（托盘）确认：开着时只输对码不够，还必须在电脑上点“允许”。
    /// 共享屏幕/肩窥/二维码被拍照都可能泄露码，本地确认是唯一能绑定“人就在电脑前”的因素。
    require_local_confirm: bool,
}

impl PairingManager {
    pub fn new(clock: Arc<dyn Clock>) -> Self {
        Self::with_policy(clock, false)
    }

    pub fn with_policy(clock: Arc<dyn Clock>, require_local_confirm: bool) -> Self {
        Self {
            inner: Arc::new(Mutex::new(State {
                active: None,
                token: Attempts::default(),
                code: Attempts::default(),
            })),
            clock,
            ttl_secs: pi_carry_protocol::PAIRING_TTL_SEC,
            max_attempts: pi_carry_protocol::PAIRING_MAX_ATTEMPTS,
            lock_secs: pi_carry_protocol::PAIRING_LOCK_SEC,
            require_local_confirm,
        }
    }

    pub fn require_local_confirm(&self) -> bool {
        self.require_local_confirm
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
        self.begin_with_name(endpoint_id, None, relay, addrs, token, code)
    }

    /// 带直连地址与显示名的配对会话。`name` 会写进二维码，手机配对页直接显示，
    /// 否则多台电脑在手机上只能看到 8 位 endpoint id 前缀。
    pub fn begin_with_name(
        &self,
        endpoint_id: &str,
        name: Option<&str>,
        relay: Option<String>,
        addrs: Vec<String>,
        token: String,
        code: String,
    ) -> PairingSession {
        let now = self.clock.now();
        let expires = now + Duration::seconds(self.ttl_secs as i64);
        let display = name
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .or_else(node_display_name);
        let uri = build_uri(
            endpoint_id,
            &token,
            display.as_deref(),
            relay.as_deref(),
            &addrs,
        );
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
        // 新一次配对重置两种凭据的进度与锁定（上一轮的错码不该拖累新一轮）。
        st.token = Attempts::default();
        st.code = Attempts::default();
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
        let locked = st
            .token
            .locked_until
            .into_iter()
            .chain(st.code.locked_until)
            .filter(|t| *t > now)
            .max();
        PairingStatus {
            active: st.active.is_some(),
            verified: st.token.verified || st.code.verified,
            local_confirmed: st.active.as_ref().is_some_and(|a| a.local_confirmed),
            attempts_remaining: self
                .max_attempts
                .saturating_sub(st.code.failures.max(st.token.failures)),
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
        self.verify_credential(token, AttemptKind::Token)
    }

    /// 校验 6 位码。成功仅标记 verified。
    pub fn verify_code(&self, code: &str) -> Result<bool> {
        self.verify_credential(code, AttemptKind::Code)
    }

    /// token 与码走同一套逻辑，只是计数与验证标记分开。
    ///
    /// 锁定期间**先比对再拒绝**：正确凭据总是能过，错的才吃锁定。
    /// 反过来（先看锁再比对）会让旁人敲 5 次错码，把合法用户也锁在门外 600 秒。
    /// 暴力破解仍然被限速：错凭据在锁定期内一律拒绝且不计次数（不再延长锁定）。
    fn verify_credential(&self, provided: &str, kind: AttemptKind) -> Result<bool> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        let Some(active) = st.active.as_ref() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        // 两种凭据都用常量时间比较，避免按响应时间逐位猜测（QUIC 下可利用性低，但不该有例外）。
        let expected = match kind {
            AttemptKind::Token => active.token.clone(),
            AttemptKind::Code => active.code.clone(),
        };
        let matched = constant_time_eq(expected.as_bytes(), provided.as_bytes());
        let locked = match kind {
            AttemptKind::Token => &st.token,
            AttemptKind::Code => &st.code,
        }
        .locked_until
        .is_some_and(|t| t > now);
        if !matched {
            if locked {
                // 锁定期内的错误尝试不计数：否则一次锁定会自我延长成永久封锁。
                // 取两种凭据里较晚的解锁时间：提示的时间必须不早于真实解锁时间，
                // 否则用户照着提示重试仍然会被拒（与 `status()` 的口径一致）。
                let until = st
                    .token
                    .locked_until
                    .filter(|t| *t > now)
                    .max(st.code.locked_until.filter(|t| *t > now))
                    .unwrap_or(now);
                return Err(pairing_err(
                    ErrorCode::PairingLocked,
                    format!("配对已锁定，请在 {} 后重试", crate::rfc3339(until)),
                ));
            }
            self.fail_attempt(&mut st, kind, now)?;
            return Ok(false);
        }
        // 正确凭据：清锁、清计数、标记已验证。
        let slot = match kind {
            AttemptKind::Token => &mut st.token,
            AttemptKind::Code => &mut st.code,
        };
        slot.verified = true;
        slot.failures = 0;
        slot.locked_until = None;
        Ok(true)
    }

    /// 锁定截止时间，供诊断与提示使用（两种凭据取较晚者）。
    ///
    /// 必须用 `max` 而不是 `or`：`or` 是「先 Some 先赢」，token 锁得更早时
    /// 会报出比 code 的真实解锁时间更早的时刻，用户按提示重试仍被拒。
    pub fn locked_deadline(&self) -> Option<DateTime<Utc>> {
        let st = self.inner.lock().expect("pairing poisoned");
        let now = self.clock.now();
        st.token
            .locked_until
            .filter(|t| *t > now)
            .max(st.code.locked_until.filter(|t| *t > now))
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

    /// 最终确认配对。
    ///
    /// 默认策略：验证过 token/码**或**本机确认即可（二维码只在电脑屏幕上显示，
    /// 能在 10 分钟内看到屏幕本身就等于物理在场）。开启 `requireLocalConfirm` 后
    /// 必须两者都满足：适用于常驻共享桌面/投屏/远程录制等二维码可能被第三方看到的场景。
    ///
    /// 两个分支都要求「已验证」且（必要时）「已本机确认」：`localConfirmed` 是辅助信号，
    /// **不能单独抵掉凭据校验**，否则任何能触发本机确认的入口都成了绕过 6 位码的后门（NEW-5）。
    pub fn confirm(&self) -> Result<PairingSession> {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        let Some(active) = st.active.as_ref() else {
            return Err(pairing_err(ErrorCode::PairingExpired, "没有进行中的配对"));
        };
        if self.is_expired(active, now) {
            st.active = None;
            return Err(pairing_err(ErrorCode::PairingExpired, "配对已过期"));
        }
        let local_confirmed = active.local_confirmed;
        let verified = st.token.verified || st.code.verified;
        if self.require_local_confirm {
            // 严格模式：两个条件都必须满足——“码对了”不代表“人就在电脑前”，
            // 反之“点了本机确认”也不代表“手机拿到了码”（NEW-5）。
            if !local_confirmed {
                return Err(pairing_err(
                    ErrorCode::PairingDenied,
                    "请先在电脑托盘里确认本次配对",
                ));
            }
            if !verified {
                return Err(pairing_err(ErrorCode::PairingDenied, "尚未验证配对码"));
            }
        } else if !verified && !local_confirmed {
            return Err(pairing_err(ErrorCode::PairingDenied, "尚未验证配对码"));
        }
        let done = active.clone();
        st.active = None;
        st.token = Attempts::default();
        st.code = Attempts::default();
        Ok(done)
    }

    pub fn cancel(&self) {
        let mut st = self.inner.lock().expect("pairing poisoned");
        st.active = None;
        st.token = Attempts::default();
        st.code = Attempts::default();
    }

    /// 供测试推进时钟后清理。
    pub fn tick(&self) {
        let now = self.clock.now();
        let mut st = self.inner.lock().expect("pairing poisoned");
        if st.active.as_ref().is_some_and(|a| self.is_expired(a, now)) {
            st.active = None;
        }
        Self::expire_locks(&mut st.token, now);
        Self::expire_locks(&mut st.code, now);
    }

    /// 锁定到期后清零计数；分方法而非循环，避免同一 struct 的重复可变借用。
    fn expire_locks(a: &mut Attempts, now: DateTime<Utc>) {
        if a.locked_until.is_some_and(|t| t <= now) {
            a.locked_until = None;
            a.failures = 0;
        }
    }

    fn is_expired(&self, a: &PairingSession, now: DateTime<Utc>) -> bool {
        a.expires_at
            .parse::<DateTime<Utc>>()
            .map(|t| t <= now)
            .unwrap_or(true)
    }

    /// 记号一次失败；达到上限时锁定并返回 `PairingLocked`。
    /// 只在「比对失败」的分支调用，见 [`Self::verify_credential`]。
    fn fail_attempt(&self, st: &mut State, kind: AttemptKind, now: DateTime<Utc>) -> Result<()> {
        let slot = match kind {
            AttemptKind::Token => &mut st.token,
            AttemptKind::Code => &mut st.code,
        };
        slot.failures += 1;
        if slot.failures >= self.max_attempts {
            // 只锁定不销毁：保留 active，用户在锁定窗口结束后还能输入正确码
            // （以前直接 active=None，只能重新开始配对）。
            slot.locked_until = Some(now + Duration::seconds(self.lock_secs as i64));
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

fn build_uri(
    endpoint_id: &str,
    token: &str,
    name: Option<&str>,
    relay: Option<&str>,
    addrs: &[String],
) -> String {
    let mut uri = format!(
        "pi-carry://pair?v=1&endpoint={}&token={}",
        encode_component(endpoint_id),
        encode_component(token)
    );
    // 手机配对页显示节点名：没有 name 时只能显示 Endpoint ID 前 8 位，多台机器分不清。
    if let Some(name) = name.map(str::trim).filter(|s| !s.is_empty()) {
        uri.push_str("&name=");
        uri.push_str(&encode_component(name));
    }
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

/// 二维码里的节点名。空名不写进 URI，避免手机端显示一个空白节点。
/// 与配置里的 `node.name` 同一个默认源（主机名），两边不会出现两个名字。
fn node_display_name() -> Option<String> {
    std::env::var("PI_CARRY_NODE_NAME")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .or_else(|| Some(crate::config::default_hostname()))
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

    /// 二维码不带 name 时，手机只能显示 endpoint id 前缀，多台机器分不清。
    #[test]
    fn uri_carries_display_name_and_percent_encodes_it() {
        let (mgr, _) = setup();
        let s = mgr.begin_with_name(
            "endpoint-abc",
            Some("我的 工作站"),
            None,
            Vec::new(),
            "deadbeefdeadbeefdeadbeefdeadbeef".into(),
            "042916".into(),
        );
        // 空格与中文都必须编码，否则 Uri.parse 会把它当成非法字符。
        assert!(
            s.uri
                .contains("name=%E6%88%91%E7%9A%84%20%E5%B7%A5%E4%BD%9C%E7%AB%99"),
            "{}",
            s.uri
        );

        // 空名不写入 URI，并用主机名兜底（与配置默认名同源）。
        let s = mgr.begin_with_name(
            "endpoint-abc",
            Some("   "),
            None,
            Vec::new(),
            "cafebabecafebabecafebabecafebabe".into(),
            "123456".into(),
        );
        assert!(s.uri.contains("name="), "{}", s.uri);
        assert!(!s.uri.contains("name=\u{3000}"));
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
        // 锁定不能销毁配对会话，否则用户只能重新开始配对。
        assert!(mgr.status().active, "锁定时必须保留 active");
    }

    /// P-4：锁定期内必须区分「正确凭据」与「错误凭据」。
    /// 正确凭据放行（否则旁人敲 5 次错码就能把合法用户锁在门外 10 分钟），
    /// 错误凭据回 LOCKED 且会话不销毁。
    #[test]
    fn lock_period_still_accepts_correct_code_but_rejects_wrong_one() {
        let (mgr, clock) = setup();
        begin(&mgr);
        for _ in 0..5 {
            let _ = mgr.verify_code("000000");
        }
        // 错误码：LOCKED（而不是 EXPIRED），且配对会话仍在。
        assert_eq!(
            mgr.verify_code("999999").unwrap_err().code(),
            ErrorCode::PairingLocked
        );
        assert!(mgr.status().active, "锁定期间配对会话不得被销毁");
        // 正确码：放行。
        assert!(mgr.verify_code("042916").unwrap());
        assert!(mgr.status().locked_until.is_none(), "验证成功后应清锁");

        // 锁定到期后所有凭据恢复正常。
        clock.advance_secs(300);
        mgr.tick();
        assert!(mgr.status().active, "锁定期间配对会话不得被销毁");
    }

    /// P-04：两种凭据同时被锁时，提示的解锁时间必须取**较晚**者。
    /// 旧实现用 `.or(...)`（先 Some 先赢），token 锁得更早时会报出比 code 的
    /// 真实解锁时间更早的时刻，用户照着提示重试仍会被拒。
    #[test]
    fn locked_deadline_takes_the_later_of_two_credentials() {
        let (mgr, clock) = setup();
        begin(&mgr);
        let now = clock.now();
        let t1 = now + Duration::seconds(60);
        let t2 = now + Duration::seconds(600);
        {
            let mut st = mgr.inner.lock().unwrap();
            st.token.locked_until = Some(t1);
            st.code.locked_until = Some(t2);
        }
        assert_eq!(mgr.locked_deadline(), Some(t2));

        // 错误凭据的错误文案走另一处 `.or`，口径必须一致：报较晚的 t2。
        let err = mgr.verify_code("000000").unwrap_err();
        assert_eq!(err.code(), ErrorCode::PairingLocked);
        assert!(
            err.to_string().contains(&crate::rfc3339(t2)),
            "锁定提示应报较晚的解锁时间 {}，实际 {err}",
            crate::rfc3339(t2)
        );
    }

    /// token 与 code 的失败计数必须互不影响：否则错一次 token 就抬高了
    /// 码的难度（反之亦然），并且「5 次」的本意是单种凭据 5 次。
    #[test]
    fn token_and_code_failures_are_counted_separately() {
        let (mgr, _) = setup();
        begin(&mgr);
        for _ in 0..4 {
            assert!(!mgr
                .verify_token("00000000000000000000000000000000")
                .unwrap());
        }
        // token 已用 4 次，码的 5 次配额未被消耗：正确码仍能一次通过。
        assert!(mgr.verify_code("042916").unwrap());
        assert!(mgr.status().verified);
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

#[cfg(test)]
mod local_confirm_tests {
    use super::*;
    use crate::clock::FixedClock;

    fn mgr(require_local: bool) -> (PairingManager, FixedClock) {
        let clock = FixedClock::new(Utc::now());
        (
            PairingManager::with_policy(Arc::new(clock.clone()), require_local),
            clock,
        )
    }

    #[test]
    fn default_policy_accepts_verified_token() {
        let (p, _c) = mgr(false);
        let s = p.begin("node-1", None);
        assert!(p.verify_token(&s.token).unwrap());
        assert!(p.confirm().is_ok());
    }

    #[test]
    fn require_local_confirm_rejects_token_only() {
        // 共享屏幕/投屏时二维码可能被第三方看到：只输对码不算数。
        let (p, _c) = mgr(true);
        let s = p.begin("node-1", None);
        assert!(p.verify_token(&s.token).unwrap());
        let err = p.confirm().unwrap_err().to_string();
        assert!(
            err.contains("电脑托盘里确认"),
            "错误应指明缺少本机确认: {err}"
        );
        // 本机确认后放行
        p.confirm_local().unwrap();
        assert!(p.confirm().is_ok());
    }

    #[test]
    fn require_local_confirm_without_any_verification_is_denied() {
        let (p, _c) = mgr(true);
        p.begin("node-1", None);
        assert!(p.confirm().is_err());
    }

    /// NEW-5：严格模式下「只点了本机确认、没验证过凭据」也必须拒绝。
    /// 否则任何能触发本机确认的入口（未来新增的快捷键/托盘菜单/命令行）
    /// 都成了绕过 6 位码的后门。
    #[test]
    fn require_local_confirm_still_needs_a_verified_credential() {
        let (p, _c) = mgr(true);
        p.begin("node-1", None);
        p.confirm_local().unwrap();
        let err = p.confirm().unwrap_err().to_string();
        assert!(err.contains("尚未验证配对码"), "实际错误: {err}");
        // 补上凭据后放行。
        let s = p.active().expect("配对会话应仍在");
        assert!(p.verify_code(&s.code).unwrap());
        assert!(p.confirm().is_ok());
    }
}
