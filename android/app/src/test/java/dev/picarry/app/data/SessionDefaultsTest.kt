package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构造默认值必须与服务端 serde 默认对齐，否则在解析不到的字段上客户端会凭猜造状态。
 *
 * 服务端 `crates/protocol/src/payloads.rs` 的 `SessionSummary`：
 * `#[serde(default)] pub last_source: String`（默认空串）、`#[serde(default)] pub writable: bool`
 * （bool 的 serde 默认是 false，但 core 在构造时显式给 true）。
 */
class SessionDefaultsTest {
    @Test
    fun lastSourceDefaultsToEmptyString() {
        // 旧默认值 "mobile" 与服务端不同，会把服务端未上报的会话误标成手机端发起的。
        val session = Session(id = "s1", nodeId = "n1")
        assertEquals("", session.lastSource)
    }

    @Test
    fun writableDefaultsToTrue() {
        val session = Session(id = "s1", nodeId = "n1")
        assertTrue(session.writable)
    }

    @Test
    fun statusDefaultsToIdleAndIsNotRunning() {
        val session = Session(id = "s1", nodeId = "n1")
        assertEquals("idle", session.status)
        assertFalse(session.isRunning)
    }
}
