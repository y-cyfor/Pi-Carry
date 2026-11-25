//! 数据目录互斥。
//!
//! 两个进程共用同一份 `endpoint_secret` 会互相顶号；SQLite WAL 并发写会 `SQLITE_BUSY`；
//! `Store` 的 `MAX(seq)+1` 在双写下会出现重复 seq。桌面的单实例锁只保护「桌面壳」，
//! 保护不了 `pi-carry pair` / `pi-carry serve` 与桌面同时跑。
//!
//! 这里按数据目录加一把进程级锁：Windows 用具名互斥，Unix 用 `flock`。
//! 桌面端的单实例锁（负责唤起窗口）保留，两者职责不重叠。

use crate::error::{CoreError, Result};
use crate::paths::Paths;
use std::path::{Path, PathBuf};

/// 同进程重复获取也必须失败：进程内两个 Bridge 同样会写坏数据。
pub struct DirLock {
    path: PathBuf,
    #[cfg(windows)]
    handle: isize,
    #[cfg(unix)]
    _file: std::fs::File,
    #[cfg(not(any(unix, windows)))]
    _file: std::fs::File,
}

impl std::fmt::Debug for DirLock {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DirLock").field("path", &self.path).finish()
    }
}

impl DirLock {
    /// 获取数据目录锁。失败意味着已有进程在用同一目录，或锁文件无法创建。
    pub fn acquire(paths: &Paths) -> Result<Self> {
        std::fs::create_dir_all(&paths.home).map_err(|e| {
            CoreError::Config(format!("创建数据目录 {} 失败: {e}", paths.home.display()))
        })?;
        #[cfg(windows)]
        {
            windows_acquire(paths)
        }
        #[cfg(unix)]
        {
            unix_acquire(paths)
        }
        #[cfg(not(any(unix, windows)))]
        {
            unsupported_acquire(paths)
        }
    }

    /// 锁文件路径（Unix）或互斥名（Windows 上仍返回占位路径，便于日志展示）。
    pub fn path(&self) -> &Path {
        &self.path
    }
}

#[cfg(unix)]
impl Drop for DirLock {
    fn drop(&mut self) {
        // File 关闭时内核自动释放 flock；不主动 LOCK_UN，避免与「先 close 再 unlink」的竞态。
    }
}

#[cfg(windows)]
impl Drop for DirLock {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;
        if self.handle != 0 {
            unsafe {
                CloseHandle(self.handle as *mut std::ffi::c_void);
            }
        }
    }
}

#[cfg(not(any(unix, windows)))]
impl Drop for DirLock {
    fn drop(&mut self) {}
}

#[cfg(windows)]
fn lock_name(home: &Path) -> String {
    use sha2::{Digest, Sha256};
    // 大小写与尾分隔符归一：同一目录的不同写法必须映射到同一把锁。
    let normalized = home
        .to_string_lossy()
        .trim_end_matches(['/', '\\'])
        .to_ascii_lowercase();
    let digest = Sha256::digest(normalized.as_bytes());
    format!("Local\\pi-carry-data-{}", hex::encode(&digest[..8]))
}

#[cfg(windows)]
fn windows_acquire(paths: &Paths) -> Result<DirLock> {
    use std::ptr;
    use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;

    let name: Vec<u16> = format!("{}\0", lock_name(&paths.home))
        .encode_utf16()
        .collect();
    let handle = unsafe { CreateMutexW(ptr::null(), 1, name.as_ptr()) };
    if handle.is_null() {
        let code = unsafe { GetLastError() };
        return Err(CoreError::Internal(format!(
            "无法创建数据目录锁（CreateMutexW 失败，错误码 {code}），数据目录 {}",
            paths.home.display()
        )));
    }
    // GetLastError 必须紧跟在 CreateMutexW 之后：中间插入任何 Win32 调用都会覆盖它。
    let already = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    if already {
        unsafe {
            CloseHandle(handle);
        }
        return Err(CoreError::Internal(conflict_message(&paths.home, None)));
    }
    Ok(DirLock {
        path: paths.home.join(".lock"),
        handle: handle as isize,
    })
}

