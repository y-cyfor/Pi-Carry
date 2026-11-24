package dev.picarry.app.data

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * EXIF 朝向校正矩阵。5（转置）/7（副对角线转置）在旧实现里落到 else，竖拍照片躺倒；
 * 它们等价于「镜像 + 90° 旋转」，只旋转仍然躺倒。
 *
 * 断言的是 [orientationMatrixValues] 而不是 Matrix：Matrix 属于 android.graphics，
 * 普通 JVM 单测里没有真实实现，分量无法读出。
 */
class ImageOrientationMatrixTest {

    @Test
    fun transposeSwapsAxes() {
        val m = orientationMatrixValues(ExifInterface.ORIENTATION_TRANSPOSE)!!
        // x' = y，y' = x：点 (7, 3) 落到 (3, 7)，这才是沿主对角线翻折。
        assertEquals(3f to 7f, apply(m, 7f, 3f))
        assertEquals(0f, m[2])
        assertEquals(0f, m[5])
    }

    @Test
    fun transverseNegatesBothAxes() {
        val m = orientationMatrixValues(ExifInterface.ORIENTATION_TRANSVERSE)!!
        // x' = -y，y' = -x：沿副对角线翻折。
        assertEquals(-3f to -7f, apply(m, 7f, 3f))
    }

    @Test
    fun transposeIsMirrorThenRotate() {
        // 注释里写的等价关系也要成立：5 = 先镜像再转 270°，7 = 先镜像再转 90°。
        assertValues(
            orientationMatrixValues(ExifInterface.ORIENTATION_TRANSPOSE)!!,
            multiply(rotation(270f), mirrorHorizontal()),
        )
        assertValues(
            orientationMatrixValues(ExifInterface.ORIENTATION_TRANSVERSE)!!,
            multiply(rotation(90f), mirrorHorizontal()),
        )
    }

    @Test
    fun transposeAndTransverseAreNotRotations() {
        val five = orientationMatrixValues(ExifInterface.ORIENTATION_TRANSPOSE)!!
        val seven = orientationMatrixValues(ExifInterface.ORIENTATION_TRANSVERSE)!!
        val six = orientationMatrixValues(ExifInterface.ORIENTATION_ROTATE_90)!!
        val eight = orientationMatrixValues(ExifInterface.ORIENTATION_ROTATE_270)!!
        // 反射的行列式是 -1，纯旋转是 +1：拿旋转冒充转置会被这条断言抓住。
        assertEquals(-1f, determinant(five))
        assertEquals(-1f, determinant(seven))
        assertEquals(1f, determinant(six))
        assertEquals(1f, determinant(eight))
        assertTrue("5 不能与 6 相同", !five.contentEquals(six))
        assertTrue("5 不能与 8 相同", !five.contentEquals(eight))
        assertTrue("7 不能与 6 相同", !seven.contentEquals(six))
        assertTrue("7 不能与 8 相同", !seven.contentEquals(eight))
        assertTrue("5 与 7 是两种不同朝向", !five.contentEquals(seven))
    }

    @Test
    fun simpleOrientationsMatchFrameworkTransforms() {
        assertNull(orientationMatrixValues(ExifInterface.ORIENTATION_NORMAL))
        assertNull(orientationMatrixValues(ExifInterface.ORIENTATION_UNDEFINED))
        assertValues(
            mirrorHorizontal(),
            orientationMatrixValues(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)!!,
        )
        assertValues(rotation(180f), orientationMatrixValues(ExifInterface.ORIENTATION_ROTATE_180)!!)
        assertValues(
            floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f),
            orientationMatrixValues(ExifInterface.ORIENTATION_FLIP_VERTICAL)!!,
        )
        assertValues(rotation(90f), orientationMatrixValues(ExifInterface.ORIENTATION_ROTATE_90)!!)
        assertValues(rotation(270f), orientationMatrixValues(ExifInterface.ORIENTATION_ROTATE_270)!!)
    }

    /** 行优先 3×3 作用在点上：x' = scaleX·x + skewX·y + transX。 */
    private fun apply(v: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (v[0] * x + v[1] * y + v[2]) to (v[3] * x + v[4] * y + v[5])

    /** Android `Matrix` 的 rotate(deg)：y 轴向下的坐标系里正角度即顺时针。 */
    private fun rotation(deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    private fun mirrorHorizontal() = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** result = a × b，即「先 b 后 a」——对应 Matrix 的 postConcat 语义。 */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val row = i / 3
        val col = i % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    private fun determinant(v: FloatArray): Float = v[0] * v[4] - v[1] * v[3]

    private fun assertValues(expected: FloatArray, actual: FloatArray) {
        assertEquals(9, actual.size)
        for (i in 0 until 9) assertEquals("分量 $i", expected[i], actual[i], 1e-5f)
    }
}
