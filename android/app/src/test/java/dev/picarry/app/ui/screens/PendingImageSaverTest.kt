package dev.picarry.app.ui.screens

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待发图片跨旋转/切屏必须保留，只有真正超过 Bundle 预算时才丢弃，且丢弃后要留下可见标记。
 *
 * 旧实现上限 200_000 字符低于典型照片（300–700K），切屏后图片被静默清空。
 */
class PendingImageSaverTest {
    private val scope = SaverScope { true }

    /** 造一个指定长度的伪 base64 正文。 */
    private fun imageOf(length: Int) = Triple("image/jpeg", "A".repeat(length), "image")

    @Test
    fun typicalPhotoSurvivesSaveAndRestore() {
        // 500K 字符：典型的 1568px / quality 85 照片，旧上限会把它丢掉。
        val image = imageOf(500_000)
        val saved = with(PendingImageSaver) { scope.save(PendingImageState(image)) }
        val restored = PendingImageSaver.restore(saved!!)
        assertEquals(image, restored?.image)
        assertEquals(false, restored?.droppedForSize)
    }

    @Test
    fun oversizedImageIsMarkedNotSilentlyDropped() {
        val saved = with(PendingImageSaver) {
            scope.save(PendingImageState(imageOf(MAX_SAVED_IMAGE_CHARS + 1)))
        }
        assertEquals(listOf(DROPPED_IMAGE_MARKER), saved)
        val restored = PendingImageSaver.restore(saved!!)
        assertTrue(restored?.droppedForSize == true)
        assertNull(restored?.image)
    }

    @Test
    fun noImageSavesNothing() {
        assertNull(with(PendingImageSaver) { scope.save(null) })
    }

    @Test
    fun unknownPayloadRestoresToNull() {
        // 旧版本保存的 [mime, data, kind] 三元素形态不再兼容：尺寸无法区分，宁可当作没有图片。
        assertNull(PendingImageSaver.restore(listOf("image/jpeg", "AAA", "image")))
    }
}
