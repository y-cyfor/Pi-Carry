//! 内容寻址的图片 blob 库。
//!
//! 图片不再内联进 1 MiB 控制帧，而是按 sha256 分片上传到 `~/.pi-carry/blobs`：
//! - 上传先写 `<sha>.part`，收齐后校验 sha256，再原子改名为 `<sha>`；
//! - `blob_refs` 记录「哪个会话引用了它」，会话关闭时按引用计数清理；
//! - 读取必须带会话引用，避免一个会话拿到另一个会话的私有图片。

use crate::error::{CoreError, Result};
use crate::store::Store;
use base64::Engine;
use pi_carry_protocol::{BLOB_CHUNK_BYTES, MAX_IMAGE_BYTES, MAX_SESSION_BLOB_BYTES};
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// pi 支持的图片类型；上传时校验，避免 pi 在 run 中才报错。
pub const ALLOWED_IMAGE_MIMES: &[&str] = &["image/png", "image/jpeg", "image/webp", "image/gif"];

#[derive(Clone)]
pub struct BlobStore {
    root: PathBuf,
    store: Store,
    /// 分片写入串行化：同一 sha 的并发上传会互相覆盖 part 文件。
    /// 图片上传不是热路径，串行足够，也避免引入更复杂的按 sha 锁表。
    lock: Arc<Mutex<()>>,
    /// 单会话上限。生产固定为协议常量；测试可调小以覆盖边界。
    limit_bytes: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BlobUsage {
    pub used_bytes: u64,
    pub count: u64,
    pub limit_bytes: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BlobStat {
    pub received: u64,
    pub complete: bool,
    pub size: u64,
    pub used_bytes: u64,
    pub limit_bytes: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BlobPutResult {
    pub received: u64,
    pub complete: bool,
    pub size: u64,
    pub used_bytes: u64,
    pub limit_bytes: u64,
}

impl BlobStore {
    pub fn new(root: PathBuf, store: Store) -> Self {
        Self {
            root,
            store,
            lock: Arc::new(Mutex::new(())),
            limit_bytes: MAX_SESSION_BLOB_BYTES,
        }
    }

    fn ensure_root(&self) -> Result<()> {
        fs::create_dir_all(&self.root).map_err(|e| {
            CoreError::Internal(format!("创建 blob 目录 {} 失败: {e}", self.root.display()))
        })
    }

    #[cfg(test)]
    fn with_limit(mut self, limit: u64) -> Self {
        self.limit_bytes = limit;
        self
    }

    fn final_path(&self, sha: &str) -> PathBuf {
        self.root.join(sha)
    }

    /// 未完成分片按会话隔离：以前所有会话共用一个 `<sha>.part`，
    /// 既不进 usage 也不受 cleanup 管理，匿名上传可以无限占盘（B-19）。
    fn session_dir(&self, session_id: &str) -> PathBuf {
        // 会话 id 只允许简单字符，避免 ../ 之类的路径注入。
        let safe: String = session_id
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        self.root.join(format!("parts-{safe}"))
    }

    fn part_path(&self, session_id: &str, sha: &str) -> PathBuf {
        self.session_dir(session_id).join(format!("{sha}.part"))
    }

    fn part_bytes(&self, session_id: &str) -> u64 {
        let Ok(entries) = fs::read_dir(self.session_dir(session_id)) else {
            return 0;
        };
        entries
            .filter_map(|e| e.ok())
            // 分片和收编临时文件都占用磁盘：都要计入预算，崩溃残留才会被
            // usage 暴露，而不是无声占盘（R-03/R-10）。
            .filter(|e| {
                let name = e.file_name().to_string_lossy().into_owned();
                name.ends_with(".part") || name.contains(".adopt-")
            })
            .filter_map(|e| e.metadata().ok())
            .filter(|m| m.is_file())
            .map(|m| m.len())
            .sum()
    }

    /// 收编前清掉进程崩溃留下的旧临时文件（超过 1 小时）。
    /// 并发收编通常只需数秒，不会被误删；当前进程的临时文件也不会清。
    fn cleanup_stale_adopt(&self, session_id: &str) {
        let Ok(entries) = fs::read_dir(self.session_dir(session_id)) else {
            return;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.contains(".adopt-") {
                continue;
            }
            let stale = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.elapsed().ok())
                .is_some_and(|age| age > Duration::from_secs(3600));
            if stale {
                let _ = fs::remove_file(entry.path());
            }
        }
    }

    /// 新 blob 的预算检查：本会话已完成引用 + 其它分片字节 + 本次完整大小。
    fn check_budget(&self, session_id: &str, sha: &str, total_size: u64) -> Result<()> {
        let (completed, _) = self.store.session_blob_usage(session_id)?;
        let part = fs::metadata(self.part_path(session_id, sha))
            .map(|m| m.len())
            .unwrap_or(0);
        let used = completed
            .saturating_add(self.part_bytes(session_id))
            .saturating_sub(part);
        if used.saturating_add(total_size) > self.limit_bytes {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::PayloadTooLarge,
                format!(
                    "会话图片空间不足：已用 {:.1} MiB，本次 {:.1} MiB，上限 {} MiB",
                    used as f64 / (1024.0 * 1024.0),
                    total_size as f64 / (1024.0 * 1024.0),
                    self.limit_bytes / (1024 * 1024)
                ),
            )));
        }
        Ok(())
    }

    /// 磁盘满时给出明确原因，而不是把 OS 的 “No space left” 藏在 IO 错误里。
    fn write_error(e: std::io::Error) -> CoreError {
        // 28=Unix ENOSPC，112=Windows ERROR_DISK_FULL，39=ERROR_HANDLE_DISK_FULL。
        let disk_full = e.kind() == std::io::ErrorKind::StorageFull
            || matches!(e.raw_os_error(), Some(28 | 112 | 39));
        if disk_full {
            CoreError::Internal(format!("磁盘空间不足，无法写入图片: {e}"))
        } else {
            CoreError::Internal(format!("写入图片分片失败: {e}"))
        }
    }

    fn validate_sha(sha: &str) -> Result<()> {
        if sha.len() != 64
            || !sha
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(CoreError::InvalidArgument(
                "sha256 必须是 64 位小写十六进制".into(),
            ));
        }
        Ok(())
    }

    fn validate_mime(mime: &str) -> Result<()> {
        if !ALLOWED_IMAGE_MIMES.contains(&mime) {
            return Err(CoreError::InvalidArgument(format!(
                "不支持的图片类型 {mime}（png/jpeg/webp/gif）"
            )));
        }
        Ok(())
    }

    pub fn usage(&self, session_id: &str) -> Result<BlobUsage> {
        let (completed, count) = self.store.session_blob_usage(session_id)?;
        // 未完成分片也占磁盘，必须计入预算，否则并发/中断上传可以绕过 100 MiB。
        let used_bytes = completed.saturating_add(self.part_bytes(session_id));
        Ok(BlobUsage {
            used_bytes,
            count,
            limit_bytes: self.limit_bytes,
        })
    }

    pub fn stat(&self, session_id: &str, sha: &str) -> Result<BlobStat> {
        Self::validate_sha(sha)?;
        let usage = self.usage(session_id)?;
        let final_path = self.final_path(sha);
        let meta = self.store.blob_meta(sha)?;
        // 只有“内容已完成且本会话已有引用”才算 complete。若只按文件存在判断，
        // 客户端会跳过上传，导致消息里的 blobId 在服务端没有引用，发送时
        // read_image 直接报权限错误（B-31）。无引用时返回 0，让 put 重放并补引用。
        let complete =
            final_path.is_file() && meta.is_some() && self.store.has_blob_ref(sha, session_id)?;
        let size = match meta {
            Some((size, _)) => size,
            None => fs::metadata(&final_path).map(|m| m.len()).unwrap_or(0),
        };
        let received = if complete {
            size
        } else {
            fs::metadata(self.part_path(session_id, sha))
                .map(|m| m.len())
                .unwrap_or(0)
        };
        Ok(BlobStat {
            received,
            complete,
            size,
            used_bytes: usage.used_bytes,
            limit_bytes: usage.limit_bytes,
        })
    }

    /// 追加一个分片。`offset` 必须等于服务端已收字节数（断点续传靠 `blob.stat` 对齐）；
    /// 最后一个分片收齐后校验 sha256，失败会删掉 part 文件并要求客户端重传。
    pub fn put(
        &self,
        session_id: &str,
        sha: &str,
        mime: &str,
        total_size: u64,
        offset: u64,
        data_b64: &str,
    ) -> Result<BlobPutResult> {
        Self::validate_sha(sha)?;
        Self::validate_mime(mime)?;
        if total_size == 0 || total_size > MAX_IMAGE_BYTES as u64 {
            return Err(CoreError::InvalidArgument(format!(
                "单张图片约 {} MiB，超过单图上限 {} MiB",
                total_size.div_ceil(1024 * 1024),
                MAX_IMAGE_BYTES / (1024 * 1024)
            )));
        }
        let chunk = base64::engine::general_purpose::STANDARD
            .decode(data_b64)
            .map_err(|e| CoreError::InvalidArgument(format!("图片分片不是合法 base64: {e}")))?;
        if chunk.is_empty() {
            return Err(CoreError::InvalidArgument("图片分片不能为空".into()));
        }
        if chunk.len() > BLOB_CHUNK_BYTES {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::PayloadTooLarge,
                format!(
                    "单个分片 {} 字节，超过上限 {BLOB_CHUNK_BYTES} 字节",
                    chunk.len()
                ),
            )));
        }
        if offset.saturating_add(chunk.len() as u64) > total_size {
            return Err(CoreError::InvalidArgument(format!(
                "分片越界：offset {offset} + {} 超过总大小 {total_size}",
                chunk.len()
            )));
        }

        self.ensure_root()?;
        let _guard = self.lock.lock().expect("blob lock poisoned");
        let final_path = self.final_path(sha);
        // 已经上传完成的 blob：幂等返回前必须确认声明的总大小/类型与内容一致，
        // 否则客户端可以用别的 mime 或截断的 totalSize 复用同一个 sha（B-23）。
        if final_path.is_file() {
            if let Some((size, stored_mime)) = self.store.blob_meta(sha)? {
                if size != total_size {
                    return Err(CoreError::InvalidArgument(format!(
                        "图片已存在，大小 {size} 与声明的 {total_size} 不一致"
                    )));
                }
                if stored_mime != mime {
                    return Err(CoreError::InvalidArgument(format!(
                        "图片已存在，类型 {stored_mime} 与声明的 {mime} 不一致"
                    )));
                }
                if !self.store.has_blob_ref(sha, session_id)? {
                    self.check_budget(session_id, sha, total_size)?;
                    let now = crate::rfc3339(chrono::Utc::now());
                    self.store
                        .finalize_blob(sha, total_size, mime, &now, session_id)?;
                }
                let usage = self.usage(session_id)?;
                return Ok(BlobPutResult {
                    received: size,
                    complete: true,
                    size,
                    used_bytes: usage.used_bytes,
                    limit_bytes: usage.limit_bytes,
                });
            }
        }

        let part_path = self.part_path(session_id, sha);
        if let Some(parent) = part_path.parent() {
            fs::create_dir_all(parent).map_err(Self::write_error)?;
        }
        if offset == 0 {
            // 重新上传从头开始：清掉可能残留的半截文件。
            let _ = fs::remove_file(&part_path);
        } else {
            let received = fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);
            if received != offset {
                return Err(CoreError::InvalidArgument(format!(
                    "上传偏移不连续：服务端已收 {received} 字节，客户端从 {offset} 开始；请先 blob.stat"
                )));
            }
        }

        // 预算检查放在写盘前：新 blob 才占用额度，重传统一 sha 不重复计费。
        // usage 已包含本会话的 part 字节，先扣掉当前 part，再按完整大小计。
        if !self.store.has_blob_ref(sha, session_id)? {
            self.check_budget(session_id, sha, total_size)?;
        }

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&part_path)
            .map_err(Self::write_error)?;
        file.write_all(&chunk).map_err(Self::write_error)?;
        file.flush().map_err(Self::write_error)?;
        drop(file);

        let received = fs::metadata(&part_path)?.len();
        if received > total_size {
            let _ = fs::remove_file(&part_path);
            return Err(CoreError::InvalidArgument(format!(
                "图片分片合计超过声明大小 {total_size}"
            )));
        }
        if received < total_size {
            let usage = self.usage(session_id)?;
            return Ok(BlobPutResult {
                received,
                complete: false,
                size: total_size,
                used_bytes: usage.used_bytes,
                limit_bytes: usage.limit_bytes,
            });
        }

        // 收齐后把 20 MiB 级的校验收出锁外：std Mutex 里做整文件哈希会阻塞
        // 同一进程所有 blob 操作（B-26）。哈希期间不会再有合法分片追加，
        // 但可能被并发的“offset=0 重传”截断，所以重新加锁后要再核对大小。
        drop(_guard);
        let actual = sha256_file(&part_path)?;
        // 崩溃恢复分支也可能要对已存在的 final 做整文件哈希：先在锁外算好，
        // 回来后再用数据库状态复核，避免持锁做 20 MiB 哈希（R-11）。
        let stale_final_hash = if final_path.exists() && self.store.blob_meta(sha)?.is_none() {
            Some(sha256_file(&final_path)?)
        } else {
            None
        };
        let _guard = self.lock.lock().expect("blob lock poisoned");
        let received = fs::metadata(&part_path).map(|m| m.len()).unwrap_or(0);
        if received != total_size {
            return Err(CoreError::InvalidArgument(
                "图片在收尾校验期间被重传，请重试".into(),
            ));
        }
        if actual != sha {
            let _ = fs::remove_file(&part_path);
            return Err(CoreError::InvalidArgument(format!(
                "图片校验失败：声明 sha256 {sha}，实际 {actual}"
            )));
        }
        if final_path.exists() {
            // 文件已存在但数据库没有元数据：可能是崩溃在 rename 与 finalize 之间，
            // 也可能是有人把坏文件按 sha 命名丢进来。必须按内容核对后再复用（N-12）。
            if self.store.blob_meta(sha)?.is_none() {
                let ok = match stale_final_hash.as_deref() {
                    Some(hash) => hash == sha,
                    // 只有 final 在锁外哈希后被并发替换时才会走到这里。
                    None => sha256_file(&final_path)? == sha,
                };
                if !ok {
                    fs::remove_file(&final_path).map_err(Self::write_error)?;
                    fs::rename(&part_path, &final_path).map_err(Self::write_error)?;
                } else {
                    let _ = fs::remove_file(&part_path);
                }
            } else {
                let _ = fs::remove_file(&part_path);
            }
        } else {
            fs::rename(&part_path, &final_path).map_err(Self::write_error)?;
        }
        let now = crate::rfc3339(chrono::Utc::now());
        self.store
            .finalize_blob(sha, total_size, mime, &now, session_id)?;
        let usage = self.usage(session_id)?;
        Ok(BlobPutResult {
            received: total_size,
            complete: true,
            size: total_size,
            used_bytes: usage.used_bytes,
            limit_bytes: usage.limit_bytes,
        })
    }

    /// 把历史消息里已有的 base64 图片收编进 blob 库。
    ///
    /// 0.1.x 的图片没有走过 blob 上传，快照若继续内联 base64 会撑爆 1 MiB 控制帧；
    /// 收编后只投影 blobId，pi 的 jsonl 本身不改写。预算不足或类型不支持时返回错误，
    /// 调用方保留原 base64（宁可这一张图不投影，也不能改坏历史）。
    pub fn adopt(&self, session_id: &str, mime: &str, bytes: &[u8]) -> Result<String> {
        Self::validate_mime(mime)?;
        if bytes.is_empty() || bytes.len() > MAX_IMAGE_BYTES {
            return Err(CoreError::InvalidArgument(format!(
                "单张图片 {} 字节，超过单图上限 {} 字节",
                bytes.len(),
                MAX_IMAGE_BYTES
            )));
        }
        let sha = hex::encode(sha2::Sha256::digest(bytes));
        let size = bytes.len() as u64;
        {
            let _guard = self.lock.lock().expect("blob lock poisoned");
            if self.store.has_blob_ref(&sha, session_id)? {
                return Ok(sha);
            }
            self.check_budget(session_id, &sha, size)?;
        }
        self.ensure_root()?;
        self.cleanup_stale_adopt(session_id);
        let final_path = self.final_path(&sha);
        // 写临时文件放在锁外：历史图可能有 20 MiB，整段写盘不应阻塞其它 blob 操作。
        // 文件名带进程内自增序号，避免同一会话并发收编同一 sha 时互相截断。
        static ADOPT_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let seq = ADOPT_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let part_path = self
            .session_dir(session_id)
            .join(format!("{sha}.adopt-{seq}"));
        if let Some(parent) = part_path.parent() {
            fs::create_dir_all(parent).map_err(Self::write_error)?;
        }
        if let Err(e) = fs::write(&part_path, bytes) {
            let _ = fs::remove_file(&part_path);
            return Err(Self::write_error(e));
        }
        let _guard = self.lock.lock().expect("blob lock poisoned");
        // 写临时文件期间可能有并发收编：最终化前按磁盘真实占用再查一次预算。
        // 此时 part_bytes 已包含本次临时文件，所以 total_size 传 0，避免重复计算。
        if let Err(e) = self.check_budget(session_id, "", 0) {
            let _ = fs::remove_file(&part_path);
            return Err(e);
        }
        if final_path.exists() {
            let _ = fs::remove_file(&part_path);
        } else if let Err(e) = fs::rename(&part_path, &final_path) {
            let _ = fs::remove_file(&part_path);
            return Err(Self::write_error(e));
        }
        let now = crate::rfc3339(chrono::Utc::now());
        self.store
            .finalize_blob(&sha, size, mime, &now, session_id)?;
        Ok(sha)
    }

    /// 分片读取。`length == 0` 表示读到本片上限；响应 base64 后仍在 1 MiB 帧内。
    /// 返回 `(bytes, total_size)`。
    pub fn get(
        &self,
        session_id: &str,
        sha: &str,
        offset: u64,
        length: usize,
    ) -> Result<(Vec<u8>, u64)> {
        Self::validate_sha(sha)?;
        // 与 cleanup 共用同一把锁：不能在读取过程中被删掉文件（B-25）。
        let _guard = self.lock.lock().expect("blob lock poisoned");
        if !self.store.has_blob_ref(sha, session_id)? {
            return Err(CoreError::PermissionDenied(format!(
                "会话 {session_id} 未引用图片 {sha}"
            )));
        }
        let path = self.final_path(sha);
        let total = fs::metadata(&path)
            .map_err(|_| CoreError::InvalidArgument(format!("图片 {sha} 尚未上传完成")))?
            .len();
        if offset > total {
            return Err(CoreError::InvalidArgument(format!(
                "读取偏移 {offset} 超过图片大小 {total}"
            )));
        }
        let want = if length == 0 {
            BLOB_CHUNK_BYTES
        } else {
            length.min(BLOB_CHUNK_BYTES)
        };
        let remaining = usize::try_from(total - offset)
            .map_err(|_| CoreError::InvalidArgument(format!("读取偏移 {offset} 超出可寻址范围")))?;
        let want = want.min(remaining);
        let mut file = File::open(&path)?;
        file.seek(SeekFrom::Start(offset))?;
        let mut buf = vec![0u8; want];
        file.read_exact(&mut buf)?;
        Ok((buf, total))
    }

    /// 读取完整图片（服务端转发给 pi 时使用）。
    pub fn read_image(&self, session_id: &str, sha: &str) -> Result<(Vec<u8>, String)> {
        Self::validate_sha(sha)?;
        // 锁内只做引用/元数据检查和 open：句柄打开后即使 cleanup 删掉目录项，
        // 本次读取仍然有效；20 MiB 的读盘放到锁外，避免阻塞其它 blob 操作（R-09）。
        let (mut file, size, mime) = {
            let _guard = self.lock.lock().expect("blob lock poisoned");
            if !self.store.has_blob_ref(sha, session_id)? {
                return Err(CoreError::PermissionDenied(format!(
                    "会话 {session_id} 未引用图片 {sha}"
                )));
            }
            let (size, mime) = self
                .store
                .blob_meta(sha)?
                .ok_or_else(|| CoreError::InvalidArgument(format!("图片 {sha} 尚未上传完成")))?;
            if size > MAX_IMAGE_BYTES as u64 {
                return Err(CoreError::InvalidArgument(format!(
                    "图片 {sha} 超过单图上限"
                )));
            }
            let file = File::open(self.final_path(sha))?;
            (file, size, mime)
        };
        let mut bytes = Vec::with_capacity(size as usize);
        file.read_to_end(&mut bytes)?;
        // 元数据说 20 MiB、文件却只有 1 MiB 时，转给 pi 的是一张坏图；
        // 这里按真实文件长度复核，避免数据库与磁盘不一致被静默接受（B-27）。
        if bytes.len() as u64 != size {
            return Err(CoreError::Internal(format!(
                "图片 {sha} 文件长度 {} 与元数据 {size} 不一致",
                bytes.len()
            )));
        }
        Ok((bytes, mime))
    }

    pub fn copy_session_refs(&self, from_session: &str, to_session: &str) -> Result<()> {
        // 与 cleanup 共用锁，避免复制引用期间孤儿文件被删（R-18）。
        let _guard = self.lock.lock().expect("blob lock poisoned");
        self.store.copy_blob_refs(from_session, to_session)
    }

    /// 释放会话对一个尚未发送图片的引用；无其它会话引用时删除文件。
    /// 用于移除待发图/上传失败清理，避免 100 MiB 预算被永久占用（B-16）。
    pub fn release(&self, session_id: &str, sha: &str) -> Result<bool> {
        Self::validate_sha(sha)?;
        let _guard = self.lock.lock().expect("blob lock poisoned");
        let orphan = self.store.release_blob_ref(sha, session_id)?;
        let _ = fs::remove_file(self.part_path(session_id, sha));
        if orphan {
            // 文件删不掉时不能对客户端谎称“已删除”：保留 false，交给后续
            // put/cleanup 或人工清理，并留下可排查日志（R-01）。
            match fs::remove_file(self.final_path(sha)) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => {
                    tracing::warn!(sha, error = %e, "释放无引用 blob 时删除文件失败");
                    return Ok(false);
                }
            }
        }
        Ok(orphan)
    }

    pub fn cleanup_session(&self, session_id: &str) -> Result<usize> {
        // DB 引用与文件删除必须在同一把锁内：否则刚清完引用、新会话又引用
        // 同一 sha 的窗口里，文件会被误删（B-25）。
        let _guard = self.lock.lock().expect("blob lock poisoned");
        let orphaned = self.store.clear_blob_refs(session_id)?;
        let mut removed = 0;
        let mut first_error: Option<String> = None;
        for sha in orphaned {
            match fs::remove_file(self.final_path(&sha)) {
                Ok(()) => removed += 1,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => {
                    // 一个文件删不掉不能阻止其余引用/分片清理，否则残留会越积越多（N-04）。
                    first_error.get_or_insert_with(|| format!("删除图片文件 {sha} 失败: {e}"));
                }
            }
        }
        // 未完成分片整体删除：它不属于任何已完成 blob，不能留给下一次会话。
        if let Err(e) = fs::remove_dir_all(self.session_dir(session_id)) {
            if e.kind() != std::io::ErrorKind::NotFound {
                first_error.get_or_insert_with(|| format!("删除会话分片目录失败: {e}"));
            }
        }
        if let Some(e) = first_error {
            return Err(CoreError::Internal(e));
        }
        Ok(removed)
    }

    /// 快照投影用：blob 是否存在（不校验会话引用，引用关系由调用方另查）。
    pub fn meta(&self, sha: &str) -> Result<Option<(u64, String)>> {
        self.store.blob_meta(sha)
    }

    pub fn exists_for_session(&self, sha: &str, session_id: &str) -> Result<bool> {
        Ok(self.final_path(sha).is_file() && self.store.has_blob_ref(sha, session_id)?)
    }
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    fn store() -> Store {
        Store::open_memory().unwrap()
    }

    fn temp_blob() -> (tempfile::TempDir, BlobStore) {
        let dir = tempfile::tempdir().unwrap();
        let blobs = BlobStore::new(dir.path().join("blobs"), store());
        (dir, blobs)
    }

    fn sha_of(bytes: &[u8]) -> String {
        hex::encode(Sha256::digest(bytes))
    }

    fn b64(bytes: &[u8]) -> String {
        base64::engine::general_purpose::STANDARD.encode(bytes)
    }

    #[test]
    fn chunked_upload_get_and_usage() {
        let (_dir, blobs) = temp_blob();
        let bytes = vec![7u8; BLOB_CHUNK_BYTES + 123];
        let sha = sha_of(&bytes);
        let first = blobs
            .put(
                "s1",
                &sha,
                "image/png",
                bytes.len() as u64,
                0,
                &b64(&bytes[..BLOB_CHUNK_BYTES]),
            )
            .unwrap();
        assert!(!first.complete);
        assert_eq!(first.received, BLOB_CHUNK_BYTES as u64);
        let stat = blobs.stat("s1", &sha).unwrap();
        assert_eq!(stat.received, BLOB_CHUNK_BYTES as u64);
        let second = blobs
            .put(
                "s1",
                &sha,
                "image/png",
                bytes.len() as u64,
                BLOB_CHUNK_BYTES as u64,
                &b64(&bytes[BLOB_CHUNK_BYTES..]),
            )
            .unwrap();
        assert!(second.complete);
        assert_eq!(second.used_bytes, bytes.len() as u64);
        let mut got = Vec::new();
        loop {
            let (chunk, total) = blobs.get("s1", &sha, got.len() as u64, 0).unwrap();
            assert_eq!(total, bytes.len() as u64);
            if chunk.is_empty() {
                break;
            }
            got.extend_from_slice(&chunk);
        }
        assert_eq!(got, bytes);
        // 其他会话没有引用，不能读。
        assert!(matches!(
            blobs.get("s2", &sha, 0, 0),
            Err(CoreError::PermissionDenied(_))
        ));
    }

    #[test]
    fn stat_requires_this_session_reference() {
        let (_dir, blobs) = temp_blob();
        let bytes = b"shared image bytes";
        let sha = sha_of(bytes);
        blobs
            .put("s1", &sha, "image/png", bytes.len() as u64, 0, &b64(bytes))
            .unwrap();
        // 其他会话虽能看到同一份内容，但还没有引用；必须返回未完成，
        // 让客户端重放 put 补引用，而不是跳过上传（B-31）。
        let stat = blobs.stat("s2", &sha).unwrap();
        assert!(!stat.complete, "没有本会话引用时不能报告完成");
        assert_eq!(stat.received, 0);
        let put = blobs
            .put("s2", &sha, "image/png", bytes.len() as u64, 0, &b64(bytes))
            .unwrap();
        assert!(put.complete);
        assert!(blobs.stat("s2", &sha).unwrap().complete);
    }

    #[test]
    fn wrong_sha_deletes_part() {
        let (_dir, blobs) = temp_blob();
        let bytes = b"hello image";
        let wrong = "0".repeat(64);
        let err = blobs
            .put(
                "s1",
                &wrong,
                "image/png",
                bytes.len() as u64,
                0,
                &b64(bytes),
            )
            .unwrap_err();
        assert!(err.to_string().contains("校验失败"));
        assert!(!blobs.part_path("s1", &wrong).exists());
    }

    #[test]
    fn offset_must_be_contiguous() {
        let (_dir, blobs) = temp_blob();
        let bytes = vec![1u8; 10];
        let sha = sha_of(&bytes);
        let err = blobs
            .put("s1", &sha, "image/png", 100, 5, &b64(&bytes))
            .unwrap_err();
        assert!(err.to_string().contains("偏移不连续"));
    }

    #[test]
    fn session_budget_rejects_upload() {
        let (_dir, blobs) = temp_blob();
        let blobs = blobs.with_limit(16);
        let bytes = vec![1u8; 32];
        let sha = sha_of(&bytes);
        let err = blobs
            .put("s1", &sha, "image/png", bytes.len() as u64, 0, &b64(&bytes))
            .unwrap_err();
        assert!(err.to_string().contains("空间不足"));
    }

    #[test]
    fn adopt_is_idempotent_and_respects_budget() {
        let dir = tempfile::tempdir().unwrap();
        let blobs = BlobStore::new(dir.path().join("blobs"), store()).with_limit(1024);
        let bytes = vec![7u8; 600];
        let a = blobs.adopt("s1", "image/png", &bytes).unwrap();
        let b = blobs.adopt("s1", "image/png", &bytes).unwrap();
        assert_eq!(a, b);
        let usage = blobs.usage("s1").unwrap();
        assert_eq!(usage.count, 1);
        assert_eq!(usage.used_bytes, 600);

        let too_big = vec![9u8; 600];
        assert!(
            blobs.adopt("s1", "image/png", &too_big).is_err(),
            "超过会话预算必须失败"
        );
    }

    #[test]
    fn cleanup_keeps_blobs_shared_with_fork() {
        let (_dir, blobs) = temp_blob();
        let bytes = b"shared";
        let sha = sha_of(bytes);
        blobs
            .put("s1", &sha, "image/png", bytes.len() as u64, 0, &b64(bytes))
            .unwrap();
        blobs.copy_session_refs("s1", "s2").unwrap();
        assert_eq!(blobs.cleanup_session("s1").unwrap(), 0);
        assert!(blobs.final_path(&sha).is_file());
        assert_eq!(blobs.cleanup_session("s2").unwrap(), 1);
        assert!(!blobs.final_path(&sha).is_file());
    }

    #[test]
    fn release_frees_usage_and_keeps_shared_file() {
        let (_dir, blobs) = temp_blob();
        let bytes = b"release me";
        let sha = sha_of(bytes);
        blobs
            .put("s1", &sha, "image/png", bytes.len() as u64, 0, &b64(bytes))
            .unwrap();
        blobs.copy_session_refs("s1", "s2").unwrap();
        // 仍有 fork 引用：只能删本会话引用，不能删文件。
        assert!(!blobs.release("s1", &sha).unwrap());
        assert!(blobs.final_path(&sha).is_file());
        let usage = blobs.usage("s1").unwrap();
        assert_eq!(usage.count, 0);
        assert_eq!(usage.used_bytes, 0);
        // 最后一个引用释放后才删文件。
        assert!(blobs.release("s2", &sha).unwrap());
        assert!(!blobs.final_path(&sha).is_file());
        assert_eq!(blobs.usage("s2").unwrap().used_bytes, 0);
    }

    #[test]
    fn idempotent_put_rejects_conflicting_metadata() {
        let (_dir, blobs) = temp_blob();
        let bytes = b"idempotent";
        let sha = sha_of(bytes);
        blobs
            .put("s1", &sha, "image/png", bytes.len() as u64, 0, &b64(bytes))
            .unwrap();
        let bad_size = blobs
            .put("s1", &sha, "image/png", 1, 0, &b64(bytes))
            .unwrap_err();
        assert!(bad_size.to_string().contains("大小"));
        let bad_mime = blobs
            .put("s1", &sha, "image/jpeg", bytes.len() as u64, 0, &b64(bytes))
            .unwrap_err();
        assert!(bad_mime.to_string().contains("类型"));
    }

    #[test]
    fn windows_disk_full_is_reported_clearly() {
        // Unix ENOSPC=28、Windows ERROR_DISK_FULL=112、ERROR_HANDLE_DISK_FULL=39。
        for code in [28, 39, 112] {
            let e = std::io::Error::from_raw_os_error(code);
            assert!(
                BlobStore::write_error(e)
                    .to_string()
                    .contains("磁盘空间不足"),
                "os code {code} 应被识别为磁盘满"
            );
        }
        let kind = std::io::Error::from(std::io::ErrorKind::StorageFull);
        assert!(BlobStore::write_error(kind)
            .to_string()
            .contains("磁盘空间不足"));
        // 其它 IO 错误不能误报成磁盘满。
        let denied = std::io::Error::from(std::io::ErrorKind::PermissionDenied);
        assert!(BlobStore::write_error(denied)
            .to_string()
            .contains("写入图片分片失败"));
    }
}
