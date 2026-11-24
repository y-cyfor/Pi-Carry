package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码采样必须保证长边不超过 MAX_SIDE（1568），否则 2K/3K 截图会全尺寸解码后再复制一份，
 * 峰值内存接近 80MB。长边不超过 1568 的图不能多降采样。
 */
class ImageSampleSizeTest {
    private val maxSide = 1568

    @Test
    fun smallImageIsNotDownsampled() {
        assertEquals(1, computeSampleSize(800, 600, maxSide))
    }

    @Test
    fun exactlyMaxSideIsNotDownsampled() {
        // 边界：长边恰好等于上限时仍然全尺寸解码，避免白白损失清晰度。
        assertEquals(1, computeSampleSize(1568, 1568, maxSide))
    }

    @Test
    fun justOverMaxSideIsHalved() {
        assertEquals(2, computeSampleSize(1569, 1000, maxSide))
    }

    @Test
    fun twiceMaxSideIsHalved() {
        assertEquals(2, computeSampleSize(3136, 3136, maxSide))
    }

    @Test
    fun beyondTwiceMaxSideKeepsHalving() {
        assertEquals(4, computeSampleSize(4000, 3000, maxSide))
        assertEquals(4, computeSampleSize(6272, 4704, maxSide))
        assertEquals(8, computeSampleSize(6400, 4800, maxSide))
    }

    @Test
    fun decodedLongSideNeverExceedsMaxSide() {
        for (side in listOf(1569, 2000, 3135, 3136, 3137, 4000, 6273)) {
            val decoded = side / computeSampleSize(side, side / 2, maxSide)
            assertTrue("长边 $side 解码后为 $decoded，超过上限", decoded <= maxSide)
        }
    }
}
