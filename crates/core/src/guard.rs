//! 把内置审批扩展安装到数据目录，并返回路径。

use crate::error::Result;
use crate::paths::Paths;
use std::path::PathBuf;

pub const GUARD_SOURCE: &str = include_str!("../../../extensions/guard.ts");

/// guard 注册的自检命令名。
/// bridge 用 `get_commands` 校验它确实被加载：pi 以 `--approve` 启动时，
/// 若扩展没加载，所有工具调用会静默直通（审批闸门消失）。
pub const GUARD_COMMAND: &str = "pi-carry-guard";

pub fn ensure(paths: &Paths) -> Result<PathBuf> {
    let dir = paths.extensions();
    std::fs::create_dir_all(&dir)?;
    let path = dir.join("guard.ts");
    let needs_write = std::fs::read_to_string(&path)
        .map(|existing| existing != GUARD_SOURCE)
        .unwrap_or(true);
    if needs_write {
        crate::config::atomic_write(&path, GUARD_SOURCE.as_bytes())?;
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_guard_once() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf());
        let p = ensure(&paths).unwrap();
        assert!(p.is_file());
        let first = std::fs::read_to_string(&p).unwrap();
        assert!(first.contains("PI_CARRY_APPROVAL"));
        // 命令名必须与 Rust 侧常量一致，否则加载校验永远失败、会话永远开不了。
        assert!(first.contains(GUARD_COMMAND));
        ensure(&paths).unwrap();
        assert_eq!(first, std::fs::read_to_string(&p).unwrap());
    }
}
