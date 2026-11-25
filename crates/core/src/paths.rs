use std::path::PathBuf;

/// 所有可变状态都在一个目录下，便于测试与卸载。
#[derive(Debug, Clone)]
pub struct Paths {
    pub home: PathBuf,
}

impl Paths {
    pub fn discover() -> Self {
        if let Ok(v) = std::env::var("PI_CARRY_HOME") {
            return Self {
                home: PathBuf::from(v),
            };
        }
        Self {
            home: Self::default_home(),
        }
    }

    /// 不带 `PI_CARRY_HOME` 时的默认数据目录。
    ///
    /// 钥匙串条目按数据目录隔离，需要区分「默认目录」与「隔离目录」：
    /// 默认目录必须沿用旧条目名，隔离目录用各自的名字（否则 `PI_CARRY_HOME`
    /// 并不能隔离节点身份）。
    pub fn default_home() -> PathBuf {
        let home = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        home.join(".pi-carry")
    }

    pub fn at(home: impl Into<PathBuf>) -> Self {
        Self { home: home.into() }
    }

    pub fn config(&self) -> PathBuf {
        self.home.join("config.toml")
    }
    pub fn secrets(&self) -> PathBuf {
        self.home.join("secrets.json")
    }
    pub fn db(&self) -> PathBuf {
        self.home.join("state.db")
    }
    pub fn logs(&self) -> PathBuf {
        self.home.join("logs")
    }
    pub fn run(&self) -> PathBuf {
        self.home.join("run")
    }
    pub fn extensions(&self) -> PathBuf {
        self.home.join("extensions")
    }
    /// 0.2.0 图片 blob 库根目录（内容寻址，文件名即 sha256）。
    pub fn blobs(&self) -> PathBuf {
        self.home.join("blobs")
    }
    pub fn ensure(&self) -> std::io::Result<()> {
        std::fs::create_dir_all(&self.home)?;
        std::fs::create_dir_all(self.logs())?;
        std::fs::create_dir_all(self.run())?;
        std::fs::create_dir_all(self.blobs())?;
        Ok(())
    }
}

pub fn default_sessions_dir() -> PathBuf {
    if let Ok(v) = std::env::var("PI_CARRY_SESSIONS") {
        return PathBuf::from(v);
    }
    let home = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    home.join(".pi").join("agent").join("sessions")
}
