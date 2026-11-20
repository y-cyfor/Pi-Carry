//! 可注入时钟与 ID 生成器；测试不 sleep。

use chrono::{DateTime, SecondsFormat, Utc};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

pub trait Clock: Send + Sync + 'static {
    fn now(&self) -> DateTime<Utc>;
}

#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> DateTime<Utc> {
        Utc::now()
    }
}

#[derive(Debug, Clone)]
pub struct FixedClock(Arc<Mutex<DateTime<Utc>>>);

impl FixedClock {
    pub fn new(at: DateTime<Utc>) -> Self {
        Self(Arc::new(Mutex::new(at)))
    }
    pub fn advance_secs(&self, secs: i64) {
        *self.0.lock().expect("clock poisoned") += chrono::Duration::seconds(secs);
    }
    pub fn set(&self, at: DateTime<Utc>) {
        *self.0.lock().expect("clock poisoned") = at;
    }
}

impl Clock for FixedClock {
    fn now(&self) -> DateTime<Utc> {
        *self.0.lock().expect("clock poisoned")
    }
}

pub fn rfc3339(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub trait IdGen: Send + Sync + 'static {
    fn next_id(&self) -> String;
}

#[derive(Debug, Clone, Copy, Default)]
pub struct UlidGen;

impl IdGen for UlidGen {
    fn next_id(&self) -> String {
        ulid::Ulid::new().to_string().to_lowercase()
    }
}

#[derive(Debug, Default)]
pub struct SeqIdGen(AtomicU64);

impl SeqIdGen {
    pub fn new() -> Self {
        Self(AtomicU64::new(1))
    }
}

impl IdGen for SeqIdGen {
    fn next_id(&self) -> String {
        format!("id-{}", self.0.fetch_add(1, Ordering::SeqCst))
    }
}
