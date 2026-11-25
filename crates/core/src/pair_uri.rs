//! `pi-carry://pair` 深链的生成与解析。
//!
//! 这里是协议的唯一事实源：`pairing::build_uri` 直接调用 [`build`]，解析走 [`parse`]。
//! 生成与解析必须同一套语义，否则产品自己发出的链接会被自己的深链入口拒绝。
//! 只接受 `pair` 这个 host，其余一律拒绝并给出可读原因。

use crate::error::{CoreError, Result};

/// 深链 scheme。大小写不敏感（URL scheme 本身不区分大小写）。
pub const SCHEME: &str = "pi-carry";
/// 唯一允许的 host。
pub const HOST: &str = "pair";
/// Endpoint ID 的 hex 长度（32 字节）。
pub const ENDPOINT_HEX_LEN: usize = 64;
/// 配对 token 的 hex 长度（16 字节）。
pub const TOKEN_HEX_LEN: usize = 32;
/// 超过这个长度的链接直接拒绝：正常配对链接不到 400 字符。
pub const MAX_URI_LEN: usize = 2048;
/// 节点名的字符上限（与手机端显示宽度匹配）。
pub const MAX_NAME_CHARS: usize = 64;
/// relay URL 的长度上限。
pub const MAX_RELAY_LEN: usize = 512;
/// 直连地址的长度上限。
pub const MAX_ADDR_LEN: usize = 128;
/// 一条链接里允许携带的直连地址条数上限。
///
/// 生成侧会把 LAN IPv4、公网 IPv4、IPv6 环回都写进二维码（默认 3 条），
/// 但外部链接不可信，仍要有上界。
pub const MAX_ADDRS: usize = 8;
/// relay 字段允许的「模式名」。
///
/// 生成侧在默认模式下写的是模式名而不是 URL（`n0`），手机端也按模式名解析
/// （`NodeConnection.kt` 把 `n0`/`default` 映射成 `RelayMode.Default`），
/// 所以这里必须接受它们，不能只认 http(s)。
const RELAY_MODES: [&str; 4] = ["n0", "default", "custom", "disabled"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairUri {
    pub version: u8,
    /// 小写 hex，64 字符。
    pub endpoint: String,
    /// 小写 hex，32 字符。
    pub token: String,
    pub relay: Option<String>,
    /// 直连地址，按链接里的顺序保留（生成侧会写多条）。
    pub addrs: Vec<String>,
    pub name: Option<String>,
}

/// 解析并校验深链。任何字段不合法都返回 `InvalidArgument`，不做「尽量猜」。
pub fn parse(uri: &str) -> Result<PairUri> {
    let uri = uri.trim();
    if uri.is_empty() {
        return Err(invalid("配对链接为空"));
    }
    if uri.len() > MAX_URI_LEN {
        return Err(invalid(format!(
            "配对链接过长（{} 字节，上限 {MAX_URI_LEN}）",
            uri.len()
        )));
    }
    let scheme_end = uri
        .find("://")
        .ok_or_else(|| invalid("配对链接缺少 scheme（应为 pi-carry://pair?...）"))?;
    let scheme = &uri[..scheme_end];
    if !scheme.eq_ignore_ascii_case(SCHEME) {
        return Err(invalid(format!(
            "配对链接 scheme 必须是 {SCHEME}，实际 {scheme}"
        )));
    }
    let rest = &uri[scheme_end + 3..];
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = &rest[..host_end];
    if !host.eq_ignore_ascii_case(HOST) {
        return Err(invalid(format!("配对链接 host 必须是 {HOST}，实际 {host}")));
    }
    let after_host = &rest[host_end..];
    // 允许 `pi-carry://pair/` 这种空 path，但拒绝更深路径：那通常意味着协议用错了。
    let query_start = after_host
        .find('?')
        .ok_or_else(|| invalid("配对链接缺少查询参数（v/endpoint/token）"))?;
    let path = after_host[..query_start].trim_end_matches('/');
    if !path.is_empty() {
        return Err(invalid(format!("配对链接不允许路径，实际 {path}")));
    }
    let query = &after_host[query_start + 1..];
    let query = query.split('#').next().unwrap_or("");

    let mut version: Option<u8> = None;
    let mut endpoint: Option<String> = None;
    let mut token: Option<String> = None;
    let mut relay: Option<String> = None;
    let mut addrs: Vec<String> = Vec::new();
    let mut name: Option<String> = None;
    for pair in query.split('&') {
        if pair.is_empty() {
            return Err(invalid("配对链接包含空查询参数"));
        }
        let (key, raw) = pair
            .split_once('=')
            .ok_or_else(|| invalid(format!("查询参数 {pair} 缺少 = 号")))?;
        let value = decode_component(raw)?;
        match key {
            "v" => {
                if version.is_some() {
                    return Err(invalid("查询参数 v 重复"));
                }
                let parsed: u8 = value
                    .parse()
                    .map_err(|_| invalid(format!("配对链接版本 v 必须是数字，实际 {value}")))?;
                if parsed != 1 {
                    return Err(invalid(format!(
                        "不支持的配对链接版本 v={parsed}（当前只支持 v=1）"
                    )));
                }
                version = Some(parsed);
            }
            "endpoint" => {
                if endpoint.is_some() {
                    return Err(invalid("查询参数 endpoint 重复"));
                }
                endpoint = Some(validate_hex(&value, ENDPOINT_HEX_LEN, "endpoint")?);
            }
            "token" => {
                if token.is_some() {
                    return Err(invalid("查询参数 token 重复"));
                }
                token = Some(validate_hex(&value, TOKEN_HEX_LEN, "token")?);
            }
            "relay" => {
                if relay.is_some() {
                    return Err(invalid("查询参数 relay 重复"));
                }
                relay = Some(validate_relay(&value)?);
            }
            "addr" => {
                // 多条 addr 是正常产物：桌面端会把所有可直连地址都写进二维码。
                if addrs.len() >= MAX_ADDRS {
                    return Err(invalid(format!("addr 不能超过 {MAX_ADDRS} 条")));
                }
                let value = value.trim();
                if value.is_empty() || value.len() > MAX_ADDR_LEN {
                    return Err(invalid(format!(
                        "addr 长度必须在 1..={MAX_ADDR_LEN} 字节内"
                    )));
                }
                if value.chars().any(char::is_control) {
                    return Err(invalid("addr 不能包含控制字符"));
                }
                addrs.push(value.to_string());
            }
            "name" => {
                if name.is_some() {
                    return Err(invalid("查询参数 name 重复"));
                }
                let value = value.trim().to_string();
                if value.chars().count() > MAX_NAME_CHARS {
                    return Err(invalid(format!("name 不能超过 {MAX_NAME_CHARS} 个字符")));
                }
                if value.chars().any(char::is_control) {
                    return Err(invalid("name 不能包含控制字符"));
                }
                if !value.is_empty() {
                    name = Some(value);
                }
            }
            // 未知参数忽略：未来加字段时旧客户端仍可用。已知字段重复仍然拒绝。
            _ => {}
        }
    }
    let version = version.ok_or_else(|| invalid("配对链接缺少 v=1"))?;
    let endpoint = endpoint.ok_or_else(|| invalid("配对链接缺少 endpoint"))?;
    let token = token.ok_or_else(|| invalid("配对链接缺少 token"))?;
    Ok(PairUri {
        version,
        endpoint,
        token,
        relay,
        addrs,
        name,
    })
}

/// 生成 `pi-carry://pair?v=1&endpoint=…&token=…`，参数顺序与 Android 侧一致。
///
/// `pairing::build_uri` 直接调用它：生成侧与解析侧只有这一份语义。
/// 生成侧必须受 [`parse`] 的同一条上限约束，否则产品自己发出的链接会被自己的深链拒绝
/// （超长节点名、多网卡机器的第 9 条地址都会触发，BUG-031）。
pub fn build(
    endpoint: &str,
    token: &str,
    name: Option<&str>,
    relay: Option<&str>,
    addrs: &[String],
) -> String {
    let mut uri = format!(
        "{SCHEME}://{HOST}?v=1&endpoint={}&token={}",
        encode_component(endpoint),
        encode_component(token)
    );
    if let Some(name) = name.map(str::trim).filter(|s| !s.is_empty()) {
        // 解析侧按字符数上限校验：这里按字符截断，中文名字也不会被截在 UTF-8 中间。
        push_param(&mut uri, "name", clamp_chars(name, MAX_NAME_CHARS));
    }
    if let Some(relay) = relay
        .map(str::trim)
        .filter(|r| !r.is_empty() && r.len() <= MAX_RELAY_LEN)
    {
        push_param(&mut uri, "relay", relay);
    }
    for addr in clamp_addrs(addrs) {
        push_param(&mut uri, "addr", addr);
    }
    uri
}

/// 追加一个可选参数；整条链接会超过 [`MAX_URI_LEN`] 时直接省略该字段。
///
/// 宁可少一个可选字段，也不能产出自己解析不了的链接：极端组合
/// （64 个中文字符的节点名 + 512 字节 relay + 多个满是 `:` 的地址）光靠裁剪长度还会超限。
fn push_param(uri: &mut String, key: &str, value: &str) {
    let piece = format!("&{key}={}", encode_component(value));
    if uri.len() + piece.len() > MAX_URI_LEN {
        return;
    }
    uri.push_str(&piece);
}

/// 截到 `max` 个**字符**（不是字节），返回的切片保证落在 UTF-8 边界上。
fn clamp_chars(value: &str, max: usize) -> &str {
    match value.char_indices().nth(max) {
        Some((idx, _)) => &value[..idx],
        None => value,
    }
}

/// 按解析侧规则筛选直连地址：去重、丢掉超长/空/含控制字符的，最多 [`MAX_ADDRS`] 条。
///
/// 地址被截断就是错误地址，所以这里只丢弃、不裁剪。
fn clamp_addrs(addrs: &[String]) -> Vec<&str> {
    let mut kept: Vec<&str> = Vec::new();
    for raw in addrs {
        let addr = raw.trim();
        if addr.is_empty()
            || addr.len() > MAX_ADDR_LEN
            || addr.chars().any(char::is_control)
            || kept.contains(&addr)
        {
            continue;
        }
        kept.push(addr);
        if kept.len() == MAX_ADDRS {
            break;
        }
    }
    kept
}

fn invalid(message: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(message.into())
}

fn validate_hex(value: &str, expected_len: usize, field: &str) -> Result<String> {
    let lower = value.to_ascii_lowercase();
    if lower.len() != expected_len {
        return Err(invalid(format!(
            "{field} 必须是 {expected_len} 位 hex，实际 {} 位",
            lower.len()
        )));
    }
    if !lower.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(invalid(format!("{field} 只能包含 0-9a-f，实际 {value}")));
    }
    // 再解一次：`is_ascii_hexdigit` 允许大小写混排，长度也校验过，这里只是兜底。
    hex::decode(&lower).map_err(|e| invalid(format!("{field} 不是合法 hex: {e}")))?;
    Ok(lower)
}

