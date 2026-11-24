//! 工作目录浏览：根目录枚举、越界校验与目录列举。
//!
//! 与 `SessionManager::resolve_cwd` 共用同一份「允许的根目录」逻辑：
//! 两处各写一遍迟早漂移（一边放行、一边拒绝），所以抽到这里由双方复用。

use crate::config::Config;
use crate::error::{CoreError, Result};
use serde_json::{json, Value};
use std::path::{Component, Path, PathBuf};

/// 单次列举的目录条数上限，防止超大目录（如 home 下的 node_modules 树）撑爆事件帧。
const MAX_DIR_ENTRIES: usize = 500;
/// `recent` 列表上限。
const MAX_RECENT: usize = 8;

/// Windows 的 `canonicalize` 返回 `\\?\C:\…` 形式（verbatim 前缀）。
///
/// 这个前缀不能直接回给客户端：既难看，又和用户先前提交的 `C:\…` 路径不相等，
/// 会让 `hasSessions` 永远判成 false。所以在展示和比较前统一剥掉。
pub fn strip_verbatim(s: &str) -> String {
    if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
        return format!(r"\\{rest}");
    }
    match s.strip_prefix(r"\\?\") {
        Some(rest) => rest.to_string(),
        None => s.to_string(),
    }
}

fn path_string(path: &Path) -> String {
    strip_verbatim(&path.to_string_lossy())
}

/// 允许浏览的根目录。配置为空时退回「主目录 + 进程启动目录」。
///
/// 不存在或无权限的根目录直接跳过并记日志：一个失效的配置项不该让整个
/// 目录选择器不可用。
pub fn roots(config: &Config) -> Vec<PathBuf> {
    let configured = &config.sessions.allowed_roots;
    let raw: Vec<PathBuf> = if configured.is_empty() {
        let mut v: Vec<PathBuf> = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from)
            .into_iter()
            .collect();
        if let Ok(cwd) = std::env::current_dir() {
            v.push(cwd);
        }
        v
    } else {
        configured.iter().map(PathBuf::from).collect()
    };
    raw.into_iter()
        .filter_map(|p| match std::fs::canonicalize(&p) {
            Ok(canon) => Some(canon),
            Err(e) => {
                tracing::warn!(path = %p.display(), error = %e, "跳过不可用的根目录");
                None
            }
        })
        .collect()
}

/// 把用户给的路径归一化成已存在的绝对路径，并校验它落在某个允许的根内。
///
/// 逐段比较（`Path::starts_with` 按组件比较）而不是拼字符串前缀：
/// 否则 `C:\Users\a` 会误把 `C:\Users\ab` 当成子目录。
pub fn ensure_allowed(config: &Config, path: &str) -> Result<PathBuf> {
    let raw = if path.trim().is_empty() {
        std::env::current_dir()?
    } else {
        let p = PathBuf::from(path);
        if p.is_absolute() {
            p
        } else {
            std::env::current_dir()?.join(p)
        }
    };
    let canon = std::fs::canonicalize(&raw)
        .map_err(|e| CoreError::InvalidArgument(format!("路径 {} 不可访问: {e}", raw.display())))?;
    if !canon.is_dir() {
        return Err(CoreError::InvalidArgument(format!(
            "路径 {} 不是目录",
            raw.display()
        )));
    }
    // 无根目录时一律拒绝（fail-closed），与 resolve_cwd 原语义一致。
    let allowed = roots(config).iter().any(|root| canon.starts_with(root));
    if !allowed {
        return Err(CoreError::PermissionDenied(format!(
            "路径 {} 不在允许的根目录内",
            raw.display()
        )));
    }
    Ok(canon)
}

/// `fs.roots` 的响应体：根目录清单 + 最近用过的目录。
pub fn roots_payload(config: &Config, recent: Vec<(String, String)>) -> Value {
    let list: Vec<Value> = roots(config)
        .into_iter()
        .map(|p| {
            let path = path_string(&p);
            json!({
                "path": path,
                "label": dir_label(&p),
                "kind": root_kind(&p),
            })
        })
        .collect();
    json!({ "roots": list, "recent": recent_json(recent) })
}

/// 根目录的展示名：优先显示最后一段（`C:\Users\me` → `me`），根盘符退化为完整路径。
fn dir_label(path: &Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| path_string(path))
}

