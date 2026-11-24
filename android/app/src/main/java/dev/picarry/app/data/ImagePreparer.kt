package dev.picarry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 选择 [BitmapFactory.Options.inSampleSize]（2 的幂）。
 * 目标是解码后的长边不超过 [maxSide]：1568-3135 这一段如果按 `>=` 判据会全尺寸解码，
 * 3135×3135 ARGB 约 39MB，再加 EXIF 旋转副本就是近 80MB 峰值。
 * 长边不超过 [maxSide] 的图保持 sample=1，行为不变。
 */
internal fun computeSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (max(width, height) / sample > maxSide) sample *= 2
    return sample
}

/**
 * EXIF 朝向对应的仿射矩阵（行优先 3×3，即 [Matrix.setValues] 的布局）；null 表示不需要变换。
 *
 * 5（TRANSPOSE，沿主对角线转置）与 7（TRANSVERSE，沿副对角线转置）：5/7 是转置，
 * 等价于镜像 + 90° 旋转，不能只旋转（只旋转拍出来的竖照仍然是躺倒的；旧实现把这两个值
 * 落到 else，连转都不转）。并且镜像必须在旋转之前——先在原位左右翻转、再整体旋转，
 * 才等价于按对角线翻折；顺序反过来得到的是另一种（仍然躺倒的）朝向。
 *
 * 返回纯浮点数组而不是 Matrix，是为了能在普通 JVM 单测里断言九个分量：Matrix 属于
 * android.graphics，单测环境里没有真实实现。
 */
internal fun orientationMatrixValues(orientation: Int): FloatArray? = when (orientation) {
    // 5 = 水平镜像后转 270°（等价于「转 90° 再水平镜像」）：x' = y，y' = x。
    ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(
        0f, 1f, 0f,
        1f, 0f, 0f,
        0f, 0f, 1f,
    )
    // 7 = 水平镜像后转 90°（等价于「转 270° 再水平镜像」）：x' = -y，y' = -x。
    ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(
        0f, -1f, 0f,
        -1f, 0f, 0f,
        0f, 0f, 1f,
    )
    // 2：上下不变、左右翻转。
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(
        -1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )
    ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(
        -1f, 0f, 0f,
        0f, -1f, 0f,
        0f, 0f, 1f,
    )
    // 4：左右不变、上下翻转。
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(
        1f, 0f, 0f,
        0f, -1f, 0f,
        0f, 0f, 1f,
    )
    ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(
        0f, -1f, 0f,
        1f, 0f, 0f,
        0f, 0f, 1f,
    )
    ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(
        0f, 1f, 0f,
        -1f, 0f, 0f,
        0f, 0f, 1f,
    )
    // 1（正立）与未知朝向：不需要变换。
    else -> null
}

/** [orientationMatrixValues] 的 Matrix 形式；生产代码用它做 `createBitmap` 的变换。 */
internal fun orientationMatrix(orientation: Int): Matrix? =
    orientationMatrixValues(orientation)?.let { values -> Matrix().apply { setValues(values) } }

/** 选择后、上传前的图片：原始字节 + 嗅探出的真实 MIME + 尺寸。 */
data class PreparedImage(
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * 0.2.0 的选图预处理：只做格式校验与 EXIF 方向纠正，不再强制缩放/降质。
 *
 * 上限由协议决定（单张 20MB、单条 10 张、会话 100MB），压缩交给用户自己选择；
 * 悄悄把 48MP 照片压成 1568px 会让「原图发送」这个承诺失效。
 */
object ImagePreparer {
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    const val MAX_IMAGES = 10
    private val ALLOWED = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    fun prepare(context: Context, uri: Uri): PreparedImage {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_IMAGE_BYTES) error("单张图片超过 20MB，请先裁剪或压缩")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error("无法读取图片")
        if (bytes.isEmpty()) error("图片为空")

        val mime = detectImageMime(bytes)
            ?: resolver.getType(uri)?.lowercase()?.let(::normalizeMime)
            ?: error("无法识别图片格式，仅支持 PNG/JPEG/WebP/GIF")
        if (mime !in ALLOWED) error("不支持的图片格式：$mime（仅支持 PNG/JPEG/WebP/GIF）")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("图片格式不受支持或文件已损坏")

        // GIF 可能有多帧，旋转会破坏动画；其余格式按 EXIF 纠正方向。
        if (mime == "image/gif") {
            return PreparedImage(mime, bytes, bounds.outWidth, bounds.outHeight)
        }
        val orientation = runCatching {
            ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation) ?: return PreparedImage(mime, bytes, bounds.outWidth, bounds.outHeight)

        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            error("图片分辨率过大，无法纠正方向，请先裁剪")
        } ?: error("图片解码失败")
        val upright = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            error("图片分辨率过大，无法纠正方向，请先裁剪")
        }
        try {
            val out = ByteArrayOutputStream()
            // 旋转必须重编码，但保持原格式与高质量：WebP→PNG 会让体积暴涨（B-50）。
            val encodedMime = mime
            val ok = when (mime) {
                "image/jpeg" -> upright.compress(Bitmap.CompressFormat.JPEG, 100, out)
                "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                    upright.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    @Suppress("DEPRECATION")
                    upright.compress(Bitmap.CompressFormat.WEBP, 100, out)
                }
                else -> upright.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!ok) error("图片编码失败")
            val encoded = out.toByteArray()
            if (encoded.isEmpty()) error("图片编码失败")
            if (encoded.size > MAX_IMAGE_BYTES) error("纠正方向后图片超过 20MB，请先裁剪")
            return PreparedImage(encodedMime, encoded, upright.width, upright.height)
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    private fun normalizeMime(raw: String): String = when (raw) {
        "image/jpg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> raw.substringBefore(';').trim()
    }

}

/** 按魔数识别图片格式；只认服务端允许的四种。 */
internal fun detectImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 6 && bytes.copyOfRange(0, 4).contentEquals("GIF8".toByteArray()) -> "image/gif"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    else -> null
}
