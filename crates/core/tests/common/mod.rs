//! 集成测试共享辅助：定位或按需构建 fake-pi。

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Once, OnceLock};

/// 集成测试固定使用明文密钥后端。
///
/// 钥匙串条目按「服务名 + 用户名」全局唯一，并行测试会互相覆盖，还会在开发者
/// 机器上留下垃圾凭据；测试只需要验证启动路径，不需要验证系统钥匙串本身。
/// `OnceLock` 保证只写一次环境变量，避免并行 `set_var` 的竞态。
pub fn use_file_secret_backend() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| std::env::set_var("PI_CARRY_SECRET_BACKEND", "file"));
}

fn find_in(dir: &Path) -> Option<PathBuf> {
    for name in ["fake-pi.exe", "fake-pi"] {
        for candidate in [dir.join(name), dir.join("debug").join(name)] {
            if candidate.exists() {
                return Some(candidate);
            }
        }
    }
    None
}

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("无法定位仓库根目录")
        .to_path_buf()
}

/// 优先使用 `PI_CARRY_FAKE_PI`，其次在 target/debug 中查找；
/// 都没有时用独立 target 目录构建一次，避免与当前 cargo 进程争锁。
pub fn fake_pi() -> PathBuf {
    use_file_secret_backend();
    if let Ok(p) = std::env::var("PI_CARRY_FAKE_PI") {
        return PathBuf::from(p);
    }
    if let Some(p) = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.ancestors().skip(1).take(8).find_map(find_in))
    {
        return p;
    }

    static BUILD: Once = Once::new();
    let root = repo_root();
    let target = root.join("target/test-fake-pi");
    BUILD.call_once(|| {
        let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
        let status = Command::new(cargo)
            .current_dir(&root)
            .env("CARGO_TARGET_DIR", &target)
            .args(["build", "-q", "-p", "pi-carry-fake-pi"])
            .status()
            .expect("无法启动 cargo 构建 fake-pi");
        assert!(status.success(), "cargo build -p pi-carry-fake-pi 失败");
    });
    find_in(&target.join("debug")).expect("构建后仍找不到 fake-pi")
}
