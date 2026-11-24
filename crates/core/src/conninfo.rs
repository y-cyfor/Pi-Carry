//! 连接方式分类：把 iroh 实际选中的传输路径翻译成对用户有意义的类型。
//!
//! 只在服务端按**实测路径**判定：客户端猜「我有 v6 地址所以应该是 v6」并不可靠，
//! 打洞失败走 relay 时地址看起来完全一样。这里的判定函数保持纯函数，便于单测。

use serde_json::{json, Value};
use std::net::{IpAddr, SocketAddr};

/// 连接方式。序列化名字即协议里的 `type` 字段。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnType {
    /// relay 转发
    Relay,
    /// 局域网（私网 / 链路本地 / CGNAT / 回环 / ULA）
    Lan,
    /// IPv4 公网直连
    V4,
    /// IPv6 全球地址直连
    V6,
    /// 无路径或自定义传输（p2p）
    P2p,
}

impl ConnType {
    pub fn as_str(self) -> &'static str {
        match self {
            ConnType::Relay => "relay",
            ConnType::Lan => "lan",
            ConnType::V4 => "v4",
            ConnType::V6 => "v6",
            ConnType::P2p => "p2p",
        }
    }
}

/// 按远端地址分类。`None` 表示没有可用路径（连接刚建立或路径尚未就绪）。
pub fn classify(remote: Option<&AddrKind>) -> ConnType {
    match remote {
        None => ConnType::P2p,
        Some(AddrKind::Relay) => ConnType::Relay,
        Some(AddrKind::Custom) => ConnType::P2p,
        Some(AddrKind::Ip(ip)) => classify_ip(*ip),
    }
}

/// iroh 传输地址的三种形态，抽成本地枚举便于纯函数测试。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddrKind {
    Relay,
    Ip(IpAddr),
    Custom,
}

pub fn classify_ip(ip: IpAddr) -> ConnType {
    match ip {
        IpAddr::V4(v4) => {
            if v4.is_loopback() || v4.is_private() || v4.is_link_local() || is_cgnat(v4) {
                ConnType::Lan
            } else {
                ConnType::V4
            }
        }
        IpAddr::V6(v6) => {
            // 双栈 socket 上的 `::ffff:a.b.c.d` 本质就是 IPv4：不先折回去就落到
            // 「全球 v6 直连」分支，把一个私网地址报成 v6 直连（徽章与文案都错）。
            if let Some(v4) = v6.to_ipv4_mapped() {
                return classify_ip(IpAddr::V4(v4));
            }
            // link-local（fe80::/10）与 loopback（::1）算局域网，
            // ULA（fc00::/7）同样是内网；其余按全球直连。
            if v6.is_loopback() || is_ipv6_link_local(v6) || is_ipv6_ula(v6) {
                ConnType::Lan
            } else {
                ConnType::V6
            }
        }
    }
}

/// 100.64.0.0/10（运营商级 NAT），`Ipv4Addr::is_private` 不覆盖它。
fn is_cgnat(ip: std::net::Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 100 && (64..=127).contains(&o[1])
}

fn is_ipv6_link_local(ip: std::net::Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xffc0) == 0xfe80
}

/// 广告地址时是否应跳过：fe80::/10 链路本地地址不带 zone id，
/// 手机（Android）无法拨通，广告它只会让每次连接先等一个超时。
pub fn skip_for_advertisement(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V6(v6) => is_ipv6_link_local(v6),
        std::net::IpAddr::V4(_) => false,
    }
}

fn is_ipv6_ula(ip: std::net::Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xfe00) == 0xfc00
}

/// 一次连接的连接方式快照，用于 `connection.updated` 事件与 `hello.connection`。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnInfo {
    pub kind: ConnType,
    pub remote_addr: Option<String>,
    pub local_addr: Option<String>,
    pub rtt_ms: Option<u64>,
    pub direct: bool,
}

impl ConnInfo {
    pub fn new(
        kind: ConnType,
        remote_addr: Option<String>,
        local_addr: Option<String>,
        rtt_ms: Option<u64>,
    ) -> Self {
        let direct = matches!(kind, ConnType::Lan | ConnType::V4 | ConnType::V6);
        Self {
            kind,
            remote_addr,
            local_addr,
            rtt_ms,
            direct,
        }
    }

    /// 连接方式对象本身，`hello.connection` 与事件里的 `connection` 字段共用这一形态。
    ///
    /// 连接方式放在 `type` 字段里与事件外层的 `type`（事件名）互不干扰：
    /// 外层属于 ServerFrame::Event 的事件体，内层才是本结构。
    pub fn to_json(&self) -> Value {
        json!({
            "type": self.kind.as_str(),
            "remoteAddr": self.remote_addr,
            "localAddr": self.local_addr,
            "rttMs": self.rtt_ms,
            "direct": self.direct,
        })
    }

