package dev.picarry.app.data

import dev.picarry.app.net.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.2.0 图片直传：格式必须按魔数判定，不能被 ContentResolver 的 MIME 骗过。 */
class ImagePreparerMimeTest {
    @Test
    fun detectsAllowedFormats() {
        assertEquals(
            "image/png",
            detectImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals("image/jpeg", detectImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", detectImageMime("GIF89a".toByteArray()))
        assertEquals("image/webp", detectImageMime("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    }

    @Test
    fun rejectsUnknownAndShortBytes() {
        assertNull(detectImageMime(byteArrayOf(1, 2, 3)))
        assertNull(detectImageMime("BM0000".toByteArray()))
    }

    @Test
    fun blobChunkBase64StaysInsideControlFrame() {
        // 512KiB 分片 base64 后约 683KiB，必须给 1MiB 控制帧留出协议余量。
        assertEquals(512 * 1024, CarryRepository.BLOB_CHUNK_BYTES)
        org.junit.Assert.assertTrue(CarryRepository.BLOB_CHUNK_BYTES * 4L / 3 + 4096 < Wire.MAX_CONTROL)
    }
}
