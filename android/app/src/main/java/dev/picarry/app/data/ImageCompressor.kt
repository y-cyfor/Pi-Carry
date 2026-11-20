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

/** 在手机上把图片压到长边 1568、JPEG 85，避免把原始照片直接塞进协议。 */
object ImageCompressor {
    private const val MAX_SIDE = 1568
    private const val QUALITY = 85

    fun compress(context: Context, uri: Uri): Pair<String, String> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法读取图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("图片格式不受支持")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
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

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val upright = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        val ratio = min(1f, MAX_SIDE.toFloat() / max(upright.width, upright.height))
        val scaled = if (ratio >= 1f) upright else Bitmap.createScaledBitmap(
            upright,
            (upright.width * ratio).toInt().coerceAtLeast(1),
            (upright.height * ratio).toInt().coerceAtLeast(1),
            true,
        )

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        if (upright !== decoded && upright !== scaled) upright.recycle()
        decoded.recycle()
        return "image/jpeg" to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