fn root_kind(path: &Path) -> &'static str {
    let home = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from);
    if let Some(home) = home {
        if std::fs::canonicalize(home)
            .map(|h| h == path)
            .unwrap_or(false)
        {
            return "home";
        }
    }
    // `path` 来自 `roots()`，是 `canonicalize` 之后的结果（Windows 上带 `\\?\` 前缀）；
    // `current_dir()` 不带前缀，直接比较永远不等，"cwd" 分支会恒为死代码。
    // 两边都规范化后再比，才和上面的 home 分支口径一致。
    if std::env::current_dir()
        .ok()
        .and_then(|c| std::fs::canonicalize(c).ok())
        .map(|c| c == path)
        .unwrap_or(false)
    {
        return "cwd";
    }
    "custom"
}

fn recent_json(recent: Vec<(String, String)>) -> Value {
    Value::Array(
        recent
            .into_iter()
            .take(MAX_RECENT)
            .map(|(path, last_used)| json!({"path": path, "lastUsed": last_used}))
            .collect(),
    )
}

/// `fs.list` 的响应体。只列目录，符号链接需目标仍在允许根内。
pub fn list_payload(config: &Config, path: &str, session_cwds: &[String]) -> Result<Value> {
    let dir = ensure_allowed(config, path)?;
    let known: Vec<String> = session_cwds.iter().map(|c| normalize_cwd(c)).collect();

    let mut entries: Vec<(String, PathBuf, bool)> = Vec::new();
    let mut truncated = false;
    let read = std::fs::read_dir(&dir)
        .map_err(|e| CoreError::InvalidArgument(format!("目录 {} 无法读取: {e}", dir.display())))?;
    for item in read {
        let item = match item {
            Ok(i) => i,
            Err(e) => {
                tracing::debug!(error = %e, "跳过无法读取的目录项");
                continue;
            }
        };
        let child = item.path();
        let is_symlink = item.file_type().map(|t| t.is_symlink()).unwrap_or(false);
        // 符号链接必须解析后再判断归属，否则一个指向 root 之外的链接就能逃出去。
        let target = if is_symlink {
            match std::fs::canonicalize(&child) {
                Ok(t) if t.is_dir() && roots(config).iter().any(|r| t.starts_with(r)) => t,
                _ => continue,
            }
        } else {
            // 普通目录不 canonicalize：500 条目录各做一次系统调用太浪费。
            match item.file_type() {
                Ok(t) if t.is_dir() => child.clone(),
                _ => continue,
            }
        };
        if !target.is_dir() {
            continue;
        }
        let name = item.file_name().to_string_lossy().into_owned();
        // 隐藏目录（.git 等）不展示：目录选择器里它们几乎总是干扰项。
        if name.starts_with('.') {
            continue;
        }
        if entries.len() >= MAX_DIR_ENTRIES {
            truncated = true;
            break;
        }
        let has_sessions = known.contains(&normalize_cwd(&path_string(&child)));
        entries.push((name, child, has_sessions));
    }
    entries.sort_by(|a, b| sort_key_cmp(&a.0, &b.0));

    let list: Vec<Value> = entries
        .into_iter()
        .map(|(name, child, has_sessions)| {
            json!({
                "name": name,
                "path": path_string(&child),
                "hasSessions": has_sessions,
            })
        })
        .collect();
    // parent 也要过允许根判定：允许根为 `C:\work\a` 时 `parent_of` 会给出 `C:\work`，
    // 那是根之外、点进去必然被 `ensure_allowed` 拒绝的目录。计划要求根目录的
    // parent 为 null，所以越界一律置空，而不是把一个不可用的「上一级」交给 UI。
    let parent = parent_of(&dir)
        .filter(|p| ensure_allowed(config, &path_string(p)).is_ok())
        .map(|p| path_string(&p));
    Ok(json!({
        "path": path_string(&dir),
        "parent": parent,
        "entries": list,
        "truncated": truncated,
    }))
}