    /// 事件体：`{"type":"connection.updated","connection":{…}}`。
    pub fn event(&self) -> Value {
        json!({
            // 事件名跟协议常量绑定，改名时编译器会报错，避免两侧字面量各自漂移。
            "type": pi_carry_protocol::events::CONNECTION_UPDATED,
            "connection": self.to_json(),
        })
    }

    /// 只有「类型或远端地址」变化的才算需要重发，避免 rtt 抖动刷屏。
    pub fn same_route(&self, other: &ConnInfo) -> bool {
        self.kind == other.kind && self.remote_addr == other.remote_addr
    }
}

/// 从 `SocketAddr` 提取分类，给调用方省一次拆解。
pub fn classify_socket(addr: SocketAddr) -> ConnType {
    classify_ip(addr.ip())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn relay_and_custom_map_to_relay_and_p2p() {
        assert_eq!(classify(Some(&AddrKind::Relay)), ConnType::Relay);
        assert_eq!(classify(Some(&AddrKind::Custom)), ConnType::P2p);
        assert_eq!(classify(None), ConnType::P2p);
    }

    #[test]
    fn ipv4_private_and_link_local_are_lan() {
        for s in [
            "192.168.1.10",
            "10.1.2.3",
            "172.16.5.4",
            "169.254.9.9",
            "127.0.0.1",
        ] {
            assert_eq!(classify_ip(ip(s)), ConnType::Lan, "{s} 应判为局域网");
        }
    }

    #[test]
    fn cgnat_is_lan_but_adjacent_blocks_are_not() {
        assert_eq!(
            classify_ip(IpAddr::V4(Ipv4Addr::new(100, 64, 0, 1))),
            ConnType::Lan
        );
        assert_eq!(
            classify_ip(IpAddr::V4(Ipv4Addr::new(100, 127, 255, 254))),
            ConnType::Lan
        );
        assert_eq!(
            classify_ip(IpAddr::V4(Ipv4Addr::new(100, 63, 0, 1))),
            ConnType::V4
        );
        assert_eq!(
            classify_ip(IpAddr::V4(Ipv4Addr::new(100, 128, 0, 1))),
            ConnType::V4
        );
    }

    #[test]
    fn ipv4_public_is_v4() {
        assert_eq!(classify_ip(ip("8.8.8.8")), ConnType::V4);
        assert_eq!(classify_ip(ip("1.1.1.1")), ConnType::V4);
    }

    #[test]
    fn ipv6_loopback_link_local_and_ula_are_lan() {
        for s in ["::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456::1"] {
            assert_eq!(classify_ip(ip(s)), ConnType::Lan, "{s} 应判为局域网");
        }
    }

    #[test]
    fn ipv4_mapped_ipv6_is_classified_as_ipv4() {
        // 双栈 socket 交接的地址可能是 ::ffff:192.168.1.1，必须按内嵌 v4 归类。
        assert_eq!(classify_ip(ip("::ffff:192.168.1.1")), ConnType::Lan);
        assert_eq!(classify_ip(ip("::ffff:8.8.8.8")), ConnType::V4);
        assert_eq!(classify_ip(ip("::ffff:10.0.0.1")), ConnType::Lan);
    }

    #[test]
    fn ipv6_global_is_v6() {
        assert_eq!(classify_ip(ip("2a00:1450:4001::1")), ConnType::V6);
        assert_eq!(classify_ip(ip("2001:4860:4860::8888")), ConnType::V6);
    }

    #[test]
    fn conn_info_direct_flag_and_event_shape() {
        let info = ConnInfo::new(
            ConnType::Lan,
            Some("192.168.0.2:5555".into()),
            None,
            Some(21),
        );
        assert!(info.direct);
        let ev = info.event();
        assert_eq!(ev["type"], "connection.updated");
        assert_eq!(ev["connection"]["type"], "lan");
        assert_eq!(ev["connection"]["rttMs"], 21);
        assert_eq!(ev["connection"]["direct"], true);
        let relay = ConnInfo::new(ConnType::Relay, Some("relay:https://x".into()), None, None);
        assert!(!relay.direct);
        assert_eq!(relay.event()["connection"]["type"], "relay");
    }

    #[test]
    fn same_route_ignores_rtt_changes() {
        let a = ConnInfo::new(ConnType::V6, Some("[2a00::1]:1".into()), None, Some(10));
        let b = ConnInfo::new(ConnType::V6, Some("[2a00::1]:1".into()), None, Some(99));
        assert!(a.same_route(&b));
        let c = ConnInfo::new(ConnType::V4, Some("[2a00::1]:1".into()), None, Some(10));
        assert!(!a.same_route(&c));
    }

    #[test]
    fn classify_socket_uses_ip_only() {
        let v: SocketAddr = "10.0.0.1:9".parse().unwrap();
        assert_eq!(classify_socket(v), ConnType::Lan);
        let _ = Ipv6Addr::LOCALHOST;
    }
}
