package dev.picarry.app.data

import kotlinx.serialization.json.Json
import dev.picarry.app.ui.screens.OutgoingImage
import dev.picarry.app.ui.screens.loadPendingImagesFrom
import dev.picarry.app.ui.screens.savePendingImagesTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarryParseTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun sha256Hex_matchesKnownDigest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun parseMigrationEvent_readsIdsAndDefaultsReason() {
        val m = parseMigrationEvent("n1", obj("""{"fromSessionId":"a","toSessionId":"b","type":"session.migrated"}"""))
        assertEquals("n1", m?.nodeId)
        assertEquals("a", m?.fromSessionId)
        assertEquals("b", m?.toSessionId)
        assertEquals("fork", m?.reason)
        assertNull(parseMigrationEvent("n1", obj("""{"type":"session.migrated"}""")))
    }

    @Test
    fun parseEntriesPage_buildsDepthAndPreviewWithCycleGuard() {
        val page = parseEntriesPage(
            obj(
                """
                {"leafId":"e2","entries":[
                  {"id":"e1","type":"message","timestamp":"t1","message":{"role":"user","content":"hello"}},
                  {"id":"e2","parentId":"e1","type":"message","timestamp":"t2","message":{"role":"assistant","content":[{"type":"text","text":"world"}]}},
                  {"id":"e3","parentId":"e2","type":"message","message":{"role":"user","content":"third"}}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("e2", page.leafId)
        assertEquals(listOf(0, 1, 2), page.entries.map { it.depth })
        assertEquals("hello", page.entries[0].preview)
        assertEquals("world", page.entries[1].preview)
        assertEquals("user", page.entries[0].role)
    }

    @Test
    fun parseEntriesPage_handlesParentCycle() {
        // A→B→A：深度必须封顶而不是死循环（B-48）。
        val page = parseEntriesPage(
            obj(
                """
                {"leafId":"a","entries":[
                  {"id":"a","parentId":"b","type":"message","message":{"content":"a"}},
                  {"id":"b","parentId":"a","type":"message","message":{"content":"b"}}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(2, 2), page.entries.map { it.depth })
    }


    @Test
    fun sha256Hex_handlesEmptyAndLargeInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "51b12eb838732b786b4d45c660a974ddf3860ae09084fd293fa6e5df46581a6c",
            sha256Hex(ByteArray(1024 * 1024) { 7 }),
        )
    }

    @Test
    fun pendingIndex_roundTripsFourColumns_andSkipsLegacyRows() {
        val dir = kotlin.io.path.createTempDirectory("pi-carry-pending-test").toFile()
        try {
            savePendingImagesTo(
                dir,
                listOf(
                    OutgoingImage("local", "image/png", byteArrayOf(1, 2, 3), 3),
                    OutgoingImage("blob", "image/jpeg", ByteArray(0), 9, blobId = "a".repeat(64)),
                ),
            )
            val loaded = loadPendingImagesFrom(dir)
            assertEquals(listOf("local", "blob"), loaded.map { it.id })
            assertEquals(3, loaded[0].bytes.size)
            assertEquals("a".repeat(64), loaded[1].blobId)
            assertTrue(File(dir, "local.bin").isFile)
            // 旧 3 列/缺少 size 的行必须跳过，不能把错误尺寸带进发送。
            File(dir, "index.txt").writeText("local\timage/png\t\n")
            assertTrue(loadPendingImagesFrom(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneBlobCacheDir_evicts_oldest_until_limit() {
        val dir = kotlin.io.path.createTempDirectory("pi-carry-blob-test").toFile()
        try {
            val keep = File(dir, "keep").apply { writeBytes(ByteArray(40)) }
            val old = File(dir, "old").apply { writeBytes(ByteArray(40)); setLastModified(1_000) }
            val newer = File(dir, "newer").apply { writeBytes(ByteArray(40)); setLastModified(2_000) }
            pruneBlobCacheDir(dir, keep, limit = 100)
            assertTrue(keep.isFile)
            assertFalse(old.exists())
            assertTrue(newer.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
