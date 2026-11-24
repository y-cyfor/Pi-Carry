package dev.picarry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

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

/** 在手机上把图片压到长边 1568、JPEG 85，避免把原始照片直接塞进协议。 */
object ImageCompressor {
    private const val MAX_SIDE = 1568
    private const val QUALITY = 85

    /**
     * 单张图片 base64 的字符预算。prompt 帧总预算 `Wire.PROMPT_FRAME_BUDGET` 约 1016 KiB，
     * 单张只吃掉 700 KiB，余量留给同一条消息里的其它图片、正文与信封字段。
     */
    private const val SINGLE_IMAGE_BUDGET = 700 * 1024

    /**
     * 体积回退档位（长边, JPEG 质量）。第一档就是正常压缩结果；超预算时按顺序往下试，
     * 取第一个落进 [SINGLE_IMAGE_BUDGET] 的结果。后两档宁可略糊也不让消息发不出去：
     * 一旦超过帧预算，用户重选同一张还是超，这张图就永远发不出去了。
     */
    private val FALLBACK_STEPS = listOf(
        MAX_SIDE to QUALITY,
        1280 to 70,
        1024 to 55,
        800 to 55,
    )

    fun compress(context: Context, uri: Uri): Pair<String, String> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法读取图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("图片格式不受支持")

        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("图片解码失败")

        val orientation = resolver.openInputStream(uri)?.use {
            runCatching {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = orientationMatrix(orientation)
        val upright = if (matrix == null) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        try {
            // 循环至少执行一档，占位值不会被返回；全档都超预算时返回最后一档（最小的那次编码）。
            var encoded = "image/jpeg" to ""
            for ((side, quality) in FALLBACK_STEPS) {
                val scaled = scaleTo(upright, side)
                // base64 是 ASCII，字符数就等于字节数，直接拿长度比预算。
                encoded = "image/jpeg" to encodeBase64(scaled, quality)
                if (scaled !== upright) scaled.recycle()
                if (encoded.second.length <= SINGLE_IMAGE_BUDGET) break
            }
            return encoded
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    /** 把长边缩到 [maxSide]；本来就不超时直接返回原图（调用方据此决定要不要 recycle）。 */
    private fun scaleTo(source: Bitmap, maxSide: Int): Bitmap {
        val ratio = min(1f, maxSide.toFloat() / max(source.width, source.height))
        if (ratio >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun encodeBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
