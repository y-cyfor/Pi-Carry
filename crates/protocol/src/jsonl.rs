//! pi RPC 的 JSONL 分帧：只按 LF 切分，绝不把 U+2028 当作换行。

use crate::error::FrameError;
use serde::de::DeserializeOwned;
use tokio::io::{AsyncBufRead, AsyncBufReadExt};

/// 逐行读取 JSONL，跳过空行，限制单行字节数。
pub struct JsonlReader<R> {
    inner: R,
    max_line: usize,
}

impl<R: AsyncBufRead + Unpin> JsonlReader<R> {
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            max_line: crate::MAX_JSONL_LINE,
        }
    }

    pub fn with_max_line(mut self, max_line: usize) -> Self {
        self.max_line = max_line;
        self
    }

    /// 读取下一条 JSON；EOF 返回 None。
    pub async fn next_value<T: DeserializeOwned>(&mut self) -> Result<Option<T>, FrameError> {
        loop {
            let Some(line) = self.next_line().await? else {
                return Ok(None);
            };
            if line.trim().is_empty() {
                continue;
            }
            return Ok(Some(serde_json::from_str(&line)?));
        }
    }

    /// 逐块读取直到 LF，并在每块之后检查上限；超长行不会无界累积。
    pub async fn next_line(&mut self) -> Result<Option<String>, FrameError> {
        let mut buf = Vec::new();
        loop {
            let chunk = self.inner.fill_buf().await?;
            if chunk.is_empty() {
                break;
            }
            let end = chunk.iter().position(|b| *b == b'\n').map(|i| i + 1);
            let take = end.unwrap_or(chunk.len());
            buf.extend_from_slice(&chunk[..take]);
            self.inner.consume(take);
            if buf.len() > self.max_line {
                return Err(FrameError::LineTooLong(buf.len()));
            }
            if end.is_some() {
                break;
            }
        }
        if buf.is_empty() {
            return Ok(None);
        }
        while matches!(buf.last(), Some(b'\n' | b'\r')) {
            buf.pop();
        }
        let s = std::str::from_utf8(&buf)?.to_owned();
        Ok(Some(s))
    }
}

/// 读取一行并解析为 JSON（跳过空行）。
pub async fn read_json_line<T: DeserializeOwned, R: AsyncBufRead + Unpin>(
    reader: &mut R,
) -> Result<Option<T>, FrameError> {
    JsonlReader::new(reader).next_value().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};

    #[tokio::test]
    async fn splits_only_on_lf_and_keeps_u2028() {
        let input = "{\"a\":\"x\u{2028}y\"}\n\n{\"a\":2}\r\n";
        let mut reader = JsonlReader::new(input.as_bytes());
        let first: Value = reader.next_value().await.unwrap().unwrap();
        assert_eq!(first, json!({"a": "x\u{2028}y"}));
        let second: Value = reader.next_value().await.unwrap().unwrap();
        assert_eq!(second, json!({"a": 2}));
        assert!(reader.next_value::<Value>().await.unwrap().is_none());
    }

    #[tokio::test]
    async fn rejects_long_line() {
        let input = format!("{}\n", "x".repeat(32));
        let mut reader = JsonlReader::new(input.as_bytes()).with_max_line(16);
        let err = reader.next_value::<Value>().await.unwrap_err();
        assert!(matches!(err, FrameError::LineTooLong(33)));
    }

    #[tokio::test]
    async fn long_line_without_lf_is_rejected_with_bounded_read() {
        // 1MB 输入、无换行：必须报超长，且只读到首个缓冲块就停，不累积整条流。
        let input = "x".repeat(1024 * 1024);
        let mut inner = tokio::io::BufReader::with_capacity(64, input.as_bytes());
        let mut reader = JsonlReader::new(&mut inner).with_max_line(16);
        let err = reader.next_value::<Value>().await.unwrap_err();
        match err {
            FrameError::LineTooLong(n) => assert!(n <= 65, "只应读到首个缓冲块，实际 {n}"),
            other => panic!("期望 LineTooLong，实际 {other}"),
        }
    }
}
