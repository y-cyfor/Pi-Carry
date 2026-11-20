//! 4 字节大端长度前缀 + JSON 的控制/事件帧。

use crate::error::FrameError;
use serde::de::DeserializeOwned;
use serde::Serialize;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// 把值编码为带长度前缀的帧。
pub fn encode_frame<T: Serialize>(value: &T, max: usize) -> Result<Vec<u8>, FrameError> {
    let body = serde_json::to_vec(value)?;
    if body.is_empty() {
        return Err(FrameError::Empty);
    }
    if body.len() > max {
        return Err(FrameError::TooLarge(body.len(), max));
    }
    if body.len() > u32::MAX as usize {
        return Err(FrameError::TooLarge(body.len(), u32::MAX as usize));
    }
    let mut out = Vec::with_capacity(4 + body.len());
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(&body);
    Ok(out)
}

/// 解码不含长度前缀的 JSON 帧体。
pub fn decode_frame<T: DeserializeOwned>(body: &[u8], max: usize) -> Result<T, FrameError> {
    if body.is_empty() {
        return Err(FrameError::Empty);
    }
    if body.len() > max {
        return Err(FrameError::TooLarge(body.len(), max));
    }
    Ok(serde_json::from_slice(body)?)
}

/// 读取一个长度前缀帧；干净 EOF 返回 `None`。
pub async fn read_frame<R: AsyncRead + Unpin>(
    reader: &mut R,
    max: usize,
) -> Result<Option<Vec<u8>>, FrameError> {
    let mut len_buf = [0u8; 4];
    let mut read = 0usize;
    while read < 4 {
        let n = reader.read(&mut len_buf[read..]).await?;
        if n == 0 {
            if read == 0 {
                return Ok(None);
            }
            return Err(FrameError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "帧长度前缀不完整",
            )));
        }
        read += n;
    }
    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 {
        return Err(FrameError::Empty);
    }
    if len > max {
        return Err(FrameError::TooLarge(len, max));
    }
    let mut body = vec![0u8; len];
    reader.read_exact(&mut body).await?;
    Ok(Some(body))
}

/// 写入一个长度前缀帧。
pub async fn write_frame<W: AsyncWrite + Unpin, T: Serialize>(
    writer: &mut W,
    value: &T,
    max: usize,
) -> Result<(), FrameError> {
    let bytes = encode_frame(value, max)?;
    writer.write_all(&bytes).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ClientFrame, ServerFrame};
    use serde_json::json;

    #[test]
    fn frame_roundtrip() {
        let frame = ClientFrame::Request {
            id: "c-1".into(),
            op: "ping".into(),
            payload: json!({"nonce": 1}),
        };
        let bytes = encode_frame(&frame, crate::MAX_CONTROL_FRAME).unwrap();
        assert_eq!(&bytes[..4], &(bytes.len() as u32 - 4).to_be_bytes());
        let body = &bytes[4..];
        let decoded: ClientFrame = decode_frame(body, crate::MAX_CONTROL_FRAME).unwrap();
        assert_eq!(decoded, frame);
    }

    #[test]
    fn rejects_oversize() {
        let frame = ClientFrame::Ping { nonce: 1 };
        let err = encode_frame(&frame, 4).unwrap_err();
        assert!(matches!(err, FrameError::TooLarge(_, 4)));
    }

    #[tokio::test]
    async fn async_roundtrip_and_eof() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        let frame = ServerFrame::Response {
            id: Some("c-1".into()),
            ok: true,
            payload: Some(json!({"pong": true})),
            error: None,
        };
        write_frame(&mut a, &frame, crate::MAX_CONTROL_FRAME)
            .await
            .unwrap();
        let body = read_frame(&mut b, crate::MAX_CONTROL_FRAME)
            .await
            .unwrap()
            .unwrap();
        let decoded: ServerFrame = decode_frame(&body, crate::MAX_CONTROL_FRAME).unwrap();
        assert_eq!(decoded, frame);
        drop(a);
        assert!(read_frame(&mut b, crate::MAX_CONTROL_FRAME)
            .await
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn rejects_truncated_prefix() {
        let (mut a, mut b) = tokio::io::duplex(16);
        a.write_all(&[0u8, 0]).await.unwrap();
        drop(a);
        let err = read_frame(&mut b, 1024).await.unwrap_err();
        assert!(matches!(err, FrameError::Io(_)));
    }
}