/// 上级目录：已经是根目录（盘符/`/`）或不在允许根内时返回 `None`，
/// 免得 UI 给出一个点进去就会被拒绝的「上一级」按钮。
fn parent_of(dir: &Path) -> Option<PathBuf> {
    let parent = dir.parent()?;
    // `C:\` 的 parent 是 `C:\` 自身，不能当成可点击的上级。
    if parent == dir {
        return None;
    }
    if parent.components().count() == 0 {
        return None;
    }
    if !parent.components().all(|c| {
        matches!(
            c,
            Component::Prefix(_) | Component::RootDir | Component::Normal(_)
        )
    }) {
        return None;
    }
    Some(parent.to_path_buf())
}

/// 目录名排序：大小写折叠后比较，折叠后并列（`Alpha` / `alpha`）时再用原始名兜底。
///
/// 只用折叠键是不够的：`sort_by` 虽然稳定，但初始顺序来自 `read_dir`（随文件系统
/// 变化），并列键会让展示顺序在不同机器/不同运行之间抖动。
fn sort_key_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    a.to_lowercase()
        .cmp(&b.to_lowercase())
        .then_with(|| a.cmp(b))
}

/// 会话 cwd 的归一化 key：剥掉 verbatim 前缀、去尾部路径分隔符、Windows 风格路径统一小写。
pub fn normalize_cwd(cwd: &str) -> String {
    let cleaned = strip_verbatim(cwd.trim());
    let trimmed = cleaned.trim_end_matches(['/', '\\']);
    let windows_style = trimmed.contains('\\')
        || trimmed
            .as_bytes()
            .get(1)
            .map(|b| *b == b':')
            .unwrap_or(false);
    if windows_style {
        trimmed.to_lowercase()
    } else {
        trimmed.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "pi-carry-fsbrowse-{}-{}-{}",
            tag,
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn config_with_roots(roots: Vec<String>) -> Config {
        let mut c = Config::default();
        c.sessions.allowed_roots = roots;
        c
    }

    #[test]
    fn normalize_cwd_strips_trailing_separator_and_lowercases_windows_paths() {
        assert_eq!(normalize_cwd("C:\\Work\\Demo\\"), "c:\\work\\demo");
        assert_eq!(normalize_cwd("/home/me/demo/"), "/home/me/demo");
        assert_eq!(normalize_cwd(""), "");
    }

    #[test]
    fn ensure_allowed_rejects_sibling_prefix() {
        let base = temp_dir("prefix");
        let mine = base.join("a");
        let sibling = base.join("ab");
        fs::create_dir_all(&mine).unwrap();
        fs::create_dir_all(&sibling).unwrap();
        let config = config_with_roots(vec![mine.to_string_lossy().into_owned()]);
        assert!(ensure_allowed(&config, &mine.to_string_lossy()).is_ok());
        let err = ensure_allowed(&config, &sibling.to_string_lossy()).unwrap_err();
        assert!(
            matches!(err, CoreError::PermissionDenied(_)),
            "同级同前缀目录必须被拒绝: {err:?}"
        );
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn ensure_allowed_rejects_missing_and_non_directory() {
        let base = temp_dir("missing");
        let file = base.join("a.txt");
        fs::write(&file, b"x").unwrap();
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        let err = ensure_allowed(&config, &base.join("nope").to_string_lossy()).unwrap_err();
        assert!(matches!(err, CoreError::InvalidArgument(_)));
        let err = ensure_allowed(&config, &file.to_string_lossy()).unwrap_err();
        assert!(matches!(err, CoreError::InvalidArgument(_)));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn list_payload_returns_sorted_dirs_only_and_marks_sessions() {
        let base = temp_dir("list");
        fs::create_dir_all(base.join("Beta")).unwrap();
        fs::create_dir_all(base.join("alpha")).unwrap();
        fs::create_dir_all(base.join(".hidden")).unwrap();
        fs::write(base.join("file.txt"), b"x").unwrap();
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        let sessions = vec![base.join("alpha").to_string_lossy().into_owned()];
        let v = list_payload(&config, &base.to_string_lossy(), &sessions).unwrap();
        let entries = v["entries"].as_array().unwrap();
        let names: Vec<&str> = entries
            .iter()
            .map(|e| e["name"].as_str().unwrap())
            .collect();
        assert_eq!(
            names,
            vec!["alpha", "Beta"],
            "按名称不区分大小写排序且只列目录"
        );
        assert_eq!(entries[0]["hasSessions"], true);
        assert_eq!(entries[1]["hasSessions"], false);
        assert_eq!(v["truncated"], false);
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn list_payload_truncates_at_limit() {
        let base = temp_dir("truncate");
        for i in 0..(MAX_DIR_ENTRIES + 5) {
            fs::create_dir_all(base.join(format!("d{i:04}"))).unwrap();
        }
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        let v = list_payload(&config, &base.to_string_lossy(), &[]).unwrap();
        assert_eq!(v["entries"].as_array().unwrap().len(), MAX_DIR_ENTRIES);
        assert_eq!(v["truncated"], true);
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn list_payload_parent_is_null_at_root() {
        let base = temp_dir("parent");
        let child = base.join("sub");
        fs::create_dir_all(&child).unwrap();
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        // 允许根本身：上一级在允许根之外，必须置 null，否则 UI 的「上一级」一点就被拒。
        let v = list_payload(&config, &base.to_string_lossy(), &[]).unwrap();
        assert!(v["path"].is_string());
        assert!(v["parent"].is_null(), "允许根目录的 parent 必须为 null");
        // 子目录：父目录就是允许根本身，必须给出且仍在允许根内。
        let v2 = list_payload(&config, &child.to_string_lossy(), &[]).unwrap();
        let parent = v2["parent"].as_str().expect("子目录必须有 parent");
        assert_eq!(
            ensure_allowed(&config, parent).unwrap(),
            std::fs::canonicalize(&base).unwrap(),
            "子目录的 parent 必须落在允许根内"
        );
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn sort_key_breaks_case_folded_ties_by_raw_name() {
        // Windows 文件系统大小写不敏感，同目录无法共存 "Alpha" 与 "alpha"，
        // 所以直接测比较函数而不是造目录：并列时必须按原始名给出确定顺序。
        use std::cmp::Ordering;
        assert_eq!(sort_key_cmp("Alpha", "alpha"), Ordering::Less);
        assert_eq!(sort_key_cmp("alpha", "Alpha"), Ordering::Greater);
        assert_eq!(sort_key_cmp("Alpha", "Alpha"), Ordering::Equal);
        // 不同键时仍按大小写折叠比较（"alpha" 在 "Beta" 之前）。
        assert_eq!(sort_key_cmp("alpha", "Beta"), Ordering::Less);
    }

    #[test]
    fn roots_payload_marks_process_cwd_as_cwd() {
        let Ok(cwd) = std::env::current_dir() else {
            return;
        };
        // home 分支优先于 cwd 分支：当前目录恰好是主目录时无法区分，跳过而不是造假断言。
        let home = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from);
        if let Some(home) = home {
            if std::fs::canonicalize(home)
                .map(|h| h == cwd)
                .unwrap_or(false)
            {
                return;
            }
        }
        let config = config_with_roots(vec![cwd.to_string_lossy().into_owned()]);
        let v = roots_payload(&config, Vec::new());
        assert_eq!(v["roots"][0]["kind"], "cwd");
    }

    #[cfg(unix)]
    #[test]
    fn list_payload_skips_symlink_escaping_root() {
        let base = temp_dir("symlink");
        let outside = temp_dir("outside");
        std::os::unix::fs::symlink(&outside, base.join("escape")).unwrap();
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        let v = list_payload(&config, &base.to_string_lossy(), &[]).unwrap();
        assert_eq!(
            v["entries"].as_array().unwrap().len(),
            0,
            "逃逸链接必须被过滤"
        );
        fs::remove_dir_all(&base).ok();
        fs::remove_dir_all(&outside).ok();
    }

    #[test]
    fn roots_payload_reports_label_kind_and_recent() {
        let base = temp_dir("roots");
        let config = config_with_roots(vec![base.to_string_lossy().into_owned()]);
        let v = roots_payload(
            &config,
            vec![("/tmp/x".into(), "2025-01-01T00:00:00Z".into())],
        );
        let roots = v["roots"].as_array().unwrap();
        assert_eq!(roots.len(), 1);
        assert_eq!(roots[0]["kind"], "custom");
        assert_eq!(v["recent"].as_array().unwrap().len(), 1);
        fs::remove_dir_all(&base).ok();
    }
}