#[cfg(unix)]
fn unix_acquire(paths: &Paths) -> Result<DirLock> {
    use std::io::{Seek, SeekFrom, Write};
    use std::os::unix::io::AsRawFd;

    let path = paths.home.join(".lock");
    // 不能用 atomic_write（临时文件 + rename）：flock 附着在 inode 上，
    // rename 换掉 inode 后新进程打开的是未加锁的新文件，锁形同虚设。
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .open(&path)
        .map_err(|e| CoreError::Config(format!("打开数据目录锁 {} 失败: {e}", path.display())))?;
    let fd = file.as_raw_fd();
    let mut rc = 0;
    for _ in 0..4 {
        rc = unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) };
        if rc == 0 || std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
            break;
        }
    }
    if rc != 0 {
        let holder = read_holder(&path);
        return Err(CoreError::Internal(conflict_message(
            &paths.home,
            holder.as_deref(),
        )));
    }
    // 持锁成功后再写诊断信息：锁内容只用于「谁占着」，不参与加锁判定。
    let line = format!(
        "pid={} acquired={}\n",
        std::process::id(),
        crate::rfc3339(chrono::Utc::now())
    );
    let _ = file.set_len(0);
    let _ = file.seek(SeekFrom::Start(0));
    let _ = file.write_all(line.as_bytes());
    let _ = file.flush();
    Ok(DirLock { path, _file: file })
}

#[cfg(unix)]
fn read_holder(path: &Path) -> Option<String> {
    let text = std::fs::read_to_string(path).ok()?;
    let pid = text
        .lines()
        .find_map(|l| l.strip_prefix("pid="))
        .map(str::trim)?;
    let acquired = text
        .lines()
        .find_map(|l| l.strip_prefix("acquired="))
        .map(str::trim)
        .unwrap_or("未知时间");
    Some(format!("pid {pid}，持锁开始于 {acquired}"))
}

#[cfg(not(any(unix, windows)))]
fn unsupported_acquire(paths: &Paths) -> Result<DirLock> {
    // 其他平台没有可靠实现：显式失败比静默放行更安全。
    Err(CoreError::Internal(format!(
        "当前平台不支持数据目录互斥，无法安全使用 {}",
        paths.home.display()
    )))
}

fn conflict_message(home: &Path, holder: Option<&str>) -> String {
    let detail = holder
        .map(|h| format!("（{h}）"))
        .unwrap_or_else(|| "（另一个 pi-carry 进程）".to_string());
    format!(
        "已有一个 pi-carry 进程在使用 {}{detail}。\n\
         请先退出它，或设置 PI_CARRY_HOME 使用独立数据目录。",
        home.display()
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn second_acquire_in_same_process_fails() {
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        let first = DirLock::acquire(&paths).expect("第一次获取应成功");
        let err = DirLock::acquire(&paths).expect_err("第二次获取必须失败");
        let text = err.to_string();
        assert!(
            text.contains("已有一个 pi-carry 进程"),
            "错误文案不可操作: {text}"
        );
        drop(first);
        let _again = DirLock::acquire(&paths).expect("释放后应能重新获取");
    }

    #[test]
    fn different_directories_do_not_conflict() {
        let a = TempDir::new().unwrap();
        let b = TempDir::new().unwrap();
        let _la = DirLock::acquire(&Paths::at(a.path())).unwrap();
        let _lb = DirLock::acquire(&Paths::at(b.path())).unwrap();
    }

    #[test]
    fn acquire_creates_missing_home_directory() {
        let dir = TempDir::new().unwrap();
        let home = dir.path().join("nested").join("home");
        let _lock = DirLock::acquire(&Paths::at(&home)).unwrap();
        assert!(home.is_dir());
    }

    #[cfg(unix)]
    #[test]
    fn lock_file_records_pid() {
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        let lock = DirLock::acquire(&paths).unwrap();
        let text = std::fs::read_to_string(lock.path()).unwrap();
        assert!(
            text.contains(&format!("pid={}", std::process::id())),
            "{text}"
        );
    }

    #[cfg(unix)]
    #[test]
    fn second_open_description_cannot_take_lock() {
        use std::os::unix::io::AsRawFd;
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        let lock = DirLock::acquire(&paths).unwrap();
        // flock 按「打开文件描述」互斥：独立 open 的文件描述等价于第二个进程。
        let other = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(lock.path())
            .unwrap();
        let rc = unsafe { libc::flock(other.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        assert_ne!(rc, 0, "第二个文件描述不应拿到锁");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn cross_process_flock_conflicts() {
        let dir = TempDir::new().unwrap();
        let paths = Paths::at(dir.path());
        let lock = DirLock::acquire(&paths).unwrap();
        let status = std::process::Command::new("flock")
            .args(["-n", &lock.path().to_string_lossy(), "true"])
            .status()
            .expect("flock 命令应存在（util-linux）");
        assert!(!status.success(), "另一个进程不应拿到锁");
    }

    #[cfg(windows)]
    #[test]
    fn lock_name_normalizes_case_and_trailing_separator() {
        let a = lock_name(Path::new("C:\\Users\\A\\pi-carry\\"));
        let b = lock_name(Path::new("c:\\users\\a\\pi-carry"));
        let c = lock_name(Path::new("C:\\Users\\B\\pi-carry"));
        assert_eq!(a, b);
        assert_ne!(a, c);
    }
}
