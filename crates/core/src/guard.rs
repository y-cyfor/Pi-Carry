//! 把内置审批扩展安装到数据目录，并返回路径。

use crate::error::Result;
use crate::paths::Paths;
use std::path::PathBuf;

pub const GUARD_SOURCE: &str = include_str!("../../../extensions/guard.ts");

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
        ensure(&paths).unwrap();
        assert_eq!(first, std::fs::read_to_string(&p).unwrap());
    }
}