fn validate_relay(value: &str) -> Result<String> {
    let value = value.trim();
    if value.len() > MAX_RELAY_LEN {
        return Err(invalid(format!("relay 不能超过 {MAX_RELAY_LEN} 字节")));
    }
    if value.chars().any(char::is_control) {
        return Err(invalid("relay 不能包含控制字符"));
    }
    let lower = value.to_ascii_lowercase();
    // 生成侧在默认模式下写模式名（`n0`），手机端也按模式名解析；只认 http(s)
    // 会让产品自己生成的二维码在桌面端变成「配对链接无效」。
    if RELAY_MODES.contains(&lower.as_str()) {
        return Ok(lower);
    }
    if !lower.starts_with("http://") && !lower.starts_with("https://") {
        return Err(invalid(format!(
            "relay 必须是 http(s) 地址或 n0/default/custom/disabled，实际 {value}"
        )));
    }
    // 只允许 ASCII：URL 里的非 ASCII 必须由调用方 percent-encode，否则不同客户端
    // 会按不同编码发送，relay 地址在日志里也会变成乱码。
    if !value.is_ascii() {
        return Err(invalid("relay 只能包含 ASCII 字符"));
    }
    if value.contains(char::is_whitespace) {
        return Err(invalid("relay 不能包含空白字符"));
    }
    Ok(value.to_string())
}

fn decode_component(raw: &str) -> Result<String> {
    let bytes = raw.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                if i + 2 >= bytes.len() {
                    return Err(invalid(format!("百分号转义不完整: {raw}")));
                }
                let hi = hex_val(bytes[i + 1])
                    .ok_or_else(|| invalid(format!("非法百分号转义: {raw}")))?;
                let lo = hex_val(bytes[i + 2])
                    .ok_or_else(|| invalid(format!("非法百分号转义: {raw}")))?;
                out.push((hi << 4) | lo);
                i += 3;
            }
            // 查询串兼容 form-urlencoded：Android 的 URLEncoder 会把空格写成 +。
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).map_err(|e| invalid(format!("查询参数不是合法 UTF-8: {e}")))
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// 与 `pairing::encode_component` 同一套规则：RFC 3986 非保留字符之外全部 percent-encode。
fn encode_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const ENDPOINT: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn parses_minimal_valid_uri() {
        let uri = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}");
        let parsed = parse(&uri).unwrap();
        assert_eq!(parsed.version, 1);
        assert_eq!(parsed.endpoint, ENDPOINT);
        assert_eq!(parsed.token, TOKEN);
        assert!(parsed.relay.is_none());
        assert!(parsed.addrs.is_empty());
        assert!(parsed.name.is_none());
    }

    #[test]
    fn parses_all_optional_fields_and_decodes_percent_escapes() {
        let uri = format!(
            "pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}\
             &name=Studio%20PC&relay=https%3A%2F%2Frelay.example.com&addr=192.168.1.5%3A4433"
        );
        let parsed = parse(&uri).unwrap();
        assert_eq!(parsed.name.as_deref(), Some("Studio PC"));
        assert_eq!(parsed.relay.as_deref(), Some("https://relay.example.com"));
        assert_eq!(parsed.addrs, vec!["192.168.1.5:4433".to_string()]);
    }

    /// 产品自己生成的链接必须能被自己的深链入口解析（BUG-001 回归）。
    ///
    /// 生成侧默认写 `relay=n0` 和全部直连地址（局域网 IPv4 + IPv6 环回），
    /// 旧解析器把这两点都当非法，导致扫码能配、点链接不能配。
    #[test]
    fn accepts_product_uri_with_relay_mode_and_multiple_addrs() {
        let uri = format!(
            "pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name=PROJECT-CHN-WIN\
             &relay=n0&addr=127.0.0.1%3A55277&addr=172.27.88.72%3A55277&addr=%5B%3A%3A1%5D%3A55279"
        );
        let parsed = parse(&uri).expect("产品自己生成的链接必须解析成功");
        assert_eq!(parsed.relay.as_deref(), Some("n0"));
        assert_eq!(
            parsed.addrs,
            vec![
                "127.0.0.1:55277".to_string(),
                "172.27.88.72:55277".to_string(),
                "[::1]:55279".to_string(),
            ]
        );
        assert_eq!(parsed.name.as_deref(), Some("PROJECT-CHN-WIN"));
    }

    #[test]
    fn rejects_too_many_addrs() {
        let mut uri = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}");
        for i in 0..=MAX_ADDRS {
            uri.push_str(&format!("&addr=10.0.0.{}%3A1", i % 250));
        }
        assert!(
            parse(&uri).unwrap_err().to_string().contains("addr"),
            "{uri}"
        );
    }

    /// BUG-031 回归：生成侧必须受解析侧同一条上限约束，极端输入也要产出可解析、且不超总长的链接。
    #[test]
    fn build_clamps_extreme_inputs_to_parser_limits() {
        let long_name = "工".repeat(MAX_NAME_CHARS + 16);
        let mut addrs: Vec<String> = vec!["10.0.0.2:4433".to_string(), "10.0.0.2:4433".to_string()];
        for i in 0..12 {
            addrs.push(format!("10.0.{}.{}:4433", i % 250, (i + 1) % 250));
        }
        addrs.push("x".repeat(MAX_ADDR_LEN + 1));
        let overlong_relay = format!("http://{}", "r".repeat(MAX_RELAY_LEN));
        assert!(overlong_relay.len() > MAX_RELAY_LEN);

        let uri = build(
            ENDPOINT,
            TOKEN,
            Some(&long_name),
            Some(&overlong_relay),
            &addrs,
        );
        assert!(uri.len() <= MAX_URI_LEN, "{}", uri.len());
        let parsed = parse(&uri).expect("生成侧产物必须可解析");
        assert_eq!(
            parsed.name.as_deref().map(|n| n.chars().count()),
            Some(MAX_NAME_CHARS),
            "节点名必须按字符截到上限"
        );
        assert_eq!(parsed.addrs.len(), MAX_ADDRS, "地址必须裁剪到上限");
        let mut unique = parsed.addrs.clone();
        unique.sort();
        unique.dedup();
        assert_eq!(unique.len(), MAX_ADDRS, "重复地址必须去掉");
        assert!(parsed.relay.is_none(), "超长 relay 必须被丢弃");
    }

    /// 最坏组合：名字取满、relay 取满、地址全是最占字节的写法 —— 仍必须可解析且不超总长。
    #[test]
    fn worst_case_build_stays_parseable() {
        let name = "工".repeat(MAX_NAME_CHARS);
        let relay = format!("http://{}", "a".repeat(MAX_RELAY_LEN - 7));
        assert_eq!(relay.len(), MAX_RELAY_LEN);
        let addrs: Vec<String> = (0..MAX_ADDRS).map(|_| ":".repeat(MAX_ADDR_LEN)).collect();

        let uri = build(ENDPOINT, TOKEN, Some(&name), Some(&relay), &addrs);
        assert!(uri.len() <= MAX_URI_LEN, "{}", uri.len());
        let parsed = parse(&uri).expect("极端组合也必须可解析");
        assert_eq!(
            parsed.name.as_deref().map(|n| n.chars().count()),
            Some(MAX_NAME_CHARS)
        );
        assert_eq!(parsed.relay.as_deref(), Some(relay.as_str()));
        // 总长到顶后停止追加地址：少几个可选地址，换来「一定可解析」。
        assert!(parsed.addrs.len() < MAX_ADDRS, "{}", parsed.addrs.len());
    }

    #[test]
    fn build_roundtrips_through_parse() {
        let uri = build(
            ENDPOINT,
            TOKEN,
            Some("桌面 机器"),
            Some("n0"),
            &["10.0.0.2:4433".to_string(), "[::1]:4433".to_string()],
        );
        let parsed = parse(&uri).unwrap();
        assert_eq!(parsed.endpoint, ENDPOINT);
        assert_eq!(parsed.token, TOKEN);
        assert_eq!(parsed.name.as_deref(), Some("桌面 机器"));
        assert_eq!(parsed.relay.as_deref(), Some("n0"));
        assert_eq!(
            parsed.addrs,
            vec!["10.0.0.2:4433".to_string(), "[::1]:4433".to_string()]
        );
    }

    #[test]
    fn accepts_uppercase_scheme_host_and_hex() {
        let uri = format!(
            "PI-CARRY://PAIR?v=1&endpoint={}&token={}",
            ENDPOINT.to_uppercase(),
            TOKEN.to_uppercase()
        );
        let parsed = parse(&uri).unwrap();
        assert_eq!(parsed.endpoint, ENDPOINT);
        assert_eq!(parsed.token, TOKEN);
    }

    #[test]
    fn accepts_plus_as_space_and_trailing_slash() {
        let uri = format!("pi-carry://pair/?v=1&endpoint={ENDPOINT}&token={TOKEN}&name=My+PC");
        let parsed = parse(&uri).unwrap();
        assert_eq!(parsed.name.as_deref(), Some("My PC"));
    }

    #[test]
    fn rejects_wrong_scheme() {
        let uri = format!("https://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}");
        let err = parse(&uri).unwrap_err().to_string();
        assert!(err.contains("scheme"), "{err}");
    }

    #[test]
    fn rejects_wrong_host() {
        let uri = format!("pi-carry://other?v=1&endpoint={ENDPOINT}&token={TOKEN}");
        let err = parse(&uri).unwrap_err().to_string();
        assert!(err.contains("host"), "{err}");
    }

    #[test]
    fn rejects_path() {
        let uri = format!("pi-carry://pair/evil?v=1&endpoint={ENDPOINT}&token={TOKEN}");
        let err = parse(&uri).unwrap_err().to_string();
        assert!(err.contains("路径"), "{err}");
    }

    #[test]
    fn rejects_missing_required_fields() {
        assert!(parse("pi-carry://pair?v=1").is_err());
        assert!(parse(&format!(
            "pi-carry://pair?endpoint={ENDPOINT}&token={TOKEN}"
        ))
        .is_err());
        assert!(parse(&format!("pi-carry://pair?v=1&endpoint={ENDPOINT}")).is_err());
    }

    #[test]
    fn rejects_unsupported_version() {
        let uri = format!("pi-carry://pair?v=2&endpoint={ENDPOINT}&token={TOKEN}");
        let err = parse(&uri).unwrap_err().to_string();
        assert!(err.contains("v=2"), "{err}");
    }

    #[test]
    fn rejects_bad_hex_length_and_chars() {
        let short = format!(
            "pi-carry://pair?v=1&endpoint={}&token={TOKEN}",
            &ENDPOINT[..62]
        );
        assert!(parse(&short).unwrap_err().to_string().contains("64 位"));
        let bad = format!(
            "pi-carry://pair?v=1&endpoint={}&token={TOKEN}",
            "z".repeat(64)
        );
        assert!(parse(&bad).unwrap_err().to_string().contains("0-9a-f"));
        let short_token = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token=abcd");
        assert!(parse(&short_token)
            .unwrap_err()
            .to_string()
            .contains("32 位"));
    }

    #[test]
    fn rejects_duplicate_known_parameters() {
        let uri = format!("pi-carry://pair?v=1&v=1&endpoint={ENDPOINT}&token={TOKEN}");
        assert!(parse(&uri).unwrap_err().to_string().contains("重复"));
    }

    #[test]
    fn rejects_bad_percent_escape() {
        let uri = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name=%ZZ");
        assert!(parse(&uri).unwrap_err().to_string().contains("百分号"));
        let truncated = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name=%4");
        assert!(parse(&truncated)
            .unwrap_err()
            .to_string()
            .contains("百分号"));
    }

    #[test]
    fn rejects_overlong_uri() {
        let uri = format!(
            "pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name={}",
            "a".repeat(MAX_URI_LEN)
        );
        assert!(parse(&uri).unwrap_err().to_string().contains("过长"));
    }

    #[test]
    fn rejects_overlong_name_and_control_chars() {
        let long = format!(
            "pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name={}",
            "a".repeat(MAX_NAME_CHARS + 1)
        );
        assert!(parse(&long).unwrap_err().to_string().contains("字符"));
        let control = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&name=a%0Ab");
        assert!(parse(&control)
            .unwrap_err()
            .to_string()
            .contains("控制字符"));
    }

    #[test]
    fn rejects_non_http_relay() {
        let uri =
            format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&relay=ftp%3A%2F%2Fx");
        assert!(parse(&uri).unwrap_err().to_string().contains("http"));
    }

    #[test]
    fn ignores_unknown_parameters() {
        let uri = format!("pi-carry://pair?v=1&endpoint={ENDPOINT}&token={TOKEN}&future=1");
        assert!(parse(&uri).is_ok());
    }

    #[test]
    fn rejects_empty_link_and_missing_scheme() {
        assert!(parse("").is_err());
        assert!(parse("   ").is_err());
        assert!(parse("pair?v=1").is_err());
        assert!(parse("pi-carry://").is_err());
    }
}
